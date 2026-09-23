package io.github.desodre.adbutils.error

import io.github.desodre.adbutils.model.*

public open class AdbException(message: String, cause: Throwable? = null) : Exception(message, cause)
public class AdbServerUnavailableException(cause: Throwable) : AdbException("ADB server unavailable", cause)
public class AdbConnectionException(message: String, cause: Throwable? = null) : AdbException(message, cause)
public class AdbTimeoutException(cause: Throwable? = null) : AdbException("ADB operation timed out", cause)
public class AdbProtocolException(message: String, cause: Throwable? = null) : AdbException(message, cause)
public class AdbFailException(public val reason: String) : AdbException("ADB FAIL: $reason")
public class NoDevicesException(public val detected: List<DeviceInfo>) : AdbException("No devices in DEVICE state are available")
public class MultipleDevicesException(public val serials: List<DeviceSerial>) : AdbException("Multiple devices available; specify a serial: ${serials.joinToString()}")
public class DeviceNotFoundException(public val serial: DeviceSerial, cause: Throwable? = null) : AdbException("Device not found: $serial", cause)
public open class DeviceUnavailableException(public val serial: DeviceSerial, public val state: DeviceState, cause: Throwable? = null) :
    AdbException("Device $serial is $state", cause)
public class DeviceUnauthorizedException(serial: DeviceSerial, cause: Throwable? = null) : DeviceUnavailableException(serial, DeviceState.UNAUTHORIZED, cause)
public class DeviceOfflineException(serial: DeviceSerial, cause: Throwable? = null) : DeviceUnavailableException(serial, DeviceState.OFFLINE, cause)
public class ShellOutputLimitException(public val maxBytes: Int) : AdbException("Shell output exceeded $maxBytes bytes")
public class SyncTransferLimitException(public val maxBytes: Long) : AdbException("SYNC transfer exceeded $maxBytes bytes")
public class PackageOperationException(message: String) : AdbException(message)
