package org.desodre.droidscope.model

enum class DeviceState {
    DEVICE, OFFLINE, UNAUTHORIZED, BOOTLOADER, RECOVERY, SIDELOAD, RESCUE, NO_PERMISSIONS, UNKNOWN;

    companion object {
        fun fromWire(value: String): DeviceState = when (value) {
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
