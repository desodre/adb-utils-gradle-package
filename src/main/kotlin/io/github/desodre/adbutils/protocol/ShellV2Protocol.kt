package io.github.desodre.adbutils.protocol

import io.github.desodre.adbutils.error.*
import io.github.desodre.adbutils.model.ShellResult

internal object ShellV2Protocol {
    private const val STDIN = 0
    private const val STDOUT = 1
    private const val STDERR = 2
    private const val EXIT = 3
    private const val CLOSE_STDIN = 4

    sealed interface Frame {
        data class Stdout(val data: ByteArray) : Frame
        data class Stderr(val data: ByteArray) : Frame
        data class Exit(val exitCode: Int) : Frame
    }

    suspend fun read(protocol: AdbProtocol, maxBytes: Int): ShellResult {
        val stdout = mutableListOf<ByteArray>()
        val stderr = mutableListOf<ByteArray>()
        var total = 0
        var exit: Int? = null
        while (exit == null) {
            val frame = try {
                readFrame(protocol, maxBytes - total)
            } catch (_: ShellFrameLimitException) {
                throw ShellOutputLimitException(maxBytes)
            }
            when (frame) {
                is Frame.Stdout -> {
                    stdout += frame.data
                    total += frame.data.size
                }
                is Frame.Stderr -> {
                    stderr += frame.data
                    total += frame.data.size
                }
                is Frame.Exit -> {
                    exit = frame.exitCode
                    total += 1
                }
            }
        }
        return ShellResult(AdbCodec.decodeText(stdout.join()), AdbCodec.decodeText(stderr.join()), exit)
    }

    suspend fun readFrame(protocol: AdbProtocol, maxFrameBytes: Int): Frame {
        require(maxFrameBytes >= 0)
        val id = protocol.readExactly(1)[0].toInt() and 0xff
        val size = littleEndianInt(protocol.readExactly(4))
        if (size < 0) throw AdbProtocolException("Negative shell v2 frame size")
        if (size > maxFrameBytes) throw ShellFrameLimitException(maxFrameBytes)
        val payload = protocol.readExactly(size)
        return when (id) {
            STDOUT -> Frame.Stdout(payload)
            STDERR -> Frame.Stderr(payload)
            EXIT -> {
                if (size != 1) throw AdbProtocolException("Shell v2 exit frame must contain one byte")
                Frame.Exit(payload[0].toInt() and 0xff)
            }
            else -> throw AdbProtocolException("Unknown shell v2 stream id: $id")
        }
    }

    suspend fun writeStdin(protocol: AdbProtocol, data: ByteArray, maxFrameBytes: Int) {
        require(maxFrameBytes > 0)
        var offset = 0
        while (offset < data.size) {
            val size = minOf(maxFrameBytes, data.size - offset)
            protocol.writeRaw(encodeFrame(STDIN, data, offset, size))
            offset += size
        }
    }

    suspend fun writeCloseStdin(protocol: AdbProtocol) {
        protocol.writeRaw(encodeFrame(CLOSE_STDIN, byteArrayOf(), 0, 0))
    }

    private fun encodeFrame(id: Int, data: ByteArray, offset: Int, size: Int): ByteArray {
        val frame = ByteArray(5 + size)
        frame[0] = id.toByte()
        frame[1] = size.toByte()
        frame[2] = (size ushr 8).toByte()
        frame[3] = (size ushr 16).toByte()
        frame[4] = (size ushr 24).toByte()
        data.copyInto(frame, destinationOffset = 5, startIndex = offset, endIndex = offset + size)
        return frame
    }

    private fun littleEndianInt(bytes: ByteArray): Int =
        (bytes[0].toInt() and 0xff) or ((bytes[1].toInt() and 0xff) shl 8) or
            ((bytes[2].toInt() and 0xff) shl 16) or ((bytes[3].toInt() and 0xff) shl 24)

    private fun List<ByteArray>.join(): ByteArray {
        val result = ByteArray(sumOf { it.size })
        var offset = 0
        forEach { it.copyInto(result, offset); offset += it.size }
        return result
    }
}
