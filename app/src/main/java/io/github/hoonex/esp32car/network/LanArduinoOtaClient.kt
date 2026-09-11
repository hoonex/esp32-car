package io.github.hoonex.esp32car.network

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest

/**
 * Direct-LAN espota client for the v3.3.1 -> v4 migration fallback.
 *
 * v3.3.1 exposes ArduinoOTA on UDP 3232 whenever its normal Wi-Fi path is online.
 * The primary migration transport remains authenticated HTTP. This client is used
 * once only after post-reboot STATUS proves that the board actually returned to
 * v3.3.1, which distinguishes a real boot handoff failure from a slow reconnect.
 *
 * No Android Network object is required. Connecting the UDP socket to the ESP32
 * lets the kernel select the exact local interface/address that can reach the
 * device, which also covers phones acting as the hotspot gateway (for example
 * 172.30.1.1 -> 172.30.1.x).
 */
object LanArduinoOtaClient {
    private const val OTA_PORT = 3232
    private const val INVITE_RETRIES = 6
    private const val UDP_TIMEOUT_MS = 1800
    private const val TCP_ACCEPT_TIMEOUT_MS = 8_000
    private const val TCP_IO_TIMEOUT_MS = 9_000
    private const val CHUNK_SIZE = 1024

    fun upload(
        remoteHost: String,
        firmware: ByteArray,
        password: String,
        onProgress: (sent: Long, total: Long) -> Unit
    ): Result<Unit> = runCatching {
        require(remoteHost.isNotBlank()) { "ESP32 host is blank" }
        require(firmware.isNotEmpty()) { "Bundled firmware is empty" }
        require(password.isNotBlank()) { "OTA password is missing" }

        val remoteAddress = InetAddress.getByName(remoteHost.substringBefore(':'))
        require(remoteAddress is Inet4Address) { "ESP32 host is not IPv4" }
        val firmwareMd5 = md5Hex(firmware)

        DatagramSocket().use { udp ->
            udp.reuseAddress = true
            udp.connect(remoteAddress, OTA_PORT)
            udp.soTimeout = UDP_TIMEOUT_MS

            val localAddress = udp.localAddress
            require(localAddress is Inet4Address && !localAddress.isAnyLocalAddress) {
                "Android could not resolve a local IPv4 route to ${remoteAddress.hostAddress}"
            }

            ServerSocket().use { server ->
                server.reuseAddress = true
                server.bind(InetSocketAddress(localAddress, 0), 1)
                server.soTimeout = TCP_ACCEPT_TIMEOUT_MS

                authorize(
                    udp = udp,
                    callbackPort = server.localPort,
                    firmwareSize = firmware.size,
                    firmwareMd5 = firmwareMd5,
                    password = password
                )

                val socket = try {
                    server.accept()
                } catch (error: SocketTimeoutException) {
                    throw IOException(
                        "ArduinoOTA authenticated but ESP32 did not connect back to ${localAddress.hostAddress}:${server.localPort}",
                        error
                    )
                }

                socket.use {
                    if (socket.inetAddress != remoteAddress) {
                        throw IOException("Unexpected ArduinoOTA callback peer: ${socket.inetAddress.hostAddress}")
                    }
                    socket.soTimeout = TCP_IO_TIMEOUT_MS
                    socket.tcpNoDelay = true
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    var offset = 0

                    while (offset < firmware.size) {
                        val count = minOf(CHUNK_SIZE, firmware.size - offset)
                        output.write(firmware, offset, count)
                        output.flush()

                        val ack = readReply(input, 64)
                        ArduinoOtaClient.validateChunkAck(ack, offset + count >= firmware.size)
                        offset += count
                        onProgress(offset.toLong(), firmware.size.toLong())
                    }

                    // Many ESP32 builds reboot immediately after the last acknowledged
                    // chunk. An EOF/timeout here is therefore acceptable, but an explicit
                    // ERROR is not.
                    val finalReply = try {
                        readReply(input, 64)
                    } catch (_: SocketTimeoutException) {
                        ""
                    }
                    if (finalReply.contains("ERROR", ignoreCase = true)) {
                        throw IOException("ArduinoOTA final error: $finalReply")
                    }
                }
            }
        }
    }

    private fun authorize(
        udp: DatagramSocket,
        callbackPort: Int,
        firmwareSize: Int,
        firmwareMd5: String,
        password: String
    ) {
        val invitation = "0 $callbackPort $firmwareSize $firmwareMd5\n"
        var authorized = false
        var lastError: Throwable? = null

        repeat(INVITE_RETRIES) {
            sendUdp(udp, invitation)
            try {
                val reply = receiveUdp(udp)
                authorized = when {
                    reply == "OK" -> true
                    reply.startsWith("AUTH ") -> authenticate(udp, password, reply.removePrefix("AUTH ").trim())
                    else -> throw IOException("Unexpected ArduinoOTA reply: $reply")
                }
                if (authorized) return
            } catch (error: IOException) {
                lastError = error
            }
            Thread.sleep(150)
        }

        throw IOException("ArduinoOTA did not answer/authenticate on UDP $OTA_PORT", lastError)
    }

    private fun authenticate(udp: DatagramSocket, password: String, nonce: String): Boolean {
        val cnonce = when (nonce.length) {
            32 -> randomHex(16)
            64 -> randomHex(32)
            else -> throw IOException("Unsupported ArduinoOTA nonce length: ${nonce.length}")
        }
        val response = when (nonce.length) {
            32 -> ArduinoOtaClient.legacyAuthResponse(password, nonce, cnonce)
            64 -> ArduinoOtaClient.sha256AuthResponse(password, nonce, cnonce)
            else -> error("unreachable")
        }
        sendUdp(udp, "200 $cnonce $response\n")
        return receiveUdp(udp) == "OK"
    }

    private fun sendUdp(socket: DatagramSocket, text: String) {
        val bytes = text.toByteArray(Charsets.US_ASCII)
        socket.send(DatagramPacket(bytes, bytes.size))
    }

    private fun receiveUdp(socket: DatagramSocket): String {
        val bytes = ByteArray(256)
        val packet = DatagramPacket(bytes, bytes.size)
        socket.receive(packet)
        return String(packet.data, packet.offset, packet.length, Charsets.US_ASCII).trim()
    }

    private fun readReply(input: java.io.InputStream, maxBytes: Int): String {
        val buffer = ByteArray(maxBytes)
        val count = input.read(buffer)
        if (count < 0) return ""
        return String(buffer, 0, count, Charsets.US_ASCII).trim()
    }

    private fun randomHex(bytes: Int): String {
        val value = ByteArray(bytes)
        java.security.SecureRandom().nextBytes(value)
        return value.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun md5Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
