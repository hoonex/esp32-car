package io.github.hoonex.esp32car.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hoonex.esp32car.bluetooth.ConnectionState
import io.github.hoonex.esp32car.model.TransportMode
import io.github.hoonex.esp32car.network.MjpegParser
import io.github.hoonex.esp32car.protocol.RcProtocol
import io.github.hoonex.esp32car.viewmodel.FirmwareUpdateUiState
import io.github.hoonex.esp32car.viewmodel.RcViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.hypot

private val PilotBg = Color(0xFF07090C)
private val PilotPanel = Color(0xE512161B)
private val PilotPanelStrong = Color(0xF20E1216)
private val PilotLine = Color(0x20FFFFFF)
private val PilotText = Color(0xFFF2F4F5)
private val PilotMuted = Color(0xFF8B959E)
private val PilotAccent = Color(0xFF63D4B1)
private val PilotBlue = Color(0xFF78A9FF)
private val PilotDanger = Color(0xFFE35A67)
private val PilotWarning = Color(0xFFE8B86A)

private enum class VisionMode { OFFLINE, CONNECTING, MJPEG, SNAPSHOT }

private data class VisionUiState(
    val mode: VisionMode = VisionMode.OFFLINE,
    val message: String = "Vision link offline"
)

@SuppressLint("MissingPermission")
@Composable
fun PilotCockpitScreen(viewModel: RcViewModel) {
    val btState by viewModel.bluetooth.connectionState.collectAsStateWithLifecycle()
    if (btState != ConnectionState.CONNECTED) {
        ReliableCockpitScreen(viewModel)
        return
    }

    val connectedName by viewModel.bluetooth.connectedDeviceName.collectAsStateWithLifecycle()
    val btStatus by viewModel.bluetooth.btStatusResponse.collectAsStateWithLifecycle()
    val wifiStatus by viewModel.wifiStatus.collectAsStateWithLifecycle()
    val wifiError by viewModel.wifiError.collectAsStateWithLifecycle()
    val speed by viewModel.speed.collectAsStateWithLifecycle()
    val light by viewModel.light.collectAsStateWithLifecycle()
    val firmwareUpdate by viewModel.firmwareUpdate.collectAsStateWithLifecycle()

    var settingsOpen by remember { mutableStateOf(false) }
    var cameraRetryToken by remember { mutableIntStateOf(0) }
    var visionState by remember { mutableStateOf(VisionUiState()) }

    val status = wifiStatus ?: btStatus
    val ip = viewModel.settings.ipAddress
    val cameraReady = status?.optBoolean("camera", false) ?: false
    val streamReady = status?.optBoolean("stream_ready", false) ?: false
    val httpReady = status?.optBoolean("http_ready", false) ?: false

    LaunchedEffect(Unit) {
        // Product architecture: Bluetooth remains the deterministic control/safety link.
        // Wi-Fi is only the high-bandwidth vision/diagnostic/OTA side channel.
        viewModel.setTransportMode(TransportMode.BLUETOOTH)
        viewModel.refreshBluetoothStatus()
        if (viewModel.settings.ipAddress.isNotBlank()) viewModel.refreshWifiStatus()
        while (isActive) {
            delay(5000)
            viewModel.refreshBluetoothStatus()
            if (viewModel.settings.ipAddress.isNotBlank()) viewModel.refreshWifiStatus()
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.emergencyStop() }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(PilotBg)) {
        val compact = maxHeight < 430.dp
        val edge = if (compact) 12.dp else 18.dp
        val driveSize = minOf(maxHeight * 0.50f, maxWidth * 0.23f)
        val railWidth = minOf(maxWidth * 0.16f, if (compact) 132.dp else 150.dp)

        VisionFeed(
            ip = ip,
            controlKey = viewModel.settings.otaKey,
            cameraReady = cameraReady,
            streamReady = streamReady,
            retryToken = cameraRetryToken,
            onState = { if (it != visionState) visionState = it },
            modifier = Modifier.fillMaxSize()
        )

        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    listOf(
                        Color(0xB8000000),
                        Color(0x33000000),
                        Color(0x10000000),
                        Color(0x40000000),
                        Color(0xC6000000)
                    )
                )
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(Color(0x80000000), Color.Transparent, Color(0x79000000))
                )
            )
        )

        PilotTopBar(
            modifier = Modifier.align(Alignment.TopCenter).padding(edge).fillMaxWidth(),
            connectedName = connectedName ?: "ESP32_CAM_RC",
            status = status,
            visionState = visionState,
            ip = ip
        )

        if (visionState.mode == VisionMode.OFFLINE) {
            VisionOfflineCard(
                modifier = Modifier.align(Alignment.Center),
                status = status,
                ip = ip,
                httpReady = httpReady,
                onRetry = {
                    viewModel.bluetooth.sendCommand(RcProtocol.CAMERA_RETRY)
                    cameraRetryToken++
                    viewModel.refreshBluetoothStatus()
                    if (ip.isNotBlank()) viewModel.refreshWifiStatus()
                },
                onSettings = {
                    viewModel.emergencyStop()
                    settingsOpen = true
                }
            )
        }

        PilotDrivePad(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = edge, bottom = edge)
                .size(driveSize),
            deadzone = viewModel.settings.controlDeadzone,
            onVector = viewModel::driveVector,
            onStop = viewModel::emergencyStop
        )

        PilotControlRail(
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = edge),
            width = railWidth,
            compact = compact,
            light = light,
            onLight = { viewModel.updateLight(if (light > 0f) 0f else 220f) },
            onCameraRetry = {
                viewModel.bluetooth.sendCommand(RcProtocol.CAMERA_RETRY)
                cameraRetryToken++
                viewModel.refreshBluetoothStatus()
            },
            onSettings = {
                viewModel.emergencyStop()
                settingsOpen = true
            },
            onStop = viewModel::emergencyStop
        )

        PilotTelemetryBar(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = edge),
            status = status,
            speed = speed,
            visionState = visionState,
            wifiError = wifiError
        )

        if (settingsOpen) {
            PilotSettingsDrawer(
                viewModel = viewModel,
                status = status,
                firmwareUpdate = firmwareUpdate,
                compact = compact,
                onCameraRetry = {
                    viewModel.bluetooth.sendCommand(RcProtocol.CAMERA_RETRY)
                    cameraRetryToken++
                    viewModel.refreshBluetoothStatus()
                    if (viewModel.settings.ipAddress.isNotBlank()) viewModel.refreshWifiStatus()
                },
                onClose = {
                    viewModel.emergencyStop()
                    settingsOpen = false
                }
            )
        }
    }
}

