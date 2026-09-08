package org.desodre.droidscope.protocol

import kotlinx.coroutines.runBlocking
import kotlin.test.*
import org.desodre.droidscope.error.*

class ShellV2ProtocolTest {
    private fun frame(id: Int, payload: ByteArray) = byteArrayOf(id.toByte()) + SyncProtocol.le(payload.size) + payload

    @Test fun `rejects unknown stream truncated exit and output overflow`() = runBlocking<Unit> {
        assertFailsWith<AdbProtocolException> { ShellV2Protocol.read(AdbProtocol(FakeTransport(frame(9, byteArrayOf()))), 10) }
        assertFailsWith<AdbProtocolException> { ShellV2Protocol.read(AdbProtocol(FakeTransport(frame(3, byteArrayOf(0, 1)))), 10) }
        assertFailsWith<ShellOutputLimitException> { ShellV2Protocol.read(AdbProtocol(FakeTransport(frame(1, "large".encodeToByteArray()))), 4) }
        assertFailsWith<AdbProtocolException> { ShellV2Protocol.read(AdbProtocol(FakeTransport(byteArrayOf(1, 2, 0))), 10) }
    }
}
