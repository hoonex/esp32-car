package io.github.hoonex.esp32car.network

import java.io.IOException

/**
 * Direct-LAN espota entry point for the v3.3.1 -> v4 migration fallback.
 *
 * The previous implementation authenticated over UDP and then opened a plain
 * ServerSocket. On Android with multiple networks that listener was not guaranteed
 * to belong to the Wi-Fi network, so the ESP32 authenticated successfully but its
 * reverse TCP callback never reached the app. Resolve the exact Wi-Fi Network/local
 * IPv4 pair and delegate to ArduinoOtaClient, whose raw listener is explicitly bound
 * with Network.bindSocket(fd) before bind/listen.
 */
object LanArduinoOtaClient {
    fun upload(
        remoteHost: String,
        firmware: ByteArray,
        password: String,
        onProgress: (sent: Long, total: Long) -> Unit
    ): Result<Unit> = runCatching {
        require(remoteHost.isNotBlank()) { "ESP32 host is blank" }
        require(firmware.isNotEmpty()) { "Bundled firmware is empty" }
        require(password.isNotBlank()) { "OTA password is missing" }

        val host = remoteHost.substringBefore(':')
        val route = AndroidNetworkRoute.findWifiRoute(host)
            ?: throw IOException("Android could not resolve the Wi-Fi Network that reaches $host")

        ArduinoOtaClient.upload(
            network = route.network,
            localAddress = route.localAddress,
            remoteHost = host,
            firmware = firmware,
            password = password,
            onProgress = onProgress
        ).getOrThrow()
    }
}
