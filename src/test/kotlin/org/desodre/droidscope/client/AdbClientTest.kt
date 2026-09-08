package org.desodre.droidscope.client

import kotlinx.coroutines.runBlocking
import kotlin.test.*
import org.desodre.droidscope.error.*
import org.desodre.droidscope.protocol.FakeTransport

class AdbClientTest {
    @Test fun `version and devices use separate closed sessions`() = runBlocking<Unit> {
        val version = FakeTransport("OKAY00040029")
        val devices = FakeTransport("OKAY000Ba\tdevice\n\n\n")
        val queue = ArrayDeque(listOf(version, devices))
        val client = AdbClient(transportFactory = { queue.removeFirst() })
        assertEquals(41, client.version().value)
        assertEquals("a", client.devices().single().serial.value)
        assertEquals(listOf("000Chost:version"), version.requests)
        assertEquals(listOf("000Ehost:devices-l"), devices.requests)
        assertTrue(version.closed && devices.closed)
    }

    @Test fun `failure also closes session`() = runBlocking<Unit> {
        val transport = FakeTransport("FAIL0004oops")
        assertFailsWith<AdbFailException> { AdbClient(transportFactory = { transport }).version() }
        assertTrue(transport.closed)
    }
}
