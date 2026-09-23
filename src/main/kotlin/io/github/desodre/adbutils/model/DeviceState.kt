package io.github.desodre.adbutils.model

public enum class DeviceState {
    DEVICE, OFFLINE, UNAUTHORIZED, BOOTLOADER, RECOVERY, SIDELOAD, RESCUE, NO_PERMISSIONS, UNKNOWN;

    public companion object {
        public fun fromWire(value: String): DeviceState = when (value) {
            "device" -> DEVICE
            "offline" -> OFFLINE
            "unauthorized" -> UNAUTHORIZED
            "bootloader" -> BOOTLOADER
            "recovery" -> RECOVERY
            "sideload" -> SIDELOAD
            "rescue" -> RESCUE
            "no permissions" -> NO_PERMISSIONS
            else -> UNKNOWN
        }
    }
}
