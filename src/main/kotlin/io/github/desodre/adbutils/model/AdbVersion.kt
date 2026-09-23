package io.github.desodre.adbutils.model

/** Internal ADB server protocol version, not the Platform Tools release number. */
@JvmInline
public value class AdbVersion(public val value: Int) {
    init { require(value in 0..0xffff) }
    override fun toString(): String = value.toString()
}
