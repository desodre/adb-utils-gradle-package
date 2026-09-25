package io.github.desodre.adbutils.client

import io.github.desodre.adbutils.model.InteractiveShellOptions
import io.github.desodre.adbutils.model.ShellOutputChunk
import io.github.desodre.adbutils.model.ShellOutputStream
import io.github.desodre.adbutils.model.ShellTermination
import io.github.desodre.adbutils.protocol.ShellV2Protocol
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * An owned bidirectional Shell v2 connection. [output] is binary-safe and supports one collector;
 * its bounded buffer applies backpressure. Call [closeStdin] for graceful completion or [cancel]
 * to force transport closure.
 */
public class InteractiveShellSession internal constructor(
    private val connection: AdbConnection,
    private val options: InteractiveShellOptions,
) {
    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName("adb-interactive-shell"),
    )
    private val outputChannel = Channel<ShellOutputChunk>(options.outputBufferCapacity)
    private val completion = CompletableDeferred<ShellTermination>()
    private val writeMutex = Mutex()
    private val cancelRequested = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    private var stdinClosed: Boolean = false
    private val reader: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) { readOutput() }

    public val output: Flow<ShellOutputChunk> = outputChannel.receiveAsFlow()

    /** Sends binary stdin, splitting it into protocol-safe frames. Empty input is a no-op. */
    public suspend fun writeStdin(data: ByteArray) {
        if (data.isEmpty()) return
        withWriteLock {
            check(!stdinClosed) { "Shell stdin is closed" }
            check(!finished.get()) { "Shell session has terminated" }
            ShellV2Protocol.writeStdin(connection.protocol, data, options.maxFrameBytes)
        }
    }

    /** Half-closes subprocess stdin. Repeated calls are safe. */
    public suspend fun closeStdin() {
        withWriteLock {
            if (stdinClosed) return@withWriteLock
            check(!finished.get()) { "Shell session has terminated" }
            ShellV2Protocol.writeCloseStdin(connection.protocol)
            stdinClosed = true
        }
    }

    /** Waits for remote exit, forced cancellation or transport/protocol failure. */
    public suspend fun awaitTermination(): ShellTermination = completion.await()

    /** Forces cancellation and waits until the underlying ADB connection has closed. */
    public suspend fun cancel() {
        if (completion.isCompleted) return
        cancelRequested.set(true)
        reader.cancel(CancellationException("Interactive shell cancelled"))
        finish(ShellTermination.Cancelled)
        reader.join()
    }

    private suspend fun readOutput() {
        try {
            while (true) {
                when (val frame = ShellV2Protocol.readFrame(connection.protocol, options.maxFrameBytes)) {
                    is ShellV2Protocol.Frame.Stdout -> outputChannel.send(
                        ShellOutputChunk(ShellOutputStream.STDOUT, frame.data),
                    )
                    is ShellV2Protocol.Frame.Stderr -> outputChannel.send(
                        ShellOutputChunk(ShellOutputStream.STDERR, frame.data),
                    )
                    is ShellV2Protocol.Frame.Exit -> {
                        finish(ShellTermination.Exited(frame.exitCode))
                        return
                    }
                }
            }
        } catch (error: CancellationException) {
            if (cancelRequested.get()) finish(ShellTermination.Cancelled)
            else finish(ShellTermination.Failed(error), error)
        } catch (error: Throwable) {
            finish(ShellTermination.Failed(error), error)
        }
    }

    private suspend fun finish(requested: ShellTermination, requestedStreamFailure: Throwable? = null) {
        if (!finished.compareAndSet(false, true)) return
        withContext(NonCancellable) {
            var termination = requested
            var streamFailure = requestedStreamFailure
            val closeFailure = try {
                connection.close()
                null
            } catch (error: Throwable) {
                error
            }
            if (closeFailure != null) {
                val previous = (termination as? ShellTermination.Failed)?.cause
                if (previous != null) previous.addSuppressed(closeFailure) else {
                    termination = ShellTermination.Failed(closeFailure)
                    streamFailure = closeFailure
                }
            }
            outputChannel.close(streamFailure)
            completion.complete(termination)
            scope.cancel()
        }
    }

    private suspend fun <T> withWriteLock(block: suspend () -> T): T {
        writeMutex.lock()
        return try {
            block()
        } finally {
            writeMutex.unlock()
        }
    }
}
