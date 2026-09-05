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
        maxRequests = 8
        maxRequestsPerHost = 6
    }

    private val client = OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .connectTimeout(1200, TimeUnit.MILLISECONDS)
        .readTimeout(1800, TimeUnit.MILLISECONDS)
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
    private val configCall = AtomicReference<Call?>(null)
    private val otaCall = AtomicReference<Call?>(null)

    @Volatile var motorTrim: Int = 0
    @Volatile var controlKey: String = ""

    fun sendLight(ip: String, lightValue: Int) {
        requestAction(ip, "light" to lightValue.coerceIn(0, 255).toString(), slot = lightCall, label = "light")
    }

    fun sendCommand(ip: String, command: String, speed: Int, overrideTrim: Int? = null) {
        val actualTrim = overrideTrim ?: motorTrim
        requestAction(
            ip,
            "go" to command.lowercase(),
            "speed" to speed.coerceIn(0, 255).toString(),
            "trim" to actualTrim.coerceIn(-50, 50).toString(),
            slot = driveCall,
            label = "drive"
        )
    }

    fun sendMotorMix(ip: String, left: Int, right: Int) {
        requestAction(
            ip,
            "left" to left.coerceIn(-255, 255).toString(),
            "right" to right.coerceIn(-255, 255).toString(),
            slot = driveCall,
            label = "motor-mix"
        )
    }

    fun setMotorConfig(ip: String, swap: Boolean, invertLeft: Boolean, invertRight: Boolean) {
        requestAction(
            ip,
            "motor_swap" to if (swap) "1" else "0",
            "invert_left" to if (invertLeft) "1" else "0",
            "invert_right" to if (invertRight) "1" else "0",
            slot = configCall,
            label = "motor-config"
        )
    }

    fun setCameraConfig(
        ip: String,
        frameSize: Int,
        quality: Int,
        streamFps: Int,
        brightness: Int,
        contrast: Int,
        saturation: Int,
        mirror: Boolean,
        flip: Boolean
    ) {
        requestAction(
            ip,
            "stream_size" to frameSize.toString(),
            "stream_quality" to quality.coerceIn(4, 20).toString(),
            "stream_fps" to streamFps.coerceIn(5, 20).toString(),
            "brightness" to brightness.coerceIn(-2, 2).toString(),
            "contrast" to contrast.coerceIn(-2, 2).toString(),
            "saturation" to saturation.coerceIn(-2, 2).toString(),
            "hmirror" to if (mirror) "1" else "0",
            "vflip" to if (flip) "1" else "0",
            slot = configCall,
            label = "camera-config"
        )
    }

    fun reboot(ip: String) {
        requestAction(ip, "go" to "REBOOT", slot = configCall, label = "reboot")
    }

    fun requestStatus(ip: String, callback: (Result<JSONObject>) -> Unit) {
        buildUrl(ip, "action") { addQueryParameter("go", "STATUS") }?.let { url ->
            val call = client.newCall(authenticatedBuilder(url).build())
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
        configCall.getAndSet(null)?.cancel()
        otaCall.getAndSet(null)?.cancel()
        dispatcher.cancelAll()
        client.connectionPool.evictAll()
        otaClient.dispatcher.cancelAll()
        otaClient.connectionPool.evictAll()
    }

    private fun requestAction(
        ip: String,
        vararg params: Pair<String, String>,
        slot: AtomicReference<Call?>,
        label: String
    ) {
        buildUrl(ip, "action") {
            params.forEach { (key, value) -> addQueryParameter(key, value) }
        }?.let { url ->
            val call = client.newCall(authenticatedBuilder(url).build())
            slot.getAndSet(call)?.cancel()
            enqueueAndClose(call, label)
        }
    }

    private fun authenticatedBuilder(url: HttpUrl): Request.Builder {
        val builder = Request.Builder().url(url)
        controlKey.trim().takeIf { it.isNotBlank() }?.let {
            builder.header("X-ESP32-Control-Key", it)
        }
        return builder
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
