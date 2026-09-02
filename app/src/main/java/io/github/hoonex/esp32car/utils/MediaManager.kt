package io.github.hoonex.esp32car.utils

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class MediaManager(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var frameChannel: Channel<Bitmap>? = null
    private var encoderJob: Job? = null

    suspend fun saveImage(bitmap: Bitmap) = withContext(Dispatchers.IO) {
        val filename = "RC_Cam_${System.currentTimeMillis()}.jpg"
        var fos: java.io.OutputStream? = null
        var imageUri: Uri? = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                }
                imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                fos = imageUri?.let { resolver.openOutputStream(it) }
            } else {
                val imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                if (!imagesDir.exists()) imagesDir.mkdirs()
                val image = File(imagesDir, filename)
                fos = FileOutputStream(image)
            }
            fos?.use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "사진이 Pictures 폴더에 저장되었습니다.", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "사진 저장 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var tempVideoFile: File? = null
    private var frameCount: Long = 0
    private val frameRate = 15

    var isRecording = false
        private set

    fun startRecording(width: Int, height: Int) {
        if (isRecording) return
        try {
            tempVideoFile = File(context.cacheDir, "temp_rc_video.mp4")
            if (tempVideoFile!!.exists()) tempVideoFile!!.delete()

            // width and height must be multiple of 2 for most encoders
            val encWidth = if (width % 2 != 0) width - 1 else width
            val encHeight = if (height % 2 != 0) height - 1 else height

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encWidth, encHeight)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 2000000)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder!!.start()

            muxer = MediaMuxer(tempVideoFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            trackIndex = -1
            muxerStarted = false
            frameCount = 0
            isRecording = true

            frameChannel = Channel(Channel.CONFLATED)
            encoderJob = scope.launch {
                for (bitmap in frameChannel!!) {
                    encodeFrameSync(bitmap, encWidth, encHeight)
                }
            }

            Toast.makeText(context, "녹화가 시작되었습니다.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "녹화 시작 오류: ${e.message}", Toast.LENGTH_SHORT).show()
            isRecording = false
        }
    }

    fun recordFrame(bitmap: Bitmap) {
        if (!isRecording) return
        frameChannel?.trySend(bitmap)
    }

    private fun encodeFrameSync(bitmap: Bitmap, encWidth: Int, encHeight: Int) {
        if (encoder == null) return
        try {
            val scaledBitmap = if (bitmap.width == encWidth && bitmap.height == encHeight) bitmap else Bitmap.createScaledBitmap(bitmap, encWidth, encHeight, false)
            val yuv = getNV12(encWidth, encHeight, scaledBitmap)

            drainEncoder(false)

            val inputBufferIndex = encoder!!.dequeueInputBuffer(10000)
            if (inputBufferIndex >= 0) {
                val inputBuffer = encoder!!.getInputBuffer(inputBufferIndex)
                inputBuffer?.clear()
                inputBuffer?.put(yuv)
                val pts = computePresentationTime(frameCount)
                encoder!!.queueInputBuffer(inputBufferIndex, 0, yuv.size, pts, 0)
                frameCount++
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getNV12(inputWidth: Int, inputHeight: Int, scaled: Bitmap): ByteArray {
        val argb = IntArray(inputWidth * inputHeight)
        scaled.getPixels(argb, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        val yuv = ByteArray(inputWidth * inputHeight * 3 / 2)
        encodeYUV420SP(yuv, argb, inputWidth, inputHeight)
        return yuv
    }

    private fun encodeYUV420SP(yuv420sp: ByteArray, argb: IntArray, width: Int, height: Int) {
        val frameSize = width * height
        var yIndex = 0
        var uvIndex = frameSize
        var a: Int
        var R: Int
        var G: Int
        var B: Int
        var Y: Int
        var U: Int
        var V: Int
        var index = 0
        for (j in 0 until height) {
            for (i in 0 until width) {
                R = (argb[index] and 0xff0000) shr 16
                G = (argb[index] and 0xff00) shr 8
                B = (argb[index] and 0xff) shr 0
                Y = ((66 * R + 129 * G + 25 * B + 128) shr 8) + 16
                U = ((-38 * R - 74 * G + 112 * B + 128) shr 8) + 128
                V = ((112 * R - 94 * G - 18 * B + 128) shr 8) + 128
                yuv420sp[yIndex++] = (if (Y < 0) 0 else if (Y > 255) 255 else Y).toByte()
                if (j % 2 == 0 && index % 2 == 0) {
                    yuv420sp[uvIndex++] = (if (U < 0) 0 else if (U > 255) 255 else U).toByte()
                    yuv420sp[uvIndex++] = (if (V < 0) 0 else if (V > 255) 255 else V).toByte()
                }
                index++
            }
        }
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val TIMEOUT_USEC = 10000L
        if (endOfStream) {
            try {
                val inputBufferIndex = encoder!!.dequeueInputBuffer(TIMEOUT_USEC)
                if (inputBufferIndex >= 0) {
                    encoder!!.queueInputBuffer(inputBufferIndex, 0, 0, computePresentationTime(frameCount), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
            } catch (e: Exception) {}
        }

        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val encoderStatus = encoder!!.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC)
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) throw RuntimeException("format changed twice")
                val newFormat = encoder!!.outputFormat
                trackIndex = muxer!!.addTrack(newFormat)
                muxer!!.start()
                muxerStarted = true
            } else if (encoderStatus < 0) {
                // ignore
            } else {
                val encodedData = encoder!!.getOutputBuffer(encoderStatus)
                    ?: throw RuntimeException("encoderOutputBuffer $encoderStatus was null")

                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    bufferInfo.size = 0
                }

                if (bufferInfo.size != 0) {
                    if (!muxerStarted) throw RuntimeException("muxer hasn't started")
                    encodedData.position(bufferInfo.offset)
                    encodedData.limit(bufferInfo.offset + bufferInfo.size)
                    muxer!!.writeSampleData(trackIndex, encodedData, bufferInfo)
                }

                encoder!!.releaseOutputBuffer(encoderStatus, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    break
                }
            }
        }
    }

    private fun computePresentationTime(frameIndex: Long): Long {
        return 132 + frameIndex * 1000000 / frameRate
    }

    suspend fun stopRecording() = withContext(Dispatchers.IO) {
        if (!isRecording) return@withContext
        isRecording = false
        
        frameChannel?.close()
        encoderJob?.join()
        
        try {
            if (encoder != null) {
                drainEncoder(true)
                encoder!!.stop()
                encoder!!.release()
                encoder = null
            }
            if (muxer != null) {
                if (muxerStarted) {
                    muxer!!.stop()
                }
                muxer!!.release()
                muxer = null
            }

            if (tempVideoFile != null && tempVideoFile!!.exists()) {
                val filename = "RC_Video_${System.currentTimeMillis()}.mp4"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = context.contentResolver
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
                    }
                    val videoUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
                    if (videoUri != null) {
                        resolver.openOutputStream(videoUri)?.use { outStream ->
                            FileInputStream(tempVideoFile!!).use { inStream ->
                                inStream.copyTo(outStream)
                            }
                        }
                    }
                } else {
                    val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                    if (!moviesDir.exists()) moviesDir.mkdirs()
                    val destFile = File(moviesDir, filename)
                    FileInputStream(tempVideoFile!!).use { inStream ->
                        FileOutputStream(destFile).use { outStream ->
                            inStream.copyTo(outStream)
                        }
                    }
                }
                tempVideoFile!!.delete()
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "동영상이 Movies 폴더에 MP4로 저장되었습니다.", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "동영상 저장 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
