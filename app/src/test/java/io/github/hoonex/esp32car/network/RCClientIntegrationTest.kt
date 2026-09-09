package io.github.hoonex.esp32car.network

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RCClientIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var client: RCClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = RCClient().apply { controlKey = "test-control-key" }
    }

    @After
    fun tearDown() {
        client.close()
        server.shutdown()
    }

    @Test
    fun requestStatus_prefersApiInfoAndSendsControlKey() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"fw":"4.0.0","protocol":2,"camera":true}""")
        )

        val latch = CountDownLatch(1)
        var success = false
        var failure: Throwable? = null
        client.requestStatus(loopback()) { result ->
            // Do not call android.jar's JSONObject methods in a local JVM test. The integration
            // contract under test is the actual HTTP route/auth/fallback behavior; JSON semantics
            // are exercised on Android itself.
            success = result.isSuccess
            result.onFailure { failure = it }
            latch.countDown()
        }

        assertTrue("status callback timed out", latch.await(3, TimeUnit.SECONDS))
        assertEquals(null, failure)
        assertTrue("status request should succeed", success)

        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/api/info", request.path)
        assertEquals("test-control-key", request.getHeader("X-ESP32-Control-Key"))
    }

    @Test
    fun requestStatus_fallsBackToLegacyActionStatus() {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"fw":"3.2.0","protocol":1,"ota":true}""")
        )

        val latch = CountDownLatch(1)
        var success = false
        var failure: Throwable? = null
        client.requestStatus(loopback()) { result ->
            success = result.isSuccess
            result.onFailure { failure = it }
            latch.countDown()
        }

        assertTrue("fallback callback timed out", latch.await(3, TimeUnit.SECONDS))
        assertEquals(null, failure)
        assertTrue("legacy fallback request should succeed", success)

        val first = server.takeRequest(1, TimeUnit.SECONDS)!!
        val second = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/api/info", first.path)
        assertTrue(second.path.orEmpty().startsWith("/action?"))
        assertTrue(second.path.orEmpty().contains("go=STATUS"))
        assertEquals("test-control-key", second.getHeader("X-ESP32-Control-Key"))
    }

    @Test
    fun uploadFirmware_sendsExactBinaryKeyAndCompletesProgress() {
        val firmware = ByteArray(32 * 1024 + 123) { index -> (index * 31).toByte() }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":true,"transport":"http","rebooting":true}""")
        )

        val latch = CountDownLatch(1)
        var lastSent = 0L
        var lastTotal = 0L
        var success = false
        var failure: Throwable? = null
        client.uploadFirmware(
            ip = loopback(),
            firmware = firmware,
            otaKey = "ota-secret",
            onProgress = { sent, total ->
                lastSent = sent
                lastTotal = total
            }
        ) { result ->
            success = result.isSuccess
            result.onFailure { failure = it }
            latch.countDown()
        }

        assertTrue("OTA callback timed out", latch.await(5, TimeUnit.SECONDS))
        assertEquals(null, failure)
        assertTrue("OTA request should succeed", success)
        assertEquals(firmware.size.toLong(), lastSent)
        assertEquals(firmware.size.toLong(), lastTotal)

        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/api/ota", request.path)
        assertEquals("ota-secret", request.getHeader("X-ESP32-OTA-Key"))
        assertEquals("application/octet-stream", request.getHeader("Content-Type"))
        assertArrayEquals(firmware, request.body.readByteArray())
    }

    private fun loopback(): String = "127.0.0.1:${server.port}"
}
