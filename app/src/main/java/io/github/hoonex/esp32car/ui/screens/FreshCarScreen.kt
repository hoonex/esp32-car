package io.github.hoonex.esp32car.ui.screens

import android.bluetooth.BluetoothDevice
import android.graphics.Bitmap
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hoonex.esp32car.bluetooth.ConnectionState
import io.github.hoonex.esp32car.network.MjpegParser
import io.github.hoonex.esp32car.protocol.RcProtocol
import io.github.hoonex.esp32car.viewmodel.FirmwareUpdateUiState
import io.github.hoonex.esp32car.viewmodel.RcViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.roundToInt

private val Bg = Color(0xFF080A0D)
private val Panel = Color(0xE8101419)
private val PanelSolid = Color(0xFF101419)
private val Line = Color(0xFF29313A)
private val TextMain = Color(0xFFF3F5F7)
private val TextMuted = Color(0xFF8D98A3)
private val Mint = Color(0xFF66D7B4)
private val Blue = Color(0xFF65C9F3)
private val Amber = Color(0xFFE8B96E)
private val Red = Color(0xFFEC6370)

enum class FreshDriveMode(val label: String) {
    RC("RC"),
    TANK("TANK"),
    PAD("PAD")
}

@Composable
fun FreshCarScreen(viewModel: RcViewModel) {
    val connection by viewModel.bluetooth.connectionState.collectAsStateWithLifecycle()
    if (connection != ConnectionState.CONNECTED) {
        FreshConnectScreen(viewModel)
    } else {
        FreshDriveScreen(viewModel)
    }
}

