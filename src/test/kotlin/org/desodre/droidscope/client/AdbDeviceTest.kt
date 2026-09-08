package org.desodre.droidscope.client

import kotlinx.coroutines.runBlocking
import kotlin.test.*
import org.desodre.droidscope.error.*
import org.desodre.droidscope.model.*
import org.desodre.droidscope.protocol.FakeTransport

class AdbDeviceTest {
    private fun listing(payload: String) = FakeTransport("OKAY" + payload.encodeToByteArray().size.toString(16).padStart(4, '0') + payload)
    private fun client(payload: String) = AdbClient(transportFactory = { listing(payload) })

    @Test fun `automatic selection picks sole online device`() = runBlocking<Unit> {
        assertEquals(DeviceSerial("a"), client("a device\nb offline\nc unauthorized\n").device().serial)
    }

    @Test fun `no available devices includes detected states`() = runBlocking<Unit> {
        assertFailsWith<NoDevicesException> { client("").device() }
        val error = assertFailsWith<NoDevicesException> { client("a unauthorized").device() }
        assertEquals(DeviceState.UNAUTHORIZED, error.detected.single().state)
    }

    @Test fun `multiple devices requires explicit serial`() = runBlocking<Unit> {
        val client = client("a device\nb device")
        val error = assertFailsWith<MultipleDevicesException> { client.device() }
        assertEquals(listOf(DeviceSerial("a"), DeviceSerial("b")), error.serials)
        assertEquals(DeviceSerial("b"), client.device(DeviceSerial("b")).serial)
    }

    @Test fun `explicit selection diagnoses device state`() = runBlocking<Unit> {
        val client = client("a offline\nb unauthorized\nc recovery")
        assertFailsWith<DeviceNotFoundException> { client.device(DeviceSerial("missing")) }
        assertFailsWith<DeviceOfflineException> { client.device(DeviceSerial("a")) }
        assertFailsWith<DeviceUnauthorizedException> { client.device(DeviceSerial("b")) }
        assertFailsWith<DeviceUnavailableException> { client.device(DeviceSerial("c")) }
    }

    @Test fun `shell selects transport on same session and assembles UTF-8`() = runBlocking<Unit> {
        val shell = FakeTransport("OKAYOKAYOlá\n")
        val queue = ArrayDeque(listOf(listing("a device"), shell))
        val device = AdbClient(transportFactory = { queue.removeFirst() }).device()
        assertEquals("Olá\n", device.shell("echo Olá"))
        assertEquals(listOf("0010host:transport:a", "000Fshell:echo Olá"), shell.requests)
        assertTrue(shell.closed)
    }

    @Test fun `getprop trims line endings and validates names`() = runBlocking<Unit> {
        val shell = FakeTransport("OKAYOKAYPixel 7\r\n")
        val queue = ArrayDeque(listOf(listing("a device"), shell))
        val device = AdbClient(transportFactory = { queue.removeFirst() }).device()
        assertEquals("Pixel 7", device.getprop("ro.product.model"))
        assertTrue(shell.requests.last().endsWith("shell:getprop 'ro.product.model'"))
        assertFailsWith<IllegalArgumentException> { device.getprop("x; reboot") }
        assertFailsWith<IllegalArgumentException> { device.shell("") }
    }

    @Test fun `transport failure detects state changes since discovery`() = runBlocking<Unit> {
        for ((reason, type) in listOf("device offline" to DeviceOfflineException::class, "device unauthorized" to DeviceUnauthorizedException::class, "device 'a' not found" to DeviceNotFoundException::class, "other" to AdbFailException::class)) {
            val transport = FakeTransport("FAIL" + reason.length.toString(16).padStart(4, '0') + reason)
            val device = AdbDevice(AdbClient(transportFactory = { transport }), DeviceSerial("a"))
            val error = assertFailsWith<AdbException> { device.shell("id") }
            assertEquals(type, error::class)
            assertEquals(1, transport.requests.size)
            assertTrue(transport.closed)
        }
    }

    @Test fun `shell failure and output limit close connection`() = runBlocking<Unit> {
        for (response in listOf("OKAYFAIL0004oops", "OKAYOKAY12345")) {
            val transport = FakeTransport(response)
            val device = AdbDevice(AdbClient(transportFactory = { transport }), DeviceSerial("a"))
            val error = assertFailsWith<AdbException> { device.shell("id", maxOutputBytes = 4) }
            if ("FAIL" in response) assertIs<AdbFailException>(error) else assertIs<ShellOutputLimitException>(error)
            assertTrue(transport.closed)
        }
    }
}
