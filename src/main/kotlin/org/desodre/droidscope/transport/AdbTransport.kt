package org.desodre.droidscope.transport

/** A single session, used sequentially. Factories must return a fresh instance per operation. */
interface AdbTransport {
    suspend fun connect()
    suspend fun write(data: ByteArray)
    /** Returns 1..[maxBytes] bytes, or an empty array on EOF. */
    suspend fun read(maxBytes: Int): ByteArray
    /** Idempotent; must release resources even when the calling coroutine is cancelled. */
    suspend fun close()
}