@Composable
private fun FreshConnectScreen(viewModel: RcViewModel) {
    val paired = remember { viewModel.pairedDevices() }
    val discovered by viewModel.bluetooth.discoveredDevices.collectAsStateWithLifecycle()
    val discovering by viewModel.bluetooth.isDiscovering.collectAsStateWithLifecycle()
    val error by viewModel.bluetooth.lastError.collectAsStateWithLifecycle()
    val connection by viewModel.bluetooth.connectionState.collectAsStateWithLifecycle()

    val devices = remember(paired, discovered) {
        (paired + discovered)
            .distinctBy { runCatching { it.address }.getOrNull() }
            .sortedByDescending { runCatching { it.name }.getOrNull() == RcProtocol.DEVICE_NAME }
    }

    Box(Modifier.fillMaxSize().background(Bg).padding(24.dp)) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Column(
                modifier = Modifier.weight(0.72f).fillMaxHeight(),
                verticalArrangement = Arrangement.Center
            ) {
                Text("ESP32 CAR", color = TextMain, fontSize = 31.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(8.dp))
                Text("RC controller", color = Mint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(22.dp))
                Text(
                    "Bluetooth로 주행하고 Wi-Fi로 카메라와 펌웨어 업데이트를 사용합니다.",
                    color = TextMuted,
                    fontSize = 11.sp,
                    lineHeight = 17.sp
                )
            }

            Surface(
                modifier = Modifier.weight(1.45f).fillMaxHeight(),
                color = PanelSolid,
                shape = RoundedCornerShape(26.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Line)
            ) {
                Column(Modifier.fillMaxSize().padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Connect", color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text("ESP32_CAM_RC", color = TextMuted, fontSize = 10.sp)
                        }
                        Button(
                            onClick = { if (discovering) viewModel.stopBluetoothScan() else viewModel.scanBluetooth() },
                            colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Color(0xFF071016))
                        ) {
                            if (discovering) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color(0xFF071016))
                                Spacer(Modifier.width(7.dp))
                            } else {
                                Icon(Icons.Default.Refresh, null, Modifier.size(17.dp))
                                Spacer(Modifier.width(7.dp))
                            }
                            Text(if (discovering) "검색 중" else "검색", fontWeight = FontWeight.Bold)
                        }
                    }

                    if (!error.isNullOrBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(error.orEmpty(), color = Red, fontSize = 9.sp)
                    }

                    Spacer(Modifier.height(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        devices.take(4).forEach { device ->
                            DeviceRow(
                                device = device,
                                busy = connection == ConnectionState.CONNECTING,
                                onConnect = { viewModel.pairAndConnect(device) }
                            )
                        }
                        if (devices.isEmpty()) {
                            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                                Text("ESP32_CAM_RC를 자동으로 찾는 중입니다.", color = TextMuted, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(device: BluetoothDevice, busy: Boolean, onConnect: () -> Unit) {
    val name = runCatching { device.name }.getOrNull().orEmpty().ifBlank { "Bluetooth device" }
    val address = runCatching { device.address }.getOrNull().orEmpty()
    val target = name.equals(RcProtocol.DEVICE_NAME, ignoreCase = true)
    Surface(
        color = if (target) Mint.copy(alpha = 0.08f) else Color(0xFF141A20),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (target) Mint.copy(alpha = 0.35f) else Line)
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = if (target) Mint.copy(alpha = 0.14f) else Color(0xFF202832)) {
                Icon(Icons.Default.Bluetooth, null, Modifier.padding(10.dp).size(18.dp), tint = if (target) Mint else TextMuted)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(address, color = TextMuted, fontSize = 8.sp)
            }
            Button(
                enabled = !busy,
                onClick = onConnect,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (target) Blue else Color(0xFF24303A),
                    contentColor = if (target) Color(0xFF071016) else TextMain
                )
            ) {
                Text(if (busy) "..." else "CONNECT", fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun FreshDriveScreen(viewModel: RcViewModel) {
    val speed by viewModel.speed.collectAsStateWithLifecycle()
    val light by viewModel.light.collectAsStateWithLifecycle()
    val btStatus by viewModel.bluetooth.btStatusResponse.collectAsStateWithLifecycle()
    val wifiStatus by viewModel.wifiStatus.collectAsStateWithLifecycle()
    val wifiError by viewModel.wifiError.collectAsStateWithLifecycle()
    val firmware by viewModel.firmwareUpdate.collectAsStateWithLifecycle()

    var mode by remember { mutableStateOf(FreshDriveMode.RC) }
    var throttle by remember { mutableFloatStateOf(0f) }
    var steering by remember { mutableFloatStateOf(0f) }
    var tankLeft by remember { mutableFloatStateOf(0f) }
    var tankRight by remember { mutableFloatStateOf(0f) }
    var cameraStreaming by remember { mutableStateOf(false) }
    var wifiDialog by remember { mutableStateOf(false) }
    var updateDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val reportedIp = wifiStatus?.optString("ip")
        ?.trim()
        ?.takeIf { it.isNotBlank() && it != "0.0.0.0" }
    val btIp = btStatus?.optString("ip")
        ?.trim()
        ?.takeIf { it.isNotBlank() && it != "0.0.0.0" }
    val ip = reportedIp ?: btIp ?: viewModel.settings.ipAddress.trim()
    val fw = btStatus?.optString("fw")?.takeIf { it.isNotBlank() }
        ?: wifiStatus?.optString("fw")?.takeIf { it.isNotBlank() }
        ?: viewModel.settings.lastFirmwareVersion.ifBlank { "—" }
    val controlKey = viewModel.settings.otaKey.trim()

    // Never advertise VISION ON from a cached IP. The status endpoint proves HTTP reachability,
    // while the first decoded MJPEG frame proves the camera path itself is genuinely usable.
    val httpConfirmed = wifiStatus != null && wifiError.isNullOrBlank()
    val otaAdvertised = wifiStatus?.optBoolean("ota", true) ?: false
    val httpAdvertised = wifiStatus?.optBoolean("http_ready", true) ?: false
    val otaHttpReady = ip.isNotBlank() && controlKey.isNotBlank() && httpConfirmed && otaAdvertised && httpAdvertised
    val cameraAttemptReady = ip.isNotBlank() && controlKey.isNotBlank()

    LaunchedEffect(Unit) {
        viewModel.updateSpeed(255f)
        viewModel.refreshBluetoothStatus()
    }

    LaunchedEffect(ip, controlKey) {
        if (ip.isNotBlank() && controlKey.isNotBlank()) viewModel.refreshWifiStatus()
    }

    LaunchedEffect(mode, speed) {
        var wasMoving = false
        while (isActive) {
            val moving = when (mode) {
                FreshDriveMode.TANK -> abs(tankLeft) > 0.02f || abs(tankRight) > 0.02f
                else -> abs(throttle) > 0.02f || abs(steering) > 0.02f
            }
            if (moving) {
                when (mode) {
                    FreshDriveMode.RC, FreshDriveMode.PAD -> viewModel.driveVector(throttle, steering)
                    FreshDriveMode.TANK -> {
                        val max = speed.roundToInt().coerceIn(50, 255)
                        viewModel.bluetooth.sendCommand(
                            RcProtocol.motorMix(
                                (tankLeft.coerceIn(-1f, 1f) * max).roundToInt(),
                                (tankRight.coerceIn(-1f, 1f) * max).roundToInt()
                            )
                        )
                    }
                }
                wasMoving = true
            } else if (wasMoving) {
                viewModel.emergencyStop()
                wasMoving = false
            }
            delay(45)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Bg)) {
        val compact = maxWidth < 720.dp
        val headerHeight = if (compact) 58.dp else 66.dp
        val touchW = if (compact) 150.dp else 174.dp
        val touchH = if (compact) 172.dp else 196.dp
        val horizontalTouchW = if (compact) 176.dp else 204.dp

        CameraCanvas(
            ip = ip,
            controlKey = controlKey,
            enabled = cameraAttemptReady,
            onStreamingChanged = { cameraStreaming = it },
            modifier = Modifier
                .fillMaxSize()
                .padding(top = headerHeight)
        )

        TopBar(
            fw = fw,
            wifiOnline = cameraStreaming,
            lightOn = light > 0f,
            onLight = { viewModel.updateLight(if (light > 0f) 0f else 255f) },
            onWifi = { wifiDialog = true },
            onUpdate = { updateDialog = true },
            onStop = {
                throttle = 0f
                steering = 0f
                tankLeft = 0f
                tankRight = 0f
                viewModel.emergencyStop()
            },
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().height(headerHeight)
        )

        if (!cameraStreaming) {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = headerHeight + 14.dp),
                color = Panel,
                shape = RoundedCornerShape(999.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Line)
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(7.dp).background(if (httpConfirmed) Amber else Red, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            ip.isBlank() -> "Wi-Fi vision not connected · Bluetooth control active"
                            controlKey.isBlank() -> "Waiting for Bluetooth security key"
                            httpConfirmed -> "Wi-Fi online · camera reconnecting"
                            else -> "Checking ESP32 Wi-Fi · Bluetooth control active"
                        },
                        color = TextMuted,
                        fontSize = 8.sp
                    )
                }
            }
        }

        when (mode) {
            FreshDriveMode.RC -> {
                VerticalControl(
                    label = "THROTTLE",
                    value = throttle,
                    onValue = { throttle = it },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 6.dp, bottom = 4.dp)
                        .width(touchW)
                        .height(touchH)
                )
                HorizontalControl(
                    label = "STEER",
                    value = steering,
                    onValue = { steering = it },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 6.dp, bottom = 4.dp)
                        .width(horizontalTouchW)
                        .height(touchH)
                )
            }
            FreshDriveMode.TANK -> {
                VerticalControl(
                    label = "LEFT",
                    value = tankLeft,
                    onValue = { tankLeft = it },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 6.dp, bottom = 4.dp)
                        .width(touchW)
                        .height(touchH)
                )
                VerticalControl(
                    label = "RIGHT",
                    value = tankRight,
                    onValue = { tankRight = it },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 6.dp, bottom = 4.dp)
                        .width(touchW)
                        .height(touchH)
                )
            }
            FreshDriveMode.PAD -> {
                HorizontalControl(
                    label = "STEER",
                    value = steering,
                    onValue = { steering = it },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 6.dp, bottom = 4.dp)
                        .width(horizontalTouchW)
                        .height(touchH)
                )
                VerticalControl(
                    label = "THROTTLE",
                    value = throttle,
                    onValue = { throttle = it },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 6.dp, bottom = 4.dp)
                        .width(touchW)
                        .height(touchH)
                )
            }
        }

        BottomDock(
            mode = mode,
            speed = speed,
            onMode = {
                throttle = 0f
                steering = 0f
                tankLeft = 0f
                tankRight = 0f
                viewModel.emergencyStop()
                mode = it
            },
            onSpeed = viewModel::updateSpeed,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
        )
    }

    if (wifiDialog) {
        WifiDialog(
            currentSsid = wifiStatus?.optString("ssid").orEmpty().ifBlank { btStatus?.optString("ssid").orEmpty() },
            currentStatus = when {
                cameraStreaming -> "카메라 스트림 연결됨"
                httpConfirmed -> "ESP32 Wi-Fi/HTTP 연결됨 · 카메라 재연결 중"
                wifiError != null -> wifiError
                ip.isNotBlank() -> "ESP32 IP $ip 확인 중"
                else -> null
            },
            onDismiss = { wifiDialog = false },
            onConnect = { ssid, pass ->
                scope.launch {
                    viewModel.provisionWifi(ssid, pass)
                    delay(300)
                    viewModel.switchEsp32ToWifi()
                }
                wifiDialog = false
            }
        )
    }

    if (updateDialog) {
        FirmwareDialog(
            installed = fw,
            bundled = firmware.bundledVersion,
            state = firmware,
            canUpdate = otaHttpReady,
            wifiDetail = when {
                otaHttpReady -> "HTTP OTA 준비됨 · $ip"
                ip.isBlank() -> "ESP32 Wi-Fi IP가 없습니다."
                controlKey.isBlank() -> "Bluetooth STATUS에서 OTA 키를 받는 중입니다."
                wifiError != null -> "HTTP 확인 실패: $wifiError"
                else -> "ESP32 HTTP OTA 상태를 확인 중입니다."
            },
            onDismiss = { updateDialog = false },
            onUpdate = { viewModel.updateFirmwareFromBundled() }
        )
    }
}

