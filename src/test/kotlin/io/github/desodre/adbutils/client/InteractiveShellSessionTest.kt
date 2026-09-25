package io.github.desodre.adbutils.client

import io.github.desodre.adbutils.error.ShellFrameLimitException
import io.github.desodre.adbutils.error.ShellV2UnsupportedException
import io.github.desodre.adbutils.model.DeviceSerial
import io.github.desodre.adbutils.model.InteractiveShellOptions
import io.github.desodre.adbutils.model.ShellOutputStream
import io.github.desodre.adbutils.model.ShellTermination
import io.github.desodre.adbutils.protocol.FakeTransport
import io.github.desodre.adbutils.transport.AdbTransport
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InteractiveShellSessionTest {
    @Test fun `streams fragmented stdout and stderr then reports exit`() = runBlocking<Unit> {
        val response = bytes(
            "OKAY".encodeToByteArray(),
            "OKAY".encodeToByteArray(),
            frame(1, "out".encodeToByteArray()),
            frame(2, "err".encodeToByteArray()),
            frame(1, byteArrayOf(0, 1, 2)),
            frame(3, byteArrayOf(7)),
        )
        val transport = FakeTransport(response, chunkSize = 1)
        val session = device(transport).openInteractiveShell("sh")

        val output = session.output.toList()

        assertEquals(listOf(ShellOutputStream.STDOUT, ShellOutputStream.STDERR, ShellOutputStream.STDOUT), output.map { it.stream })
        assertContentEquals("out".encodeToByteArray(), output[0].data)
        assertContentEquals("err".encodeToByteArray(), output[1].data)
        assertContentEquals(byteArrayOf(0, 1, 2), output[2].data)
        assertEquals(ShellTermination.Exited(7), session.awaitTermination())
        assertTrue(transport.closed)
    }

    @Test fun `writes bounded stdin frames and closes stdin once`() = runBlocking<Unit> {
        val transport = DuplexTransport("OKAYOKAY".encodeToByteArray())
        val session = device(transport).openInteractiveShell(
            options = InteractiveShellOptions(maxFrameBytes = 2, outputBufferCapacity = 1),
        )

        session.writeStdin(byteArrayOf(1, 2, 3, 4, 5))
        session.closeStdin()
        session.closeStdin()
        val output = async { session.output.toList() }
        transport.feed(frame(3, byteArrayOf(0)))

        assertEquals(ShellTermination.Exited(0), withTimeout(1_000) { session.awaitTermination() })
        output.await()
        val protocolWrites = transport.writes.drop(2)
        assertEquals(4, protocolWrites.size)
        assertContentEquals(frame(0, byteArrayOf(1, 2)), protocolWrites[0])
        assertContentEquals(frame(0, byteArrayOf(3, 4)), protocolWrites[1])
        assertContentEquals(frame(0, byteArrayOf(5)), protocolWrites[2])
        assertContentEquals(frame(4, byteArrayOf()), protocolWrites[3])
        assertTrue(transport.closed)
    }

    @Test fun `forced cancellation closes transport and is observable`() = runBlocking<Unit> {
        val transport = DuplexTransport("OKAYOKAY".encodeToByteArray())
        val session = device(transport).openInteractiveShell()

        session.cancel()

        assertEquals(ShellTermination.Cancelled, session.awaitTermination())
        assertTrue(transport.closed)
    }

    @Test fun `frame limit fails output and termination`() = runBlocking<Unit> {
        val transport = FakeTransport(bytes(
            "OKAYOKAY".encodeToByteArray(),
            frame(1, "large".encodeToByteArray()),
        ))
        val session = device(transport).openInteractiveShell(
            options = InteractiveShellOptions(maxFrameBytes = 4),
        )

        assertFailsWith<ShellFrameLimitException> { session.output.toList() }
        val termination = assertIs<ShellTermination.Failed>(session.awaitTermination())
        assertIs<ShellFrameLimitException>(termination.cause)
        assertTrue(transport.closed)
    }

    @Test fun `adb fail while opening becomes unsupported error and closes transport`() = runBlocking<Unit> {
        val reason = "unknown service"
        val transport = FakeTransport(bytes(
            "OKAY".encodeToByteArray(),
            "FAIL".encodeToByteArray(),
            reason.length.toString(16).padStart(4, '0').encodeToByteArray(),
            reason.encodeToByteArray(),
        ))

        assertFailsWith<ShellV2UnsupportedException> { device(transport).openInteractiveShell() }
        assertTrue(transport.closed)
    }

    private fun device(transport: AdbTransport): AdbDevice = AdbDevice(
        AdbClient(transportFactory = { transport }),
        DeviceSerial("serial"),
    )

    private class DuplexTransport(initial: ByteArray) : AdbTransport {
        private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        private var pending = byteArrayOf()
        val writes = mutableListOf<ByteArray>()
        var closed = false

        init { incoming.trySend(initial) }

        override suspend fun connect() = Unit
        override suspend fun write(data: ByteArray) { writes += data.copyOf() }
        override suspend fun read(maxBytes: Int): ByteArray {
            while (pending.isEmpty()) pending = incoming.receiveCatching().getOrNull() ?: return byteArrayOf()
            val count = minOf(maxBytes, pending.size)
            return pending.copyOfRange(0, count).also { pending = pending.copyOfRange(count, pending.size) }
        }
        override suspend fun close() {
            closed = true
            incoming.close()
        }

        fun feed(data: ByteArray) { incoming.trySend(data).getOrThrow() }
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
