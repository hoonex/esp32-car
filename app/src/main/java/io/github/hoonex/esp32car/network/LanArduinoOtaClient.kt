package io.github.hoonex.esp32car.network

import java.io.IOException

/**
 * Direct-LAN espota entry point for the v3.3.1 -> v4 migration fallback.
 *
 * Android routing is only one possible reason for a missing reverse TCP callback.
 * ArduinoOTA authenticates first, then the ESP32 calls Update.begin(), and only after
 * that succeeds does it connect back to the uploader. Field testing showed the same
 * v3.3.1 device also returning HTTP `update_begin_failed`, so a successful UDP/auth
 * handshake followed by no callback can be a flash-layout/OTA-slot failure rather
 * than a phone-network failure. Keep the Network-bound listener, but report that
 * distinction instead of telling the user to retry the same transport indefinitely.
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

        val result = ArduinoOtaClient.upload(
            network = route.network,
            localAddress = route.localAddress,
            remoteHost = host,
            firmware = firmware,
            password = password,
            onProgress = onProgress
        )
        result.exceptionOrNull()?.let { throw diagnoseFailure(it, firmware.size) }
    }

    internal fun diagnoseFailure(error: Throwable, firmwareBytes: Int): IOException {
        val detail = error.message.orEmpty()
        val authenticatedWithoutCallback =
            detail.contains("UDP/auth succeeded", ignoreCase = true) ||
                detail.contains("timed out after UDP/auth OK", ignoreCase = true)

        if (authenticatedWithoutCallback) {
            return IOException(
                "ArduinoOTA 인증은 성공했지만 ESP32가 업로드 TCP 연결을 열지 않았습니다. " +
                    "ESP32 ArduinoOTA는 이 연결 전에 내부 Update.begin()을 실행합니다. " +
                    "HTTP OTA도 update_begin_failed였다면 현재 플래시 파티션에 사용 가능한 OTA 슬롯이 없거나 " +
                    "${firmwareBytes} byte 이미지보다 작은 레이아웃일 가능성이 큽니다. " +
                    "앱 재시도로는 파티션 테이블을 바꿀 수 없습니다. 원본 오류: $detail",
                error
            )
        }

        return IOException(detail.ifBlank { error.javaClass.simpleName }, error)
    }
}
