package io.github.hoonex.esp32car.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.FlashlightOff

import androidx.compose.material.icons.filled.Settings

import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import android.widget.Toast
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hoonex.esp32car.network.MjpegParser
import io.github.hoonex.esp32car.network.RCClient
import io.github.hoonex.esp32car.utils.SettingsManager
import io.github.hoonex.esp32car.utils.MediaManager

import org.opencv.core.Mat
import org.opencv.core.MatOfRect
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.android.Utils
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.CascadeClassifier
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import org.opencv.android.OpenCVLoader
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Stop
import io.github.hoonex.esp32car.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection

import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)

fun getFrameSizeInt(res: String): Int {
    return when(res) {
        "QQVGA" -> 1
        "QCIF" -> 2
        "HQVGA" -> 3
        "QVGA" -> 5
        "VGA" -> 8
        "XGA" -> 10
        "SXGA" -> 12
        "UXGA" -> 13
        else -> 5 // QVGA
    }
}

@Composable

fun WebControllerScreen(rcClient: RCClient, settingsManager: SettingsManager) {
    val context = LocalContext.current
    val mediaManager = remember { MediaManager(context) }
    val coroutineScope = rememberCoroutineScope()
        var cascadeFile by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(Unit) { 
        OpenCVLoader.initDebug()
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val file = File(context.cacheDir, "haarcascade_frontalface_default.xml")
                if (!file.exists()) {
                    val url = URL("https://raw.githubusercontent.com/opencv/opencv/master/data/haarcascades/haarcascade_frontalface_default.xml")
                    url.openStream().use { input ->
                        FileOutputStream(file).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                cascadeFile = file
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    var ipInput by remember { mutableStateOf(settingsManager.ipAddress) }
    var connectedIp by remember { mutableStateOf("") }
    var speed by remember { mutableStateOf(settingsManager.speed) }
    var isJoystickMode by remember { mutableStateOf(false) }

    var latestBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var frameCount by remember { mutableStateOf(0) }
    var currentFps by remember { mutableStateOf(0) }
    
    LaunchedEffect(connectedIp) {
        while(isActive) {
            kotlinx.coroutines.delay(1000)
            currentFps = frameCount
            frameCount = 0
        }
    }

    var streamError by remember { mutableStateOf<String?>(null) }
    var isFlashOn by remember { mutableStateOf(false) }
    var showCameraSettingsDialog by remember { mutableStateOf(false) }
    var selectedCameraMode by remember { mutableStateOf("Color") }
    var cannyThreshold1 by remember { mutableStateOf(50f) }
    var cannyThreshold2 by remember { mutableStateOf(150f) }
    var haarScaleFactor by remember { mutableStateOf(1.2f) }
    var haarMinNeighbors by remember { mutableStateOf(5f) }

    var streamResolution by remember { mutableStateOf(settingsManager.streamResolution) }
    var streamQuality by remember { mutableStateOf(settingsManager.streamQuality) }
    var previewBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var captureResolution by remember { mutableStateOf(settingsManager.captureResolution) }
    var captureQuality by remember { mutableStateOf(settingsManager.captureQuality) }



    LaunchedEffect(connectedIp) {
        if (connectedIp.isBlank()) {
            latestBitmap = null
            streamError = null
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            val streamUrl = "http://${connectedIp}:81/stream"
            var connection: HttpURLConnection? = null
            try {
                connection = URL(streamUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                val inputStream: InputStream = java.io.BufferedInputStream(connection.inputStream, 65536)
                streamError = null
                while (isActive) {
                    val bitmap = MjpegParser.readFrame(inputStream)
                    var outBitmap = bitmap
                    try {
                        if (bitmap != null && selectedCameraMode != "Color") {
                            val mat = Mat()
                            Utils.bitmapToMat(bitmap, mat)
                            when (selectedCameraMode) {
                                "Gray" -> {
                                    Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2GRAY)
                                    Imgproc.cvtColor(mat, mat, Imgproc.COLOR_GRAY2RGBA)
                                }
                                "Canny" -> {
                                    val gray = Mat()
                                    Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)
                                    Imgproc.Canny(gray, mat, cannyThreshold1.toDouble(), cannyThreshold2.toDouble())
                                    Imgproc.cvtColor(mat, mat, Imgproc.COLOR_GRAY2RGBA)
                                    gray.release()
                                }
                                "Haar" -> {
                                    if (cascadeFile != null) {
                                        val classifier = CascadeClassifier(cascadeFile!!.absolutePath)
                                        if (!classifier.empty()) {
                                            val gray = Mat()
                                            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)
                                            val faces = MatOfRect()
                                            classifier.detectMultiScale(gray, faces, haarScaleFactor.toDouble(), haarMinNeighbors.toInt())
                                            for (rect in faces.toArray()) {
                                                Imgproc.rectangle(mat, rect.tl(), rect.br(), Scalar(255.0, 0.0, 0.0, 255.0), 3)
                                            }
                                            gray.release()
                                            faces.release()
                                        }
                                    }
                                }
                            }
                            val processedBmp = android.graphics.Bitmap.createBitmap(mat.cols(), mat.rows(), android.graphics.Bitmap.Config.ARGB_8888)
                            Utils.matToBitmap(mat, processedBmp)
                            outBitmap = processedBmp
                            mat.release()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    if (outBitmap != null) {
                        frameCount++
                        if (mediaManager.isRecording) { mediaManager.recordFrame(outBitmap) }
                        withContext(Dispatchers.Main) {
                            latestBitmap = outBitmap
                        }
                    } else {
                        streamError = "스트림 프레임 읽기 실패"
                        break
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                streamError = "연결 오류: ${e.message}"
            } finally {
                connection?.disconnect()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppleBackground)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. IP Input Row (Compact)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = ipInput,
                onValueChange = { ipInput = it },
                label = { Text("ESP32-CAM IP") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AppleBlue,
                    unfocusedBorderColor = AppleLightGray,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f).height(60.dp)
            )
            Button(
                onClick = { 
                    connectedIp = ipInput 
                    settingsManager.ipAddress = ipInput
                    rcClient.motorTrim = settingsManager.trim.toInt()
                    // Sync with ESP32 device upon connection
                    rcClient.sendCommand(ipInput, "stop", settingsManager.speed.toInt())
                },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppleBlue),
                modifier = Modifier.height(50.dp).padding(top = 4.dp)
            ) {
                Text("연결", fontWeight = FontWeight.Bold, color = Color.White)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 2. Camera View (Fixed 4:3 Aspect Ratio)
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Black),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .shadow(8.dp, RoundedCornerShape(16.dp))
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                if (connectedIp.isNotBlank()) {
                    if (latestBitmap != null) {
                        Image(
                            bitmap = latestBitmap!!.asImageBitmap(),
                            contentDescription = "Camera Stream",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                        Row(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(8.dp)
                                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("FPS: $currentFps", color = Color.Green, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp)
                                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    isFlashOn = !isFlashOn
                                    rcClient.sendLight(connectedIp, if (isFlashOn) settingsManager.light.toInt() else 0)
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(if (isFlashOn) Icons.Default.FlashlightOn else Icons.Default.FlashlightOff, contentDescription = "플래시", tint = if (isFlashOn) Color.Yellow else Color.White)
                            }
                            IconButton(
                                onClick = {
                                    coroutineScope.launch {
                                        mediaManager.saveImage(latestBitmap!!)
                                    }
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.PhotoCamera, contentDescription = "사진 캡처", tint = Color.White)
                            }
                            IconButton(
                                onClick = {
                                    if (mediaManager.isRecording) {
                                        coroutineScope.launch { mediaManager.stopRecording() }
                                    } else {
                                        mediaManager.startRecording(latestBitmap!!.width, latestBitmap!!.height)
                                    }
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(if (mediaManager.isRecording) Icons.Default.Stop else Icons.Default.Videocam, contentDescription = "동영상 녹화", tint = if (mediaManager.isRecording) AppleRed else Color.White)
                            }
                        }
                    } else if (streamError != null) {
                        Text(streamError ?: "오류 발생", color = AppleRed)
                    } else {
                        Text("스트리밍 연결 중...", color = Color.White)
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, tint = AppleGray, modifier = Modifier.size(40.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("IP 입력 후 연결", color = AppleGray, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        var showLightDialog by remember { mutableStateOf(false) }
        
var showSpeedDialog by remember { mutableStateOf(false) }
        var showTrimDialog by remember { mutableStateOf(false) }

        // 3. Settings Buttons Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = { showLightDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = AppleSurface, contentColor = Color.Black),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
            ) {
                Icon(Icons.Default.WbSunny, contentDescription = null, tint = Color.Yellow, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("라이트", fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = { showSpeedDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = AppleSurface, contentColor = Color.Black),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
            ) {
                Icon(Icons.Default.Speed, contentDescription = null, tint = AppleBlue, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("속도", fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = { showTrimDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = AppleSurface, contentColor = Color.Black),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
            ) {
                Icon(Icons.Default.Build, contentDescription = null, tint = AppleRed, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("트림", fontWeight = FontWeight.Bold)
            }
        }
        
        if (showLightDialog) {
            AlertDialog(
                onDismissRequest = { showLightDialog = false },
                confirmButton = { TextButton(onClick = { showLightDialog = false }) { Text("확인") } },
                title = { Text("라이트 밝기 조절", fontWeight = FontWeight.Bold) },
                text = {
                    var currentLight by remember { mutableStateOf(settingsManager.light) }
                    Column {
                        Slider(
                            value = currentLight,
                            onValueChange = { 
                                currentLight = it
                                settingsManager.light = it
                                if (isFlashOn) {
                                    rcClient.sendLight(connectedIp, it.toInt())
                                }
                            },
                            valueRange = 0f..255f
                        )
                        Text(text = "밝기: ${currentLight.toInt()}", fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally))
                    }
                }
            )
        }
        if (showCameraSettingsDialog) {
            AlertDialog(
                onDismissRequest = { showCameraSettingsDialog = false },
                title = { Text("카메라 상세 설정", fontWeight = FontWeight.Bold) },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("영상 처리 모드 선택:", fontWeight = FontWeight.Bold, color = Color.Black)
                        
                        val modes = listOf("Color" to "일반 컬러", "Gray" to "그레이스케일", "Canny" to "엣지 검출 (Canny)", "Haar" to "얼굴 검출 (Haar Cascade)")
                        modes.forEach { (id, label) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable { selectedCameraMode = id }
                            ) {
                                RadioButton(
                                    selected = selectedCameraMode == id,
                                    onClick = { selectedCameraMode = id },
                                    colors = RadioButtonDefaults.colors(selectedColor = AppleBlue)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(label, fontSize = 14.sp)
                            }
                        }
                        
                        if (selectedCameraMode == "Canny") {
                            Text("Canny Threshold 1: ${cannyThreshold1.toInt()}", fontSize = 12.sp)
                            Slider(value = cannyThreshold1, onValueChange = { cannyThreshold1 = it }, valueRange = 0f..255f)
                            Text("Canny Threshold 2: ${cannyThreshold2.toInt()}", fontSize = 12.sp)
                            Slider(value = cannyThreshold2, onValueChange = { cannyThreshold2 = it }, valueRange = 0f..255f)
                        } else if (selectedCameraMode == "Haar") {
                            Text("Scale Factor: ${String.format("%.2f", haarScaleFactor)}", fontSize = 12.sp)
                            Slider(value = haarScaleFactor, onValueChange = { haarScaleFactor = it }, valueRange = 1.05f..2.0f)
                            Text("Min Neighbors: ${haarMinNeighbors.toInt()}", fontSize = 12.sp)
                            Slider(value = haarMinNeighbors, onValueChange = { haarMinNeighbors = it }, valueRange = 1f..10f)
                        }
                        
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        
                        Text("스트림 프리셋", fontWeight = FontWeight.Bold, color = Color.Black)
                        val presets = listOf(
                            Triple("초고속", "QQVGA", 25f),
                            Triple("고속", "QQVGA", 15f),
                            Triple("균형", "QVGA", 10f),
                            Triple("고화질", "VGA", 12f)
                        )
                        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            presets.forEach { preset ->
                                val name = preset.first
                                val res = preset.second
                                val qual = preset.third
                                androidx.compose.material3.Button(
                                    onClick = { 
                                        streamResolution = res
                                        streamQuality = qual
                                        settingsManager.streamQuality = qual
                                        settingsManager.streamResolution = res
                                        android.widget.Toast.makeText(context, "$name 프리셋 적용 중...", android.widget.Toast.LENGTH_SHORT).show()
                                        coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                            try {
                                                java.net.URL("http://${connectedIp}/action?stream_quality=${qual.toInt()}&stream_size=${getFrameSizeInt(res)}").readText()
                                            } catch (e: Exception) {}
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = AppleLightGray, contentColor = AppleBlue),
                                    modifier = Modifier.padding(bottom = 8.dp)
                                ) { Text(name, fontSize = 12.sp) }
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        
                        Text("스트림 설정 (실시간 영상)", fontWeight = FontWeight.Bold, color = Color.Black)
                        Text("해상도: $streamResolution", fontSize = 12.sp)
                        val streamResOptions = listOf("QQVGA", "HQVGA", "QVGA", "VGA")
                        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            streamResOptions.forEach { res ->
                                androidx.compose.material3.FilterChip(
                                    selected = streamResolution == res,
                                    onClick = { streamResolution = res },
                                    label = { Text(res, fontSize = 10.sp) }
                                )
                            }
                        }
                        Text("JPEG 압축률 (5~30): ${streamQuality.toInt()}", fontSize = 12.sp)
                        Slider(value = streamQuality, onValueChange = { streamQuality = it }, valueRange = 5f..30f)
                        Button(
                            onClick = { 
                                settingsManager.streamQuality = streamQuality
                                settingsManager.streamResolution = streamResolution
                                Toast.makeText(context, "스트림 설정 적용 (RC카 재연결 시 반영될 수 있습니다)", Toast.LENGTH_SHORT).show()
                                coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    try {
                                        java.net.URL("http://${connectedIp}/action?stream_quality=${streamQuality.toInt()}&stream_size=${getFrameSizeInt(streamResolution)}").readText()
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AppleBlue)
                        ) { Text("스트림 설정 적용") }
                        
                        Divider(modifier = Modifier.padding(vertical = 8.dp))
                        
                        Text("캡처 설정 (사진 촬영)", fontWeight = FontWeight.Bold, color = Color.Black)
                        Text("해상도: $captureResolution", fontSize = 12.sp)
                        val capResOptions = listOf("VGA", "XGA", "SXGA", "UXGA")
                        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            capResOptions.forEach { res ->
                                androidx.compose.material3.FilterChip(
                                    selected = captureResolution == res,
                                    onClick = { captureResolution = res },
                                    label = { Text(res, fontSize = 10.sp) }
                                )
                            }
                        }
                        Text("JPEG 품질 (5~12): ${captureQuality.toInt()}", fontSize = 12.sp)
                        Slider(value = captureQuality, onValueChange = { captureQuality = it }, valueRange = 5f..12f)
                        Button(
                            onClick = { 
                                settingsManager.captureQuality = captureQuality
                                settingsManager.captureResolution = captureResolution
                                Toast.makeText(context, "캡처 테스트 요청 중...", Toast.LENGTH_SHORT).show()
                                coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    try {
                                        val captureUrl = "http://${connectedIp}/capture"
                                        val inputStream = java.net.URL(captureUrl).openStream()
                                        val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            previewBitmap = bitmap
                                            Toast.makeText(context, "캡처 완료! (화면 하단에 표시)", Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            Toast.makeText(context, "캡처 실패", Toast.LENGTH_SHORT).show()
                                        }
                                        e.printStackTrace()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AppleBlue)
                        ) { Text("캡처 설정 적용") }

                        
                        if (previewBitmap != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            androidx.compose.foundation.Image(
                                bitmap = previewBitmap!!.asImageBitmap(),
                                contentDescription = "미리보기",
                                modifier = Modifier.fillMaxWidth().aspectRatio(4f/3f)
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { 
                        showCameraSettingsDialog = false 
                    }) { Text("닫기", color = AppleBlue) }
                },
                containerColor = AppleSurface
            )
        }

        
        if (showSpeedDialog) {
            AlertDialog(
                onDismissRequest = { showSpeedDialog = false },
                confirmButton = { TextButton(onClick = { showSpeedDialog = false }) { Text("확인") } },
                title = { Text("속도 조절", fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        Slider(
                            value = speed,
                            onValueChange = { 
                                speed = it
                                settingsManager.speed = it
                            },
                            valueRange = 50f..255f
                        )
                        Text(text = "속도: ${speed.toInt()}", fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally))
                    }
                }
            )
        }
        
        if (showTrimDialog) {
            AlertDialog(
                onDismissRequest = { showTrimDialog = false },
                confirmButton = { TextButton(onClick = { showTrimDialog = false }) { Text("확인") } },
                title = { Text("조향 미세조정 (Trim)", fontWeight = FontWeight.Bold) },
                text = {
                    var currentTrim by remember { mutableStateOf(settingsManager.trim) }
                    Column {
                        Slider(
                            value = currentTrim,
                            onValueChange = { 
                                currentTrim = it
                                rcClient.motorTrim = it.toInt()
                                settingsManager.trim = it
                            },
                            valueRange = -50f..50f,
                            steps = 100
                        )
                        Text(text = "Trim: ${currentTrim.toInt()}", fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally))
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 4. Controller & Mode Toggle side-by-side
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (isJoystickMode) {
                    JoystickArea(
                        modifier = Modifier.size(160.dp)
                    ) { x, y ->
                        val distance = kotlin.math.hypot(x, y)
                        if (distance < 0.15f) {
                            rcClient.sendCommand(connectedIp, "stop", 0, 0)
                        } else {
                            val absY = kotlin.math.abs(y)
                            val absX = kotlin.math.abs(x)
                            val currentMaxSpeed = speed.toInt()
                            
                            if (absY < 0.2f && absX > 0.5f) {
                                // Spin in place
                                val turnSpeed = 70 + (currentMaxSpeed - 70) * absX
                                rcClient.sendCommand(connectedIp, if (x < 0) "left" else "right", turnSpeed.toInt(), 0)
                            } else {
                                // Forward or backward with trim
                                val moveSpeed = 70 + (currentMaxSpeed - 70) * distance
                                val trimValue = (x * 50f).toInt().coerceIn(-50, 50)
                                rcClient.sendCommand(connectedIp, if (y < 0) "forward" else "backward", moveSpeed.toInt(), trimValue)
                            }
                        }
                    }
                } else {
                    // D-Pad Controls
                    Box(
                        modifier = Modifier
                            .size(140.dp)
                            .shadow(8.dp, CircleShape)
                            .clip(CircleShape)
                            .background(AppleSurface),
                        contentAlignment = Alignment.Center
                    ) {
                        DPadArea(Modifier.align(Alignment.TopCenter).size(50.dp), Icons.Rounded.KeyboardArrowUp) { isPressed ->
                            rcClient.sendCommand(connectedIp, if (isPressed) "forward" else "stop", speed.toInt())
                        }
                        DPadArea(Modifier.align(Alignment.BottomCenter).size(50.dp), Icons.Rounded.KeyboardArrowDown) { isPressed ->
                            rcClient.sendCommand(connectedIp, if (isPressed) "backward" else "stop", speed.toInt())
                        }
                        DPadArea(Modifier.align(Alignment.CenterStart).size(50.dp), Icons.AutoMirrored.Rounded.KeyboardArrowLeft) { isPressed ->
                            rcClient.sendCommand(connectedIp, if (isPressed) "left" else "stop", speed.toInt())
                        }
                        DPadArea(Modifier.align(Alignment.CenterEnd).size(50.dp), Icons.AutoMirrored.Rounded.KeyboardArrowRight) { isPressed ->
                            rcClient.sendCommand(connectedIp, if (isPressed) "right" else "stop", speed.toInt())
                        }
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .shadow(4.dp, CircleShape)
                                .clip(CircleShape)
                                .background(AppleBackground)
                                .pointerInput(Unit) {
                                    detectTapGestures(onPress = {
                                        rcClient.sendCommand(connectedIp, "stop", speed.toInt())
                                    })
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("STOP", color = AppleRed, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }
            }
            
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(end = 16.dp)
            ) {
                Text("Joystick", fontSize = 11.sp, color = if (isJoystickMode) AppleBlue else AppleGray)
                Switch(
                    checked = isJoystickMode,
                    onCheckedChange = { isJoystickMode = it },
                    modifier = Modifier.scale(0.7f),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = AppleBlue,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = AppleLightGray,
                        uncheckedBorderColor = Color.Transparent
                    )
                )
                Text("D-Pad", fontSize = 11.sp, color = if (!isJoystickMode) AppleBlue else AppleGray)
                Spacer(modifier = Modifier.height(16.dp))
                IconButton(
                    onClick = { showCameraSettingsDialog = true },
                    modifier = Modifier
                        .size(40.dp)
                        .background(AppleSurface, CircleShape)
                        .shadow(4.dp, CircleShape)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "카메라 설정", tint = AppleGray)
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        Spacer(modifier = Modifier.height(80.dp))
    }
}

@Composable
fun JoystickArea(
    modifier: Modifier = Modifier,
    onJoystickMoved: (Float, Float) -> Unit
) {
    var thumbOffset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var lastSentTime by remember { mutableStateOf(0L) }
    val density = androidx.compose.ui.platform.LocalDensity.current

    Box(
        modifier = modifier
            .shadow(12.dp, CircleShape, spotColor = Color.Black.copy(alpha = 0.1f))
            .clip(CircleShape)
            .background(AppleSurface)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        
                        if (change.pressed) {
                            val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                            val rawOffset = change.position - center
                            val distance = rawOffset.getDistance()
                            
                            val maxDistance = size.width / 2f - with(density) { 40.dp.toPx() }
                            
                            thumbOffset = if (distance > maxDistance) {
                                rawOffset * (maxDistance / distance)
                            } else {
                                rawOffset
                            }

                            val xRatio = (thumbOffset.x / maxDistance).coerceIn(-1f, 1f)
                            val yRatio = (thumbOffset.y / maxDistance).coerceIn(-1f, 1f)
                            
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastSentTime > 100) { // Limit updates to 10Hz
                                onJoystickMoved(xRatio, yRatio)
                                lastSentTime = currentTime
                            }
                        } else {
                            thumbOffset = androidx.compose.ui.geometry.Offset.Zero
                            onJoystickMoved(0f, 0f)
                            lastSentTime = 0L
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .offset { androidx.compose.ui.unit.IntOffset(thumbOffset.x.roundToInt(), thumbOffset.y.roundToInt()) }
                .size(80.dp)
                .shadow(4.dp, CircleShape)
                .clip(CircleShape)
                .background(AppleBlue),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Gamepad, contentDescription = null, tint = Color.White)
        }
    }
}

@Composable
fun DPadArea(
    modifier: Modifier,
    icon: ImageVector,
    onPressStateChange: (Boolean) -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(if (isPressed) AppleLightGray else Color.Transparent)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        onPressStateChange(true)
                        tryAwaitRelease()
                        isPressed = false
                        onPressStateChange(false)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isPressed) AppleBlue else Color.Black,
            modifier = Modifier.size(40.dp)
        )
    }
}

