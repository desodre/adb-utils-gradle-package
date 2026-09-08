package org.desodre.droidscope.client

import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.*
import kotlin.test.*
import org.desodre.droidscope.error.*

class AdbTcpIntegrationTest {
    private fun Socket.expectRequest(expected: String) {
        soTimeout = 3_000
        val input = getInputStream()
        val prefix = input.readNBytes(4).decodeToString()
        assertEquals(4, prefix.length)
        assertEquals(expected, input.readNBytes(prefix.toInt(16)).decodeToString())
    }

    private fun Socket.reply(text: String) {
        // Deliberately send byte-sized writes; unit tests additionally force partial reads.
        for (byte in text.encodeToByteArray()) getOutputStream().write(byte.toInt())
        getOutputStream().flush()
    }

    @Test fun `milestone API over actual TCP sockets`() = runBlocking<Unit> {
        ServerSocket(0).use { server ->
            server.soTimeout = 3_000
            val peer = async(Dispatchers.IO) {
                server.accept().use {
                    it.expectRequest("host:version")
                    it.reply("OKAY00040029")
                }
                repeat(2) {
                    server.accept().use {
                        it.expectRequest("host:devices-l")
                        val payload = "SERIAL device product:pixel model:Pixel_7 device:panther transport_id:1\n"
                        it.reply("OKAY" + payload.length.toString(16).padStart(4, '0') + payload)
                    }
                }
                for (command in listOf("getprop ro.product.model", "getprop 'ro.product.model'")) {
                    server.accept().use {
                        it.expectRequest("host:transport:SERIAL")
                        it.reply("OKAY")
                        it.expectRequest("shell:$command")
                        it.reply("OKAYPixel 7\n")
                    }
                }
            }
            val adb = AdbClient(port = server.localPort, timeoutMillis = 3_000)
            assertEquals(41, adb.version().value)
            assertEquals("Pixel_7", adb.devices().single().model)
            val device = adb.device()
            assertEquals("Pixel 7\n", device.shell("getprop ro.product.model"))
            assertEquals("Pixel 7", device.getprop("ro.product.model"))
            peer.await()
        }
    }

    @Test fun `connection refusal reports unavailable server`() = runBlocking<Unit> {
        val port = ServerSocket(0).use { it.localPort }
        assertFailsWith<AdbServerUnavailableException> { AdbClient(port = port).version() }
    }

    @Test fun `TCP deadline closes peer connection`() = runBlocking<Unit> {
        ServerSocket(0).use { server ->
            server.soTimeout = 3_000
            val peer = async(Dispatchers.IO) {
                server.accept().use {
                    it.expectRequest("host:version")
                    assertEquals(-1, it.getInputStream().read())
                }
            }
            assertFailsWith<AdbTimeoutException> { AdbClient(port = server.localPort, timeoutMillis = 300).version() }
            peer.await()
        }
    }
}
