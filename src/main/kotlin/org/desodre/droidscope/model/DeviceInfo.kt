package org.desodre.droidscope.model

data class DeviceInfo(
    val serial: DeviceSerial,
    val state: DeviceState,
    val product: String? = null,
    val model: String? = null,
    val device: String? = null,
    val transportId: Long? = null,
) {
    init { require(transportId == null || transportId > 0) }
}
