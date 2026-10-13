package io.github.desodre.adbutils.model

@JvmInline
public value class TcpPort(public val value: Int) : AdbEndpoint {
    init { require(value in 1..65535) { "TCP port must be in 1..65535" } }
    override fun toString(): String = "tcp:$value"
}

public data class PortForward(public val serial: DeviceSerial, public val local: TcpPort, public val remote: TcpPort)
public data class ReverseForward(public val remote: TcpPort, public val local: TcpPort)

/** A typed ADB endpoint. Allowed roles are checked before connecting to the server. */
public sealed interface AdbEndpoint

/** A debuggable Android process; valid only as the remote destination of forward. */
public data class JdwpPid(public val value: Int) : AdbEndpoint {
    init { require(value > 0) { "JDWP PID must be positive" } }
    override fun toString(): String = "jdwp:$value"
}

public data class LocalAbstract(public val name: String) : AdbEndpoint {
    init { validateEndpointPart(name) }
    override fun toString(): String = "localabstract:$name"
}

/** Android's reserved socket namespace (normally /dev/socket); permissions still apply. */
public data class LocalReserved(public val name: String) : AdbEndpoint {
    init {
        validateEndpointPart(name)
        require('/' !in name && name != "." && name != "..") { "Reserved socket must be a name, not a path" }
    }
    override fun toString(): String = "localreserved:$name"
}

public data class LocalFilesystem(public val path: String) : AdbEndpoint {
    init { validateEndpointPath(path) }
    override fun toString(): String = "localfilesystem:$path"
}

/** Device-side file destination of forward; does not grant additional permissions. */
public data class DeviceFile(public val path: String) : AdbEndpoint {
    init { validateEndpointPath(path) }
    override fun toString(): String = "dev:$path"
}

/** Like [DeviceFile], with raw terminal mode; remote forward only. */
public data class RawDeviceFile(public val path: String) : AdbEndpoint {
    init { validateEndpointPath(path) }
    override fun toString(): String = "dev-raw:$path"
}

/** An unrecognized listing entry, retained for inspection but never accepted by commands. */
@ConsistentCopyVisibility
public data class UnknownEndpoint internal constructor(public val value: String) : AdbEndpoint {
    override fun toString(): String = value
}

public data class ForwardMapping(public val serial: DeviceSerial, public val local: AdbEndpoint, public val remote: AdbEndpoint)
public data class ReverseMapping(public val remote: AdbEndpoint, public val local: AdbEndpoint)

private fun validateEndpointPart(value: String) {
    require(value.isNotEmpty() && value.none { it == ';' || it.isWhitespace() || it.isISOControl() } &&
        Charsets.UTF_8.newEncoder().canEncode(value)) {
        "Endpoint must be valid UTF-8, nonempty and contain no delimiters, whitespace or control characters"
    }
}

private fun validateEndpointPath(value: String) {
    validateEndpointPart(value)
    require(value.startsWith('/') && value.length > 1) { "Endpoint path must be absolute and not the root directory" }
}
