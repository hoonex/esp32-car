package io.github.hoonex.esp32car.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.util.Properties

class MjpegInputStream(inStream: InputStream) : DataInputStream(BufferedInputStream(inStream, 8192)) {
    private val SEQUENCE_MARKER = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    private val SEQUENCE_END = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    private val CONTENT_LENGTH = "Content-Length".toByteArray()
    private val HEADER_MAX_LENGTH = 100

    private fun getEndOfSeqeunce(inStream: DataInputStream, sequence: ByteArray): Int {
        var seqIndex = 0
        var c: Byte
        for (i in 0 until HEADER_MAX_LENGTH) {
            c = inStream.readByte()
            if (c == sequence[seqIndex]) {
                seqIndex++
                if (seqIndex == sequence.size) {
                    return i + 1
                }
            } else {
                seqIndex = 0
            }
        }
        return -1
    }

    private fun getStartOfSequence(inStream: DataInputStream, sequence: ByteArray): Int {
        var end = getEndOfSeqeunce(inStream, sequence)
        return if (end < 0) -1 else end - sequence.size
    }

    private fun parseContentLength(headerBytes: ByteArray): Int {
        val headerStr = String(headerBytes)
        val lines = headerStr.split("\r\n", "\n")
        for (line in lines) {
            if (line.startsWith("Content-Length:")) {
                return line.substringAfter(":").trim().toIntOrNull() ?: -1
            }
        }
        return -1
    }

    fun readMjpegFrame(): Bitmap? {
        mark(HEADER_MAX_LENGTH)
        val headerLen = getStartOfSequence(this, SEQUENCE_MARKER)
        reset()
        if (headerLen < 0) {
            // No marker found, try reading byte by byte until we find it
            var seqIndex = 0
            while(true) {
                try {
                    val c = readByte()
                    if (c == SEQUENCE_MARKER[seqIndex]) {
                        seqIndex++
                        if (seqIndex == SEQUENCE_MARKER.size) {
                            break
                        }
                    } else {
                        seqIndex = 0
                    }
                } catch(e: Exception) {
                    return null
                }
            }
        } else {
            val header = ByteArray(headerLen)
            readFully(header)
        }
        
        // We are now just past or just before FF D8? If we used the byte-by-byte we are past FF D8.
        // Wait, the byte-by-byte read swallowed FF D8.
        return null // Let's use a simpler approach
    }
}
