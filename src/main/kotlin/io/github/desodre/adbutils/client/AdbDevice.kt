package io.github.desodre.adbutils.client

import io.github.desodre.adbutils.error.*
import io.github.desodre.adbutils.model.DeviceSerial
import io.github.desodre.adbutils.model.ShellResult
import io.github.desodre.adbutils.protocol.ShellV2Protocol
import io.github.desodre.adbutils.protocol.SyncProtocol
import io.github.desodre.adbutils.model.*

/** A serial-bound handle, not a persistent connection or a guarantee that the device remains online. */
class AdbDevice internal constructor(private val client: AdbClient, val serial: DeviceSerial) {
    /**
     * Executes a non-interactive legacy shell command as supplied, including shell metacharacters.
     * Returns UTF-8 output up to EOF; legacy shell provides no exit code or separate stderr.
     * Do not interpolate untrusted input. The client's timeout applies to the entire session.
     */
    suspend fun shell(command: String, maxOutputBytes: Int = 16 * 1024 * 1024): String {
        validateCommand(command, maxOutputBytes)
        return client.session { protocol ->
            selectTransport(protocol)
            protocol.request("shell:$command")
            protocol.readShellOutput(maxOutputBytes)
        }
    }

    /** Executes shell v2 and returns separated UTF-8 streams and the remote exit code. */
    suspend fun shellV2(command: String, maxOutputBytes: Int = 16 * 1024 * 1024): ShellResult {
        validateCommand(command, maxOutputBytes)
        return client.session { protocol ->
            selectTransport(protocol)
            protocol.request("shell,v2,raw:$command")
            ShellV2Protocol.read(protocol, maxOutputBytes)
        }
    }

    /** Reads one property without caching. An absent property returns an empty string. */
    suspend fun getprop(name: String): String {
        require(name.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*"))) { "Invalid Android property name" }
        return shell("getprop '$name'").trimEnd('\r', '\n')
    }

    suspend fun stat(path: String): RemoteFileStat = sync { it.stat(validatePath(path)) }
    suspend fun list(path: String): List<RemoteFile> = sync { it.list(validatePath(path)) }
    suspend fun pull(path: String, maxBytes: Int = 64 * 1024 * 1024): ByteArray {
        require(maxBytes > 0)
        return sync { it.pull(validatePath(path), maxBytes) }
    }
    suspend fun push(
        data: ByteArray,
        remotePath: String,
        mode: Int = 0b110100100,
        modifiedAtEpochSeconds: Long = 0,
    ) {
        require(mode in 0..0x1ff && modifiedAtEpochSeconds in 0..0xffffffffL)
        sync { it.push(validatePath(remotePath), data, mode, modifiedAtEpochSeconds) }
    }

    /** Uploads an APK through SYNC, invokes Package Manager and removes the temporary file. */
    suspend fun install(apk: ByteArray, options: InstallOptions = InstallOptions()): InstallResult {
        require(apk.isNotEmpty()) { "APK cannot be empty" }
        val remote = "/data/local/tmp/droidscope-${apk.size}-${apk.contentHashCode().toUInt()}.apk"
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

    suspend fun uninstall(packageName: String, keepData: Boolean = false) {
        require(packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) { "Invalid package name" }
        val result = shellV2("pm uninstall ${if (keepData) "-k " else ""}'$packageName'")
        val message = (result.stdout + result.stderr).trim()
        if (result.exitCode != 0 || message.lineSequence().none { it.trim() == "Success" }) {
            throw PackageOperationException(if (message.isEmpty()) "Package uninstall failed" else message)
        }
    }

    suspend fun forward(local: TcpPort, remote: TcpPort, noRebind: Boolean = false) {
        client.hostCommand("host-serial:$serial:forward:${if (noRebind) "norebind:" else ""}$local;$remote")
    }

    suspend fun removeForward(local: TcpPort) {
        client.hostCommand("host-serial:$serial:killforward:$local")
    }

    suspend fun listForwards(): List<PortForward> = client.hostPayload("host:list-forward")
        .lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
            val fields = line.split(Regex("\\s+"))
            if (fields.size != 3 || fields[0] != serial.value) null else PortForward(serial, parseTcp(fields[1]), parseTcp(fields[2]))
        }.toList()

    suspend fun reverse(remote: TcpPort, local: TcpPort, noRebind: Boolean = false) = deviceCommand(
        "reverse:forward:${if (noRebind) "norebind:" else ""}$remote;$local",
    )

    suspend fun removeReverse(remote: TcpPort) = deviceCommand("reverse:killforward:$remote")

    suspend fun listReverses(): List<ReverseForward> = client.session { protocol ->
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
}
