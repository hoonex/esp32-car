package io.github.hoonex.esp32car.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.InputStream

object MjpegParser {
    private const val MAX_HEADER_BYTES = 8 * 1024
    private const val MAX_FRAME_BYTES = 2 * 1024 * 1024
    private val HEADER_END = "\r\n\r\n".toByteArray(Charsets.US_ASCII)

    fun readFrame(inputStream: InputStream): Bitmap? {
        val headerBytes = readUntil(inputStream, HEADER_END, MAX_HEADER_BYTES) ?: return null
        val header = String(headerBytes, Charsets.US_ASCII)
        val contentLength = header.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.toIntOrNull()
            ?: return null

        if (contentLength !in 1..MAX_FRAME_BYTES) return null

        val frameData = ByteArray(contentLength)
        var offset = 0
        while (offset < contentLength) {
            val read = inputStream.read(frameData, offset, contentLength - offset)
            if (read < 0) return null
            if (read == 0) continue
            offset += read
        }

        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(frameData, 0, frameData.size, options)
    }

    private fun readUntil(inputStream: InputStream, sequence: ByteArray, maxBytes: Int): ByteArray? {
        val out = ByteArrayOutputStream(minOf(512, maxBytes))
        var matchIndex = 0
        while (out.size() < maxBytes) {
            val next = inputStream.read()
            if (next < 0) return null
            out.write(next)
            val b = next.toByte()
            if (b == sequence[matchIndex]) {
                matchIndex++
                if (matchIndex == sequence.size) return out.toByteArray()
            } else {
                matchIndex = if (b == sequence[0]) 1 else 0
            }
        }
        return null
    }
}
