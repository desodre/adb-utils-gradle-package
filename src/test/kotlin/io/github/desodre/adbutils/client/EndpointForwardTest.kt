package io.github.desodre.adbutils.client

import io.github.desodre.adbutils.error.*
import io.github.desodre.adbutils.model.*
import io.github.desodre.adbutils.protocol.FakeTransport
import io.github.desodre.adbutils.protocol.parseEndpoint
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class EndpointForwardTest {
    private val serial = DeviceSerial("test-device")
    private fun device(transport: FakeTransport) = AdbDevice(AdbClient(transportFactory = { transport }), serial)
    private fun payload(text: String) = text.encodeToByteArray().size.toString(16).padStart(4, '0') + text
    private val sockets = listOf<AdbEndpoint>(TcpPort(8700), LocalAbstract("socket"), LocalReserved("socket"), LocalFilesystem("/tmp/socket"))
    private val destinations = sockets + listOf(JdwpPid(1234), DeviceFile("/dev/test"), RawDeviceFile("/dev/test"))

    @Test fun `all supported endpoints round trip with exact wire forms`() {
        val expected = listOf("tcp:8700", "localabstract:socket", "localreserved:socket", "localfilesystem:/tmp/socket",
            "jdwp:1234", "dev:/dev/test", "dev-raw:/dev/test")
        destinations.zip(expected).forEach { (endpoint, wire) ->
            assertEquals(wire, endpoint.toString())
            assertEquals(endpoint, parseEndpoint(wire))
        }
        assertEquals(LocalAbstract("ação-🚀"), parseEndpoint("localabstract:ação-🚀"))
    }

    @Test fun `all forward combinations encode serial and noRebind`() = runBlocking<Unit> {
        for (local in sockets) for (remote in destinations) for (noRebind in listOf(false, true)) {
            val transport = FakeTransport("OKAYOKAY")
            device(transport).forward(local, remote, noRebind)
            assertEquals("host-serial:$serial:forward:${if (noRebind) "norebind:" else ""}$local;$remote", transport.requests.single().drop(4))
            assertTrue(transport.closed)
        }
    }

    @Test fun `reverse combinations and endpoint removals encode correctly`() = runBlocking<Unit> {
        for (remote in sockets) for (local in sockets) for (noRebind in listOf(false, true)) {
            val transport = FakeTransport("OKAYOKAYOKAY")
            device(transport).reverse(remote, local, noRebind)
            assertEquals("host:transport:$serial", transport.requests[0].drop(4))
            assertEquals("reverse:forward:${if (noRebind) "norebind:" else ""}$remote;$local", transport.requests[1].drop(4))
            assertTrue(transport.closed)
        }
        for (endpoint in sockets) {
            val forward = FakeTransport("OKAYOKAY")
            device(forward).removeForward(endpoint)
            assertEquals("host-serial:$serial:killforward:$endpoint", forward.requests.single().drop(4))
            val reverse = FakeTransport("OKAYOKAYOKAY")
            device(reverse).removeReverse(endpoint)
            assertEquals("reverse:killforward:$endpoint", reverse.requests.last().drop(4))
        }
    }

    @Test fun `unsupported roles reject before creating a connection`() = runBlocking<Unit> {
        val device = AdbDevice(AdbClient(transportFactory = { error("Must not connect") }), serial)
        for (endpoint in listOf(JdwpPid(1), DeviceFile("/dev/test"), RawDeviceFile("/dev/test"), parseEndpoint("vsock:1:2"))) {
            assertFailsWith<UnsupportedEndpointException> { device.forward(endpoint, TcpPort(1)) }
            assertFailsWith<UnsupportedEndpointException> { device.reverse(endpoint, TcpPort(1)) }
            assertFailsWith<UnsupportedEndpointException> { device.reverse(TcpPort(1), endpoint) }
            assertFailsWith<UnsupportedEndpointException> { device.removeForward(endpoint) }
            assertFailsWith<UnsupportedEndpointException> { device.removeReverse(endpoint) }
        }
        assertFailsWith<UnsupportedEndpointException> { device.forward(TcpPort(1), parseEndpoint("vsock:1:2")) }
        assertFailsWith<IllegalArgumentException> { device.forward(TcpPort(1), LocalAbstract("é".repeat(33000))) }
    }

    @Test fun `endpoint constructors reject unsafe values`() {
        listOf(0, -1, Int.MIN_VALUE).forEach { assertFailsWith<IllegalArgumentException> { JdwpPid(it) } }
        assertEquals(Int.MAX_VALUE, JdwpPid(Int.MAX_VALUE).value)
        for (bad in listOf("", "a;b", "a\u0000b", "a\nb", "a\tb", "a b", "a\u007fb", "\uD800")) {
            assertFailsWith<IllegalArgumentException> { LocalAbstract(bad) }
            assertFailsWith<IllegalArgumentException> { LocalReserved(bad) }
            assertFailsWith<IllegalArgumentException> { LocalFilesystem(bad) }
            assertFailsWith<IllegalArgumentException> { DeviceFile(bad) }
            assertFailsWith<IllegalArgumentException> { RawDeviceFile(bad) }
        }
        for (bad in listOf("relative", "/")) {
            assertFailsWith<IllegalArgumentException> { LocalFilesystem(bad) }
            assertFailsWith<IllegalArgumentException> { DeviceFile(bad) }
            assertFailsWith<IllegalArgumentException> { RawDeviceFile(bad) }
        }
        for (bad in listOf("../test", "/test", ".", "..")) assertFailsWith<IllegalArgumentException> { LocalReserved(bad) }
    }

    @Test fun `typed listing preserves unknown endpoints and TCP compatibility filters`() = runBlocking<Unit> {
        val entries = "test-device tcp:1 tcp:2\nother-device invalid ignored\ntest-device tcp:3 jdwp:1234\n" +
            "test-device localabstract:socket localfilesystem:/tmp/socket\ntest-device tcp:4 vsock:5:6\n"
        val typed = device(FakeTransport("OKAY" + payload(entries))).listForwardMappings()
        assertEquals(4, typed.size)
        assertEquals(JdwpPid(1234), typed[1].remote)
        assertEquals("vsock:5:6", assertIs<UnknownEndpoint>(typed.last().remote).value)
        assertEquals(listOf(PortForward(serial, TcpPort(1), TcpPort(2))), device(FakeTransport("OKAY" + payload(entries))).listForwards())
        val reverseEntries = "tcp:1 tcp:2\nUsbFfs localabstract:s localfilesystem:/tmp/s\nhost tcp:3 vsock:4:5\n"
        val reverse = device(FakeTransport("OKAYOKAY" + payload(reverseEntries))).listReverseMappings()
        assertEquals(3, reverse.size)
        assertEquals(LocalAbstract("s"), reverse[1].remote)
        assertIs<UnknownEndpoint>(reverse.last().local)
        assertEquals(listOf(ReverseForward(TcpPort(1), TcpPort(2))), device(FakeTransport("OKAYOKAY" + payload(reverseEntries))).listReverses())
    }

    @Test fun `malformed known endpoints and listing rows are protocol failures`() = runBlocking<Unit> {
        for (bad in listOf("tcp:0", "tcp:65536", "tcp:+1", "tcp:no", "tcp:99999999999", "jdwp:0", "jdwp:-1",
            "localabstract:", "localreserved:../socket", "localfilesystem:relative", "dev:", "dev-raw:/", "unknown")) {
            assertFailsWith<AdbProtocolException> { parseEndpoint(bad) }
        }
        for (row in listOf("test-device tcp:1", "test-device tcp:1 tcp:2 extra", "test-device tcp:0 tcp:2")) {
            assertFailsWith<AdbProtocolException> { device(FakeTransport("OKAY" + payload(row))).listForwardMappings() }
        }
        for (row in listOf("tcp:1", "host tcp:1 tcp:2 extra", "tcp:0 tcp:2")) {
            assertFailsWith<AdbProtocolException> { device(FakeTransport("OKAYOKAY" + payload(row))).listReverseMappings() }
        }
    }

    @Test fun `forward status failures preserve reason and incomplete responses fail closed`() = runBlocking<Unit> {
        for (prefix in listOf("", "OKAY")) {
            val transport = FakeTransport(prefix + "FAIL" + payload("cannot rebind existing socket"))
            val error = assertFailsWith<AdbFailException> { device(transport).forward(TcpPort(8700), JdwpPid(1234), true) }
            assertEquals("cannot rebind existing socket", error.reason)
            assertTrue(transport.closed)
        }
        for (response in listOf("NOPE", "OKAY", "OKAYNOPE", "OKAYOK", "OKAYFAILzzzz")) {
            val transport = FakeTransport(response)
            assertFailsWith<AdbProtocolException> { device(transport).forward(TcpPort(8700), JdwpPid(1234)) }
            assertTrue(transport.closed)
        }
        val reverse = FakeTransport("OKAYOKAYFAIL" + payload("permission denied"))
        assertEquals("permission denied", assertFailsWith<AdbFailException> {
            device(reverse).reverse(LocalReserved("protected"), TcpPort(8700))
        }.reason)
        assertTrue(reverse.closed)
    }
}
