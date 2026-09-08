package io.github.hoonex.esp32car

import io.github.hoonex.esp32car.protocol.RcProtocol
import org.junit.Assert.assertEquals
import org.junit.Test

class FreshRebuildContractTest {
    @Test
    fun cleanFirmwareCommandsRemainStable() {
        assertEquals("ESP32_CAM_RC", RcProtocol.DEVICE_NAME)
        assertEquals("M:255,-255", RcProtocol.motorMix(255, -255))
        assertEquals("CAM_RETRY", RcProtocol.CAMERA_RETRY)
        assertEquals("X", RcProtocol.SWITCH_TO_WIFI)
    }
}