@Composable
private fun TopBar(
    fw: String,
    wifiOnline: Boolean,
    lightOn: Boolean,
    onLight: () -> Unit,
    onWifi: () -> Unit,
    onUpdate: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Color(0xF50B0E12),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, Line)
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("ESP32 CAR", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Black)
                Text("Bluetooth control", color = Mint, fontSize = 7.sp, fontWeight = FontWeight.Bold)
            }
            StatusPill(Icons.Default.Bluetooth, "BT", "ON", Mint)
            Spacer(Modifier.width(7.dp))
            StatusPill(
                Icons.Default.Wifi,
                "VISION",
                if (wifiOnline) "ON" else "OFF",
                if (wifiOnline) Blue else TextMuted
            )
            Spacer(Modifier.width(7.dp))
            Surface(
                color = Color(0xFF151A20),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Line)
            ) {
                Column(
                    Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("FW", color = TextMuted, fontSize = 5.sp)
                    Text(fw, color = TextMain, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(10.dp))
            HeaderAction(Icons.Default.FlashlightOn, if (lightOn) Mint else TextMuted, onLight)
            HeaderAction(Icons.Default.Wifi, Blue, onWifi)
            HeaderAction(Icons.Default.Download, TextMain, onUpdate)
            Surface(onClick = onStop, color = Red, shape = RoundedCornerShape(13.dp)) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Stop, null, Modifier.size(15.dp), tint = Color.White)
                    Spacer(Modifier.width(5.dp))
                    Text("STOP", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

@Composable
private fun StatusPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
    tint: Color
) {
    Surface(
        color = Color(0xFF151A20),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Line)
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, Modifier.size(14.dp), tint = tint)
            Spacer(Modifier.width(6.dp))
            Column {
                Text(title, color = TextMuted, fontSize = 5.sp)
                Text(value, color = tint, fontSize = 7.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun HeaderAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color(0xFF151A20),
        shape = RoundedCornerShape(13.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Line)
    ) {
        Icon(icon, null, Modifier.padding(9.dp).size(16.dp), tint = tint)
    }
    Spacer(Modifier.width(6.dp))
}

@Composable
private fun BottomDock(
    mode: FreshDriveMode,
    speed: Float,
    onMode: (FreshDriveMode) -> Unit,
    onSpeed: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Panel,
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Line)
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            FreshDriveMode.entries.forEach { item ->
                Surface(
                    onClick = { onMode(item) },
                    color = if (item == mode) Blue.copy(alpha = 0.17f) else Color.Transparent,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        item.label,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        color = if (item == mode) Blue else TextMuted,
                        fontSize = 7.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.width(5.dp))
            Text(
                "${((speed / 255f) * 100).roundToInt()}%",
                color = TextMain,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold
            )
            Slider(
                value = speed.coerceIn(50f, 255f),
                onValueChange = onSpeed,
                valueRange = 50f..255f,
                modifier = Modifier.width(118.dp)
            )
        }
    }
}

