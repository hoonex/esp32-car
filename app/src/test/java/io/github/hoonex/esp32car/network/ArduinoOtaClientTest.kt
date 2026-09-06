package io.github.hoonex.esp32car.network

import org.junit.Assert.assertEquals
import org.junit.Test

class ArduinoOtaClientTest {
    @Test
    fun legacyMd5HandshakeMatchesEspota() {
        val response = ArduinoOtaClient.legacyAuthResponse(
            password = "ABCDEF1234567890",
            nonce = "0123456789abcdef0123456789abcdef",
            cnonce = "fedcba9876543210fedcba9876543210"
        )

        assertEquals("93f06dea7874491efd4af95ce60a71d5", response)
    }

    @Test
    fun sha256Pbkdf2HandshakeMatchesArduinoEsp32() {
        val response = ArduinoOtaClient.sha256AuthResponse(
            password = "ABCDEF1234567890",
            nonce = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            cnonce = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
        )

        assertEquals(
            "9878e125445db9ce4a0155ede6b1d8c749ea32cd869b6d70a8440235bcee6f01",
            response
        )
    }
}
