package io.github.desodre.adbutils.client

import io.github.desodre.adbutils.error.LogcatLineLimitException
import io.github.desodre.adbutils.error.LogcatProcessException
import io.github.desodre.adbutils.model.DeviceSerial
import io.github.desodre.adbutils.model.LogcatBuffer
import io.github.desodre.adbutils.model.LogcatFilter
import io.github.desodre.adbutils.model.LogcatFormat
import io.github.desodre.adbutils.model.LogcatOptions
import io.github.desodre.adbutils.model.LogcatPriority
import io.github.desodre.adbutils.protocol.FakeTransport
import io.github.desodre.adbutils.transport.AdbTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

class LogcatTest {
    @Test fun `logcat reconstructs lines split across shell frames`() = runBlocking<Unit> {
        val response = bytes(
            "OKAYOKAY".encodeToByteArray(),
            frame(1, "1720000000.123  42  43 I Sample".encodeToByteArray()),
            frame(1, "Tag: hello\nplatform banner\n".encodeToByteArray()),
            frame(3, byteArrayOf(0)),
        )
        val transport = FakeTransport(response, chunkSize = 1)

        val entries = device(transport).logcat().toList()

        assertEquals(2, entries.size)
        assertEquals("hello", entries[0].message)
        assertEquals(42, entries[0].processId)
        assertEquals(43, entries[0].threadId)
        assertEquals(LogcatPriority.INFO, entries[0].priority)
        assertEquals("SampleTag", entries[0].tag)
        assertEquals("platform banner", entries[1].message)
        assertEquals(null, entries[1].timestamp)
        assertTrue(transport.closed)
    }

    @Test fun `logcat command serializes validated buffers format and filters`() = runBlocking<Unit> {
        val transport = FakeTransport(bytes("OKAYOKAY".encodeToByteArray(), frame(3, byteArrayOf(0))))
        val options = LogcatOptions(
            buffers = setOf(LogcatBuffer.CRASH, LogcatBuffer.MAIN),
            format = LogcatFormat.RAW,
            filters = listOf(LogcatFilter("Fixture", LogcatPriority.DEBUG)),
        )

        device(transport).logcat(options).toList()

        assertTrue(transport.requests.last().endsWith("shell,v2,raw:logcat -b main -b crash -v raw Fixture:D *:S"))
        assertFailsWith<IllegalArgumentException> { LogcatFilter("tag; reboot") }
    }

    @Test fun `clear before start uses a finite session before streaming`() = runBlocking<Unit> {
        val clear = FakeTransport(bytes("OKAYOKAY".encodeToByteArray(), frame(3, byteArrayOf(0))))
        val stream = FakeTransport(bytes("OKAYOKAY".encodeToByteArray(), frame(3, byteArrayOf(0))))
        val transports = ArrayDeque<AdbTransport>(listOf(clear, stream))
        val device = AdbDevice(AdbClient(transportFactory = { transports.removeFirst() }), DeviceSerial("serial"))

        device.logcat(
            LogcatOptions(buffers = setOf(LogcatBuffer.MAIN), clearBeforeStart = true),
        ).toList()

        assertTrue(clear.requests.last().endsWith("shell,v2,raw:logcat -b main -c"))
        assertTrue(clear.closed)
        assertTrue(stream.closed)
    }

    @Test fun `logcat surfaces line bounds and remote exit failures`() = runBlocking<Unit> {
        val tooLong = FakeTransport(bytes(
            "OKAYOKAY".encodeToByteArray(),
            frame(1, "12345".encodeToByteArray()),
        ))
        assertFailsWith<LogcatLineLimitException> {
            device(tooLong).logcat(LogcatOptions(maxLineBytes = 4)).toList()
        }
        assertTrue(tooLong.closed)

        val failed = FakeTransport(bytes(
            "OKAYOKAY".encodeToByteArray(),
            frame(2, "permission denied".encodeToByteArray()),
            frame(3, byteArrayOf(1)),
        ))
        val error = assertFailsWith<LogcatProcessException> { device(failed).logcat().toList() }
        assertEquals(1, error.exitCode)
        assertEquals("permission denied", error.stderr)
    }

    @Test fun `cancelling collection closes the logcat transport`() = runBlocking<Unit> {
        val line = "1720000000.000 1 2 I Tag: ready\n".encodeToByteArray()
        val transport = BlockingTransport(bytes("OKAYOKAY".encodeToByteArray(), frame(1, line)))

        val entry = device(transport).logcat().first()

        assertEquals("ready", entry.message)
        assertTrue(transport.closed)
    }

    private fun device(transport: AdbTransport): AdbDevice = AdbDevice(
        AdbClient(transportFactory = { transport }),
        DeviceSerial("serial"),
    )

    private class BlockingTransport(private val initial: ByteArray) : AdbTransport {
        private var offset = 0
        var closed = false

        override suspend fun connect(): Unit = Unit
        override suspend fun write(data: ByteArray): Unit = Unit
        override suspend fun read(maxBytes: Int): ByteArray {
            if (offset == initial.size) awaitCancellation()
            val end = minOf(offset + maxBytes, initial.size)
            return initial.copyOfRange(offset, end).also { offset = end }
        }
        override suspend fun close(): Unit { closed = true }
    }

    private fun frame(id: Int, payload: ByteArray): ByteArray = bytes(
        byteArrayOf(id.toByte()),
        byteArrayOf(
            payload.size.toByte(),
            (payload.size ushr 8).toByte(),
            (payload.size ushr 16).toByte(),
            (payload.size ushr 24).toByte(),
        ),
        payload,
    )

    private fun bytes(vararg chunks: ByteArray): ByteArray = ByteArray(chunks.sumOf { it.size }).also { result ->
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(result, offset)
            offset += chunk.size
        }
    }
}
