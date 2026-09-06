package io.github.hoonex.esp32car.network

import android.net.Network
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal espota-compatible client used only to migrate legacy firmware when its
 * recovery AP is alive but the HTTP server on port 80 never started.
 *
 * Supports both the legacy MD5 ArduinoOTA authentication handshake and the
 * SHA-256/PBKDF2 handshake used by recent arduino-esp32 releases.
 */
object ArduinoOtaClient {
    private const val OTA_PORT = 3232
    private const val PREFERRED_CALLBACK_PORT = 3233
    private const val INVITE_RETRIES = 6
    private const val UDP_TIMEOUT_MS = 1600
    private const val TCP_ACCEPT_TIMEOUT_MS = 7_000
    private const val TCP_IO_TIMEOUT_MS = 8_000
    private const val CHUNK_SIZE = 1024

    private data class ListenerStrategy(
        val label: String,
        val bindAddress: InetAddress?,
        val port: Int
    )

    private class ReverseTcpTimeout(
        message: String,
        cause: Throwable
    ) : IOException(message, cause)

    fun upload(
        network: Network,
        localAddress: InetAddress,
        remoteHost: String,
        firmware: ByteArray,
        password: String,
        onProgress: (sent: Long, total: Long) -> Unit
    ): Result<Unit> = runCatching {
        require(localAddress is Inet4Address) { "Recovery Wi-Fi has no IPv4 address" }
        require(remoteHost.isNotBlank()) { "Recovery host is blank" }
        require(firmware.isNotEmpty()) { "Bundled firmware is empty" }
        require(password.isNotBlank()) { "OTA password is missing" }

        val firmwareMd5 = md5Hex(firmware)
        val remoteAddress = InetAddress.getByName(remoteHost)
        val strategies = listOf(
            ListenerStrategy("wildcard-fixed", null, PREFERRED_CALLBACK_PORT),
            ListenerStrategy("wifi-fixed", localAddress, PREFERRED_CALLBACK_PORT),
            ListenerStrategy("wildcard-ephemeral", null, 0),
            ListenerStrategy("wifi-ephemeral", localAddress, 0)
        )

        var reverseTcpFailure: Throwable? = null
        var listenerBindFailure: Throwable? = null

        for ((index, strategy) in strategies.withIndex()) {
            try {
                uploadAttempt(
                    network = network,
                    localAddress = localAddress,
                    remoteAddress = remoteAddress,
                    firmware = firmware,
                    firmwareMd5 = firmwareMd5,
                    password = password,
                    strategy = strategy,
                    onProgress = onProgress
                )
                return@runCatching
            } catch (error: ReverseTcpTimeout) {
                reverseTcpFailure = error
                if (index + 1 < strategies.size) Thread.sleep(450)
            } catch (error: java.net.BindException) {
                listenerBindFailure = error
            }
        }

        val detail = reverseTcpFailure?.message
            ?: listenerBindFailure?.message
            ?: "unknown callback listener failure"
        throw IOException("ArduinoOTA UDP/auth succeeded but ESP32 -> Android TCP callback failed: $detail", reverseTcpFailure ?: listenerBindFailure)
    }

    private fun uploadAttempt(
        network: Network,
        localAddress: InetAddress,
        remoteAddress: InetAddress,
        firmware: ByteArray,
        firmwareMd5: String,
        password: String,
        strategy: ListenerStrategy,
        onProgress: (sent: Long, total: Long) -> Unit
    ) {
        openServer(strategy).use { server ->
            server.soTimeout = TCP_ACCEPT_TIMEOUT_MS

            DatagramSocket(null).use { udp ->
                udp.reuseAddress = true
                udp.bind(InetSocketAddress(localAddress, 0))
                network.bindSocket(udp)
                udp.soTimeout = UDP_TIMEOUT_MS

                val target = InetSocketAddress(remoteAddress, OTA_PORT)
                val invitation = "0 ${server.localPort} ${firmware.size} $firmwareMd5\n"
                var authorized = false
                var lastFailure: Throwable? = null

                for (attempt in 0 until INVITE_RETRIES) {
                    sendUdp(udp, target, invitation)
                    try {
                        val reply = receiveUdp(udp)
                        authorized = when {
                            reply == "OK" -> true
                            reply.startsWith("AUTH ") -> {
                                val nonce = reply.removePrefix("AUTH ").trim()
                                authenticate(udp, target, password, nonce)
                            }
                            else -> throw IOException("Unexpected ArduinoOTA reply: $reply")
                        }
                        if (authorized) break
                    } catch (error: SocketTimeoutException) {
                        lastFailure = error
                    } catch (error: IOException) {
                        lastFailure = error
                    }
                    Thread.sleep(120)
                }

                if (!authorized) {
                    throw IOException(
                        "ArduinoOTA did not answer/authenticate on UDP $OTA_PORT",
                        lastFailure
                    )
                }
            }

            val socket = try {
                server.accept()
            } catch (error: SocketTimeoutException) {
                throw ReverseTcpTimeout(
                    "${strategy.label} listener ${localAddress.hostAddress}:${server.localPort} timed out after UDP/auth OK",
                    error
                )
            }

            transferFirmware(socket, firmware, remoteAddress, onProgress)
        }
    }

