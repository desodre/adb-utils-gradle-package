package org.desodre.droidscope.error

import org.desodre.droidscope.model.*

open class AdbException(message: String, cause: Throwable? = null) : Exception(message, cause)
class AdbServerUnavailableException(cause: Throwable) : AdbException("ADB server unavailable", cause)
class AdbConnectionException(message: String, cause: Throwable? = null) : AdbException(message, cause)
class AdbTimeoutException(cause: Throwable? = null) : AdbException("ADB operation timed out", cause)
class AdbProtocolException(message: String, cause: Throwable? = null) : AdbException(message, cause)
class AdbFailException(val reason: String) : AdbException("ADB FAIL: $reason")
class NoDevicesException(val detected: List<DeviceInfo>) : AdbException("No devices in DEVICE state are available")
class MultipleDevicesException(val serials: List<DeviceSerial>) : AdbException("Multiple devices available; specify a serial: ${serials.joinToString()}")
class DeviceNotFoundException(val serial: DeviceSerial, cause: Throwable? = null) : AdbException("Device not found: $serial", cause)
open class DeviceUnavailableException(val serial: DeviceSerial, val state: DeviceState, cause: Throwable? = null) :
    AdbException("Device $serial is $state", cause)
class DeviceUnauthorizedException(serial: DeviceSerial, cause: Throwable? = null) : DeviceUnavailableException(serial, DeviceState.UNAUTHORIZED, cause)
class DeviceOfflineException(serial: DeviceSerial, cause: Throwable? = null) : DeviceUnavailableException(serial, DeviceState.OFFLINE, cause)
class ShellOutputLimitException(val maxBytes: Int) : AdbException("Shell output exceeded $maxBytes bytes")
class SyncTransferLimitException(val maxBytes: Int) : AdbException("SYNC transfer exceeded $maxBytes bytes")
class PackageOperationException(message: String) : AdbException(message)
