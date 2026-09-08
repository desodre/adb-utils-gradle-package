package io.github.desodre.adbutils.model

@JvmInline
value class DeviceSerial(val value: String) {
    init { require(value.isNotEmpty() && value.none { it.isWhitespace() || it.isISOControl() }) { "Invalid device serial" } }
    override fun toString(): String = value
}
