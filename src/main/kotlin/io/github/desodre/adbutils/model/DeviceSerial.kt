package io.github.desodre.adbutils.model

@JvmInline
public value class DeviceSerial(public val value: String) {
    init { require(value.isNotEmpty() && value.none { it.isWhitespace() || it.isISOControl() }) { "Invalid device serial" } }
    override fun toString(): String = value
}
