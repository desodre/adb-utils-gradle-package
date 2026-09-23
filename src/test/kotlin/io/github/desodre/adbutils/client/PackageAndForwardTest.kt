package io.github.desodre.adbutils.client

import kotlinx.coroutines.runBlocking
import kotlin.test.*
import io.github.desodre.adbutils.error.PackageOperationException
import io.github.desodre.adbutils.model.*
import io.github.desodre.adbutils.protocol.FakeTransport

class PackageAndForwardTest {
    private fun shellResponse(stdout: String, exit: Int = 0): ByteArray {
        fun frame(id: Int, bytes: ByteArray) = byteArrayOf(id.toByte()) +
            byteArrayOf(bytes.size.toByte(), 0, 0, 0) + bytes
        return "OKAYOKAY".encodeToByteArray() + frame(1, stdout.encodeToByteArray()) + frame(3, byteArrayOf(exit.toByte()))
    }

    @Test fun `install uses SYNC package manager and cleanup sessions`() = runBlocking<Unit> {
        val sync = FakeTransport("OKAYOKAYOKAY")
        val install = FakeTransport(shellResponse("Success\n"))
        val cleanup = FakeTransport(shellResponse(""))
        val queue = ArrayDeque(listOf(sync, install, cleanup))
        val device = AdbDevice(AdbClient(transportFactory = { queue.removeFirst() }), DeviceSerial("a"))
        assertEquals("Success", device.install(byteArrayOf(1, 2), InstallOptions(grantRuntimePermissions = true)).message)
        assertTrue(sync.requests.any { it.startsWith("SEND") })
        assertTrue(sync.requests.any { "adb-utils-2-" in it })
        assertTrue(install.requests.last().contains("pm install -r -g"))
        assertTrue(cleanup.requests.last().contains("rm -f"))
    }

    @Test fun `uninstall validates package and reports failure`() = runBlocking<Unit> {
        val transport = FakeTransport(shellResponse("Failure [missing]\n", 1))
        val device = AdbDevice(AdbClient(transportFactory = { transport }), DeviceSerial("a"))
        assertFailsWith<PackageOperationException> { device.uninstall("com.example.app") }
        assertFailsWith<IllegalArgumentException> { device.uninstall("bad;name") }
    }

    @Test fun `forward reverse list and remove encode services`() = runBlocking<Unit> {
        val forward = FakeTransport("OKAY")
        val listingPayload = "a tcp:7000 tcp:8000\nb tcp:1 tcp:2\n"
        val listing = FakeTransport("OKAY" + listingPayload.length.toString(16).padStart(4, '0') + listingPayload)
        val remove = FakeTransport("OKAY")
        val reverse = FakeTransport("OKAYOKAY")
        val reversePayload = "host tcp:9000 tcp:10000\n"
        val reverseList = FakeTransport("OKAYOKAY" + reversePayload.length.toString(16).padStart(4, '0') + reversePayload)
        val reverseRemove = FakeTransport("OKAYOKAY")
        val queue = ArrayDeque(listOf(forward, listing, remove, reverse, reverseList, reverseRemove))
        val device = AdbDevice(AdbClient(transportFactory = { queue.removeFirst() }), DeviceSerial("a"))
        device.forward(TcpPort(7000), TcpPort(8000), true)
        assertTrue(forward.requests.single().endsWith("host-serial:a:forward:norebind:tcp:7000;tcp:8000"))
        assertEquals(TcpPort(8000), device.listForwards().single().remote)
        device.removeForward(TcpPort(7000))
        device.reverse(TcpPort(9000), TcpPort(10000))
        assertEquals(TcpPort(9000), device.listReverses().single().remote)
        device.removeReverse(TcpPort(9000))
        assertTrue(queue.isEmpty())
    }

    @Test fun `reverse list accepts current two-column format`() = runBlocking<Unit> {
        val payload = "tcp:9000 tcp:10000\n"
        val transport = FakeTransport("OKAYOKAY" + payload.length.toString(16).padStart(4, '0') + payload)
        val device = AdbDevice(AdbClient(transportFactory = { transport }), DeviceSerial("a"))
        assertEquals(ReverseForward(TcpPort(9000), TcpPort(10000)), device.listReverses().single())
    }

    @Test fun `reverse list accepts transport name prefix`() = runBlocking<Unit> {
        val payload = "UsbFfs tcp:9000 tcp:10000\n"
        val transport = FakeTransport("OKAYOKAY" + payload.length.toString(16).padStart(4, '0') + payload)
        val device = AdbDevice(AdbClient(transportFactory = { transport }), DeviceSerial("a"))
        assertEquals(ReverseForward(TcpPort(9000), TcpPort(10000)), device.listReverses().single())
    }

    @Test fun `TCP endpoints reject invalid ports`() {
        assertFailsWith<IllegalArgumentException> { TcpPort(0) }
        assertFailsWith<IllegalArgumentException> { TcpPort(65536) }
    }
}
