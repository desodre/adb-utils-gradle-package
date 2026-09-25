package io.github.desodre.adbutils.diagnostics

import io.github.desodre.adbutils.model.AndroidVersionInfo
import io.github.desodre.adbutils.model.BatteryCondition
import io.github.desodre.adbutils.model.BatteryHealth
import io.github.desodre.adbutils.model.BatteryPowerSource
import io.github.desodre.adbutils.model.BatteryStatus
import io.github.desodre.adbutils.model.HardwareInfo
import io.github.desodre.adbutils.model.MemoryHealth
import io.github.desodre.adbutils.model.StorageHealth
import io.github.desodre.adbutils.model.UptimeHealth
import kotlin.math.roundToLong

internal object DeviceHealthParsers {
    fun battery(output: String): BatteryHealth {
        val values = keyValues(output)
        val level = values["level"]?.toIntOrNull()
        val scale = values["scale"]?.toIntOrNull()
        val levelPercent = if (level != null && scale != null && scale > 0) {
            ((level.toLong() * 100) / scale).toInt().coerceIn(0, 100)
        } else null
        val sources = buildSet {
            if (values["AC powered"].toBooleanValue() == true) add(BatteryPowerSource.AC)
            if (values["USB powered"].toBooleanValue() == true) add(BatteryPowerSource.USB)
            if (values["Wireless powered"].toBooleanValue() == true) add(BatteryPowerSource.WIRELESS)
            if (values["Dock powered"].toBooleanValue() == true) add(BatteryPowerSource.DOCK)
        }
        val result = BatteryHealth(
            levelPercent = levelPercent,
            status = values["status"]?.toIntOrNull()?.let(::batteryStatus),
            condition = values["health"]?.toIntOrNull()?.let(::batteryCondition),
            present = values["present"].toBooleanValue(),
            powerSources = sources,
            voltageMillivolts = values["voltage"]?.toIntOrNull(),
            temperatureDeciCelsius = values["temperature"]?.toIntOrNull(),
            technology = values["technology"].nonBlank(),
        )
        if (values.isEmpty() || result == BatteryHealth(null, null, null, null, emptySet(), null, null, null)) {
            throw DiagnosticParseException("Battery output contains no recognized fields")
        }
        return result
    }

    fun storage(output: String): StorageHealth {
        val lines = output.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val data = lines.lastOrNull { line ->
            val fields = line.split(Regex("\\s+"))
            fields.size >= 4 && parseSize(fields.getOrNull(1)) != null
        } ?: throw DiagnosticParseException("Storage output contains no data row")
        val fields = data.split(Regex("\\s+"))
        val total = parseSize(fields.getOrNull(1)) ?: throw DiagnosticParseException("Invalid total storage")
        val used = parseSize(fields.getOrNull(2)) ?: throw DiagnosticParseException("Invalid used storage")
        val available = parseSize(fields.getOrNull(3)) ?: throw DiagnosticParseException("Invalid available storage")
        val percentIndex = fields.indexOfFirst { it.endsWith('%') }
        val percent = fields.getOrNull(percentIndex)?.removeSuffix("%")?.toIntOrNull()
        val mount = when {
            percentIndex >= 0 -> fields.getOrNull(percentIndex + 1)
            fields.size >= 6 -> fields.last()
            else -> "/data"
        }.nonBlank() ?: "/data"
        return StorageHealth(mount, total, used, available, percent)
    }

    fun memory(output: String): MemoryHealth {
        val values = keyValues(output)
        fun bytes(key: String): Long? = values[key]?.let(::parseMemoryBytes)
        val total = bytes("MemTotal") ?: throw DiagnosticParseException("MemTotal is missing")
        val free = bytes("MemFree") ?: 0
        val buffers = bytes("Buffers") ?: 0
        val cached = bytes("Cached") ?: 0
        val available = (bytes("MemAvailable") ?: (free + buffers + cached)).coerceIn(0, total)
        return MemoryHealth(
            totalBytes = total,
            availableBytes = available,
            usedBytes = (total - available).coerceAtLeast(0),
            swapTotalBytes = bytes("SwapTotal") ?: 0,
            swapFreeBytes = bytes("SwapFree") ?: 0,
        )
    }

    fun uptime(output: String): UptimeHealth {
        val fields = output.trim().split(Regex("\\s+"))
        val uptime = secondsToMillis(fields.firstOrNull()) ?: throw DiagnosticParseException("Invalid uptime")
        val idle = secondsToMillis(fields.getOrNull(1))
        return UptimeHealth(uptime, idle)
    }

