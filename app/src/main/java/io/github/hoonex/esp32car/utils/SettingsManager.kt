package io.github.hoonex.esp32car.utils

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("rc_settings", Context.MODE_PRIVATE)

    var preferredMode: String
        get() = prefs.getString("preferred_mode", "BT") ?: "BT"
        set(value) = prefs.edit().putString("preferred_mode", value).apply()

    var ipAddress: String
        get() = prefs.getString("ip_address", "192.168.4.1") ?: "192.168.4.1"
        set(value) = prefs.edit().putString("ip_address", value).apply()

    var otaKey: String
        get() = prefs.getString("ota_key", "") ?: ""
        set(value) = prefs.edit().putString("ota_key", value).apply()

    var lastFirmwareVersion: String
        get() = prefs.getString("last_firmware_version", "") ?: ""
        set(value) = prefs.edit().putString("last_firmware_version", value).apply()

    var speed: Float
        get() = prefs.getFloat("speed", 190f)
        set(value) = prefs.edit().putFloat("speed", value).apply()

    var light: Float
        get() = prefs.getFloat("light", 0f)
        set(value) = prefs.edit().putFloat("light", value).apply()

    var trim: Float
        get() = prefs.getFloat("trim", 0f)
        set(value) = prefs.edit().putFloat("trim", value).apply()

    var controlDeadzone: Float
        get() = prefs.getFloat("control_deadzone", 0.12f)
        set(value) = prefs.edit().putFloat("control_deadzone", value.coerceIn(0.02f, 0.35f)).apply()

    var steeringGain: Float
        get() = prefs.getFloat("steering_gain", 1.0f)
        set(value) = prefs.edit().putFloat("steering_gain", value.coerceIn(0.5f, 1.8f)).apply()

    var steeringExpo: Float
        get() = prefs.getFloat("steering_expo", 1.35f)
        set(value) = prefs.edit().putFloat("steering_expo", value.coerceIn(1f, 2.5f)).apply()

    var invertThrottle: Boolean
        get() = prefs.getBoolean("invert_throttle", false)
        set(value) = prefs.edit().putBoolean("invert_throttle", value).apply()

    var invertSteering: Boolean
        get() = prefs.getBoolean("invert_steering", false)
        set(value) = prefs.edit().putBoolean("invert_steering", value).apply()

    var haptics: Boolean
        get() = prefs.getBoolean("haptics", true)
        set(value) = prefs.edit().putBoolean("haptics", value).apply()

    var showTelemetry: Boolean
        get() = prefs.getBoolean("show_telemetry", true)
        set(value) = prefs.edit().putBoolean("show_telemetry", value).apply()

    var streamResolution: String
        get() = prefs.getString("stream_res", "QVGA") ?: "QVGA"
        set(value) = prefs.edit().putString("stream_res", value).apply()

    var streamQuality: Float
        get() = prefs.getFloat("stream_quality", 10f)
        set(value) = prefs.edit().putFloat("stream_quality", value.coerceIn(4f, 20f)).apply()

    var cameraBrightness: Float
        get() = prefs.getFloat("camera_brightness", 0f)
        set(value) = prefs.edit().putFloat("camera_brightness", value.coerceIn(-2f, 2f)).apply()

    var cameraContrast: Float
        get() = prefs.getFloat("camera_contrast", 0f)
        set(value) = prefs.edit().putFloat("camera_contrast", value.coerceIn(-2f, 2f)).apply()

    var cameraSaturation: Float
        get() = prefs.getFloat("camera_saturation", 0f)
        set(value) = prefs.edit().putFloat("camera_saturation", value.coerceIn(-2f, 2f)).apply()

    var cameraMirror: Boolean
        get() = prefs.getBoolean("camera_mirror", false)
        set(value) = prefs.edit().putBoolean("camera_mirror", value).apply()

    var cameraFlip: Boolean
        get() = prefs.getBoolean("camera_flip", true)
        set(value) = prefs.edit().putBoolean("camera_flip", value).apply()

    var captureResolution: String
        get() = prefs.getString("cap_res", "UXGA") ?: "UXGA"
        set(value) = prefs.edit().putString("cap_res", value).apply()

    var captureQuality: Float
        get() = prefs.getFloat("cap_quality", 8f)
        set(value) = prefs.edit().putFloat("cap_quality", value).apply()

    var swapMotors: Boolean
        get() = prefs.getBoolean("swap_motors", false)
        set(value) = prefs.edit().putBoolean("swap_motors", value).apply()

    var invertLeftMotor: Boolean
        get() = prefs.getBoolean("invert_left_motor", false)
        set(value) = prefs.edit().putBoolean("invert_left_motor", value).apply()

    var invertRightMotor: Boolean
        get() = prefs.getBoolean("invert_right_motor", false)
        set(value) = prefs.edit().putBoolean("invert_right_motor", value).apply()
}