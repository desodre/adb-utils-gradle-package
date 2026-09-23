package io.github.desodre.adbutils.model

@JvmInline
public value class TcpPort(public val value: Int) {
    init { require(value in 1..65535) { "TCP port must be in 1..65535" } }
    override fun toString(): String = "tcp:$value"
}

public data class PortForward(public val serial: DeviceSerial, public val local: TcpPort, public val remote: TcpPort)
public data class ReverseForward(public val remote: TcpPort, public val local: TcpPort)
