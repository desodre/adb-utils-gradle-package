package io.github.desodre.adbutils.transport

import java.net.ServerSocket
import kotlinx.coroutines.*
import kotlin.test.*
import io.github.desodre.adbutils.error.*
import io.github.desodre.adbutils.transport.jvm.JvmAdbTransport

class JvmAdbTransportTest {
    @Test fun `TCP writes reads and EOF`() = runBlocking<Unit> {
        ServerSocket(0).use { server ->
            val peer = async(Dispatchers.IO) {
                server.accept().use { socket ->
                    assertContentEquals("ping".encodeToByteArray(), socket.getInputStream().readNBytes(4))
                    socket.getOutputStream().write("pong".encodeToByteArray())
                }
            }
            val transport = JvmAdbTransport(port = server.localPort)
            try {
                transport.connect()
                transport.write("ping".encodeToByteArray())
                val output = mutableListOf<Byte>()
                while (true) {
                    val chunk = transport.read(2)
                    if (chunk.isEmpty()) break
                    output.addAll(chunk.toList())
                }
                assertEquals("pong", output.toByteArray().decodeToString())
            } finally { transport.close() }
            transport.close()
            assertFailsWith<AdbConnectionException> { transport.read(1) }
            peer.await()
        }
    }

    @Test fun `cancellation closes blocked socket promptly`() = runBlocking<Unit> {
        ServerSocket(0).use { server ->
            val transport = JvmAdbTransport(port = server.localPort, timeoutMillis = 30_000)
            try {
                transport.connect()
                server.accept().use { peer ->
                    val read = async { transport.read(1) }
                    yield()
                    withTimeout(2_000) { read.cancelAndJoin() }
                    peer.soTimeout = 2_000
                    assertEquals(-1, peer.getInputStream().read())
                }
            } finally { transport.close() }
        }
    }
}
