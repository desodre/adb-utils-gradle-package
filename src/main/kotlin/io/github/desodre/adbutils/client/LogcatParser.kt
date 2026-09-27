package io.github.desodre.adbutils.client

import io.github.desodre.adbutils.error.LogcatLineLimitException
import io.github.desodre.adbutils.model.LogcatEntry
import io.github.desodre.adbutils.model.LogcatFormat
import io.github.desodre.adbutils.model.LogcatPriority
import io.github.desodre.adbutils.protocol.AdbCodec
import java.io.ByteArrayOutputStream
import java.time.Instant

internal class LogcatParser(
    private val format: LogcatFormat,
    private val maxLineBytes: Int,
) {
    private val pending = ByteArrayOutputStream(minOf(maxLineBytes, 8 * 1024))

    fun accept(chunk: ByteArray): List<LogcatEntry> = buildList {
        chunk.forEach { byte ->
            if (byte == '\n'.code.toByte()) {
                add(parsePending())
                pending.reset()
            } else {
                if (pending.size() >= maxLineBytes) throw LogcatLineLimitException(maxLineBytes)
                pending.write(byte.toInt())
            }
        }
    }

    fun finish(): List<LogcatEntry> = if (pending.size() == 0) emptyList() else {
        listOf(parsePending()).also { pending.reset() }
    }

    private fun parsePending(): LogcatEntry {
        val bytes = pending.toByteArray().let { value ->
            if (value.lastOrNull() == '\r'.code.toByte()) value.copyOf(value.size - 1) else value
        }
        val line = AdbCodec.decodeText(bytes)
        if (format == LogcatFormat.RAW) return LogcatEntry(rawLine = line, message = line)
        val match = EPOCH_LINE.matchEntire(line)
            ?: return LogcatEntry(rawLine = line, message = line)
        val seconds = match.groupValues[1].toLongOrNull()
        val nanos = match.groupValues[2].padEnd(9, '0').take(9).toIntOrNull()
        val timestamp = if (seconds != null && nanos != null) runCatching {
            Instant.ofEpochSecond(seconds, nanos.toLong())
        }.getOrNull() else null
        return LogcatEntry(
            rawLine = line,
            message = match.groupValues[7],
            timestamp = timestamp,
            processId = match.groupValues[3].toIntOrNull(),
            threadId = match.groupValues[4].toIntOrNull(),
            priority = priority(match.groupValues[5].single()),
            tag = match.groupValues[6].trim(),
        )
    }

    private fun priority(code: Char): LogcatPriority? = when (code) {
        'V' -> LogcatPriority.VERBOSE
        'D' -> LogcatPriority.DEBUG
        'I' -> LogcatPriority.INFO
        'W' -> LogcatPriority.WARN
        'E' -> LogcatPriority.ERROR
        'F' -> LogcatPriority.FATAL
        'S' -> LogcatPriority.SILENT
        else -> null
    }

    private companion object {
        val EPOCH_LINE: Regex = Regex(
            "^\\s*(\\d+)(?:\\.(\\d{1,9}))?\\s+(\\d+)\\s+(\\d+)\\s+([VDIWEFS])\\s+([^:]+):\\s?(.*)$",
        )
    }
}
