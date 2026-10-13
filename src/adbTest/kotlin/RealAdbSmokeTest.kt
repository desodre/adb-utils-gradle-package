import io.github.desodre.adbutils.client.AdbClient
import io.github.desodre.adbutils.client.AdbDevice
import io.github.desodre.adbutils.error.AdbFailException
import io.github.desodre.adbutils.error.ShellV2UnsupportedException
import io.github.desodre.adbutils.model.DeviceSerial
import io.github.desodre.adbutils.model.HealthSection
import io.github.desodre.adbutils.model.InstallOptions
import io.github.desodre.adbutils.model.JdwpPid
import io.github.desodre.adbutils.model.LocalAbstract
import io.github.desodre.adbutils.model.LogcatFilter
import io.github.desodre.adbutils.model.LogcatOptions
import io.github.desodre.adbutils.model.LogcatPriority
import io.github.desodre.adbutils.model.ShellOutputStream
import io.github.desodre.adbutils.model.ShellTermination
import io.github.desodre.adbutils.model.TcpPort
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext

class RealAdbSmokeTest {
    @Test fun `release candidate smoke test on explicit target`() = runBlocking<Unit> {
        val serial = DeviceSerial(requiredProperty("adb.serial"))
        val targetKind = requiredProperty("adb.target.kind")
        val fixtureApk = Path.of(requiredProperty("adb.fixture.apk"))
        check(Files.isRegularFile(fixtureApk)) { "Fixture APK does not exist: $fixtureApk" }

        val adb = AdbClient(timeoutMillis = 30_000)
        val device = adb.device(serial)
        assertTrue(adb.trackDevices().first().any { it.serial == serial })
        val model = device.getprop("ro.product.model")
        val apiLevel = device.getprop("ro.build.version.sdk")
        val abi = device.getprop("ro.product.cpu.abi")
        println("ADB_TEST_TARGET kind=$targetKind model=$model api=$apiLevel abi=$abi")

        step("legacy-shell")
        assertEquals("adb-utils-legacy", device.shell("echo adb-utils-legacy").trim())
        step("finite-shell-v2")
        val shellV2Available = try {
            val result = device.shellV2("sh -c 'printf out; printf err >&2; exit 7'")
            assertEquals("out", result.stdout)
            assertEquals("err", result.stderr)
            assertEquals(7, result.exitCode)
            true
        } catch (error: ShellV2UnsupportedException) {
            if (apiLevel == "21" && (error.cause as? AdbFailException)?.reason == "closed") {
                println("ADB_TEST_LIMITATION shell-v2-unavailable-on-api-21")
                false
            } else throw error
        }
        step("health")
        val health = device.healthSnapshot()
        if (shellV2Available) {
            assertIs<HealthSection.Available<*>>(health.uptime)
            assertIs<HealthSection.Available<*>>(health.android)
        } else {
            assertIs<HealthSection.Unavailable>(health.uptime)
            assertIs<HealthSection.Unavailable>(health.android)
        }

        if (shellV2Available) step("interactive-shell") { validateInteractiveShell(device) }
        step("sync-and-forward") { validateSyncAndForwarding(device) }
        if (shellV2Available) {
            step("fixture-screenshot-logcat") { validateFixtureScreenshotAndLogcat(device, fixtureApk, apiLevel) }
        } else {
            println("ADB_TEST_LIMITATION package-and-logcat-require-shell-v2-on-api-21")
            step("legacy-screenshot") { validateScreenshot(device) }
        }
    }

    private suspend fun validateInteractiveShell(device: AdbDevice) {
        val session = device.openInteractiveShell("sh")
        kotlinx.coroutines.coroutineScope {
            val output = async { session.output.toList() }
            session.writeStdin("printf adb-utils-interactive; exit 0\n".encodeToByteArray())
            session.closeStdin()
            assertEquals(ShellTermination.Exited(0), withTimeout(15_000) { session.awaitTermination() })
            assertTrue(output.await().filter { it.stream == ShellOutputStream.STDOUT }
                .flatMap { it.data.toList() }.toByteArray().decodeToString().contains("adb-utils-interactive"))
        }
    }

    private suspend fun validateSyncAndForwarding(device: AdbDevice) {
        val path = "/data/local/tmp/adb-utils-release-smoke-${UUID.randomUUID()}.txt"
        val content = ByteArray(70_000) { (it % 251).toByte() }
        try {
            val progress = device.pushChunks(flowOf(content), path).toList()
            assertEquals(content.size.toLong(), progress.last().bytesTransferred)
            assertContentEquals(content, device.pull(path))
            assertContentEquals(content, device.pullChunks(path).toList().flatMap { it.toList() }.toByteArray())
            assertEquals(content.size.toLong(), device.stat(path).size)
            assertTrue(device.list("/data/local/tmp").any { it.name == path.substringAfterLast('/') })
        } finally {
            device.shell("rm -f '$path'")
        }

        val occupiedPorts = device.listForwards().mapTo(mutableSetOf()) { it.local.value }
        val forward = (43171..43270).firstOrNull { it !in occupiedPorts }?.let(::TcpPort)
            ?: error("No free host port in the adbTest range 43171..43270")
        var forwardCreated = false
        try {
            device.forward(forward, TcpPort(43172), noRebind = true)
            forwardCreated = true
            assertTrue(device.listForwards().any { it.local == forward })
        } finally {
            if (forwardCreated) device.removeForward(forward)
        }

        val reverseRemote = TcpPort(43271)
        var reverseCreated = false
        try {
            device.reverse(reverseRemote, TcpPort(43272), noRebind = true)
            reverseCreated = true
            assertTrue(device.listReverses().any { it.remote == reverseRemote })
        } finally {
            if (reverseCreated) device.removeReverse(reverseRemote)
        }
    }

