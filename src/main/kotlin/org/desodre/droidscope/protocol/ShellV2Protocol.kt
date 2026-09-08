package org.desodre.droidscope.protocol

import org.desodre.droidscope.error.*
import org.desodre.droidscope.model.ShellResult

internal object ShellV2Protocol {
    private const val STDOUT = 1
    private const val STDERR = 2
    private const val EXIT = 3

    suspend fun read(protocol: AdbProtocol, maxBytes: Int): ShellResult {
        val stdout = mutableListOf<ByteArray>()
        val stderr = mutableListOf<ByteArray>()
        var total = 0
        var exit: Int? = null
        while (exit == null) {
            val id = protocol.readExactly(1)[0].toInt() and 0xff
            val size = littleEndianInt(protocol.readExactly(4))
            if (size < 0) throw AdbProtocolException("Negative shell v2 frame size")
            if (size > maxBytes - total) throw ShellOutputLimitException(maxBytes)
            val payload = protocol.readExactly(size)
            when (id) {
                STDOUT -> stdout += payload
                STDERR -> stderr += payload
                EXIT -> {
                    if (size != 1) throw AdbProtocolException("Shell v2 exit frame must contain one byte")
                    exit = payload[0].toInt() and 0xff
                }
                else -> throw AdbProtocolException("Unknown shell v2 stream id: $id")
            }
            total += size
        }
        return ShellResult(AdbCodec.decodeText(stdout.join()), AdbCodec.decodeText(stderr.join()), exit)
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
