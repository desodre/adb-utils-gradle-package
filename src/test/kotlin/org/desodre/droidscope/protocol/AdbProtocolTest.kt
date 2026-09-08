package org.desodre.droidscope.protocol

import kotlinx.coroutines.runBlocking
import kotlin.test.*
import org.desodre.droidscope.error.*
import org.desodre.droidscope.transport.AdbTransport

internal class FakeTransport(response: String, private val chunkSize: Int = 1) : AdbTransport {
    private val bytes = response.encodeToByteArray()
    private var offset = 0
    val requests = mutableListOf<String>()
    var closed = false
    override suspend fun connect() = Unit
    override suspend fun close() { closed = true }
    override suspend fun write(data: ByteArray) { requests += data.decodeToString() }
    override suspend fun read(maxBytes: Int): ByteArray {
        val end = minOf(offset + minOf(chunkSize, maxBytes), bytes.size)
        return bytes.copyOfRange(offset, end).also { offset = end }
    }
}

class AdbProtocolTest {
    @Test fun `requests count UTF-8 bytes`() {
        assertEquals("000Chost:version", AdbCodec.encodeRequest("host:version").decodeToString())
        assertEquals("0002é", AdbCodec.encodeRequest("é").decodeToString())
        assertFailsWith<IllegalArgumentException> { AdbCodec.encodeRequest("x".repeat(65536)) }
    }

    @Test fun `length requires exactly four hex bytes`() {
        assertEquals(65535, AdbCodec.decodeLength("fFfF".encodeToByteArray()))
        assertEquals(0, AdbCodec.decodeLength("0000".encodeToByteArray()))
        for (invalid in listOf("000", "00000", "-001", "00 G", "zzzz")) {
            assertFailsWith<AdbProtocolException> { AdbCodec.decodeLength(invalid.encodeToByteArray()) }
        }
    }

    @Test fun `OKAY and payload survive partial reads`() = runBlocking<Unit> {
        val protocol = AdbProtocol(FakeTransport("OKAY00040029"))
        protocol.request("host:version")
        assertEquals("0029", protocol.readPayload())
    }

    @Test fun `FAIL preserves reason`() = runBlocking<Unit> {
        val error = assertFailsWith<AdbFailException> { AdbProtocol(FakeTransport("FAIL0004oops")).request("host:version") }
        assertEquals("oops", error.reason)
    }

    @Test fun `malformed and truncated responses fail`() = runBlocking<Unit> {
        for (response in listOf("", "OK", "NOPE", "FAIL0004no", "FAILzzzz")) {
            assertFailsWith<AdbProtocolException> { AdbProtocol(FakeTransport(response)).request("host:version") }
        }
        assertFailsWith<AdbProtocolException> { AdbCodec.decodeText(byteArrayOf(0xc3.toByte())) }
    }
}
