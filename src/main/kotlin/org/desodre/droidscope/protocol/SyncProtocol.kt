package org.desodre.droidscope.protocol

import org.desodre.droidscope.error.*
import org.desodre.droidscope.model.*

internal class SyncProtocol(private val protocol: AdbProtocol) {
    suspend fun stat(path: String): RemoteFileStat {
        request("STAT", path)
        expect("STAT")
        return readStat()
    }

    suspend fun list(path: String): List<RemoteFile> {
        request("LIST", path)
        val files = mutableListOf<RemoteFile>()
        while (true) when (val id = readId()) {
            "DENT" -> {
                val stat = readStat()
                val name = AdbCodec.decodeText(protocol.readExactly(readLength()))
                files += RemoteFile(name, stat)
            }
            "DONE" -> { protocol.readExactly(16); return files }
            "FAIL" -> fail()
            else -> throw AdbProtocolException("Unexpected SYNC list id: $id")
        }
    }

    suspend fun pull(path: String, maxBytes: Int): ByteArray {
        request("RECV", path)
        val chunks = mutableListOf<ByteArray>()
        var total = 0
        while (true) when (val id = readId()) {
            "DATA" -> {
                val length = readLength()
                if (length > maxBytes - total) throw SyncTransferLimitException(maxBytes)
                chunks += protocol.readExactly(length)
                total += length
            }
            "DONE" -> { protocol.readExactly(4); return chunks.join(total) }
            "FAIL" -> fail()
            else -> throw AdbProtocolException("Unexpected SYNC pull id: $id")
        }
    }

    suspend fun push(path: String, data: ByteArray, mode: Int, modifiedAt: Long) {
        request("SEND", "$path,$mode")
        var offset = 0
        while (offset < data.size) {
            val bytes = data.copyOfRange(offset, minOf(offset + MAX_DATA, data.size))
            protocol.writeRaw("DATA".encodeToByteArray() + le(bytes.size) + bytes)
            offset += bytes.size
        }
        protocol.writeRaw("DONE".encodeToByteArray() + le(modifiedAt.toInt()))
        when (val id = readId()) {
            "OKAY" -> Unit
            "FAIL" -> fail()
            else -> throw AdbProtocolException("Unexpected SYNC push id: $id")
        }
    }

    private suspend fun request(id: String, value: String) {
        val bytes = value.encodeToByteArray(throwOnInvalidSequence = true)
        require(bytes.isNotEmpty() && bytes.size <= MAX_NAME) { "SYNC path must contain 1..$MAX_NAME UTF-8 bytes" }
        protocol.writeRaw(id.encodeToByteArray() + le(bytes.size) + bytes)
    }

    private suspend fun readStat(): RemoteFileStat = RemoteFileStat(readInt(), uint(readInt()), uint(readInt()))
    private suspend fun expect(expected: String) {
        when (val id = readId()) {
            expected -> Unit
            "FAIL" -> fail()
            else -> throw AdbProtocolException("Expected SYNC $expected, received $id")
        }
    }
    private suspend fun fail(): Nothing {
        val reason = AdbCodec.decodeText(protocol.readExactly(readLength()))
        throw AdbFailException(reason)
    }
    private suspend fun readId() = AdbCodec.decodeText(protocol.readExactly(4))
    private suspend fun readLength(): Int = readInt().also { if (it < 0) throw AdbProtocolException("Invalid SYNC length") }
    private suspend fun readInt(): Int = fromLe(protocol.readExactly(4))

    companion object {
        const val MAX_DATA = 64 * 1024
        const val MAX_NAME = 1024
        fun le(value: Int) = byteArrayOf(value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte(), (value ushr 24).toByte())
        fun fromLe(value: ByteArray) = (value[0].toInt() and 255) or ((value[1].toInt() and 255) shl 8) or
            ((value[2].toInt() and 255) shl 16) or ((value[3].toInt() and 255) shl 24)
        private fun uint(value: Int) = value.toLong() and 0xffffffffL
        private fun List<ByteArray>.join(size: Int) = ByteArray(size).also { out ->
            var offset = 0; forEach { it.copyInto(out, offset); offset += it.size }
        }
    }
}
