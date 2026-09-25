package io.github.desodre.adbutils.client

import io.github.desodre.adbutils.error.*
import io.github.desodre.adbutils.diagnostics.DeviceHealthCollector
import io.github.desodre.adbutils.model.DeviceSerial
import io.github.desodre.adbutils.model.ShellResult
import io.github.desodre.adbutils.protocol.ShellV2Protocol
import io.github.desodre.adbutils.protocol.SyncProtocol
import io.github.desodre.adbutils.model.*
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/** A serial-bound handle, not a persistent connection or a guarantee that the device remains online. */
public class AdbDevice internal constructor(private val client: AdbClient, public val serial: DeviceSerial) {
    /** Collects bounded diagnostic sections concurrently; individual failures remain in the snapshot. */
    public suspend fun healthSnapshot(options: DeviceHealthOptions = DeviceHealthOptions()): DeviceHealthSnapshot =
        DeviceHealthCollector.collect(this, options)

    /**
     * Executes a non-interactive legacy shell command as supplied, including shell metacharacters.
     * Returns UTF-8 output up to EOF; legacy shell provides no exit code or separate stderr.
     * Do not interpolate untrusted input. The client's timeout applies to the entire session.
     */
    public suspend fun shell(command: String, maxOutputBytes: Int = 16 * 1024 * 1024): String {
        validateCommand(command, maxOutputBytes)
        return client.session { protocol ->
            selectTransport(protocol)
            protocol.request("shell:$command")
            protocol.readShellOutput(maxOutputBytes)
        }
    }

    /** Executes shell v2 and returns separated UTF-8 streams and the remote exit code. */
    public suspend fun shellV2(command: String, maxOutputBytes: Int = 16 * 1024 * 1024): ShellResult {
        validateCommand(command, maxOutputBytes)
        return client.session { protocol ->
            selectTransport(protocol)
            protocol.request("shell,v2,raw:$command")
            ShellV2Protocol.read(protocol, maxOutputBytes)
        }
    }

    /**
     * Opens an owned, bidirectional Shell v2 session. An empty command starts the default shell.
     * The returned session must reach remote exit or be cancelled by the caller.
     */
    public suspend fun openInteractiveShell(
        command: String = "",
        options: InteractiveShellOptions = InteractiveShellOptions(),
    ): InteractiveShellSession {
        require('\u0000' !in command) { "Shell command cannot contain NUL" }
        val connection = client.openConnection()
        var failure: Throwable? = null
        try {
            selectTransport(connection.protocol)
            try {
                connection.protocol.request("shell,v2,raw:$command")
            } catch (error: AdbFailException) {
                throw ShellV2UnsupportedException(error)
            }
            return InteractiveShellSession(connection, options)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val primaryFailure = failure
            if (primaryFailure != null) {
                withContext(kotlinx.coroutines.NonCancellable) {
                    try { connection.close() } catch (closeError: Throwable) { primaryFailure.addSuppressed(closeError) }
                }
            }
        }
    }

