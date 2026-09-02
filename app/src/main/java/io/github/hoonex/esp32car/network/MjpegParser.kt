package io.github.hoonex.esp32car.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.io.BufferedInputStream

object MjpegParser {
    fun readFrame(inputStream: InputStream): Bitmap? {
        val headerBytes = readUntil(inputStream, "\r\n\r\n".toByteArray()) ?: return null
        val headerStr = String(headerBytes)
        
        var contentLength = -1
        headerStr.split("\n", "\r\n").forEach { line ->
            if (line.trim().startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(":").trim().toIntOrNull() ?: -1
            }
        }

        if (contentLength > 0) {
            val frameData = ByteArray(contentLength)
            var bytesRead = 0
            while (bytesRead < contentLength) {
                val read = inputStream.read(frameData, bytesRead, contentLength - bytesRead)
                if (read == -1) return null
                bytesRead += read
            }
            
            val rawBitmap = BitmapFactory.decodeByteArray(frameData, 0, frameData.size)
            return rawBitmap?.let {
                if (it.config != Bitmap.Config.ARGB_8888) {
                    it.copy(Bitmap.Config.ARGB_8888, true)
                } else {
                    it
                }
            }
        }
        
        // Fallback: no content length found, just read until FF D9
        return null
    }

    private fun readUntil(inputStream: InputStream, sequence: ByteArray): ByteArray? {
        val out = ByteArrayOutputStream()
        var matchIndex = 0
        while (true) {
            val byte = inputStream.read()
            if (byte == -1) return null
            out.write(byte)
            if (byte.toByte() == sequence[matchIndex]) {
                matchIndex++
                if (matchIndex == sequence.size) {
                    return out.toByteArray()
                }
            } else {
                matchIndex = if (byte.toByte() == sequence[0]) 1 else 0
            }
        }
    }
}
