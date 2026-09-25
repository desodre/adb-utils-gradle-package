package io.github.desodre.adbutils.client

import io.github.desodre.adbutils.error.AdbTimeoutException
import io.github.desodre.adbutils.model.DeviceSerial
import io.github.desodre.adbutils.model.DeviceState
import io.github.desodre.adbutils.protocol.FakeTransport
import io.github.desodre.adbutils.transport.AdbTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WaitForDeviceTest {
    @Test fun `waits through absence and intermediate states`() = runBlocking<Unit> {
        val snapshots = listOf(
            "other\tdevice\n",
            "target\toffline\n",
            "target\tdevice product:pixel model:Pixel_9 transport_id:42\n",
        )
        val transport = FakeTransport(trackingResponse(snapshots), chunkSize = 1)
        val client = AdbClient(transportFactory = { transport })

        val device = client.waitForDevice(DeviceSerial("target"), timeoutMillis = 1_000)

        assertEquals(DeviceState.DEVICE, device.state)
        assertEquals("Pixel_9", device.model)
        assertEquals(42L, device.transportId)
        assertTrue(transport.closed)
    }

    @Test fun `can wait for unavailable state`() = runBlocking<Unit> {
        val transport = FakeTransport(trackingResponse(listOf("target\tunauthorized\n")))
        val client = AdbClient(transportFactory = { transport })

        val device = client.waitForDevice(
            DeviceSerial("target"),
            state = DeviceState.UNAUTHORIZED,
            timeoutMillis = 1_000,
        )

        assertEquals(DeviceState.UNAUTHORIZED, device.state)
        assertTrue(transport.closed)
    }

    @Test fun `timeout closes tracking connection`() = runBlocking<Unit> {
        val transport = WaitingTransport()
        val client = AdbClient(transportFactory = { transport })

        assertFailsWith<AdbTimeoutException> {
            client.waitForDevice(DeviceSerial("target"), timeoutMillis = 50)
        }

        assertTrue(transport.closed)
    }

    @Test fun `caller cancellation is preserved and closes tracking connection`() = runBlocking<Unit> {
        val transport = WaitingTransport()
        val client = AdbClient(transportFactory = { transport })
        val waiting = async { client.waitForDevice(DeviceSerial("target"), timeoutMillis = 10_000) }
        transport.started.await()

        waiting.cancelAndJoin()

        assertTrue(waiting.isCancelled)
        assertTrue(transport.closed)
    }

    @Test fun `rejects non-positive timeout`() = runBlocking<Unit> {
        assertFailsWith<IllegalArgumentException> {
            AdbClient().waitForDevice(DeviceSerial("target"), timeoutMillis = 0)
        }
    }

    private class WaitingTransport : AdbTransport {
        val started = CompletableDeferred<Unit>()
        var closed = false

        override suspend fun connect() = Unit
        override suspend fun write(data: ByteArray) = Unit
        override suspend fun read(maxBytes: Int): ByteArray {
            started.complete(Unit)
            awaitCancellation()
        }
        override suspend fun close() { closed = true }
    }

    private fun trackingResponse(snapshots: List<String>): ByteArray = buildList<Byte> {
        addAll("OKAY".encodeToByteArray().asList())
        snapshots.forEach { snapshot ->
            addAll(snapshot.encodeToByteArray().size.toString(16).padStart(4, '0').encodeToByteArray().asList())
            addAll(snapshot.encodeToByteArray().asList())
        }
    }.toByteArray()
}