@Composable
private fun VerticalControl(
    label: String,
    value: Float,
    onValue: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    // The outer transparent box is the hit target. The visible Surface is deliberately inset so
    // the controller occupies less of the camera view while touch acquisition gets easier.
    Box(
        modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { offset ->
                    onValue((1f - (offset.y / size.height) * 2f).coerceIn(-1f, 1f))
                },
                onDragEnd = { onValue(0f) },
                onDragCancel = { onValue(0f) }
            ) { change, _ ->
                change.consume()
                onValue((1f - (change.position.y / size.height) * 2f).coerceIn(-1f, 1f))
            }
        }
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp),
            color = Panel,
            shape = RoundedCornerShape(24.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Line)
        ) {
            Box(Modifier.fillMaxSize()) {
                Text(
                    label,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
                    color = TextMuted,
                    fontSize = 6.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Canvas(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 27.dp)) {
                    val cx = size.width / 2f
                    val outerRadius = 14.dp.toPx()
                    val coreRadius = 4.dp.toPx()
                    drawLine(Line, Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 2f, cap = StrokeCap.Round)
                    drawLine(
                        Line.copy(alpha = 0.5f),
                        Offset(cx - 18.dp.toPx(), size.height / 2f),
                        Offset(cx + 18.dp.toPx(), size.height / 2f),
                        strokeWidth = 1f
                    )
                    val y = ((1f - value) / 2f) * size.height
                    drawCircle(Mint.copy(alpha = 0.20f), outerRadius, Offset(cx, y))
                    drawCircle(Mint, coreRadius, Offset(cx, y))
                }
            }
        }
    }
}

