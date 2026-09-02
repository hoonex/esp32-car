package io.github.hoonex.esp32car

import io.github.hoonex.esp32car.model.DriveDirection
import io.github.hoonex.esp32car.protocol.RcProtocol
import org.junit.Assert.assertEquals
import org.junit.Test

class RcProtocolTest {
    @Test
    fun bluetoothDirectionCommandsAreStable() {
        assertEquals("F", RcProtocol.bluetoothDrive(DriveDirection.FORWARD))
        assertEquals("B", RcProtocol.bluetoothDrive(DriveDirection.BACKWARD))
        assertEquals("L", RcProtocol.bluetoothDrive(DriveDirection.LEFT))
        assertEquals("R", RcProtocol.bluetoothDrive(DriveDirection.RIGHT))
        assertEquals("S", RcProtocol.bluetoothDrive(DriveDirection.STOP))
    }

    @Test
    fun tuningCommandsAreClamped() {
        assertEquals("V50", RcProtocol.speed(0))
        assertEquals("V255", RcProtocol.speed(999))
        assertEquals("T-50", RcProtocol.trim(-999))
        assertEquals("T50", RcProtocol.trim(999))
        assertEquals("H0", RcProtocol.light(-1))
        assertEquals("H255", RcProtocol.light(999))
    }

    @Test
    fun wifiProvisioningMatchesFirmwareProtocol() {
        assertEquals("W:MyWifi,secret", RcProtocol.provisionWifi(" MyWifi ", "secret"))
    }
}
