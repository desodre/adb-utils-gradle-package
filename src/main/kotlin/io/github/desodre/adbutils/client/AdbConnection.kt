package io.github.desodre.adbutils.client

import io.github.desodre.adbutils.protocol.AdbProtocol
import io.github.desodre.adbutils.transport.AdbTransport
import java.util.concurrent.atomic.AtomicBoolean

/** Internal ownership boundary for operations that outlive a single suspending call. */
internal class AdbConnection(private val transport: AdbTransport) {
    val protocol: AdbProtocol = AdbProtocol(transport)
    private val closed = AtomicBoolean(false)

    suspend fun close() {
        if (closed.compareAndSet(false, true)) transport.close()
    }
}