@Composable
private fun HorizontalControl(
    label: String,
    value: Float,
    onValue: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { offset ->
                    onValue(((offset.x / size.width) * 2f - 1f).coerceIn(-1f, 1f))
                },
                onDragEnd = { onValue(0f) },
                onDragCancel = { onValue(0f) }
            ) { change, _ ->
                change.consume()
                onValue(((change.position.x / size.width) * 2f - 1f).coerceIn(-1f, 1f))
            }
        }
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 20.dp),
            color = Panel,
            shape = RoundedCornerShape(24.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Line)
        ) {
            Box(Modifier.fillMaxSize()) {
                Text(
                    label,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
                    color = TextMuted,
                    fontSize = 6.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Canvas(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 30.dp)) {
                    val cy = size.height / 2f
                    val outerRadius = 14.dp.toPx()
                    val coreRadius = 4.dp.toPx()
                    drawLine(Line, Offset(0f, cy), Offset(size.width, cy), strokeWidth = 2f, cap = StrokeCap.Round)
                    drawLine(
                        Line.copy(alpha = 0.5f),
                        Offset(size.width / 2f, cy - 18.dp.toPx()),
                        Offset(size.width / 2f, cy + 18.dp.toPx()),
                        strokeWidth = 1f
                    )
                    val x = ((value + 1f) / 2f) * size.width
                    drawCircle(Blue.copy(alpha = 0.20f), outerRadius, Offset(x, cy))
                    drawCircle(Blue, coreRadius, Offset(x, cy))
                }
            }
        }
    }
}

@Composable
private fun CameraCanvas(
    ip: String,
    controlKey: String,
    enabled: Boolean,
    onStreamingChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }

    LaunchedEffect(ip, controlKey, enabled, retry) {
        frame = null
        failed = false
        onStreamingChanged(false)
        if (!enabled || ip.isBlank() || controlKey.isBlank()) return@LaunchedEffect

        try {
            withContext(Dispatchers.IO) {
                val host = ip.removePrefix("http://")
                    .removePrefix("https://")
                    .substringBefore('/')
                    .substringBefore(':')
                var announcedStreaming = false
                while (isActive) {
                    var connection: HttpURLConnection? = null
                    try {
                        connection = URL("http://$host:81/stream").openConnection() as HttpURLConnection
                        connection.connectTimeout = 2_500
                        connection.readTimeout = 6_000
                        connection.useCaches = false
                        // Firmware 3.3.1 protects its MJPEG endpoint with this per-device key.
                        // v4 ignores the extra header, so one request path is compatible with both.
                        connection.setRequestProperty("X-ESP32-Control-Key", controlKey)
                        connection.connect()
                        if (connection.responseCode !in 200..299) {
                            throw IOException("Camera HTTP ${connection.responseCode}")
                        }
                        failed = false
                        BufferedInputStream(connection.inputStream, 64 * 1024).use { input ->
                            while (isActive) {
                                val next = MjpegParser.readFrame(input)
                                    ?: throw IOException("Camera stream ended")
                                frame = next
                                if (!announcedStreaming) {
                                    announcedStreaming = true
                                    onStreamingChanged(true)
                                }
                            }
                        }
                    } catch (_: Throwable) {
                        if (!isActive) break
                        if (announcedStreaming) {
                            announcedStreaming = false
                            onStreamingChanged(false)
                        }
                        failed = true
                        delay(1_200)
                    } finally {
                        connection?.disconnect()
                    }
                }
            }
        } finally {
            onStreamingChanged(false)
        }
    }

    Box(modifier.background(Color.Black)) {
        frame?.let {
            Image(
                it.asImageBitmap(),
                "ESP32 camera",
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        if (failed) {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp),
                onClick = { retry += 1 },
                color = Panel,
                shape = RoundedCornerShape(999.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Line)
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(13.dp), tint = Amber)
                    Spacer(Modifier.width(6.dp))
                    Text("Camera reconnecting automatically", color = TextMuted, fontSize = 7.sp)
                }
            }
        }
    }
}

