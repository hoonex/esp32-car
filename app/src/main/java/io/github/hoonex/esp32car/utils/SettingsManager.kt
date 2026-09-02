package io.github.hoonex.esp32car.utils

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("rc_settings", Context.MODE_PRIVATE)

    var preferredMode: String
        get() = prefs.getString("preferred_mode", "WIFI") ?: "WIFI"
        set(value) = prefs.edit().putString("preferred_mode", value).apply()

    var ipAddress: String
        get() = prefs.getString("ip_address", "192.168.4.1") ?: "192.168.4.1"
        set(value) = prefs.edit().putString("ip_address", value).apply()

    var speed: Float
        get() = prefs.getFloat("speed", 150f)
        set(value) = prefs.edit().putFloat("speed", value).apply()

    var light: Float
        get() = prefs.getFloat("light", 128f)
        set(value) = prefs.edit().putFloat("light", value).apply()

    var trim: Float
        get() = prefs.getFloat("trim", 0f)
        set(value) = prefs.edit().putFloat("trim", value).apply()

    var streamResolution: String
        get() = prefs.getString("stream_res", "QVGA") ?: "QVGA"
        set(value) = prefs.edit().putString("stream_res", value).apply()

    var streamQuality: Float
        get() = prefs.getFloat("stream_quality", 10f)
        set(value) = prefs.edit().putFloat("stream_quality", value).apply()

    var captureResolution: String
        get() = prefs.getString("cap_res", "UXGA") ?: "UXGA"
        set(value) = prefs.edit().putString("cap_res", value).apply()

    var captureQuality: Float
        get() = prefs.getFloat("cap_quality", 8f)
        set(value) = prefs.edit().putFloat("cap_quality", value).apply()
}