    fun properties(output: String): Map<String, String> = output.lineSequence().mapNotNull { line ->
        val separator = line.indexOf('=')
        if (separator <= 0) null else line.substring(0, separator).trim() to line.substring(separator + 1).trim()
    }.toMap()

    fun android(properties: Map<String, String>): AndroidVersionInfo {
        val result = AndroidVersionInfo(
            release = properties["release"].nonBlank(),
            sdk = properties["sdk"]?.toIntOrNull(),
            securityPatch = properties["security_patch"].nonBlank(),
            buildId = properties["build_id"].nonBlank(),
        )
        if (result.release == null && result.sdk == null && result.securityPatch == null && result.buildId == null) {
            throw DiagnosticParseException("Android version properties are missing")
        }
        return result
    }

    fun hardware(properties: Map<String, String>): HardwareInfo {
        val result = HardwareInfo(
            manufacturer = properties["manufacturer"].nonBlank(),
            model = properties["model"].nonBlank(),
            device = properties["device"].nonBlank(),
            product = properties["product"].nonBlank(),
            primaryAbi = properties["abi"].nonBlank(),
            serialNumber = properties["serial"].nonBlank() ?: properties["boot_serial"].nonBlank(),
        )
        if (result.manufacturer == null && result.model == null && result.device == null &&
            result.product == null && result.primaryAbi == null && result.serialNumber == null
        ) throw DiagnosticParseException("Hardware properties are missing")
        return result
    }

    private fun keyValues(output: String): Map<String, String> = output.lineSequence().mapNotNull { line ->
        val separator = line.indexOf(':')
        if (separator <= 0) null else line.substring(0, separator).trim() to line.substring(separator + 1).trim()
    }.toMap()

    private fun parseSize(value: String?): Long? {
        if (value == null) return null
        val match = Regex("([0-9]+(?:\\.[0-9]+)?)([KMGT]?)", RegexOption.IGNORE_CASE).matchEntire(value) ?: return null
        val multiplier = when (match.groupValues[2].uppercase()) {
            "" -> 1024.0
            "K" -> 1024.0
            "M" -> 1024.0 * 1024
            "G" -> 1024.0 * 1024 * 1024
            "T" -> 1024.0 * 1024 * 1024 * 1024
            else -> return null
        }
        val bytes = match.groupValues[1].toDoubleOrNull()?.times(multiplier) ?: return null
        return if (bytes.isFinite() && bytes in 0.0..Long.MAX_VALUE.toDouble()) bytes.roundToLong() else null
    }

    private fun parseMemoryBytes(value: String): Long {
        val fields = value.split(Regex("\\s+"))
        val amount = fields.firstOrNull()?.toLongOrNull() ?: throw DiagnosticParseException("Invalid memory value: $value")
        return try {
            when (fields.getOrNull(1)?.lowercase()) {
                null, "b" -> amount
                "kb" -> Math.multiplyExact(amount, 1024)
                "mb" -> Math.multiplyExact(amount, 1024 * 1024)
                else -> throw DiagnosticParseException("Unsupported memory unit: $value")
            }
        } catch (_: ArithmeticException) {
            throw DiagnosticParseException("Memory value overflow: $value")
        }
    }

    private fun secondsToMillis(value: String?): Long? {
        val seconds = value?.toDoubleOrNull() ?: return null
        val millis = seconds * 1_000
        return if (millis.isFinite() && millis in 0.0..Long.MAX_VALUE.toDouble()) millis.roundToLong() else null
    }

    private fun batteryStatus(value: Int): BatteryStatus = when (value) {
        2 -> BatteryStatus.CHARGING
        3 -> BatteryStatus.DISCHARGING
        4 -> BatteryStatus.NOT_CHARGING
        5 -> BatteryStatus.FULL
        else -> BatteryStatus.UNKNOWN
    }

    private fun batteryCondition(value: Int): BatteryCondition = when (value) {
        2 -> BatteryCondition.GOOD
        3 -> BatteryCondition.OVERHEAT
        4 -> BatteryCondition.DEAD
        5 -> BatteryCondition.OVER_VOLTAGE
        6 -> BatteryCondition.UNSPECIFIED_FAILURE
        7 -> BatteryCondition.COLD
        else -> BatteryCondition.UNKNOWN
    }

    private fun String?.toBooleanValue(): Boolean? = when (this?.lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }

    private fun String?.nonBlank(): String? = this?.takeIf(String::isNotBlank)
}

internal class DiagnosticParseException(message: String) : Exception(message)
