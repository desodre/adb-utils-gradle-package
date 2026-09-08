package io.github.desodre.adbutils.client

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import io.github.desodre.adbutils.model.DeviceState
import io.github.desodre.adbutils.protocol.FakeTransport

class TrackDevicesTest {
    @Test fun `cold flow emits snapshots and closes each collector`() = runBlocking<Unit> {
        val response = "OKAY0009a\tdevice\n000Aa\toffline\n"
        val transports = mutableListOf<FakeTransport>()
        val client = AdbClient(transportFactory = { FakeTransport(response, 1).also(transports::add) })
        repeat(2) {
            val snapshots = client.trackDevices().take(2).toList()
            assertEquals(DeviceState.DEVICE, snapshots[0].single().state)
            assertEquals(DeviceState.OFFLINE, snapshots[1].single().state)
        }
        assertEquals(2, transports.size)
        assertTrue(transports.all { it.closed && it.requests == listOf("0014host:track-devices-l") })
    }
}
