package org.desodre.droidscope.protocol

import org.desodre.droidscope.error.*
import org.desodre.droidscope.transport.AdbTransport

internal class AdbProtocol(private val transport: AdbTransport) {
    suspend fun request(service: String) {
        transport.write(AdbCodec.encodeRequest(service))
        when (AdbCodec.decodeText(readExactly(4))) {
            "OKAY" -> Unit
            "FAIL" -> throw AdbFailException(readPayload())
            else -> throw AdbProtocolException("Expected OKAY or FAIL status")
        }
    }

    suspend fun readPayload(): String = AdbCodec.decodeText(readExactly(AdbCodec.decodeLength(readExactly(4))))

    suspend fun readShellOutput(maxBytes: Int): String {
        val chunks = mutableListOf<ByteArray>()
        var size = 0
        while (true) {
            val chunk = transport.read(8192)
            if (chunk.isEmpty()) break
            if (chunk.size > maxBytes - size) throw ShellOutputLimitException(maxBytes)
            chunks += chunk
            size += chunk.size
        }
        val output = ByteArray(size)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(output, offset)
            offset += chunk.size
        }
        return AdbCodec.decodeText(output)
    }

    private suspend fun readExactly(size: Int): ByteArray {
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val chunk = transport.read(size - offset)
            if (chunk.isEmpty()) throw AdbProtocolException("Unexpected EOF: expected $size bytes, received $offset")
            if (chunk.size > size - offset) throw AdbProtocolException("Transport exceeded requested read size")
            chunk.copyInto(result, offset)
            offset += chunk.size
        }
        return result
    }
}
