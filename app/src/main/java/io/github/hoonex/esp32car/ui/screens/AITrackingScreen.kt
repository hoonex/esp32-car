package io.github.hoonex.esp32car.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hoonex.esp32car.network.RCClient
import io.github.hoonex.esp32car.utils.SettingsManager
import io.github.hoonex.esp32car.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AITrackingScreen(rcClient: RCClient, settingsManager: SettingsManager) {
    var ipInput by remember { mutableStateOf(settingsManager.ipAddress) }
    var isRunning by remember { mutableStateOf(false) }
    var targetColor by remember { mutableStateOf("R") } // R, G, B
    var latestBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var aiStatus by remember { mutableStateOf("대기 중") }
    
    // Config values based on python code
    val baseTurnSpeed = 40f
    val maxTurnSpeed = 70f
    val forwardSpeed = 100f
    val backwardSpeed = 85f

    // Check OpenCV initialization
    LaunchedEffect(Unit) {
        if (!OpenCVLoader.initDebug()) {
            Log.e("OpenCV", "OpenCV initialization failed.")
        } else {
            Log.d("OpenCV", "OpenCV initialization succeeded.")
        }
    }

    // Background streaming and OpenCV processing coroutine
    LaunchedEffect(isRunning, targetColor) {
        if (!isRunning) {
            rcClient.sendCommand(ipInput, "stop", 0)
            aiStatus = "정지됨"
            return@LaunchedEffect
        }

        withContext(Dispatchers.IO) {
            val streamUrl = "http://${ipInput}:81/stream"
            var connection: HttpURLConnection? = null
            try {
                connection = URL(streamUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                val inputStream: InputStream = java.io.BufferedInputStream(connection.inputStream, 65536)

                var lastCommandTime = 0L
                val CMD_COOLDOWN = 100L

                // EMA Variables
                var cxEma = -1f
                var areaEma = -1f
                val emaAlpha = 0.2f

                while (isActive) {
                    val bitmap = io.github.hoonex.esp32car.network.MjpegParser.readFrame(inputStream)
                    
                    if (bitmap != null) {
                        try {
                            val (processedBmp, action, spd, area, errX, cxNew, areaNew) = processFrameOpenCV(
                                bitmap, targetColor, cxEma, areaEma, emaAlpha,
                                baseTurnSpeed, maxTurnSpeed, forwardSpeed, backwardSpeed
                            )
                            cxEma = cxNew
                            areaEma = areaNew

                            withContext(Dispatchers.Main) {
                                latestBitmap = processedBmp
                                if (action == "stop") {
                                    aiStatus = "LOST / 정지 (면적: ${area.toInt()})"
                                } else {
                                    aiStatus = "주행: $action (면적: ${area.toInt()}, 오차: ${errX.toInt()})"
                                }
                            }

                            val now = System.currentTimeMillis()
                            if (now - lastCommandTime > CMD_COOLDOWN) {
                                rcClient.sendCommand(ipInput, action, spd)
                                lastCommandTime = now
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    aiStatus = "연결 오류: ${e.message}"
                    isRunning = false
                }
            } finally {
                connection?.disconnect()
                rcClient.sendCommand(ipInput, "stop", 0)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppleBackground)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // --- Header Card ---
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = AppleSurface),
            modifier = Modifier
                .fillMaxWidth()
                .shadow(8.dp, RoundedCornerShape(20.dp), spotColor = Color.Black.copy(alpha = 0.05f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("AI 자율주행 (OpenCV 색상 추적)", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.Black)
                OutlinedTextField(
                    value = ipInput,
                    onValueChange = { ipInput = it },
                    label = { Text("IP 주소") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppleBlue,
                        unfocusedBorderColor = AppleLightGray,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isRunning
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- Status and Target Color ---
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = AppleSurface),
            modifier = Modifier
                .fillMaxWidth()
                .shadow(8.dp, RoundedCornerShape(20.dp), spotColor = Color.Black.copy(alpha = 0.05f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(aiStatus, fontWeight = FontWeight.Medium, color = if (isRunning) AppleBlue else AppleGray)
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ColorButton("R", AppleRed, targetColor == "R") { targetColor = "R" }
                    ColorButton("G", AppleGreen, targetColor == "G") { targetColor = "G" }
                    ColorButton("B", AppleBlue, targetColor == "B") { targetColor = "B" }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- Stream Output ---
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Black),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .shadow(8.dp, RoundedCornerShape(20.dp))
        ) {
            if (latestBitmap != null) {
                Image(
                    bitmap = latestBitmap!!.asImageBitmap(),
                    contentDescription = "AI Camera Stream",
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text("카메라 스트리밍 대기 중...", color = Color.White)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // --- Start/Stop Button ---
        Button(
            onClick = { isRunning = !isRunning },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) AppleRed else AppleGreen
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
        ) {
            Icon(if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                if (isRunning) "자율주행 정지" else "자율주행 시작",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = Color.White
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun ColorButton(label: String, color: Color, isSelected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = if (isSelected) color else color.copy(alpha = 0.3f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.size(60.dp)
    ) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
    }
}

data class OpenCVResult(
    val processedBmp: Bitmap,
    val action: String,
    val speed: Int,
    val area: Float,
    val errX: Float,
    val cxNew: Float,
    val areaNew: Float
)

fun processFrameOpenCV(
    bitmap: Bitmap,
    targetColor: String,
    cxEmaIn: Float,
    areaEmaIn: Float,
    emaAlpha: Float,
    baseTurnSpeed: Float,
    maxTurnSpeed: Float,
    forwardSpeed: Float,
    backwardSpeed: Float
): OpenCVResult {
    // 1. Convert Bitmap to Mat
    val mat = Mat()
    Utils.bitmapToMat(bitmap, mat)

    // Optional: resize if too large to match Python (e.g. w//2, h//2)
    // For QVGA (320x240) it's small enough, no resize needed.
    val w = mat.cols()
    val h = mat.rows()

    // 2. Gaussian Blur
    Imgproc.GaussianBlur(mat, mat, Size(5.0, 5.0), 0.0)

    // 3. Convert to HSV
    val hsv = Mat()
    Imgproc.cvtColor(mat, hsv, Imgproc.COLOR_RGB2HSV)

    // 4. Create Mask based on target color
    val mask = Mat()
    when (targetColor) {
        "R" -> {
            val mask1 = Mat()
            val mask2 = Mat()
            Core.inRange(hsv, Scalar(0.0, 70.0, 50.0), Scalar(15.0, 255.0, 255.0), mask1)
            Core.inRange(hsv, Scalar(165.0, 70.0, 50.0), Scalar(179.0, 255.0, 255.0), mask2)
            Core.bitwise_or(mask1, mask2, mask)
            mask1.release()
            mask2.release()
        }
        "G" -> {
            Core.inRange(hsv, Scalar(40.0, 100.0, 70.0), Scalar(80.0, 255.0, 255.0), mask)
        }
        "B" -> {
            Core.inRange(hsv, Scalar(90.0, 60.0, 50.0), Scalar(140.0, 255.0, 255.0), mask)
        }
    }

    // 5. Morphological operations (Open & Close) to remove noise
    val kernel = Mat.ones(Size(5.0, 5.0), CvType.CV_8U)
    Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel)
    Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)

    // 6. Find Contours
    val contours = ArrayList<MatOfPoint>()
    val hierarchy = Mat()
    Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

    // 7. Find largest blob
    var maxArea = 0.0
    var bestContour: MatOfPoint? = null
    for (contour in contours) {
        val area = Imgproc.contourArea(contour)
        if (area > maxArea) {
            maxArea = area
            bestContour = contour
        }
    }

    val MIN_AREA = 1500.0
    val TARGET_AREA = 7000.0
    val AREA_BAND = 1500.0

    var action = "stop"
    var spd = 0
    var cxEmaOut = cxEmaIn
    var areaEmaOut = areaEmaIn
    var finalErrX = 0f

    val deadRatio = 0.08f
    val fineRatio = 0.15f
    val deadX = (w * deadRatio).toInt()
    val fineX = (w * fineRatio).toInt()

    if (bestContour != null && maxArea > MIN_AREA) {
        val rect = Imgproc.boundingRect(bestContour)
        val cx = rect.x + rect.width / 2f
        val cy = rect.y + rect.height / 2f

        if (cxEmaOut < 0f) {
            cxEmaOut = cx
            areaEmaOut = maxArea.toFloat()
        } else {
            cxEmaOut = emaAlpha * cx + (1 - emaAlpha) * cxEmaOut
            areaEmaOut = emaAlpha * maxArea.toFloat() + (1 - emaAlpha) * areaEmaOut
        }

        val errX = cxEmaOut - (w / 2f)
        val absErr = abs(errX)
        finalErrX = errX

        // Status & Motor Logic
        if (absErr < deadX) {
            if (areaEmaOut < TARGET_AREA - AREA_BAND) {
                action = "forward"
                spd = forwardSpeed.toInt()
            } else if (areaEmaOut > TARGET_AREA + AREA_BAND) {
                action = "backward"
                spd = backwardSpeed.toInt()
            } else {
                action = "stop"
                spd = 0
            }
        } else {
            // Turn speed proportional calculation
            val errNorm = (absErr / (w / 2f)).coerceAtMost(1f)
            spd = (baseTurnSpeed + (maxTurnSpeed - baseTurnSpeed) * errNorm).toInt()

            if (errX < 0) {
                action = "left"
            } else {
                action = "right"
            }
        }

        // 8. Visualization drawing
        // Bounding Box
        Imgproc.rectangle(mat, Point(rect.x.toDouble(), rect.y.toDouble()), Point((rect.x + rect.width).toDouble(), (rect.y + rect.height).toDouble()), Scalar(0.0, 255.0, 0.0, 255.0), 3)
        // Center of target
        Imgproc.circle(mat, Point(cxEmaOut.toDouble(), cy.toDouble()), 8, Scalar(255.0, 255.0, 255.0, 255.0), -1)
        Imgproc.circle(mat, Point(cxEmaOut.toDouble(), cy.toDouble()), 10, Scalar(0.0, 255.0, 0.0, 255.0), 2)
    } else {
        // Lost or Too small
        action = "stop"
        spd = 0
    }

    // Screen Center and Guides
    Imgproc.circle(mat, Point(w / 2.0, h / 2.0), 6, Scalar(0.0, 255.0, 255.0, 255.0), -1)
    Imgproc.line(mat, Point(w / 2.0, 0.0), Point(w / 2.0, h.toDouble()), Scalar(255.0, 255.0, 0.0, 255.0), 2)
    
    // Deadzone lines
    Imgproc.line(mat, Point((w / 2.0 - deadX), 0.0), Point((w / 2.0 - deadX), h.toDouble()), Scalar(0.0, 255.0, 0.0, 255.0), 3)
    Imgproc.line(mat, Point((w / 2.0 + deadX), 0.0), Point((w / 2.0 + deadX), h.toDouble()), Scalar(0.0, 255.0, 0.0, 255.0), 3)
    
    // Finezone lines
    Imgproc.line(mat, Point((w / 2.0 - fineX), 0.0), Point((w / 2.0 - fineX), h.toDouble()), Scalar(255.0, 165.0, 0.0, 255.0), 2)
    Imgproc.line(mat, Point((w / 2.0 + fineX), 0.0), Point((w / 2.0 + fineX), h.toDouble()), Scalar(255.0, 165.0, 0.0, 255.0), 2)

    // Convert back to Bitmap
    val resultBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    Utils.matToBitmap(mat, resultBmp)

    // Release native memory
    mat.release()
    hsv.release()
    mask.release()
    kernel.release()
    hierarchy.release()

    return OpenCVResult(resultBmp, action, spd, areaEmaOut, finalErrX, cxEmaOut, areaEmaOut)
}
