package io.github.hoonex.esp32car.network

import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class RCClient {
    private val dispatcher = Dispatcher().apply {
        maxRequests = 6
        maxRequestsPerHost = 4
    }

    private val client = OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .connectTimeout(1200, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .writeTimeout(1200, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val otaClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val driveCall = AtomicReference<Call?>(null)
    private val lightCall = AtomicReference<Call?>(null)
    private val otaCall = AtomicReference<Call?>(null)

    @Volatile
    var motorTrim: Int = 0

    fun sendLight(ip: String, lightValue: Int) {
        buildUrl(ip, "action") {
            addQueryParameter("light", lightValue.coerceIn(0, 255).toString())
        }?.let { url ->
            val call = client.newCall(Request.Builder().url(url).build())
            lightCall.getAndSet(call)?.cancel()
            enqueueAndClose(call, "light")
        }
    }

    fun sendCommand(ip: String, command: String, speed: Int, overrideTrim: Int? = null) {
        val normalized = command.lowercase()
        val actualTrim = overrideTrim ?: motorTrim
        buildUrl(ip, "action") {
            addQueryParameter("go", normalized)
            addQueryParameter("speed", speed.coerceIn(0, 255).toString())
            addQueryParameter("trim", actualTrim.coerceIn(-50, 50).toString())
        }?.let { url ->
            val call = client.newCall(Request.Builder().url(url).build())
            driveCall.getAndSet(call)?.cancel()
            enqueueAndClose(call, "drive")
        }
    }

    fun requestStatus(ip: String, callback: (Result<JSONObject>) -> Unit) {
        buildUrl(ip, "action") { addQueryParameter("go", "STATUS") }?.let { url ->
            val call = client.newCall(Request.Builder().url(url).build())
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = callback(Result.failure(e))

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!it.isSuccessful) {
                            callback(Result.failure(IOException("HTTP ${it.code}")))
                            return
                        }
                        val body = it.body?.string().orEmpty()
                        runCatching { JSONObject(body) }
                            .onSuccess { json -> callback(Result.success(json)) }
                            .onFailure { error -> callback(Result.failure(error)) }
                    }
                }
            })
        } ?: callback(Result.failure(IllegalArgumentException("Invalid ESP32 IP address")))
    }

    fun uploadFirmware(
        ip: String,
        firmware: ByteArray,
        otaKey: String,
        onProgress: (sent: Long, total: Long) -> Unit,
        callback: (Result<JSONObject>) -> Unit
    ) {
        val url = buildUrl(ip, "api", "ota") ?: run {
            callback(Result.failure(IllegalArgumentException("Invalid ESP32 IP address")))
            return
        }
        if (otaKey.isBlank()) {
            callback(Result.failure(IllegalStateException("OTA key is missing. Connect by Bluetooth and refresh STATUS first.")))
            return
        }
        if (firmware.isEmpty()) {
            callback(Result.failure(IllegalArgumentException("Bundled firmware is empty")))
            return
        }

        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength(): Long = firmware.size.toLong()

            override fun writeTo(sink: BufferedSink) {
                val chunk = 16 * 1024
                var offset = 0
                while (offset < firmware.size) {
                    val count = minOf(chunk, firmware.size - offset)
                    sink.write(firmware, offset, count)
                    offset += count
                    onProgress(offset.toLong(), firmware.size.toLong())
                }
            }
        }

        val request = Request.Builder()
            .url(url)
            .header("X-ESP32-OTA-Key", otaKey)
            .post(body)
            .build()

        val call = otaClient.newCall(request)
        otaCall.getAndSet(call)?.cancel()
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) callback(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val text = it.body?.string().orEmpty()
                    if (!it.isSuccessful) {
                        callback(Result.failure(IOException("OTA HTTP ${it.code}: $text")))
                        return
                    }
                    runCatching { JSONObject(text) }
                        .onSuccess { json -> callback(Result.success(json)) }
                        .onFailure { callback(Result.failure(it)) }
                }
            }
        })
    }

    fun close() {
        driveCall.getAndSet(null)?.cancel()
        lightCall.getAndSet(null)?.cancel()
        otaCall.getAndSet(null)?.cancel()
        dispatcher.cancelAll()
        client.connectionPool.evictAll()
        otaClient.dispatcher.cancelAll()
        otaClient.connectionPool.evictAll()
    }

    private fun enqueueAndClose(call: Call, label: String) {
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) Log.w("RCClient", "$label request failed: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) = response.close()
        })
    }

    private fun buildUrl(ip: String, vararg path: String, block: HttpUrl.Builder.() -> Unit = {}): HttpUrl? {
        val host = ip.trim().removePrefix("http://").removePrefix("https://").substringBefore('/')
        if (host.isBlank()) return null
        return runCatching {
            HttpUrl.Builder()
                .scheme("http")
                .host(host.substringBefore(':'))
                .apply { host.substringAfter(':', "").toIntOrNull()?.let { port(it) } }
                .apply { path.forEach { addPathSegment(it) } }
                .apply(block)
                .build()
        }.getOrNull()
    }
}
