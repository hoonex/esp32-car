package io.github.hoonex.esp32car.network

import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
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

    private val driveCall = AtomicReference<Call?>(null)
    private val lightCall = AtomicReference<Call?>(null)

    @Volatile
    var motorTrim: Int = 0

    fun sendLight(ip: String, lightValue: Int) {
        buildActionUrl(ip) {
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
        buildActionUrl(ip) {
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
        buildActionUrl(ip) { addQueryParameter("go", "STATUS") }?.let { url ->
            val call = client.newCall(Request.Builder().url(url).build())
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    callback(Result.failure(e))
                }

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

    fun close() {
        driveCall.getAndSet(null)?.cancel()
        lightCall.getAndSet(null)?.cancel()
        dispatcher.cancelAll()
        client.connectionPool.evictAll()
    }

    private fun enqueueAndClose(call: Call, label: String) {
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) Log.w("RCClient", "$label request failed: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }

    private fun buildActionUrl(ip: String, block: HttpUrl.Builder.() -> Unit): HttpUrl? {
        val host = ip.trim().removePrefix("http://").removePrefix("https://").substringBefore('/')
        if (host.isBlank()) return null
        return runCatching {
            HttpUrl.Builder()
                .scheme("http")
                .host(host.substringBefore(':'))
                .apply {
                    host.substringAfter(':', "").toIntOrNull()?.let { port(it) }
                }
                .addPathSegment("action")
                .apply(block)
                .build()
        }.getOrNull()
    }
}
