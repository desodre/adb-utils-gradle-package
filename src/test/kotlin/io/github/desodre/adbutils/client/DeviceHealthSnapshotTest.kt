package io.github.desodre.adbutils.client

import io.github.desodre.adbutils.model.DeviceHealthOptions
import io.github.desodre.adbutils.model.DeviceSerial
import io.github.desodre.adbutils.model.DiagnosticFailureKind
import io.github.desodre.adbutils.model.HealthSection
import io.github.desodre.adbutils.transport.AdbTransport
import java.util.Collections
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceHealthSnapshotTest {
    @Test fun `collects typed sections with one bounded command per source`() = runBlocking<Unit> {
        val commands = Collections.synchronizedList(mutableListOf<String>())
        val client = AdbClient(transportFactory = { HealthTransport(commands) })
        val device = AdbDevice(client, DeviceSerial("serial"))

        val snapshot = device.healthSnapshot()

        assertEquals(80, assertIs<HealthSection.Available<*>>(snapshot.battery).value.let {
            (it as io.github.desodre.adbutils.model.BatteryHealth).levelPercent
        })
        assertEquals(36, assertIs<HealthSection.Available<*>>(snapshot.android).value.let {
            (it as io.github.desodre.adbutils.model.AndroidVersionInfo).sdk
        })
        val hardware = assertIs<HealthSection.Available<*>>(snapshot.hardware).value as io.github.desodre.adbutils.model.HardwareInfo
        assertEquals("Pixel 9", hardware.model)
        assertNull(hardware.serialNumber)
        assertEquals(5, commands.size)
        assertTrue(commands.none { "ro.serialno" in it || "ro.boot.serialno" in it })
    }

    @Test fun `section timeout does not discard successful sections`() = runBlocking<Unit> {
        val commands = Collections.synchronizedList(mutableListOf<String>())
        val client = AdbClient(transportFactory = { HealthTransport(commands, hangBattery = true) })
        val device = AdbDevice(client, DeviceSerial("serial"))

        val snapshot = device.healthSnapshot(DeviceHealthOptions(sectionTimeoutMillis = 100))

        val failure = assertIs<HealthSection.Unavailable>(snapshot.battery).failure
        assertEquals(DiagnosticFailureKind.TIMEOUT, failure.kind)
        assertIs<HealthSection.Available<*>>(snapshot.storage)
        assertIs<HealthSection.Available<*>>(snapshot.memory)
        assertIs<HealthSection.Available<*>>(snapshot.uptime)
        assertIs<HealthSection.Available<*>>(snapshot.android)
        assertIs<HealthSection.Available<*>>(snapshot.hardware)
    }

    @Test fun `identifiers are collected only when explicitly enabled`() = runBlocking<Unit> {
        val commands = Collections.synchronizedList(mutableListOf<String>())
        val client = AdbClient(transportFactory = { HealthTransport(commands) })
        val device = AdbDevice(client, DeviceSerial("serial"))

        val snapshot = device.healthSnapshot(DeviceHealthOptions(includeIdentifiers = true))

        val hardware = assertIs<HealthSection.Available<*>>(snapshot.hardware).value as io.github.desodre.adbutils.model.HardwareInfo
        assertEquals("hardware-secret", hardware.serialNumber)
        assertTrue(commands.any { "ro.serialno" in it && "ro.boot.serialno" in it })
    }

    private class HealthTransport(
        private val commands: MutableList<String>,
        private val hangBattery: Boolean = false,
    ) : AdbTransport {
        private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        private var pending = byteArrayOf()
        private var requestCount = 0

        override suspend fun connect() = Unit

        override suspend fun write(data: ByteArray) {
            val service = data.copyOfRange(4, data.size).decodeToString()
            requestCount += 1
            incoming.send("OKAY".encodeToByteArray())
            if (requestCount == 2) {
                val command = service.removePrefix("shell,v2,raw:")
                commands += command
                if (hangBattery && command == "dumpsys battery") return
                val output = outputFor(command)
                incoming.send(frame(1, output.encodeToByteArray()))
                incoming.send(frame(3, byteArrayOf(0)))
            }
        }

        override suspend fun read(maxBytes: Int): ByteArray {
            while (pending.isEmpty()) pending = incoming.receiveCatching().getOrNull() ?: return byteArrayOf()
            val count = minOf(maxBytes, pending.size)
            return pending.copyOfRange(0, count).also { pending = pending.copyOfRange(count, pending.size) }
        }

        override suspend fun close() { incoming.close() }

        private fun outputFor(command: String): String = when {
            command == "dumpsys battery" -> "level: 80\nscale: 100\nstatus: 3\nhealth: 2\npresent: true"
            command == "df -k /data" -> "Filesystem 1K-blocks Used Available Use% Mounted on\n/dev/block/dm-8 1000 250 750 25% /data"
            command == "cat /proc/meminfo" -> "MemTotal: 1000 kB\nMemAvailable: 400 kB\nSwapTotal: 0 kB\nSwapFree: 0 kB"
            command == "cat /proc/uptime" -> "123.45 678.90"
            "ro.build.version.release" in command -> buildString {
                append("release=16\nsdk=36\nsecurity_patch=2026-09-01\nbuild_id=BP2A\n")
                append("manufacturer=Google\nmodel=Pixel 9\ndevice=tokay\nproduct=tokay\nabi=arm64-v8a\n")
                if ("ro.serialno" in command) append("serial=hardware-secret\nboot_serial=hardware-secret")
            }
            else -> error("Unexpected command: $command")
        }

        private fun frame(id: Int, payload: ByteArray): ByteArray = byteArrayOf(
            id.toByte(),
            payload.size.toByte(),
            (payload.size ushr 8).toByte(),
            (payload.size ushr 16).toByte(),
            (payload.size ushr 24).toByte(),
        ) + payload
    }
}
