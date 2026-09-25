package io.github.desodre.adbutils.diagnostics

import io.github.desodre.adbutils.model.BatteryCondition
import io.github.desodre.adbutils.model.BatteryPowerSource
import io.github.desodre.adbutils.model.BatteryStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DeviceHealthParsersTest {
    @Test fun `parses modern battery output with optional vendor fields`() {
        val battery = DeviceHealthParsers.battery(
            """
            Current Battery Service state:
              AC powered: false
              USB powered: true
              Wireless powered: false
              Dock powered: false
              status: 2
              health: 2
              present: true
              level: 81
              scale: 100
              voltage: 4381
              temperature: 312
              technology: Li-ion
              vendor extension: ignored
            """.trimIndent(),
        )

        assertEquals(81, battery.levelPercent)
        assertEquals(BatteryStatus.CHARGING, battery.status)
        assertEquals(BatteryCondition.GOOD, battery.condition)
        assertEquals(setOf(BatteryPowerSource.USB), battery.powerSources)
        assertEquals(312, battery.temperatureDeciCelsius)
    }

    @Test fun `parses battery output with fields missing`() {
        val battery = DeviceHealthParsers.battery("level: 25\nscale: 50\nhealth: 7")
        assertEquals(50, battery.levelPercent)
        assertEquals(BatteryCondition.COLD, battery.condition)
        assertNull(battery.status)
    }

    @Test fun `parses modern and legacy storage layouts`() {
        val modern = DeviceHealthParsers.storage(
            "Filesystem 1K-blocks Used Available Use% Mounted on\n/dev/block/dm-8 100000 25000 75000 25% /data",
        )
        assertEquals(102_400_000, modern.totalBytes)
        assertEquals(25, modern.usagePercent)
        assertEquals("/data", modern.mountPoint)

        val legacy = DeviceHealthParsers.storage(
            "Filesystem Size Used Free Blksize\n/dev/block/mmcblk0p10 11.7G 5.2G 6.5G 4096",
        )
        assertEquals("/data", legacy.mountPoint)
        assertEquals(12_562_779_341, legacy.totalBytes)
        assertNull(legacy.usagePercent)
    }

    @Test fun `parses meminfo with and without MemAvailable`() {
        val modern = DeviceHealthParsers.memory(
            "MemTotal: 8000000 kB\nMemAvailable: 3000000 kB\nSwapTotal: 1000 kB\nSwapFree: 400 kB\nVendorField: unknown",
        )
        assertEquals(8_192_000_000, modern.totalBytes)
        assertEquals(5_120_000_000, modern.usedBytes)

        val legacy = DeviceHealthParsers.memory(
            "MemTotal: 1000 kB\nMemFree: 100 kB\nBuffers: 50 kB\nCached: 250 kB",
        )
        assertEquals(409_600, legacy.availableBytes)
        assertEquals(614_400, legacy.usedBytes)
    }

    @Test fun `parses uptime and property groups`() {
        val uptime = DeviceHealthParsers.uptime("123.45 678.90")
        assertEquals(123_450, uptime.uptimeMillis)
        assertEquals(678_900, uptime.idleMillis)

        val properties = DeviceHealthParsers.properties(
            "release=16\nsdk=36\nsecurity_patch=2026-09-01\nbuild_id=BP2A\n" +
                "manufacturer=Google\nmodel=Pixel 9\ndevice=tokay\nproduct=tokay\nabi=arm64-v8a\nserial=secret",
        )
        assertEquals(36, DeviceHealthParsers.android(properties).sdk)
        assertEquals("Pixel 9", DeviceHealthParsers.hardware(properties).model)
        assertEquals("secret", DeviceHealthParsers.hardware(properties).serialNumber)
    }

    @Test fun `rejects outputs without required structure`() {
        assertFailsWith<DiagnosticParseException> { DeviceHealthParsers.battery("noise") }
        assertFailsWith<DiagnosticParseException> { DeviceHealthParsers.storage("Filesystem only") }
        assertFailsWith<DiagnosticParseException> { DeviceHealthParsers.memory("MemFree: 1 kB") }
        assertFailsWith<DiagnosticParseException> { DeviceHealthParsers.uptime("unknown") }
        assertFailsWith<DiagnosticParseException> { DeviceHealthParsers.android(emptyMap()) }
        assertFailsWith<DiagnosticParseException> { DeviceHealthParsers.hardware(emptyMap()) }
    }
}