@Composable
private fun PilotTopBar(
    modifier: Modifier,
    connectedName: String,
    status: JSONObject?,
    visionState: VisionUiState,
    ip: String
) {
    Surface(
        modifier = modifier,
        color = PilotPanelStrong,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, PilotLine)
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "ESP32 CAR",
                    color = PilotText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
                Text(
                    connectedName,
                    color = PilotMuted,
                    fontSize = 8.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            LinkChip(Icons.Default.Bluetooth, "Control", "Bluetooth", PilotAccent, true)
            LinkChip(
                Icons.Default.Wifi,
                "Vision",
                when (visionState.mode) {
                    VisionMode.MJPEG -> "Wi-Fi live"
                    VisionMode.SNAPSHOT -> "Wi-Fi fallback"
                    VisionMode.CONNECTING -> "Connecting"
                    VisionMode.OFFLINE -> if (ip.isBlank()) "Not configured" else "Offline"
                },
                if (visionState.mode == VisionMode.OFFLINE) PilotWarning else PilotBlue,
                visionState.mode != VisionMode.OFFLINE
            )

            val fw = status?.optString("fw").orEmpty().ifBlank { "—" }
            Surface(color = Color(0x141FFFFFF), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(horizontal = 11.dp, vertical = 7.dp), horizontalAlignment = Alignment.End) {
                    Text("Firmware", color = PilotMuted, fontSize = 7.sp)
                    Text("v$fw", color = PilotText, fontWeight = FontWeight.SemiBold, fontSize = 9.sp)
                }
            }
        }
    }
}

