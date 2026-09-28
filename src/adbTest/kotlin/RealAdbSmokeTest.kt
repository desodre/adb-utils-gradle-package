import io.github.desodre.adbutils.client.AdbClient
import io.github.desodre.adbutils.client.AdbDevice
import io.github.desodre.adbutils.model.DeviceSerial
import io.github.desodre.adbutils.model.HealthSection
import io.github.desodre.adbutils.model.InstallOptions
import io.github.desodre.adbutils.model.LogcatFilter
import io.github.desodre.adbutils.model.LogcatOptions
import io.github.desodre.adbutils.model.LogcatPriority
import io.github.desodre.adbutils.model.ShellOutputStream
import io.github.desodre.adbutils.model.ShellTermination
import io.github.desodre.adbutils.model.TcpPort
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

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

        step("finite-shell")
        assertEquals(7, device.shellV2("sh -c 'exit 7'").exitCode)
        step("health")
        val health = device.healthSnapshot()
        assertIs<HealthSection.Available<*>>(health.uptime)
        assertIs<HealthSection.Available<*>>(health.android)

        step("interactive-shell") { validateInteractiveShell(device) }
        step("sync-and-forward") { validateSyncAndForwarding(device) }
        step("fixture-screenshot-logcat") { validateFixtureScreenshotAndLogcat(device, fixtureApk) }
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
        val content = "adb-utils".encodeToByteArray()
        try {
            device.push(content, path)
            assertContentEquals(content, device.pull(path))
            assertEquals(content.size.toLong(), device.stat(path).size)
        } finally {
            device.shellV2("rm -f '$path'")
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
    }

    private suspend fun validateFixtureScreenshotAndLogcat(device: AdbDevice, fixtureApk: Path) {
        val packageName = "io.github.desodre.adbutils.fixture"
        val component = "$packageName/.FixtureActivity"
        step("fixture-clean-before")
        runCatching { device.uninstall(packageName) }
        try {
            step("fixture-install")
            device.install(Files.readAllBytes(fixtureApk), InstallOptions(replace = true))
            step("fixture-logcat")
            kotlinx.coroutines.coroutineScope {
                val marker = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(15_000) {
                        device.logcat(
                            LogcatOptions(
                                filters = listOf(LogcatFilter("AdbUtilsFixture", LogcatPriority.INFO)),
                            ),
                        ).first { it.message.contains("fixture-ready") }
                    }
                }
                delay(500)
                val start = device.shellV2("am start -W -n '$component'")
                assertEquals(0, start.exitCode, start.stderr)
                assertEquals("fixture-ready", marker.await().message)
            }

            step("fixture-screenshot-memory")
            val png = device.screenshot()
            assertContentEquals(PNG_SIGNATURE, png.copyOf(PNG_SIGNATURE.size))
            val destination = Files.createTempFile("adb-utils-device-", ".png")
            try {
                step("fixture-screenshot-path")
                assertTrue(device.screenshotTo(destination) >= PNG_SIGNATURE.size)
                assertContentEquals(PNG_SIGNATURE, Files.readAllBytes(destination).copyOf(PNG_SIGNATURE.size))
            } finally {
                Files.deleteIfExists(destination)
            }
        } finally {
            runCatching { device.shellV2("am force-stop '$packageName'") }
            runCatching { device.uninstall(packageName) }
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
        val PNG_SIGNATURE: ByteArray = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        )
    }
}
