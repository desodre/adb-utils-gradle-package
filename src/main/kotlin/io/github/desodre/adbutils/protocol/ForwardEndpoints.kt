package io.github.desodre.adbutils.protocol

import io.github.desodre.adbutils.error.AdbProtocolException
import io.github.desodre.adbutils.error.UnsupportedEndpointException
import io.github.desodre.adbutils.model.*

internal fun validateEndpoint(endpoint: AdbEndpoint, operation: String, role: String, remoteForward: Boolean = false) {
    val supported = when (endpoint) {
        is TcpPort, is LocalAbstract, is LocalReserved, is LocalFilesystem -> true
        is JdwpPid, is DeviceFile, is RawDeviceFile -> remoteForward
        is UnknownEndpoint -> false
    }
    if (!supported) throw UnsupportedEndpointException(endpoint, operation, role)
}

internal fun parseEndpoint(value: String): AdbEndpoint {
    val type = value.substringBefore(':')
    val part = value.substringAfter(':', "")
    try {
        return when (type) {
            "tcp" -> TcpPort(parseEndpointNumber(part))
            "jdwp" -> JdwpPid(parseEndpointNumber(part))
            "localabstract" -> LocalAbstract(part)
            "localreserved" -> LocalReserved(part)
            "localfilesystem" -> LocalFilesystem(part)
            "dev" -> DeviceFile(part)
            "dev-raw" -> RawDeviceFile(part)
            else -> {
                require(':' in value && type.isNotEmpty() && part.isNotEmpty())
                require(value.none { it.isWhitespace() || it.isISOControl() || it == ';' })
                UnknownEndpoint(value)
            }
        }
    } catch (error: IllegalArgumentException) {
        throw AdbProtocolException("Invalid endpoint in ADB listing: $value", error)
    }
}

private fun parseEndpointNumber(value: String): Int {
    require(value.isNotEmpty() && value.all { it in '0'..'9' })
    return requireNotNull(value.toIntOrNull()) { "Endpoint number is outside Int range" }
}