    private fun openServer(strategy: ListenerStrategy): ServerSocket = ServerSocket().apply {
        reuseAddress = true
        val endpoint = if (strategy.bindAddress == null) {
            InetSocketAddress(strategy.port)
        } else {
            InetSocketAddress(strategy.bindAddress, strategy.port)
        }
        bind(endpoint)
    }

    private fun transferFirmware(
        socket: Socket,
        firmware: ByteArray,
        remoteAddress: InetAddress,
        onProgress: (sent: Long, total: Long) -> Unit
    ) {
        socket.use {
            if (socket.inetAddress != remoteAddress) {
                throw IOException("Unexpected ArduinoOTA TCP peer: ${socket.inetAddress.hostAddress}")
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
                if (ack.contains("ERROR", ignoreCase = true)) {
                    throw IOException("ArduinoOTA flash error: $ack")
                }
                val written = ack.trim().takeWhile { it.isDigit() }.toIntOrNull()
                if (written == null || written <= 0) {
                    throw IOException("Invalid ArduinoOTA acknowledgement: $ack")
                }

                offset += count
                onProgress(offset.toLong(), firmware.size.toLong())
            }

            // The normal result is "OK". Some ESP32 builds reboot/close so quickly
            // that Android observes EOF first; every chunk was already acknowledged,
            // so EOF at this point is treated as a completed transfer.
            val finalReply = try {
                readReply(input, 64)
            } catch (_: SocketTimeoutException) {
                ""
            }
            if (finalReply.isNotBlank() &&
                !finalReply.contains("OK", ignoreCase = true) &&
                finalReply.contains("ERROR", ignoreCase = true)
            ) {
                throw IOException("ArduinoOTA final error: $finalReply")
            }
        }
    }

    private fun authenticate(
        udp: DatagramSocket,
        target: InetSocketAddress,
        password: String,
        nonce: String
    ): Boolean {
        val (cnonce, response) = when (nonce.length) {
            32 -> {
                val cnonce = randomHex(16)
                cnonce to legacyAuthResponse(password, nonce, cnonce)
            }
            64 -> {
                val cnonce = randomHex(32)
                cnonce to sha256AuthResponse(password, nonce, cnonce)
            }
            else -> throw IOException("Unsupported ArduinoOTA nonce length: ${nonce.length}")
        }

        sendUdp(udp, target, "200 $cnonce $response\n")
        return receiveUdp(udp) == "OK"
    }

    internal fun legacyAuthResponse(password: String, nonce: String, cnonce: String): String {
        val passwordHash = md5Hex(password.toByteArray(Charsets.UTF_8))
        return md5Hex("$passwordHash:$nonce:$cnonce".toByteArray(Charsets.UTF_8))
    }

    internal fun sha256AuthResponse(password: String, nonce: String, cnonce: String): String {
        val passwordHash = sha256Hex(password.toByteArray(Charsets.UTF_8))
        val salt = "$nonce:$cnonce".toByteArray(Charsets.UTF_8)
        val derivedKey = pbkdf2HmacSha256(
            password = passwordHash.toByteArray(Charsets.UTF_8),
            salt = salt,
            iterations = 10_000,
            derivedKeyLength = 32
        ).toHex()
        return sha256Hex("$derivedKey:$nonce:$cnonce".toByteArray(Charsets.UTF_8))
    }

    private fun pbkdf2HmacSha256(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        derivedKeyLength: Int
    ): ByteArray {
        require(iterations > 0)
        require(derivedKeyLength > 0)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val hashLength = mac.macLength
        val blockCount = (derivedKeyLength + hashLength - 1) / hashLength
        val output = ByteArray(blockCount * hashLength)

        for (blockIndex in 1..blockCount) {
            val blockSalt = ByteArray(salt.size + 4)
            salt.copyInto(blockSalt)
            blockSalt[blockSalt.lastIndex - 3] = (blockIndex ushr 24).toByte()
            blockSalt[blockSalt.lastIndex - 2] = (blockIndex ushr 16).toByte()
            blockSalt[blockSalt.lastIndex - 1] = (blockIndex ushr 8).toByte()
            blockSalt[blockSalt.lastIndex] = blockIndex.toByte()

            var u = mac.doFinal(blockSalt)
            val t = u.copyOf()
            for (iteration in 2..iterations) {
                u = mac.doFinal(u)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            t.copyInto(output, (blockIndex - 1) * hashLength)
        }
        return output.copyOf(derivedKeyLength)
    }

    private fun sendUdp(socket: DatagramSocket, target: InetSocketAddress, text: String) {
        val bytes = text.toByteArray(Charsets.US_ASCII)
        socket.send(DatagramPacket(bytes, bytes.size, target))
    }

    private fun receiveUdp(socket: DatagramSocket): String {
        val buffer = ByteArray(256)
        val packet = DatagramPacket(buffer, buffer.size)
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
        SecureRandom().nextBytes(value)
        return value.toHex()
    }

    private fun md5Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).toHex()

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
