package io.github.desodre.adbutils.diagnostics

import io.github.desodre.adbutils.client.AdbDevice
import io.github.desodre.adbutils.error.AdbException
import io.github.desodre.adbutils.error.AdbServerUnavailableException
import io.github.desodre.adbutils.error.AdbTimeoutException
import io.github.desodre.adbutils.error.DeviceUnavailableException
import io.github.desodre.adbutils.model.AndroidVersionInfo
import io.github.desodre.adbutils.model.BatteryHealth
import io.github.desodre.adbutils.model.DeviceHealthOptions
import io.github.desodre.adbutils.model.DeviceHealthSnapshot
import io.github.desodre.adbutils.model.DiagnosticFailure
import io.github.desodre.adbutils.model.DiagnosticFailureKind
import io.github.desodre.adbutils.model.HardwareInfo
import io.github.desodre.adbutils.model.HealthSection
import io.github.desodre.adbutils.model.MemoryHealth
import io.github.desodre.adbutils.model.StorageHealth
import io.github.desodre.adbutils.model.UptimeHealth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

internal object DeviceHealthCollector {
    suspend fun collect(device: AdbDevice, options: DeviceHealthOptions): DeviceHealthSnapshot = supervisorScope {
        val collectedAt = System.currentTimeMillis()
        val battery = async { collectSection(device, options, BATTERY, DeviceHealthParsers::battery) }
        val storage = async { collectSection(device, options, STORAGE, DeviceHealthParsers::storage) }
        val memory = async { collectSection(device, options, MEMORY, DeviceHealthParsers::memory) }
        val uptime = async { collectSection(device, options, UPTIME, DeviceHealthParsers::uptime) }
        val properties = async {
            collectSection(device, options, propertiesCommand(options.includeIdentifiers), DeviceHealthParsers::properties)
        }
        val propertyValues = properties.await()
        DeviceHealthSnapshot(
            collectedAtEpochMillis = collectedAt,
            battery = battery.await(),
            storage = storage.await(),
            memory = memory.await(),
            uptime = uptime.await(),
            android = parseProperties(propertyValues, DeviceHealthParsers::android),
            hardware = parseProperties(propertyValues, DeviceHealthParsers::hardware),
        )
    }

    private suspend fun <T> collectSection(
        device: AdbDevice,
        options: DeviceHealthOptions,
        command: String,
        parser: (String) -> T,
    ): HealthSection<T> = try {
        val value = withTimeoutOrNull(options.sectionTimeoutMillis) {
            val result = device.shellV2(command, options.maxOutputBytes)
            if (result.exitCode != 0) {
                val detail = (result.stderr.ifBlank { result.stdout }).trim().take(MAX_ERROR_LENGTH)
                throw DiagnosticCommandException(detail.ifBlank { "Command exited with ${result.exitCode}" })
            }
            parser(result.stdout)
        }
        if (value == null) unavailable(DiagnosticFailureKind.TIMEOUT, "Section timed out")
        else HealthSection.Available(value)
    } catch (error: CancellationException) {
        throw error
    } catch (error: AdbTimeoutException) {
        unavailable(DiagnosticFailureKind.TIMEOUT, error.message ?: "ADB operation timed out")
    } catch (error: DiagnosticParseException) {
        unavailable(DiagnosticFailureKind.MALFORMED_OUTPUT, error.message ?: "Malformed diagnostic output")
    } catch (error: DiagnosticCommandException) {
        unavailable(DiagnosticFailureKind.COMMAND_FAILED, error.message ?: "Diagnostic command failed")
    } catch (error: AdbServerUnavailableException) {
        unavailable(DiagnosticFailureKind.UNAVAILABLE, error.message ?: "ADB server unavailable")
    } catch (error: DeviceUnavailableException) {
        unavailable(DiagnosticFailureKind.UNAVAILABLE, error.message ?: "Device unavailable")
    } catch (error: AdbException) {
        unavailable(DiagnosticFailureKind.COMMAND_FAILED, error.message ?: "ADB diagnostic failed")
    } catch (error: RuntimeException) {
        unavailable(DiagnosticFailureKind.MALFORMED_OUTPUT, error.message ?: "Invalid diagnostic data")
    }

    private fun <T> parseProperties(
        section: HealthSection<Map<String, String>>,
        parser: (Map<String, String>) -> T,
    ): HealthSection<T> = when (section) {
        is HealthSection.Available -> try {
            HealthSection.Available(parser(section.value))
        } catch (error: DiagnosticParseException) {
            unavailable(DiagnosticFailureKind.MALFORMED_OUTPUT, error.message ?: "Malformed property output")
        } catch (error: RuntimeException) {
            unavailable(DiagnosticFailureKind.MALFORMED_OUTPUT, error.message ?: "Invalid property data")
        }
        is HealthSection.Unavailable -> section
    }

    private fun unavailable(kind: DiagnosticFailureKind, message: String): HealthSection.Unavailable =
        HealthSection.Unavailable(DiagnosticFailure(kind, message.take(MAX_ERROR_LENGTH).ifBlank { kind.name }))

    private fun propertiesCommand(includeIdentifiers: Boolean): String {
        val properties = buildList {
            add("release" to "ro.build.version.release")
            add("sdk" to "ro.build.version.sdk")
            add("security_patch" to "ro.build.version.security_patch")
            add("build_id" to "ro.build.id")
            add("manufacturer" to "ro.product.manufacturer")
            add("model" to "ro.product.model")
            add("device" to "ro.product.device")
            add("product" to "ro.product.name")
            add("abi" to "ro.product.cpu.abi")
            if (includeIdentifiers) {
                add("serial" to "ro.serialno")
                add("boot_serial" to "ro.boot.serialno")
            }
        }
        return properties.joinToString("; ") { (alias, property) ->
            "printf '$alias=%s\\n' \"${'$'}(getprop '$property')\""
        }
    }

    private const val BATTERY = "dumpsys battery"
    private const val STORAGE = "df -k /data"
    private const val MEMORY = "cat /proc/meminfo"
    private const val UPTIME = "cat /proc/uptime"
    private const val MAX_ERROR_LENGTH = 512
}

private class DiagnosticCommandException(message: String) : Exception(message)