    private suspend fun validateFixtureScreenshotAndLogcat(device: AdbDevice, fixtureApk: Path, apiLevel: String) {
        val packageName = "io.github.desodre.adbutils.fixture"
        val component = "$packageName/.FixtureActivity"
        val abstractEndpoint = LocalAbstract("adb-utils-fixture-${UUID.randomUUID()}")
        val readyPrefix = "fixture-ready:${abstractEndpoint.name}:pid="
        var fixturePid: JdwpPid? = null
        step("fixture-preflight")
        check(device.shell("pm path '$packageName'").lineSequence().none { it.startsWith("package:") }) {
            "Fixture package is already installed; refusing to replace an existing app"
        }
        try {
            step("fixture-install")
            device.install(Files.readAllBytes(fixtureApk), InstallOptions(replace = true))
            val installedPath = device.shellV2("pm path '$packageName'")
            println("ADB_TEST_FIXTURE_VISIBLE exit=${installedPath.exitCode} present=${installedPath.stdout.contains("package:")}")
            val currentUser = device.shellV2("am get-current-user")
            println("ADB_TEST_CURRENT_USER exit=${currentUser.exitCode} value=${currentUser.stdout.trim()}")
            val installedForUser = device.shellV2("pm list packages --user 0 '$packageName'")
            println("ADB_TEST_FIXTURE_USER_0 exit=${installedForUser.exitCode} present=${installedForUser.stdout.contains("package:$packageName")}")
            val packageDump = device.shellV2("dumpsys package '$packageName'")
            println("ADB_TEST_FIXTURE_ACTIVITY_REGISTERED exit=${packageDump.exitCode} present=${packageDump.stdout.contains("FixtureActivity")}")
            val resolvedActivity = device.shellV2(
                "cmd package resolve-activity --brief -a android.intent.action.MAIN " +
                    "-c android.intent.category.LAUNCHER -p '$packageName'",
            )
            println("ADB_TEST_FIXTURE_RESOLVED exit=${resolvedActivity.exitCode} value=${resolvedActivity.stdout.trim()}")
            step("fixture-logcat")
            kotlinx.coroutines.coroutineScope {
                val marker = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(75_000) {
                        device.logcat(
                            LogcatOptions(
                                filters = listOf(LogcatFilter("AdbUtilsFixture", LogcatPriority.INFO)),
                            ),
                        ).first { it.message.startsWith(readyPrefix) }
                    }
                }
                delay(500)
                withTimeout(60_000) {
                    var attempt = 0
                    while (true) {
                        attempt++
                        val start = device.shellV2(
                            "am start -W -n '$component' --es forward_socket '${abstractEndpoint.name}'",
                        )
                        if (start.exitCode == 0) {
                            println("ADB_TEST_ACTIVITY_START attempts=$attempt")
                            break
                        }
                        val output = start.stdout + start.stderr
                        val transientEmulatorFailure = apiLevel == "37" &&
                            (output.contains("Error type 3") || output.contains("Broken pipe") ||
                                output.contains("Can't find service: activity"))
                        assertTrue(transientEmulatorFailure, "Activity launch failed: exit=${start.exitCode} output=$output")
                        println("ADB_TEST_ACTIVITY_RETRY attempt=$attempt")
                        delay(2_000)
                    }
                }
                fixturePid = JdwpPid(marker.await().message.removePrefix(readyPrefix).trim().toInt())
            }
            step("jdwp-handshake") { validateJdwpHandshake(device, checkNotNull(fixturePid)) }
            step("abstract-forward-echo") { validateAbstractForward(device, abstractEndpoint) }
            step("abstract-reverse-echo") { validateAbstractReverse(device, component) }
            validateScreenshot(device)
            step("jdwp-terminated-target") {
                device.shell("am force-stop '$packageName'")
                validateTerminatedJdwpTarget(device, checkNotNull(fixturePid))
            }
        } finally {
            runCatching { device.shell("am force-stop '$packageName'") }
            runCatching { device.uninstall(packageName) }
        }
    }

    private suspend fun validateJdwpHandshake(device: AdbDevice, pid: JdwpPid) {
        val local = unusedHostPort()
        var created = false
        try {
            device.forward(local, pid, noRebind = true)
            created = true
            assertTrue(device.listForwardMappings().any { it.local == local && it.remote == pid })
            assertTrue(device.listForwards().none { it.local == local }, "Legacy listing must retain only TCP/TCP mappings")
            withContext(Dispatchers.IO) { jdwpHandshake(local) }
        } finally {
            if (created) device.removeForward(local)
        }
    }

    private suspend fun validateTerminatedJdwpTarget(device: AdbDevice, pid: JdwpPid) {
        val local = unusedHostPort()
        var created = false
        try {
            // Some servers reject here; others install the listener and reject only when a client attaches.
            try {
                device.forward(local, pid, noRebind = true)
                created = true
            } catch (error: AdbFailException) {
                assertTrue(
                    listOf("jdwp", "pid", "process").any { error.reason.contains(it, ignoreCase = true) },
                    "Forward registration failed for a reason unrelated to the terminated JDWP target: ${error.reason}",
                )
                return
            }
            val failure = withContext(Dispatchers.IO) { runCatching { jdwpHandshake(local) }.exceptionOrNull() }
            assertTrue(
                failure is EOFException || failure is SocketException,
                "A terminated JDWP target must close/reset the connection, not accept a handshake or time out; result=$failure",
            )
        } finally {
            if (created) device.removeForward(local)
        }
    }

    private fun jdwpHandshake(local: TcpPort) {
        connectHost(local).use { socket ->
            val handshake = "JDWP-Handshake".encodeToByteArray()
            socket.getOutputStream().write(handshake)
            socket.getOutputStream().flush()
            val reply = ByteArray(handshake.size)
            DataInputStream(socket.getInputStream()).readFully(reply)
            assertContentEquals(handshake, reply)
        }
    }

    private suspend fun validateAbstractForward(device: AdbDevice, remote: LocalAbstract) {
        val local = unusedHostPort()
        var created = false
        try {
            device.forward(local, remote, noRebind = true)
            created = true
            assertTrue(device.listForwardMappings().any { it.local == local && it.remote == remote })
            assertTrue(device.listForwards().none { it.local == local })
            withContext(Dispatchers.IO) { connectHost(local).use(::assertEcho) }
        } finally {
            if (created) device.removeForward(local)
        }
    }

    private suspend fun validateAbstractReverse(device: AdbDevice, component: String) {
        val remote = LocalAbstract("adb-utils-reverse-${UUID.randomUUID()}")
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = SOCKET_TIMEOUT_MILLIS
            val local = TcpPort(server.localPort)
            var created = false
            try {
                device.reverse(remote, local, noRebind = true)
                created = true
                assertTrue(device.listReverseMappings().any { it.remote == remote && it.local == local })
                assertTrue(device.listReverses().none { it.local == local })
                val start = device.shellV2(
                    "am start -W -f 0x20000000 -n '$component' --es reverse_socket '${remote.name}'",
                )
                assertEquals(0, start.exitCode, "Cannot request fixture reverse connection")
                withContext(Dispatchers.IO) {
                    server.accept().use { socket ->
                        socket.soTimeout = SOCKET_TIMEOUT_MILLIS
                        assertEcho(socket)
                    }
                }
            } finally {
                if (created) device.removeReverse(remote)
            }
        }
    }

    private fun assertEcho(socket: Socket) {
        val message = "adb-utils-echo-${UUID.randomUUID()}"
        val output = DataOutputStream(socket.getOutputStream())
        output.writeUTF(message)
        output.flush()
        assertEquals(message, DataInputStream(socket.getInputStream()).readUTF())
    }

    private fun connectHost(port: TcpPort): Socket = Socket().apply {
        try {
            soTimeout = SOCKET_TIMEOUT_MILLIS
            connect(InetSocketAddress("127.0.0.1", port.value), SOCKET_TIMEOUT_MILLIS)
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    private fun unusedHostPort(): TcpPort =
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { TcpPort(it.localPort) }

    private suspend fun validateScreenshot(device: AdbDevice) {
        step("screenshot-memory")
        val png = device.screenshot()
        assertContentEquals(PNG_SIGNATURE, png.copyOf(PNG_SIGNATURE.size))
        val destination = Files.createTempFile("adb-utils-device-", ".png")
        try {
            step("screenshot-path")
            assertTrue(device.screenshotTo(destination) >= PNG_SIGNATURE.size)
            assertContentEquals(PNG_SIGNATURE, Files.readAllBytes(destination).copyOf(PNG_SIGNATURE.size))
        } finally {
            Files.deleteIfExists(destination)
        }
    }

    private fun requiredProperty(name: String): String = checkNotNull(System.getProperty(name)?.takeIf { it.isNotBlank() }) {
        "Missing required test system property: $name"
    }

    private fun step(name: String) {
        println("ADB_TEST_STEP $name")
    }

    private suspend fun step(name: String, block: suspend () -> Unit) {
        step(name)
        block()
        println("ADB_TEST_STEP $name passed")
    }

    private companion object {
        const val SOCKET_TIMEOUT_MILLIS: Int = 10_000
        val PNG_SIGNATURE: ByteArray = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        )
    }
}
