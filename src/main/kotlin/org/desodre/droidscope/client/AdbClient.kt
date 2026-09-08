package org.desodre.droidscope.client

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.desodre.droidscope.error.*
import org.desodre.droidscope.model.*
import org.desodre.droidscope.protocol.*
import org.desodre.droidscope.transport.AdbTransport
import org.desodre.droidscope.transport.jvm.JvmAdbTransport

/** Each operation owns a fresh connection and closes it on success, failure or cancellation. */
class AdbClient(
    host: String = "127.0.0.1",
    port: Int = 5037,
    private val timeoutMillis: Int = 10_000,
    private val transportFactory: () -> AdbTransport = { JvmAdbTransport(host, port, timeoutMillis) },
) {
    init {
        require(host.isNotBlank())
        require(port in 1..65535)
        require(timeoutMillis > 0)
    }

    suspend fun version(): AdbVersion = session { protocol ->
        protocol.request("host:version")
        AdbVersion(AdbCodec.decodeLength(protocol.readPayload().encodeToByteArray()))
    }

    /** Includes unavailable devices and retains the server's model spelling (including underscores). */
    suspend fun devices(): List<DeviceInfo> = session { protocol ->
        protocol.request("host:devices-l")
        DeviceListParser.parse(protocol.readPayload())
    }

    /** Cold stream. Every collector owns and closes an independent tracking connection. */
    fun trackDevices(): Flow<List<DeviceInfo>> = flow {
        streamingSession { protocol ->
            protocol.request("host:track-devices-l")
            while (true) emit(DeviceListParser.parse(AdbCodec.decodeText(protocol.readPayloadBytes())))
        }
    }

    /** Without a serial, selects the sole DEVICE entry. Other states are available in devices(). */
    suspend fun device(serial: DeviceSerial? = null): AdbDevice {
        val detected = devices()
        val selected = if (serial != null) {
            detected.firstOrNull { it.serial == serial } ?: throw DeviceNotFoundException(serial)
        } else {
            val available = detected.filter { it.state == DeviceState.DEVICE }
            when (available.size) {
                0 -> throw NoDevicesException(detected)
                1 -> available.single()
                else -> throw MultipleDevicesException(available.map { it.serial })
            }
        }
        when (selected.state) {
            DeviceState.DEVICE -> Unit
            DeviceState.UNAUTHORIZED -> throw DeviceUnauthorizedException(selected.serial)
            DeviceState.OFFLINE -> throw DeviceOfflineException(selected.serial)
            else -> throw DeviceUnavailableException(selected.serial, selected.state)
        }
        return AdbDevice(this, selected.serial)
    }

    internal suspend fun <T> session(block: suspend (AdbProtocol) -> T): T {
        return withTimeoutOrNull(timeoutMillis.toLong()) { managedSession(block) } ?: throw AdbTimeoutException()
    }

    internal suspend fun <T> streamingSession(block: suspend (AdbProtocol) -> T): T = managedSession(block)

    internal suspend fun hostPayload(service: String): String = session { protocol ->
        protocol.request(service)
        protocol.readPayload()
    }

    internal suspend fun hostCommand(service: String) = session { it.request(service) }

    private suspend fun <T> managedSession(block: suspend (AdbProtocol) -> T): T {
        val transport = transportFactory()
        var failure: Throwable? = null
        try {
            transport.connect()
            return block(AdbProtocol(transport))
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            withContext(NonCancellable) {
                try { transport.close() } catch (closeError: Throwable) {
                    if (failure != null) failure.addSuppressed(closeError) else throw closeError
                }
            }
        }
    }
}
