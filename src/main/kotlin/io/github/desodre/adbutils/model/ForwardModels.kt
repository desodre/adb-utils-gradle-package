package io.github.desodre.adbutils.model

@JvmInline
value class TcpPort(val value: Int) {
    init { require(value in 1..65535) { "TCP port must be in 1..65535" } }
    override fun toString() = "tcp:$value"
}

data class PortForward(val serial: DeviceSerial, val local: TcpPort, val remote: TcpPort)
data class ReverseForward(val remote: TcpPort, val local: TcpPort)
