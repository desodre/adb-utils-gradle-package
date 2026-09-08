package io.github.desodre.adbutils.protocol

import io.github.desodre.adbutils.error.AdbProtocolException
import io.github.desodre.adbutils.model.*

internal object DeviceListParser {
    fun parse(payload: String): List<DeviceInfo> = payload.lineSequence()
        .filter { it.isNotBlank() }
        .map { line ->
            val tokens = line.trim().split(Regex("\\s+"))
            if (tokens.size < 2) throw AdbProtocolException("Device entry is missing its state")
            val state = if (tokens.drop(1).take(2) == listOf("no", "permissions")) "no permissions" else tokens[1]
            val attributes = tokens.drop(2).filter { ':' in it }.associate {
                it.substringBefore(':') to it.substringAfter(':')
            }
            val transportId = attributes["transport_id"]?.let {
                it.toLongOrNull()?.takeIf { id -> id > 0 }
                    ?: throw AdbProtocolException("Invalid transport_id: $it")
            }
            val serial = try { DeviceSerial(tokens[0]) } catch (error: IllegalArgumentException) {
                throw AdbProtocolException("Invalid device serial", error)
            }
            DeviceInfo(serial, DeviceState.fromWire(state), attributes["product"], attributes["model"], attributes["device"], transportId)
        }.toList()
}
