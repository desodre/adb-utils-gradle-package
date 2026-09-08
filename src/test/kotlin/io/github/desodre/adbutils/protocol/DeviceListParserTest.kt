package io.github.desodre.adbutils.protocol

import kotlin.test.*
import io.github.desodre.adbutils.error.AdbProtocolException
import io.github.desodre.adbutils.model.*

class DeviceListParserTest {
    @Test fun `representative long device list`() {
        val payload = checkNotNull(javaClass.getResource("/adb/devices-l.txt")).readText()
        val devices = DeviceListParser.parse(payload)
        assertEquals(7, devices.size)
        assertEquals(DeviceInfo(DeviceSerial("R58M123ABC"), DeviceState.DEVICE, "a52", "SM_A525F", "a52", 1), devices[0])
        assertEquals(4294967296L, devices[1].transportId)
        assertEquals(DeviceState.UNAUTHORIZED, devices[2].state)
        assertEquals(DeviceState.OFFLINE, devices[3].state)
        assertEquals(DeviceState.RECOVERY, devices[4].state)
        assertEquals(DeviceState.UNKNOWN, devices[5].state)
        assertEquals(DeviceState.NO_PERMISSIONS, devices[6].state)
    }

    @Test fun `empty optional fields CRLF and tabs`() {
        assertEquals(emptyList(), DeviceListParser.parse("\n\r\n"))
        assertEquals(listOf(DeviceInfo(DeviceSerial("abc"), DeviceState.DEVICE)), DeviceListParser.parse("abc\tdevice\r\n"))
        for (line in listOf("abc", "abc device transport_id:no", "abc device transport_id:0")) {
            assertFailsWith<AdbProtocolException> { DeviceListParser.parse(line) }
        }
    }

    @Test fun `known states and future fallback`() {
        for (state in DeviceState.entries.filter { it != DeviceState.UNKNOWN && it != DeviceState.NO_PERMISSIONS }) {
            assertEquals(state, DeviceState.fromWire(state.name.lowercase()))
        }
        assertEquals(DeviceState.UNKNOWN, DeviceState.fromWire("new-state"))
    }

    @Test fun `serial rejects invalid tokens but accepts network devices`() {
        for (serial in listOf("", "a b", "a\n", "a\u0000")) assertFailsWith<IllegalArgumentException> { DeviceSerial(serial) }
        assertEquals("192.168.1.1:5555", DeviceSerial("192.168.1.1:5555").toString())
    }
}
