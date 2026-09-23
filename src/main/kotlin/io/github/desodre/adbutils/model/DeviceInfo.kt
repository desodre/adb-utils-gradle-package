package io.github.desodre.adbutils.model

public data class DeviceInfo(
    public val serial: DeviceSerial,
    public val state: DeviceState,
    public val product: String? = null,
    public val model: String? = null,
    public val device: String? = null,
    public val transportId: Long? = null,
) {
    init { require(transportId == null || transportId > 0) }
}
