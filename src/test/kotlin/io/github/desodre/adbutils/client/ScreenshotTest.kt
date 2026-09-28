package io.github.desodre.adbutils.client

import io.github.desodre.adbutils.error.InvalidScreenshotException
import io.github.desodre.adbutils.error.ScreenshotLimitException
import io.github.desodre.adbutils.model.DeviceSerial
import io.github.desodre.adbutils.protocol.FakeTransport
import io.github.desodre.adbutils.transport.AdbTransport
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class ScreenshotTest {
    @Test fun `screenshot preserves binary PNG bytes across fragmented reads`() = runBlocking<Unit> {
        val png = PNG_SIGNATURE + ByteArray(37) { it.toByte() }
        val transport = FakeTransport("OKAYOKAY".encodeToByteArray() + png, chunkSize = 1)

        val result = device(transport).screenshot()

        assertContentEquals(png, result)
        assertTrue(transport.closed)
        assertTrue(transport.requests.last().endsWith("exec:screencap -p"))
    }

    @Test fun `screenshot rejects invalid PNG and bounded overflow`() = runBlocking<Unit> {
        val invalid = FakeTransport("OKAYOKAYnot-png")
        assertFailsWith<InvalidScreenshotException> { device(invalid).screenshot() }
        assertTrue(invalid.closed)

        val oversized = FakeTransport("OKAYOKAY".encodeToByteArray() + PNG_SIGNATURE + byteArrayOf(1))
        assertFailsWith<ScreenshotLimitException> { device(oversized).screenshot(PNG_SIGNATURE.size) }
        assertTrue(oversized.closed)
    }

    @Test fun `screenshotTo replaces destination only after validation`() = runBlocking<Unit> {
        val directory = createTempDirectory("adb-utils-screenshot")
        val destination = directory.resolve("screen.png")
        Files.writeString(destination, "previous")
        val png = PNG_SIGNATURE + "payload".encodeToByteArray()

        val written = device(FakeTransport("OKAYOKAY".encodeToByteArray() + png, chunkSize = 2))
            .screenshotTo(destination)

        assertTrue(written == png.size.toLong())
        assertContentEquals(png, Files.readAllBytes(destination))
        assertFalse(Files.list(directory).use { files -> files.anyMatch { it.fileName.toString().endsWith(".part") } })
    }

    @Test fun `screenshotTo removes partial file when collection is cancelled`() = runBlocking<Unit> {
        val directory = createTempDirectory("adb-utils-screenshot-cancel")
        val destination = directory.resolve("screen.png")
        val transport = BlockingTransport("OKAYOKAY".encodeToByteArray() + PNG_SIGNATURE)

        val job = launch { device(transport).screenshotTo(destination) }
        delay(25)
        job.cancelAndJoin()

        assertTrue(transport.closed)
        assertFalse(Files.exists(destination))
        assertFalse(Files.list(directory).use { files -> files.anyMatch { it.fileName.toString().endsWith(".part") } })
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

    private companion object {
        val PNG_SIGNATURE: ByteArray = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        )
    }
}