@Composable
private fun LinkChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    active: Boolean
) {
    Surface(
        color = if (active) accent.copy(alpha = 0.10f) else Color(0x151FFFFFF),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (active) accent.copy(alpha = 0.22f) else PilotLine)
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Icon(icon, null, Modifier.size(15.dp), tint = if (active) accent else PilotMuted)
            Column {
                Text(title, color = PilotText, fontSize = 8.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = PilotMuted, fontSize = 7.sp)
            }
        }
    }
}

@Composable
private fun VisionOfflineCard(
    modifier: Modifier,
    status: JSONObject?,
    ip: String,
    httpReady: Boolean,
    onRetry: () -> Unit,
    onSettings: () -> Unit
) {
    val cameraReady = status?.optBoolean("camera", false) ?: false
    val hasErrorCode = status?.has("camera_error") == true
    val cameraError = status?.optInt("camera_error", 0) ?: 0
    val attempts = status?.optInt("camera_attempts", 0) ?: 0

    val title: String
    val detail: String
    when {
        ip.isBlank() -> {
            title = "Vision link is not configured"
            detail = "주행은 Bluetooth로 정상 유지됩니다. 카메라와 OTA에 사용할 Wi-Fi만 연결하면 됩니다."
        }
        !cameraReady && hasErrorCode -> {
            title = "OV2640 camera is not ready"
            detail = "초기화 오류 0x${cameraError.toUInt().toString(16).uppercase()} · 시도 ${attempts}회 · HTTP ${if (httpReady) "online" else "offline"}"
        }
        !cameraReady -> {
            title = "Camera service is unavailable"
            detail = "현재 펌웨어는 상세 카메라 오류 코드를 보고하지 않습니다. v3.3.2로 OTA 후 센서 재시도를 사용할 수 있습니다."
        }
        else -> {
            title = "Vision stream is unavailable"
            detail = "센서는 응답하지만 영상 스트림을 열지 못했습니다. 앱이 port 80 snapshot fallback도 함께 시도합니다."
        }
    }

    Surface(
        modifier = modifier.width(340.dp),
        color = Color(0xE20D1115),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, PilotLine),
        shadowElevation = 14.dp
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Surface(color = PilotWarning.copy(alpha = 0.12f), shape = CircleShape) {
                    Icon(Icons.Default.Videocam, null, Modifier.padding(9.dp).size(18.dp), tint = PilotWarning)
                }
                Column {
                    Text(title, color = PilotText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text("Bluetooth control remains active", color = PilotAccent, fontSize = 8.sp)
                }
            }
            Text(detail, color = PilotMuted, fontSize = 9.sp, lineHeight = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRetry, enabled = ip.isNotBlank()) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("카메라 재시도", fontSize = 9.sp)
                }
                OutlinedButton(onClick = onSettings) { Text("연결 설정", fontSize = 9.sp) }
            }
        }
    }
}

@Composable
private fun PilotControlRail(
    modifier: Modifier,
    width: androidx.compose.ui.unit.Dp,
    compact: Boolean,
    light: Float,
    onLight: () -> Unit,
    onCameraRetry: () -> Unit,
    onSettings: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        modifier = modifier.width(width),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 9.dp)
    ) {
        RailButton(Icons.Default.Lightbulb, if (light > 0f) "Light on" else "Light", light > 0f, onLight)
        RailButton(Icons.Default.CameraAlt, "Retry vision", false, onCameraRetry)
        RailButton(Icons.Default.Settings, "Settings", false, onSettings)
        Button(
            onClick = onStop,
            modifier = Modifier.fillMaxWidth().height(if (compact) 48.dp else 56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PilotDanger, contentColor = Color.White)
        ) {
            Icon(Icons.Default.Stop, null, Modifier.size(17.dp))
            Spacer(Modifier.width(7.dp))
            Text("STOP", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
        }
    }
}

