package io.github.desodre.adbutils.protocol

import io.github.desodre.adbutils.error.*
import io.github.desodre.adbutils.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf

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
        val chunks = mutableListOf<ByteArray>()
        val total = pull(path, maxBytes.toLong()) { chunks += it }
        return chunks.join(total.toInt())
    }

    suspend fun pull(path: String, maxBytes: Long, onChunk: suspend (ByteArray) -> Unit): Long {
        request("RECV", path)
        var total = 0L
        while (true) when (val id = readId()) {
            "DATA" -> {
                val length = readLength()
                if (length > MAX_DATA) throw AdbProtocolException("SYNC DATA frame exceeds $MAX_DATA bytes")
                if (length > maxBytes - total) throw SyncTransferLimitException(maxBytes)
                onChunk(protocol.readExactly(length))
                total += length
            }
            "DONE" -> { protocol.readExactly(4); return total }
            "FAIL" -> fail()
            else -> throw AdbProtocolException("Unexpected SYNC pull id: $id")
        }
    }

    suspend fun push(path: String, data: ByteArray, mode: Int, modifiedAt: Long) {
        push(path, flowOf(data), mode, modifiedAt, data.size.toLong()) {}
    }

    suspend fun push(
        path: String,
        chunks: Flow<ByteArray>,
        mode: Int,
        modifiedAt: Long,
        maxBytes: Long,
        onProgress: suspend (Long) -> Unit,
    ): Long {
        request("SEND", "$path,$mode")
        var total = 0L
        chunks.collect { chunk ->
            var offset = 0
            while (offset < chunk.size) {
                val length = minOf(MAX_DATA, chunk.size - offset)
                if (length > maxBytes - total) throw SyncTransferLimitException(maxBytes)
                val bytes = chunk.copyOfRange(offset, offset + length)
                protocol.writeRaw("DATA".encodeToByteArray() + le(length) + bytes)
                offset += length
                total += length
                onProgress(total)
            }
        }
        protocol.writeRaw("DONE".encodeToByteArray() + le(modifiedAt.toInt()))
        when (val id = readId()) {
            "OKAY" -> Unit
            "FAIL" -> fail()
            else -> throw AdbProtocolException("Unexpected SYNC push id: $id")
        }
        return total
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
        fun le(value: Int): ByteArray = byteArrayOf(value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte(), (value ushr 24).toByte())
        fun fromLe(value: ByteArray): Int = (value[0].toInt() and 255) or ((value[1].toInt() and 255) shl 8) or
            ((value[2].toInt() and 255) shl 16) or ((value[3].toInt() and 255) shl 24)
        private fun uint(value: Int) = value.toLong() and 0xffffffffL
        private fun List<ByteArray>.join(size: Int) = ByteArray(size).also { out ->
            var offset = 0; forEach { it.copyInto(out, offset); offset += it.size }
        }
    }
}
