package io.github.desodre.adbutils.model

/** Controls bounded collection of a device health snapshot. */
public data class DeviceHealthOptions(
    public val sectionTimeoutMillis: Long = 2_000,
    public val maxOutputBytes: Int = 256 * 1024,
    public val includeIdentifiers: Boolean = false,
) {
    init {
        require(sectionTimeoutMillis > 0) { "sectionTimeoutMillis must be positive" }
        require(maxOutputBytes > 0) { "maxOutputBytes must be positive" }
    }
}

public enum class DiagnosticFailureKind { TIMEOUT, COMMAND_FAILED, MALFORMED_OUTPUT, UNAVAILABLE }

public data class DiagnosticFailure(
    public val kind: DiagnosticFailureKind,
    public val message: String,
) {
    init { require(message.isNotBlank()) }
}

/** A section is either available or unavailable without invalid nullable combinations. */
public sealed interface HealthSection<out T> {
    public data class Available<T>(public val value: T) : HealthSection<T>
    public data class Unavailable(public val failure: DiagnosticFailure) : HealthSection<Nothing>
}

public enum class BatteryStatus { UNKNOWN, CHARGING, DISCHARGING, NOT_CHARGING, FULL }
public enum class BatteryCondition { UNKNOWN, GOOD, OVERHEAT, DEAD, OVER_VOLTAGE, UNSPECIFIED_FAILURE, COLD }
public enum class BatteryPowerSource { AC, USB, WIRELESS, DOCK }

public data class BatteryHealth(
    public val levelPercent: Int?,
    public val status: BatteryStatus?,
    public val condition: BatteryCondition?,
    public val present: Boolean?,
    public val powerSources: Set<BatteryPowerSource>,
    public val voltageMillivolts: Int?,
    public val temperatureDeciCelsius: Int?,
    public val technology: String?,
) {
    init { require(levelPercent == null || levelPercent in 0..100) }
}

public data class StorageHealth(
    public val mountPoint: String,
    public val totalBytes: Long,
    public val usedBytes: Long,
    public val availableBytes: Long,
    public val usagePercent: Int?,
) {
    init {
        require(mountPoint.isNotBlank())
        require(totalBytes >= 0 && usedBytes >= 0 && availableBytes >= 0)
        require(usagePercent == null || usagePercent in 0..100)
    }
}

public data class MemoryHealth(
    public val totalBytes: Long,
    public val availableBytes: Long,
    public val usedBytes: Long,
    public val swapTotalBytes: Long,
    public val swapFreeBytes: Long,
) {
    init {
        require(totalBytes >= 0 && availableBytes >= 0 && usedBytes >= 0)
        require(swapTotalBytes >= 0 && swapFreeBytes >= 0)
    }
}

public data class UptimeHealth(
    public val uptimeMillis: Long,
    public val idleMillis: Long?,
) {
    init { require(uptimeMillis >= 0 && (idleMillis == null || idleMillis >= 0)) }
}

public data class AndroidVersionInfo(
    public val release: String?,
    public val sdk: Int?,
    public val securityPatch: String?,
    public val buildId: String?,
) {
    init { require(sdk == null || sdk > 0) }
}

public data class HardwareInfo(
    public val manufacturer: String?,
    public val model: String?,
    public val device: String?,
    public val product: String?,
    public val primaryAbi: String?,
    /** Hardware serial is collected only when DeviceHealthOptions.includeIdentifiers is true. */
    public val serialNumber: String?,
)

public data class DeviceHealthSnapshot(
    public val collectedAtEpochMillis: Long,
    public val battery: HealthSection<BatteryHealth>,
    public val storage: HealthSection<StorageHealth>,
    public val memory: HealthSection<MemoryHealth>,
    public val uptime: HealthSection<UptimeHealth>,
    public val android: HealthSection<AndroidVersionInfo>,
    public val hardware: HealthSection<HardwareInfo>,
) {
    init { require(collectedAtEpochMillis >= 0) }
}