@Composable
private fun RailButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(44.dp),
        shape = RoundedCornerShape(15.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (active) PilotAccent.copy(alpha = 0.18f) else PilotPanel,
            contentColor = if (active) PilotAccent else PilotText
        )
    ) {
        Icon(icon, null, Modifier.size(16.dp))
        Spacer(Modifier.width(7.dp))
        Text(label, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PilotTelemetryBar(
    modifier: Modifier,
    status: JSONObject?,
    speed: Float,
    visionState: VisionUiState,
    wifiError: String?
) {
    Surface(
        modifier = modifier,
        color = Color(0xD90B0F13),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, PilotLine)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            TelemetryValue("MAX", speed.toInt().toString(), PilotText)
            TelemetryValue("RSSI", status?.optLong("rssi")?.takeIf { it != 0L }?.let { "$it dBm" } ?: "—", PilotText)
            TelemetryValue(
                "VISION",
                when (visionState.mode) {
                    VisionMode.MJPEG -> "MJPEG"
                    VisionMode.SNAPSHOT -> "SNAPSHOT"
                    VisionMode.CONNECTING -> "CONNECTING"
                    VisionMode.OFFLINE -> "OFFLINE"
                },
                if (visionState.mode == VisionMode.OFFLINE) PilotWarning else PilotAccent
            )
            val trips = status?.optInt("deadman_trips", 0) ?: 0
            TelemetryValue("FAILSAFE", if (trips == 0) "ARMED" else "$trips TRIPS", if (trips == 0) PilotAccent else PilotDanger)
            wifiError?.let {
                Text(it, color = PilotWarning, fontSize = 7.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun TelemetryValue(label: String, value: String, color: Color) {
    Column {
        Text(label, color = PilotMuted, fontSize = 6.sp, letterSpacing = 0.7.sp)
        Text(value, color = color, fontSize = 8.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PilotDrivePad(
    modifier: Modifier,
    deadzone: Float,
    onVector: (Float, Float) -> Unit,
    onStop: () -> Unit
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var knob by remember { mutableStateOf(Offset.Zero) }
    var active by remember { mutableStateOf(false) }
    var throttle by remember { mutableFloatStateOf(0f) }
    var steering by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current

    fun reset() {
        active = false
        knob = Offset.Zero
        throttle = 0f
        steering = 0f
        onStop()
    }

    fun update(position: Offset) {
        if (size.width == 0 || size.height == 0) return
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = minOf(size.width, size.height) * 0.37f
        val dx = position.x - center.x
        val dy = position.y - center.y
        val len = hypot(dx, dy)
        val scale = if (len > radius && len > 0f) radius / len else 1f
        knob = Offset(dx * scale, dy * scale)
        steering = (knob.x / radius).coerceIn(-1f, 1f)
        throttle = (-knob.y / radius).coerceIn(-1f, 1f)
    }

    LaunchedEffect(active, throttle, steering) {
        if (!active) return@LaunchedEffect
        while (isActive && active) {
            onVector(throttle, steering)
            delay(75)
        }
    }

    Box(
        modifier = modifier
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { active = true; update(it) },
                    onDrag = { change, _ -> change.consume(); update(change.position) },
                    onDragEnd = { reset() },
                    onDragCancel = { reset() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = minOf(size.width, size.height) * 0.37f
            drawCircle(Color(0xA90D1217), radius = radius * 1.22f)
            drawCircle(PilotLine, radius = radius * 1.22f, style = androidx.compose.ui.graphics.drawscope.Stroke(1.4.dp.toPx()))
            drawCircle(Color.White.copy(alpha = 0.035f), radius = radius * 0.86f, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            drawCircle(PilotAccent.copy(alpha = 0.12f), radius = radius * deadzone.coerceIn(0.02f, 0.35f))
            drawLine(PilotLine, Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), 1.dp.toPx())
            drawLine(PilotLine, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), 1.dp.toPx())
        }

        val knobPixels = if (size.height > 0) (minOf(size.width, size.height) * 0.27f).coerceAtLeast(50f) else 62f
        val knobDp = with(density) { knobPixels.toDp() }
        Surface(
            modifier = Modifier.size(knobDp).graphicsLayer {
                translationX = knob.x
                translationY = knob.y
            },
            color = if (active) PilotAccent else Color(0xFF202830),
            shape = CircleShape,
            border = BorderStroke(1.dp, if (active) Color.White.copy(alpha = 0.25f) else PilotLine),
            shadowElevation = if (active) 10.dp else 3.dp
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("DRIVE", color = if (active) Color(0xFF09271F) else PilotText, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    Text("BT", color = if (active) Color(0xB309271F) else PilotMuted, fontSize = 6.sp)
                }
            }
        }
    }
}

@Composable
private fun PilotSettingsDrawer(
    viewModel: RcViewModel,
    status: JSONObject?,
    firmwareUpdate: FirmwareUpdateUiState,
    compact: Boolean,
    onCameraRetry: () -> Unit,
    onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val speed by viewModel.speed.collectAsStateWithLifecycle()
    val trim by viewModel.trim.collectAsStateWithLifecycle()
    val wifiStatus by viewModel.wifiStatus.collectAsStateWithLifecycle()
    val wifiError by viewModel.wifiError.collectAsStateWithLifecycle()

    var ssid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var deadzone by remember { mutableFloatStateOf(viewModel.settings.controlDeadzone) }
    var steeringGain by remember { mutableFloatStateOf(viewModel.settings.steeringGain) }
    var steeringExpo by remember { mutableFloatStateOf(viewModel.settings.steeringExpo) }
    var streamResolution by remember { mutableStateOf(viewModel.settings.streamResolution) }
    var streamQuality by remember { mutableFloatStateOf(viewModel.settings.streamQuality) }
    var streamFps by remember { mutableFloatStateOf(viewModel.settings.streamFps) }
    var brightness by remember { mutableFloatStateOf(viewModel.settings.cameraBrightness) }
    var mirror by remember { mutableStateOf(viewModel.settings.cameraMirror) }
    var flip by remember { mutableStateOf(viewModel.settings.cameraFlip) }

    Box(Modifier.fillMaxSize().background(Color(0x93000000))) {
        Surface(
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(if (compact) 0.58f else 0.46f),
            color = Color(0xFF0C1014),
            shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp),
            border = BorderStroke(1.dp, PilotLine),
            shadowElevation = 20.dp
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Car settings", color = PilotText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text("Bluetooth control · Wi-Fi vision + OTA", color = PilotMuted, fontSize = 8.sp)
                    }
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = PilotText) }
                }

                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        DrawerSection("Links", "주행 링크와 영상 링크를 분리해서 관리합니다.") {
                            StatusLine("Control", "Bluetooth · verified", PilotAccent)
                            StatusLine("Vision IP", viewModel.settings.ipAddress.ifBlank { "not configured" }, if (viewModel.settings.ipAddress.isBlank()) PilotWarning else PilotBlue)
                            status?.let {
                                StatusLine("Wi-Fi", it.optString("ssid").ifBlank { "—" }, PilotText)
                                StatusLine("HTTP / stream", "${if (it.optBoolean("http_ready")) "80 online" else "80 offline"} · ${if (it.optBoolean("stream_ready")) "81 online" else "81 offline"}", PilotText)
                            }

                            OutlinedTextField(
                                value = ssid,
                                onValueChange = { ssid = it },
                                label = { Text("Wi-Fi SSID") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text("Password") },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            viewModel.provisionWifi(ssid, password)
                                            delay(300)
                                            viewModel.switchEsp32ToWifi()
                                            delay(1800)
                                            viewModel.refreshBluetoothStatus()
                                        }
                                    },
                                    enabled = ssid.isNotBlank()
                                ) { Text("저장하고 연결", fontSize = 9.sp) }
                                OutlinedButton(onClick = {
                                    viewModel.refreshBluetoothStatus()
                                    if (viewModel.settings.ipAddress.isNotBlank()) viewModel.refreshWifiStatus()
                                }) {
                                    Icon(Icons.Default.Refresh, null, Modifier.size(14.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text("새로고침", fontSize = 9.sp)
                                }
                            }
                            wifiError?.let { Text(it, color = PilotWarning, fontSize = 8.sp) }
                            wifiStatus?.let { Text("${it.optString("mode")} · ${it.optString("ip")}", color = PilotMuted, fontSize = 8.sp) }
                        }
                    }

                    item {
                        DrawerSection("Drive", "Bluetooth SPP가 항상 조종과 failsafe를 담당합니다.") {
                            Text("Max output · ${speed.toInt()}", color = PilotText, fontSize = 9.sp)
                            Slider(value = speed, onValueChange = viewModel::updateSpeed, valueRange = 50f..255f)
                            Text("Trim · ${trim.toInt()}", color = PilotText, fontSize = 9.sp)
                            Slider(value = trim, onValueChange = viewModel::updateTrim, valueRange = -50f..50f)
                            Text("Deadzone · ${"%.2f".format(deadzone)}", color = PilotText, fontSize = 9.sp)
                            Slider(
                                value = deadzone,
                                onValueChange = { deadzone = it; viewModel.settings.controlDeadzone = it },
                                valueRange = 0.02f..0.35f
                            )
                            Text("Steering gain · ${"%.2f".format(steeringGain)}", color = PilotText, fontSize = 9.sp)
                            Slider(
                                value = steeringGain,
                                onValueChange = { steeringGain = it; viewModel.settings.steeringGain = it },
                                valueRange = 0.5f..1.8f
                            )
                            Text("Steering expo · ${"%.2f".format(steeringExpo)}", color = PilotText, fontSize = 9.sp)
                            Slider(
                                value = steeringExpo,
                                onValueChange = { steeringExpo = it; viewModel.settings.steeringExpo = it },
                                valueRange = 1f..2.5f
                            )
                        }
                    }

                    item {
                        DrawerSection("Vision", "MJPEG :81 실패 시 /capture snapshot fallback을 자동 사용합니다.") {
                            val cameraReady = status?.optBoolean("camera", false) ?: false
                            StatusLine("Camera", if (cameraReady) "ready" else "not ready", if (cameraReady) PilotAccent else PilotWarning)
                            if (status?.has("camera_error") == true) {
                                val err = status.optInt("camera_error")
                                StatusLine("Init error", "0x${err.toUInt().toString(16).uppercase()} · attempt ${status.optInt("camera_attempts")}", if (err == 0) PilotMuted else PilotWarning)
                                StatusLine("XCLK", "${status.optLong("camera_xclk_hz") / 1_000_000} MHz", PilotMuted)
                            }
                            OutlinedButton(onClick = onCameraRetry, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.CameraAlt, null, Modifier.size(15.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("센서 + 스트림 재시도", fontSize = 9.sp)
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                listOf("QQVGA", "QVGA", "VGA").forEach { value ->
                                    FilterChip(
                                        selected = streamResolution == value,
                                        onClick = { streamResolution = value; viewModel.settings.streamResolution = value },
                                        label = { Text(value, fontSize = 8.sp) }
                                    )
                                }
                            }
                            Text("JPEG quality · ${streamQuality.toInt()}", color = PilotText, fontSize = 9.sp)
                            Slider(value = streamQuality, onValueChange = { streamQuality = it; viewModel.settings.streamQuality = it }, valueRange = 4f..20f)
                            Text("FPS · ${streamFps.toInt()}", color = PilotText, fontSize = 9.sp)
                            Slider(value = streamFps, onValueChange = { streamFps = it; viewModel.settings.streamFps = it }, valueRange = 5f..20f, steps = 14)
                            Text("Brightness · ${brightness.toInt()}", color = PilotText, fontSize = 9.sp)
                            Slider(value = brightness, onValueChange = { brightness = it; viewModel.settings.cameraBrightness = it }, valueRange = -2f..2f, steps = 3)
                            ToggleSetting("Mirror", mirror) { mirror = it; viewModel.settings.cameraMirror = it }
                            ToggleSetting("Vertical flip", flip) { flip = it; viewModel.settings.cameraFlip = it }
                            Button(onClick = viewModel::applyCameraConfig, enabled = viewModel.settings.ipAddress.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                                Text("카메라 설정 적용", fontSize = 9.sp)
                            }
                        }
                    }

                    item {
                        DrawerSection("Firmware", "현재 v3.3.1이면 이 APK의 v3.3.2를 Wi-Fi OTA로 올려 실제 무선 업데이트 경로를 검증할 수 있습니다.") {
                            val current = status?.optString("fw").orEmpty().ifBlank { viewModel.settings.lastFirmwareVersion.ifBlank { "unknown" } }
                            StatusLine("Installed", "v$current", PilotText)
                            StatusLine("Bundled", "v${firmwareUpdate.bundledVersion}", PilotAccent)

                            when (firmwareUpdate.stage) {
                                FirmwareUpdateUiState.Stage.PREPARING,
                                FirmwareUpdateUiState.Stage.UPLOADING,
                                FirmwareUpdateUiState.Stage.REBOOTING -> {
                                    LinearProgressIndicator(
                                        progress = { firmwareUpdate.progress.coerceIn(0, 100) / 100f },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                else -> Unit
                            }
                            if (firmwareUpdate.message.isNotBlank()) {
                                Text(
                                    firmwareUpdate.message,
                                    color = if (firmwareUpdate.stage == FirmwareUpdateUiState.Stage.ERROR) PilotDanger else PilotMuted,
                                    fontSize = 8.sp,
                                    lineHeight = 12.sp
                                )
                            }

                            Button(
                                onClick = viewModel::updateFirmwareFromBundled,
                                enabled = viewModel.settings.ipAddress.isNotBlank() &&
                                    viewModel.settings.otaKey.isNotBlank() &&
                                    firmwareUpdate.stage != FirmwareUpdateUiState.Stage.UPLOADING &&
                                    firmwareUpdate.stage != FirmwareUpdateUiState.Stage.PREPARING &&
                                    firmwareUpdate.stage != FirmwareUpdateUiState.Stage.REBOOTING,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.SystemUpdate, null, Modifier.size(15.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("v${firmwareUpdate.bundledVersion} 무선 설치", fontSize = 9.sp)
                            }
                            Text("OTA 중에도 모터는 먼저 정지하며, 성공 판정은 재부팅 후 실제 FW 버전 확인으로만 합니다.", color = PilotMuted, fontSize = 7.sp)
                        }
                    }

                    item { Spacer(Modifier.height(20.dp)) }
                }
            }
        }
    }
}

@Composable
private fun DrawerSection(title: String, subtitle: String, content: @Composable () -> Unit) {
    Surface(
        color = Color(0xFF12171C),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, PilotLine)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = PilotText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = PilotMuted, fontSize = 8.sp, lineHeight = 12.sp)
            content()
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String, valueColor: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = PilotMuted, fontSize = 8.sp, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, fontSize = 8.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ToggleSetting(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = PilotText, fontSize = 9.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun VisionFeed(
    ip: String,
    controlKey: String,
    cameraReady: Boolean,
    streamReady: Boolean,
    retryToken: Int,
    onState: (VisionUiState) -> Unit,
    modifier: Modifier = Modifier
) {
    var latestBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(ip, controlKey, cameraReady, streamReady, retryToken) {
        latestBitmap = null
        if (ip.isBlank()) {
            onState(VisionUiState(VisionMode.OFFLINE, "Wi-Fi vision link not configured"))
            return@LaunchedEffect
        }
        if (!cameraReady) {
            onState(VisionUiState(VisionMode.OFFLINE, "Camera sensor is not ready"))
            return@LaunchedEffect
        }

        val host = normalizeHost(ip)
        var backoff = 700L

        while (isActive) {
            if (streamReady) {
                onState(VisionUiState(VisionMode.CONNECTING, "Opening MJPEG stream"))
                val streamed = runCatching {
                    streamMjpeg(host, controlKey) { frame ->
                        withContext(Dispatchers.Main) {
                            latestBitmap = frame
                            onState(VisionUiState(VisionMode.MJPEG, "MJPEG :81"))
                        }
                    }
                }.isSuccess
                if (streamed) continue
            }

            // Port 81 can fail independently. Port 80 capture is deliberately a second vision path.
            var snapshotFrames = 0
            while (isActive) {
                val frame = runCatching { fetchSnapshot(host, controlKey) }.getOrNull() ?: break
                latestBitmap = frame
                snapshotFrames++
                onState(VisionUiState(VisionMode.SNAPSHOT, "Snapshot fallback :80"))
                delay(180)
                if (streamReady && snapshotFrames >= 20) break
            }

            if (!isActive) break
            onState(VisionUiState(VisionMode.OFFLINE, "Stream and snapshot paths unavailable"))
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(4000L)
        }
    }

    Box(modifier.background(PilotBg), contentAlignment = Alignment.Center) {
        latestBitmap?.let { bitmap ->
            Image(
                bitmap.asImageBitmap(),
                contentDescription = "ESP32 camera",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

private suspend fun streamMjpeg(host: String, controlKey: String, onFrame: suspend (Bitmap) -> Unit) {
    withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = URL("http://$host:81/stream").openConnection() as HttpURLConnection
            connection.connectTimeout = 1800
            connection.readTimeout = 7000
            connection.useCaches = false
            if (controlKey.isNotBlank()) connection.setRequestProperty("X-ESP32-Control-Key", controlKey)
            connection.connect()
            if (connection.responseCode !in 200..299) throw IOException("Camera HTTP ${connection.responseCode}")

            BufferedInputStream(connection.inputStream, 64 * 1024).use { input ->
                while (true) {
                    val frame = MjpegParser.readFrame(input) ?: throw IOException("MJPEG stream ended")
                    onFrame(frame)
                }
            }
        } finally {
            connection?.disconnect()
        }
    }
}

private suspend fun fetchSnapshot(host: String, controlKey: String): Bitmap = withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    try {
        connection = URL("http://$host/capture").openConnection() as HttpURLConnection
        connection.connectTimeout = 1400
        connection.readTimeout = 2200
        connection.useCaches = false
        if (controlKey.isNotBlank()) connection.setRequestProperty("X-ESP32-Control-Key", controlKey)
        connection.connect()
        if (connection.responseCode !in 200..299) throw IOException("Snapshot HTTP ${connection.responseCode}")
        BitmapFactory.decodeStream(connection.inputStream) ?: throw IOException("Invalid camera JPEG")
    } finally {
        connection?.disconnect()
    }
}

private fun normalizeHost(ip: String): String =
    ip.trim()
        .removePrefix("http://")
        .removePrefix("https://")
        .substringBefore('/')
        .substringBefore(':')
