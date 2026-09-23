package example

import io.github.desodre.adbutils.model.DeviceInfo
import io.github.desodre.adbutils.model.DeviceSerial
import io.github.desodre.adbutils.model.DeviceState
import kotlin.test.Test
import kotlin.test.assertEquals

class MainTest {
    @Test
    fun `formats public device model from Maven artifact`(): Unit {
        val device = DeviceInfo(DeviceSerial("emulator-5554"), DeviceState.DEVICE, model = "Pixel_9", transportId = 1)
        assertEquals("emulator-5554  device  model=Pixel_9  transportId=1", formatDevice(device))
    }
}