    /** Reads one property without caching. An absent property returns an empty string. */
    public suspend fun getprop(name: String): String {
        require(name.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*"))) { "Invalid Android property name" }
        return shell("getprop '$name'").trimEnd('\r', '\n')
    }

    public suspend fun stat(path: String): RemoteFileStat = sync { it.stat(validatePath(path)) }
    public suspend fun list(path: String): List<RemoteFile> = sync { it.list(validatePath(path)) }
    public suspend fun pull(path: String, maxBytes: Int = 64 * 1024 * 1024): ByteArray {
        require(maxBytes > 0)
        return sync { it.pull(validatePath(path), maxBytes) }
    }

    /**
     * Streams a remote file without accumulating it in memory. Collection owns one ADB connection,
     * and cancellation closes that connection. Every emitted byte array is an independent chunk.
     */
    public fun pullChunks(
        remotePath: String,
        maxBytes: Long = Long.MAX_VALUE,
    ): Flow<ByteArray> {
        val path = validatePath(remotePath)
        require(maxBytes > 0) { "maxBytes must be positive" }
        return flow {
            client.streamingSession { protocol ->
                selectTransport(protocol)
                protocol.request("sync:")
                SyncProtocol(protocol).pull(path, maxBytes) { emit(it) }
            }
        }
    }

    /**
     * Downloads a remote file through a temporary sibling file and atomically replaces [destination]
     * when supported. Remote modification time and POSIX permissions are preserved by default.
     */
    public suspend fun pullTo(
        remotePath: String,
        destination: Path,
        maxBytes: Long = Long.MAX_VALUE,
        preserveAttributes: Boolean = true,
    ): SyncTransferResult = withContext(Dispatchers.IO) {
        val path = validatePath(remotePath)
        require(maxBytes > 0) { "maxBytes must be positive" }
        val absoluteDestination = destination.toAbsolutePath()
        val parent = requireNotNull(absoluteDestination.parent) { "Destination must have a parent directory" }
        val temporary = Files.createTempFile(parent, ".${absoluteDestination.fileName}.", ".part")
        try {
            var remoteStat: RemoteFileStat? = null
            val transferred = client.streamingSession { protocol ->
                selectTransport(protocol)
                protocol.request("sync:")
                val sync = SyncProtocol(protocol)
                remoteStat = sync.stat(path)
                Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING).buffered().use { output ->
                    sync.pull(path, maxBytes) { output.write(it) }
                }
            }
            moveReplacing(temporary, absoluteDestination)
            if (preserveAttributes) applyRemoteAttributes(absoluteDestination, requireNotNull(remoteStat))
            SyncTransferResult(transferred)
        } catch (error: Throwable) {
            Files.deleteIfExists(temporary)
            throw error
        }
    }

    public suspend fun push(
        data: ByteArray,
        remotePath: String,
        mode: Int = 0b110100100,
        modifiedAtEpochSeconds: Long = 0,
    ): Unit {
        require(mode in 0..0x1ff && modifiedAtEpochSeconds in 0..0xffffffffL)
        sync { it.push(validatePath(remotePath), data, mode, modifiedAtEpochSeconds) }
    }

    /**
     * Uploads chunks lazily and emits cumulative progress after every SYNC data frame. Large input
     * chunks are split into protocol-safe 64 KiB frames; empty chunks are ignored.
     */
    public fun pushChunks(
        chunks: Flow<ByteArray>,
        remotePath: String,
        mode: Int = DEFAULT_FILE_MODE,
        modifiedAtEpochSeconds: Long = 0,
        maxBytes: Long = Long.MAX_VALUE,
    ): Flow<SyncTransferProgress> {
        val path = validatePath(remotePath)
        validatePushArguments(mode, modifiedAtEpochSeconds, maxBytes)
        return flow {
            client.streamingSession { protocol ->
                selectTransport(protocol)
                protocol.request("sync:")
                SyncProtocol(protocol).push(path, chunks, mode, modifiedAtEpochSeconds, maxBytes) {
                    emit(SyncTransferProgress(it))
                }
            }
        }
    }

