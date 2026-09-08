import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import io.github.desodre.adbutils.client.AdbClient
import io.github.desodre.adbutils.model.TcpPort
import kotlin.test.*

class RealAdbSmokeTest {
    @Test fun `release smoke test`() = runBlocking<Unit> {
        val adb = AdbClient(timeoutMillis = 15_000)
        val device = adb.device()
        assertTrue(adb.trackDevices().first().any { it.serial == device.serial })
        assertEquals(7, device.shellV2("sh -c 'exit 7'").exitCode)

        val path = "/data/local/tmp/adb-utils-release-smoke.txt"
        val content = "adb-utils".encodeToByteArray()
        try {
            device.push(content, path)
            assertContentEquals(content, device.pull(path))
            assertEquals(content.size.toLong(), device.stat(path).size)
        } finally { device.shellV2("rm -f '$path'") }

        val forward = TcpPort(43171)
        try { device.forward(forward, TcpPort(43172)); assertTrue(device.listForwards().any { it.local == forward }) }
        finally { device.removeForward(forward) }
    }
}
