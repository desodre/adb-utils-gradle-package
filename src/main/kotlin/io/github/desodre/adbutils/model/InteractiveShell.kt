package io.github.desodre.adbutils.model

/** Resource and backpressure limits for an interactive Shell v2 session. */
public data class InteractiveShellOptions(
    public val maxFrameBytes: Int = 64 * 1024,
    public val outputBufferCapacity: Int = 16,
) {
    init {
        require(maxFrameBytes > 0) { "maxFrameBytes must be positive" }
        require(outputBufferCapacity > 0) { "outputBufferCapacity must be positive" }
    }
}

public enum class ShellOutputStream { STDOUT, STDERR }

/** One binary-safe Shell v2 output frame. The byte array is owned by this value. */
public data class ShellOutputChunk(
    public val stream: ShellOutputStream,
    public val data: ByteArray,
)

/** Terminal state of an interactive shell. */
public sealed interface ShellTermination {
    public data class Exited(public val exitCode: Int) : ShellTermination {
        init { require(exitCode in 0..255) }
    }

    public data object Cancelled : ShellTermination

    public data class Failed(public val cause: Throwable) : ShellTermination
}