    /** Uploads [source] without loading it into memory and derives mode and timestamp when omitted. */
    public suspend fun push(
        source: Path,
        remotePath: String,
        mode: Int? = null,
        modifiedAtEpochSeconds: Long? = null,
        maxBytes: Long = Long.MAX_VALUE,
        chunkSize: Int = SyncProtocol.MAX_DATA,
    ): SyncTransferResult {
        require(chunkSize in 1..SyncProtocol.MAX_DATA) { "chunkSize must be in 1..${SyncProtocol.MAX_DATA}" }
        require(maxBytes > 0) { "maxBytes must be positive" }
        val (resolvedMode, resolvedModifiedAt) = withContext(Dispatchers.IO) {
            require(Files.isRegularFile(source)) { "Source must be a regular file: $source" }
            val size = Files.size(source)
            require(size <= maxBytes) { "Source contains $size bytes, exceeding maxBytes=$maxBytes" }
            (mode ?: localMode(source)) to (modifiedAtEpochSeconds
                ?: (Files.getLastModifiedTime(source).toMillis() / 1_000).coerceIn(0, 0xffffffffL))
        }
        val chunks = flow {
            Files.newInputStream(source).buffered().use { input ->
                val buffer = ByteArray(chunkSize)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) emit(buffer.copyOf(count))
                }
            }
        }.flowOn(Dispatchers.IO)
        var transferred = 0L
        pushChunks(chunks, remotePath, resolvedMode, resolvedModifiedAt, maxBytes).collect {
            transferred = it.bytesTransferred
        }
        return SyncTransferResult(transferred)
    }

    /** Uploads an APK through SYNC, invokes Package Manager and removes the temporary file. */
    public suspend fun install(apk: ByteArray, options: InstallOptions = InstallOptions()): InstallResult {
        require(apk.isNotEmpty()) { "APK cannot be empty" }
        val remote = "/data/local/tmp/adb-utils-${apk.size}-${apk.contentHashCode().toUInt()}.apk"
        push(apk, remote)
        var failure: Throwable? = null
        return try {
            val flags = buildList {
                if (options.replace) add("-r")
                if (options.grantRuntimePermissions) add("-g")
                if (options.allowTestPackages) add("-t")
                if (options.allowDowngrade) add("-d")
            }.joinToString(" ")
            val result = shellV2("pm install $flags '$remote'")
            val message = (result.stdout + result.stderr).trim()
            if (result.exitCode != 0 || message.lineSequence().none { it.trim() == "Success" }) {
                throw PackageOperationException(if (message.isEmpty()) "Package installation failed" else message)
            }
            InstallResult(message)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try { shellV2("rm -f '$remote'") } catch (cleanupError: Throwable) {
                if (failure != null) failure.addSuppressed(cleanupError) else throw cleanupError
            }
        }
    }

    public suspend fun uninstall(packageName: String, keepData: Boolean = false): Unit {
        require(packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) { "Invalid package name" }
        val result = shellV2("pm uninstall ${if (keepData) "-k " else ""}'$packageName'")
        val message = (result.stdout + result.stderr).trim()
        if (result.exitCode != 0 || message.lineSequence().none { it.trim() == "Success" }) {
            throw PackageOperationException(if (message.isEmpty()) "Package uninstall failed" else message)
        }
    }

    public suspend fun forward(local: TcpPort, remote: TcpPort, noRebind: Boolean = false): Unit {
        client.hostCommand("host-serial:$serial:forward:${if (noRebind) "norebind:" else ""}$local;$remote")
    }

    public suspend fun removeForward(local: TcpPort): Unit {
        client.hostCommand("host-serial:$serial:killforward:$local")
    }

    public suspend fun listForwards(): List<PortForward> = client.hostPayload("host:list-forward")
        .lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
            val fields = line.split(Regex("\\s+"))
            if (fields.size != 3 || fields[0] != serial.value) null else PortForward(serial, parseTcp(fields[1]), parseTcp(fields[2]))
        }.toList()

    public suspend fun reverse(remote: TcpPort, local: TcpPort, noRebind: Boolean = false): Unit = deviceCommand(
        "reverse:forward:${if (noRebind) "norebind:" else ""}$remote;$local",
    )

    public suspend fun removeReverse(remote: TcpPort): Unit = deviceCommand("reverse:killforward:$remote")

    public suspend fun listReverses(): List<ReverseForward> = client.session { protocol ->
        selectTransport(protocol)
        protocol.request("reverse:list-forward")
        protocol.readPayload().lineSequence().filter { it.isNotBlank() }.map { line ->
            val fields = line.split(Regex("\\s+"))
            if (fields.size !in 2..3) throw AdbProtocolException("Malformed reverse forward entry: $line")
            if (fields.size == 2) return@map ReverseForward(parseTcp(fields[0]), parseTcp(fields[1]))
            ReverseForward(parseTcp(fields[1]), parseTcp(fields[2]))
        }.toList()
    }

    private suspend fun deviceCommand(service: String) = client.session { protocol ->
        selectTransport(protocol)
        protocol.request(service)
    }

    private fun parseTcp(value: String): TcpPort {
        if (!value.startsWith("tcp:")) throw AdbProtocolException("Unsupported endpoint: $value")
        return value.removePrefix("tcp:").toIntOrNull()?.let(::TcpPort)
            ?: throw AdbProtocolException("Invalid TCP endpoint: $value")
    }

    private suspend fun <T> sync(block: suspend (SyncProtocol) -> T): T = client.session { protocol ->
        selectTransport(protocol)
        protocol.request("sync:")
        block(SyncProtocol(protocol))
    }

    private fun validatePath(path: String): String {
        require(path.isNotBlank() && '\u0000' !in path) { "Remote path cannot be blank or contain NUL" }
        return path
    }

    internal suspend fun selectTransport(protocol: io.github.desodre.adbutils.protocol.AdbProtocol) {
        try { protocol.request("host:transport:$serial") } catch (error: AdbFailException) {
            val reason = error.reason.lowercase()
            throw when {
                "unauthorized" in reason -> DeviceUnauthorizedException(serial, error)
                "offline" in reason -> DeviceOfflineException(serial, error)
                "not found" in reason -> DeviceNotFoundException(serial, error)
                else -> error
            }
        }
    }

    private fun validateCommand(command: String, maxOutputBytes: Int) {
        require(command.isNotBlank() && '\u0000' !in command) { "A non-empty shell command without NUL is required" }
        require(maxOutputBytes > 0)
    }

    private fun validatePushArguments(mode: Int, modifiedAtEpochSeconds: Long, maxBytes: Long) {
        require(mode in 0..0x1ff) { "mode must contain only POSIX permission bits" }
        require(modifiedAtEpochSeconds in 0..0xffffffffL) { "Modification time is outside SYNC v1 range" }
        require(maxBytes > 0) { "maxBytes must be positive" }
    }

    private fun localMode(source: Path): Int = try {
        Files.getPosixFilePermissions(source).fold(0) { mode, permission ->
            mode or when (permission) {
                PosixFilePermission.OWNER_READ -> 0b100000000
                PosixFilePermission.OWNER_WRITE -> 0b010000000
                PosixFilePermission.OWNER_EXECUTE -> 0b001000000
                PosixFilePermission.GROUP_READ -> 0b000100000
                PosixFilePermission.GROUP_WRITE -> 0b000010000
                PosixFilePermission.GROUP_EXECUTE -> 0b000001000
                PosixFilePermission.OTHERS_READ -> 0b000000100
                PosixFilePermission.OTHERS_WRITE -> 0b000000010
                PosixFilePermission.OTHERS_EXECUTE -> 0b000000001
            }
        }
    } catch (_: UnsupportedOperationException) {
        DEFAULT_FILE_MODE
    }

    private fun applyRemoteAttributes(path: Path, stat: RemoteFileStat) {
        Files.setLastModifiedTime(path, FileTime.fromMillis(stat.modifiedAtEpochSeconds * 1_000))
        val permissions = PosixFilePermission.entries.filterTo(mutableSetOf()) { permission ->
            stat.mode and permissionMask(permission) != 0
        }
        try {
            Files.setPosixFilePermissions(path, permissions)
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX filesystems still preserve the modification time.
        }
    }

    private fun permissionMask(permission: PosixFilePermission): Int = when (permission) {
        PosixFilePermission.OWNER_READ -> 0b100000000
        PosixFilePermission.OWNER_WRITE -> 0b010000000
        PosixFilePermission.OWNER_EXECUTE -> 0b001000000
        PosixFilePermission.GROUP_READ -> 0b000100000
        PosixFilePermission.GROUP_WRITE -> 0b000010000
        PosixFilePermission.GROUP_EXECUTE -> 0b000001000
        PosixFilePermission.OTHERS_READ -> 0b000000100
        PosixFilePermission.OTHERS_WRITE -> 0b000000010
        PosixFilePermission.OTHERS_EXECUTE -> 0b000000001
    }

    private fun moveReplacing(source: Path, destination: Path) {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val DEFAULT_FILE_MODE: Int = 0b110100100
    }
}
