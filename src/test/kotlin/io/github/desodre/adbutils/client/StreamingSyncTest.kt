package io.github.desodre.adbutils.client

import io.github.desodre.adbutils.model.DeviceSerial
import io.github.desodre.adbutils.protocol.FakeTransport
import io.github.desodre.adbutils.protocol.SyncProtocol
import io.github.desodre.adbutils.transport.AdbTransport
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class StreamingSyncTest {
    @Test
    fun `pullTo streams through an atomic temporary file and preserves attributes`() = runBlocking<Unit> {
        val mode = 0b110100000
        val modifiedAt = 1_700_000_000
        val payload = "streamed".encodeToByteArray()
        val response = "OKAYOKAY".encodeToByteArray() +
            "STAT".encodeToByteArray() + SyncProtocol.le(mode) + SyncProtocol.le(payload.size) + SyncProtocol.le(modifiedAt) +
            "DATA".encodeToByteArray() + SyncProtocol.le(payload.size) + payload +
            "DONE".encodeToByteArray() + SyncProtocol.le(0)
        val destination = createTempDirectory("adb-utils-pull").resolve("result.bin")
        val device = AdbDevice(AdbClient(transportFactory = { FakeTransport(response, 3) }), DeviceSerial("serial"))

        val result = device.pullTo("/sdcard/result.bin", destination)

        assertEquals(payload.size.toLong(), result.bytesTransferred)
        assertContentEquals(payload, Files.readAllBytes(destination))
        assertEquals(modifiedAt.toLong(), Files.getLastModifiedTime(destination).toMillis() / 1_000)
        if (Files.getFileStore(destination).supportsFileAttributeView("posix")) {
            assertEquals(
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ,
                ),
                Files.getPosixFilePermissions(destination),
            )
        }
        assertFalse(Files.list(destination.parent).use { entries -> entries.anyMatch { it.fileName.toString().endsWith(".part") } })
    }

    @Test
    fun `path push streams a file and preserves explicit metadata`() = runBlocking<Unit> {
        val source = Files.createTempFile("adb-utils-push", ".bin")
        val payload = ByteArray(SyncProtocol.MAX_DATA + 17) { (it % 251).toByte() }
        Files.write(source, payload)
        Files.setLastModifiedTime(source, FileTime.fromMillis(2_000_000))
        val transport = FakeTransport("OKAYOKAYOKAY", 2)
        val device = AdbDevice(AdbClient(transportFactory = { transport }), DeviceSerial("serial"))

        val result = device.push(source, "/data/local/tmp/result.bin", mode = 420, modifiedAtEpochSeconds = 7, chunkSize = 8_192)

        assertEquals(payload.size.toLong(), result.bytesTransferred)
        assertTrue(transport.requests[2].contains("/data/local/tmp/result.bin,420"))
        assertContentEquals("DONE".encodeToByteArray() + SyncProtocol.le(7), transport.writes.last())
        assertTrue(transport.writes.drop(3).dropLast(1).all { SyncProtocol.fromLe(it.copyOfRange(4, 8)) <= 8_192 })
    }

    @Test
    fun `path push rejects an oversized source before opening an ADB session`() = runBlocking<Unit> {
        val source = Files.createTempFile("adb-utils-limit", ".bin")
        Files.write(source, ByteArray(5))
        val transport = FakeTransport("OKAYOKAYOKAY")
        val device = AdbDevice(AdbClient(transportFactory = { transport }), DeviceSerial("serial"))

        assertFailsWith<IllegalArgumentException> {
            device.push(source, "/data/local/tmp/result.bin", maxBytes = 4)
        }

        assertTrue(transport.writes.isEmpty())
    }

    @Test
    fun `cancelling a streaming pull closes its transport`() = runBlocking<Unit> {
        val transport = BlockingSyncTransport()
        val device = AdbDevice(AdbClient(transportFactory = { transport }), DeviceSerial("serial"))

        val job = launch { device.pullChunks("/sdcard/large.bin").collect() }
        delay(25)
        job.cancelAndJoin()

        assertTrue(transport.closed)
    }

    private class BlockingSyncTransport : AdbTransport {
        private val response = "OKAYOKAY".encodeToByteArray()
        private var offset = 0
        var closed = false

        override suspend fun connect(): Unit = Unit
        override suspend fun write(data: ByteArray): Unit = Unit
        override suspend fun read(maxBytes: Int): ByteArray {
            if (offset == response.size) awaitCancellation()
            val end = minOf(offset + maxBytes, response.size)
            return response.copyOfRange(offset, end).also { offset = end }
        }
        override suspend fun close(): Unit {
            closed = true
        }
    }
}
