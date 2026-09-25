package io.github.desodre.adbutils.transport

/**
 * A single session. Implementations support one concurrent reader and writer; callers serialize
 * writes and never perform multiple reads concurrently. Factories return a fresh instance per operation.
 */
public interface AdbTransport {
    public suspend fun connect(): Unit
    public suspend fun write(data: ByteArray): Unit
    /** Returns 1..[maxBytes] bytes, or an empty array on EOF. */
    public suspend fun read(maxBytes: Int): ByteArray
    /** Idempotent; must release resources even when the calling coroutine is cancelled. */
    public suspend fun close(): Unit
}
