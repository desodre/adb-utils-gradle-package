package org.desodre.droidscope.client

import org.desodre.droidscope.error.*
import org.desodre.droidscope.model.DeviceSerial

/** A serial-bound handle, not a persistent connection or a guarantee that the device remains online. */
class AdbDevice internal constructor(private val client: AdbClient, val serial: DeviceSerial) {
    /**
     * Executes a non-interactive legacy shell command as supplied, including shell metacharacters.
     * Returns UTF-8 output up to EOF; legacy shell provides no exit code or separate stderr.
     * Do not interpolate untrusted input. The client's timeout applies to the entire session.
     */
    suspend fun shell(command: String, maxOutputBytes: Int = 16 * 1024 * 1024): String {
        require(command.isNotBlank() && '\u0000' !in command) { "A non-empty shell command without NUL is required" }
        require(maxOutputBytes > 0)
        return client.session { protocol ->
            try {
                protocol.request("host:transport:$serial")
            } catch (error: AdbFailException) {
                val reason = error.reason.lowercase()
                throw when {
                    "unauthorized" in reason -> DeviceUnauthorizedException(serial, error)
                    "offline" in reason -> DeviceOfflineException(serial, error)
                    "not found" in reason -> DeviceNotFoundException(serial, error)
                    else -> error
                }
            }
            protocol.request("shell:$command")
            protocol.readShellOutput(maxOutputBytes)
        }
    }

    /** Reads one property without caching. An absent property returns an empty string. */
    suspend fun getprop(name: String): String {
        require(name.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*"))) { "Invalid Android property name" }
        return shell("getprop '$name'").trimEnd('\r', '\n')
    }
}
