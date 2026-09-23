package example

import io.github.desodre.adbutils.client.AdbClient
import io.github.desodre.adbutils.model.DeviceInfo
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>): Unit = runBlocking {
    val host = args.getOrNull(0) ?: "127.0.0.1"
    val port = args.getOrNull(1)?.toIntOrNull() ?: 5037
    val devices = AdbClient(host, port).devices()
    if (devices.isEmpty()) {
        println("No devices reported by $host:$port")
    } else {
        devices.forEach { println(formatDevice(it)) }
    }
}

internal fun formatDevice(device: DeviceInfo): String = buildString {
    append(device.serial.value)
    append("  ")
    append(device.state.name.lowercase())
    device.model?.let { append("  model=").append(it) }
    device.transportId?.let { append("  transportId=").append(it) }
}
