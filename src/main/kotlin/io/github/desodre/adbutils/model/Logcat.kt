package io.github.desodre.adbutils.model

import java.time.Instant

/** Android log buffers accepted by logcat. An empty selection uses the platform defaults. */
public enum class LogcatBuffer {
    MAIN,
    SYSTEM,
    RADIO,
    EVENTS,
    CRASH,
    DEFAULT,
    ALL,
}

/** Output formats with stable parsing semantics exposed by this SDK. */
public enum class LogcatFormat {
    /** Structured epoch timestamp, PID, TID, priority, tag and message when Android emits them. */
    EPOCH,

    /** Message lines without metadata. */
    RAW,
}

public enum class LogcatPriority {
    VERBOSE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
    FATAL,
    SILENT,
}

/** A validated Android logcat filter specification such as `ActivityManager:I`. */
public data class LogcatFilter(
    public val tag: String,
    public val minimumPriority: LogcatPriority = LogcatPriority.VERBOSE,
) {
    init {
        require(tag == "*" || tag.matches(Regex("[A-Za-z0-9_.-]+"))) {
            "Logcat filter tags may contain only letters, digits, underscore, dot and hyphen"
        }
    }
}

public data class LogcatOptions(
    public val buffers: Set<LogcatBuffer> = emptySet(),
    public val format: LogcatFormat = LogcatFormat.EPOCH,
    public val filters: List<LogcatFilter> = emptyList(),
    public val clearBeforeStart: Boolean = false,
    public val maxLineBytes: Int = 64 * 1024,
    public val maxFrameBytes: Int = 1024 * 1024,
) {
    init {
        require(maxLineBytes > 0) { "maxLineBytes must be positive" }
        require(maxFrameBytes > 0) { "maxFrameBytes must be positive" }
    }
}

/**
 * One logcat line. Metadata is populated for structured lines in [LogcatFormat.EPOCH]; platform
 * banners, malformed lines and [LogcatFormat.RAW] remain observable through [rawLine]/[message].
 */
public data class LogcatEntry(
    public val rawLine: String,
    public val message: String,
    public val timestamp: Instant? = null,
    public val processId: Int? = null,
    public val threadId: Int? = null,
    public val priority: LogcatPriority? = null,
    public val tag: String? = null,
)
