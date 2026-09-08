package io.github.desodre.adbutils.protocol

import io.github.desodre.adbutils.error.AdbProtocolException

internal object AdbCodec {
    fun encodeRequest(request: String): ByteArray {
        val payload = request.encodeToByteArray(throwOnInvalidSequence = true)
        require(payload.size in 1..0xffff) { "ADB request must contain 1..65535 UTF-8 bytes" }
        return payload.size.toString(16).uppercase().padStart(4, '0').encodeToByteArray() + payload
    }

    fun decodeLength(prefix: ByteArray): Int {
        if (prefix.size != 4 || prefix.any { it.toInt().toChar() !in "0123456789abcdefABCDEF" }) {
            throw AdbProtocolException("Invalid four-byte hexadecimal length prefix")
        }
        return prefix.decodeToString().toInt(16)
    }

    fun decodeText(bytes: ByteArray): String = try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (error: CharacterCodingException) {
        throw AdbProtocolException("Invalid UTF-8 response", error)
    }
}
