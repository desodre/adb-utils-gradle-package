package io.github.desodre.adbutils.client

import kotlinx.coroutines.*
import kotlin.test.*
import io.github.desodre.adbutils.error.*
import io.github.desodre.adbutils.transport.AdbTransport

class AdbSessionTest {
    private class WaitingTransport : AdbTransport {
        val started = CompletableDeferred<Unit>()
        var closed = false
        override suspend fun connect() = Unit
        override suspend fun write(data: ByteArray) = Unit
        override suspend fun read(maxBytes: Int): ByteArray {
            started.complete(Unit)
            awaitCancellation()
        }
        override suspend fun close() { closed = true }
    }

    @Test fun `operation deadline closes transport`() = runBlocking<Unit> {
        val transport = WaitingTransport()
        assertFailsWith<AdbTimeoutException> {
            AdbClient(timeoutMillis = 100, transportFactory = { transport }).version()
        }
        assertTrue(transport.closed)
    }

    @Test fun `parent cancellation is preserved and closes transport`() = runBlocking<Unit> {
        val transport = WaitingTransport()
        val operation = async { AdbClient(transportFactory = { transport }).version() }
        transport.started.await()
        operation.cancelAndJoin()
        assertFailsWith<CancellationException> { operation.await() }
        assertTrue(transport.closed)
    }

    @Test fun `parent deadline is not converted into ADB timeout`() = runBlocking<Unit> {
        val transport = WaitingTransport()
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(100) { AdbClient(transportFactory = { transport }).version() }
        }
        assertTrue(transport.closed)
    }

    @Test fun `connect failure remains primary when cleanup also fails`() = runBlocking<Unit> {
        val failure = AdbConnectionException("connect failed")
        val cleanup = AdbConnectionException("close failed")
        val transport = object : AdbTransport {
            override suspend fun connect() { throw failure }
            override suspend fun write(data: ByteArray) = error("unreachable")
            override suspend fun read(maxBytes: Int): ByteArray = error("unreachable")
            override suspend fun close() { throw cleanup }
        }
        val error = assertFailsWith<AdbConnectionException> { AdbClient(transportFactory = { transport }).version() }
        assertEquals("connect failed", error.message)
        // Coroutine stack-trace recovery may copy exceptions and retain the original as a cause.
        assertTrue(generateSequence<Throwable>(error) { it.cause }.any { cleanup in it.suppressedExceptions })
    }
}
