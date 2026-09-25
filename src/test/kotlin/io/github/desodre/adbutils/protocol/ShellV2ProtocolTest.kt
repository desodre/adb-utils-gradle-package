package io.github.desodre.adbutils.protocol

import kotlinx.coroutines.runBlocking
import kotlin.test.*
import io.github.desodre.adbutils.error.*

class ShellV2ProtocolTest {
    private fun frame(id: Int, payload: ByteArray) = byteArrayOf(id.toByte()) + SyncProtocol.le(payload.size) + payload

    @Test fun `rejects unknown stream truncated exit and output overflow`() = runBlocking<Unit> {
        assertFailsWith<AdbProtocolException> { ShellV2Protocol.read(AdbProtocol(FakeTransport(frame(9, byteArrayOf()))), 10) }
        assertFailsWith<AdbProtocolException> { ShellV2Protocol.read(AdbProtocol(FakeTransport(frame(3, byteArrayOf(0, 1)))), 10) }
        assertFailsWith<ShellOutputLimitException> { ShellV2Protocol.read(AdbProtocol(FakeTransport(frame(1, "large".encodeToByteArray()))), 4) }
        assertFailsWith<AdbProtocolException> { ShellV2Protocol.read(AdbProtocol(FakeTransport(byteArrayOf(1, 2, 0))), 10) }
    }

    @Test fun `writes bounded stdin and close frames`() = runBlocking<Unit> {
        val transport = FakeTransport(byteArrayOf())
        val protocol = AdbProtocol(transport)

        ShellV2Protocol.writeStdin(protocol, byteArrayOf(1, 2, 3, 4, 5), maxFrameBytes = 2)
        ShellV2Protocol.writeCloseStdin(protocol)

        assertContentEquals(frame(0, byteArrayOf(1, 2)), transport.writes[0])
        assertContentEquals(frame(0, byteArrayOf(3, 4)), transport.writes[1])
        assertContentEquals(frame(0, byteArrayOf(5)), transport.writes[2])
        assertContentEquals(frame(4, byteArrayOf()), transport.writes[3])
    }
}
