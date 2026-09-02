package io.github.hoonex.esp32car.protocol

import io.github.hoonex.esp32car.model.DriveDirection

object RcProtocol {
    const val DEVICE_NAME = "ESP32_CAM_RC"
    const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"

    const val MIN_SPEED = 50
    const val MAX_SPEED = 255
    const val MIN_TRIM = -50
    const val MAX_TRIM = 50
    const val MIN_LIGHT = 0
    const val MAX_LIGHT = 255

    fun bluetoothDrive(direction: DriveDirection): String = when (direction) {
        DriveDirection.FORWARD -> "F"
        DriveDirection.BACKWARD -> "B"
        DriveDirection.LEFT -> "L"
        DriveDirection.RIGHT -> "R"
        DriveDirection.STOP -> "S"
    }

    fun wifiDrive(direction: DriveDirection): String = when (direction) {
        DriveDirection.FORWARD -> "forward"
        DriveDirection.BACKWARD -> "backward"
        DriveDirection.LEFT -> "left"
        DriveDirection.RIGHT -> "right"
        DriveDirection.STOP -> "stop"
    }

    fun speed(value: Number): String = "V${value.toInt().coerceIn(MIN_SPEED, MAX_SPEED)}"
    fun trim(value: Number): String = "T${value.toInt().coerceIn(MIN_TRIM, MAX_TRIM)}"
    fun light(value: Number): String = "H${value.toInt().coerceIn(MIN_LIGHT, MAX_LIGHT)}"
    fun provisionWifi(ssid: String, password: String): String = "W:${ssid.trim()},$password"

    const val STATUS = "STATUS"
    const val SWITCH_TO_WIFI = "X"
}
