package io.github.desodre.adbutils.protocol

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.flowOf
import kotlin.test.*
import io.github.desodre.adbutils.error.*

class SyncProtocolTest {
    private fun le(value: Int) = SyncProtocol.le(value)

    @Test fun `stat list and pull parse fragmented binary responses`() = runBlocking<Unit> {
        val statResponse = "STAT".encodeToByteArray() + le(420) + le(7) + le(9)
        val statTransport = FakeTransport(statResponse, 1)
        val stat = SyncProtocol(AdbProtocol(statTransport)).stat("/a")
        assertEquals(420, stat.mode); assertEquals(7, stat.size); assertEquals(9, stat.modifiedAtEpochSeconds)
        assertContentEquals("STAT".encodeToByteArray() + le(2) + "/a".encodeToByteArray(), statTransport.writes.single())

        val dent = "DENT".encodeToByteArray() + le(420) + le(3) + le(5) + le(1) + "x".encodeToByteArray()
        val done = "DONE".encodeToByteArray() + ByteArray(16)
        val files = SyncProtocol(AdbProtocol(FakeTransport(dent + done, 2))).list("/")
        assertEquals("x", files.single().name)

        val pull = "DATA".encodeToByteArray() + le(2) + "ab".encodeToByteArray() +
            "DATA".encodeToByteArray() + le(1) + "c".encodeToByteArray() + "DONE".encodeToByteArray() + le(0)
        assertContentEquals("abc".encodeToByteArray(), SyncProtocol(AdbProtocol(FakeTransport(pull, 1))).pull("/x", 3))
    }

    @Test fun `push chunks data and reads acknowledgement`() = runBlocking<Unit> {
        val transport = FakeTransport("OKAY")
        SyncProtocol(AdbProtocol(transport)).push("/x", "abc".encodeToByteArray(), 420, 7)
        assertEquals(3, transport.writes.size)
        assertTrue(transport.requests[0].startsWith("SEND"))
        assertContentEquals("DATA".encodeToByteArray() + le(3) + "abc".encodeToByteArray(), transport.writes[1])
        assertContentEquals("DONE".encodeToByteArray() + le(7), transport.writes[2])
    }

    @Test fun `streaming pull forwards independent chunks and reports total`() = runBlocking<Unit> {
        val response = "DATA".encodeToByteArray() + le(2) + "ab".encodeToByteArray() +
            "DATA".encodeToByteArray() + le(1) + "c".encodeToByteArray() + "DONE".encodeToByteArray() + le(0)
        val received = mutableListOf<ByteArray>()

        val total = SyncProtocol(AdbProtocol(FakeTransport(response, 1))).pull("/x", 3L) { received += it }

        assertEquals(3L, total)
        assertEquals(listOf("ab", "c"), received.map { it.decodeToString() })
    }

    @Test fun `streaming push splits large chunks and emits cumulative progress`() = runBlocking<Unit> {
        val transport = FakeTransport("OKAY")
        val progress = mutableListOf<Long>()
        val data = ByteArray(SyncProtocol.MAX_DATA + 1) { 7 }

        val total = SyncProtocol(AdbProtocol(transport)).push(
            "/x",
            flowOf(data, byteArrayOf()),
            420,
            7,
            data.size.toLong(),
        ) { progress += it }

        assertEquals(data.size.toLong(), total)
        assertEquals(listOf(SyncProtocol.MAX_DATA.toLong(), data.size.toLong()), progress)
        assertEquals(SyncProtocol.MAX_DATA, SyncProtocol.fromLe(transport.writes[1].copyOfRange(4, 8)))
        assertEquals(1, SyncProtocol.fromLe(transport.writes[2].copyOfRange(4, 8)))
    }

    @Test fun `SYNC fail limit and malformed ids are typed`() = runBlocking<Unit> {
        val fail = "FAIL".encodeToByteArray() + le(4) + "oops".encodeToByteArray()
        assertEquals("oops", assertFailsWith<AdbFailException> { SyncProtocol(AdbProtocol(FakeTransport(fail))).stat("/x") }.reason)
        val data = "DATA".encodeToByteArray() + le(5) + "12345".encodeToByteArray()
        assertFailsWith<SyncTransferLimitException> { SyncProtocol(AdbProtocol(FakeTransport(data))).pull("/x", 4) }
        assertFailsWith<SyncTransferLimitException> {
            SyncProtocol(AdbProtocol(FakeTransport("OKAY"))).push("/x", flowOf("12345".encodeToByteArray()), 420, 0, 4) {}
        }
        val oversized = "DATA".encodeToByteArray() + le(SyncProtocol.MAX_DATA + 1)
        assertFailsWith<AdbProtocolException> {
            SyncProtocol(AdbProtocol(FakeTransport(oversized))).pull("/x", Long.MAX_VALUE) {}
        }
        assertFailsWith<AdbProtocolException> { SyncProtocol(AdbProtocol(FakeTransport("WHAT"))).list("/") }
    }
}