@Composable
private fun WifiDialog(
    currentSsid: String,
    currentStatus: String?,
    onDismiss: () -> Unit,
    onConnect: (String, String) -> Unit
) {
    var ssid by remember { mutableStateOf(currentSsid) }
    var password by remember { mutableStateOf("") }
    var validationError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelSolid,
        title = { Text("Wi-Fi vision", color = TextMain, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "폰과 ESP32가 같은 2.4GHz Wi-Fi에 연결되면 카메라와 OTA를 사용할 수 있습니다.",
                    color = TextMuted,
                    fontSize = 10.sp
                )
                currentStatus?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = if (it.contains("실패")) Red else Blue, fontSize = 8.sp)
                }
                OutlinedTextField(
                    value = ssid,
                    onValueChange = {
                        ssid = it
                        validationError = null
                    },
                    label = { Text("SSID") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        validationError = null
                    },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                validationError?.let { Text(it, color = Red, fontSize = 8.sp) }
                Text(
                    "저장 후 ESP32가 연결을 시도하는 동안 Bluetooth 제어는 유지됩니다.",
                    color = TextMuted,
                    fontSize = 8.sp
                )
            }
        },
        confirmButton = {
            Button(
                enabled = ssid.isNotBlank(),
                onClick = {
                    val cleanSsid = ssid.trim()
                    when {
                        cleanSsid.isBlank() -> validationError = "SSID를 입력하세요."
                        cleanSsid.contains(',') -> validationError = "현재 ESP32 프로토콜에서는 SSID에 쉼표를 사용할 수 없습니다."
                        cleanSsid.contains('\n') || cleanSsid.contains('\r') -> validationError = "SSID에 줄바꿈을 사용할 수 없습니다."
                        password.contains('\n') || password.contains('\r') -> validationError = "비밀번호에 줄바꿈을 사용할 수 없습니다."
                        else -> onConnect(cleanSsid, password)
                    }
                }
            ) {
                Text("저장하고 연결")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

@Composable
private fun FirmwareDialog(
    installed: String,
    bundled: String,
    state: FirmwareUpdateUiState,
    canUpdate: Boolean,
    wifiDetail: String,
    onDismiss: () -> Unit,
    onUpdate: () -> Unit
) {
    val busy = state.stage == FirmwareUpdateUiState.Stage.PREPARING ||
        state.stage == FirmwareUpdateUiState.Stage.UPLOADING ||
        state.stage == FirmwareUpdateUiState.Stage.REBOOTING
    val migration = installed.startsWith("3.") && bundled.startsWith("4.")

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = PanelSolid,
        title = { Text("Firmware", color = TextMain, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text("Installed", color = TextMuted, fontSize = 9.sp, modifier = Modifier.weight(1f))
                    Text(installed, color = TextMain, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                Row(Modifier.fillMaxWidth()) {
                    Text("Bundled", color = TextMuted, fontSize = 9.sp, modifier = Modifier.weight(1f))
                    Text(bundled, color = Mint, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                Text(wifiDetail, color = if (canUpdate) Blue else Amber, fontSize = 8.sp)
                if (migration) {
                    Text(
                        "3.x → 4.x 전환: HTTP OTA 전송 후 Wi-Fi와 Bluetooth STATUS를 둘 다 확인해 실제 새 펌웨어 부팅까지 검증합니다.",
                        color = Amber,
                        fontSize = 8.sp
                    )
                }
                if (state.stage != FirmwareUpdateUiState.Stage.IDLE) {
                    Text(
                        state.message,
                        color = if (state.stage == FirmwareUpdateUiState.Stage.ERROR) Red else TextMuted,
                        fontSize = 9.sp
                    )
                    if (busy) {
                        LinearProgressIndicator(
                            progress = { state.progress.coerceIn(0, 100) / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = canUpdate && !busy, onClick = onUpdate) {
                Text(if (busy) "진행 중" else if (migration) "v4로 안전 업데이트" else "무선 업데이트")
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Icon(Icons.Default.Close, null, Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("닫기")
            }
        }
    )
}
