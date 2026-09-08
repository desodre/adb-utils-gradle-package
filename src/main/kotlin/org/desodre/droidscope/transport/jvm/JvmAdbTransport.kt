package org.desodre.droidscope.transport.jvm

import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.desodre.droidscope.error.*
import org.desodre.droidscope.transport.AdbTransport

/** Blocking socket I/O runs on Dispatchers.IO; cancellation closes the socket to unblock it. */
class JvmAdbTransport(
    private val host: String = "127.0.0.1",
    private val port: Int = 5037,
    private val timeoutMillis: Int = 10_000,
) : AdbTransport {
    init {
        require(host.isNotBlank())
        require(port in 1..65535)
        require(timeoutMillis > 0)
    }

    private val socket = Socket()

    override suspend fun connect() = io {
        socket.soTimeout = timeoutMillis
        socket.connect(InetSocketAddress(host, port), timeoutMillis)
    }

    override suspend fun write(data: ByteArray) = io { socket.getOutputStream().write(data) }

    override suspend fun read(maxBytes: Int): ByteArray {
        require(maxBytes > 0)
        return io {
            val buffer = ByteArray(maxBytes)
            val count = socket.getInputStream().read(buffer)
            if (count < 0) byteArrayOf() else buffer.copyOf(count)
        }
    }

    override suspend fun close() {
        try { socket.close() } catch (error: IOException) {
            throw AdbConnectionException("Failed to close ADB TCP connection", error)
        }
    }

    private suspend fun <T> io(block: () -> T): T = try {
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { runCatching { socket.close() } }
                if (continuation.isActive) {
                    val result = runCatching(block).recoverCatching { error ->
                        throw when (error) {
                            is SocketTimeoutException -> AdbTimeoutException(error)
                            is ConnectException -> AdbServerUnavailableException(error)
                            is IOException -> AdbConnectionException("ADB TCP connection failed", error)
                            else -> error
                        }
                    }
                    continuation.resumeWith(result)
                }
            }
        }
    } catch (error: CancellationException) {
        // Also handles cancellation before the IO dispatcher starts the block.
        runCatching { socket.close() }
        throw error
    }
}
