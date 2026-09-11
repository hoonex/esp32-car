package io.github.hoonex.esp32car.network

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class LanArduinoOtaClientTest {
    @Test
    fun authenticatedWithoutCallbackExplainsPreCallbackUpdateBegin() {
        val source = IOException(
            "ArduinoOTA UDP/auth succeeded but ESP32 -> Android TCP callback failed: " +
                "network-fd-fixed listener 172.30.1.51:3233 timed out after UDP/auth OK"
        )

        val diagnosed = LanArduinoOtaClient.diagnoseFailure(source, 1_769_296)

        assertTrue(diagnosed.message.orEmpty().contains("Update.begin()"))
        assertTrue(diagnosed.message.orEmpty().contains("OTA 슬롯"))
        assertTrue(diagnosed.message.orEmpty().contains("1769296 byte"))
        assertTrue(diagnosed.message.orEmpty().contains("파티션 테이블"))
    }

    @Test
    fun unrelatedNetworkFailureIsNotMisclassifiedAsPartitionFailure() {
        val source = IOException("Android could not resolve the Wi-Fi Network")

        val diagnosed = LanArduinoOtaClient.diagnoseFailure(source, 1_769_296)

        assertTrue(diagnosed.message.orEmpty().contains("could not resolve"))
        assertTrue(!diagnosed.message.orEmpty().contains("OTA 슬롯"))
    }
}
