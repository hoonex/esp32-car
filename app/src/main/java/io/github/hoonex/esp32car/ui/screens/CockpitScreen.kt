package io.github.hoonex.esp32car.ui.screens

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hoonex.esp32car.bluetooth.ConnectionState
import io.github.hoonex.esp32car.model.TransportMode
import io.github.hoonex.esp32car.network.MjpegParser
import io.github.hoonex.esp32car.viewmodel.FirmwareUpdateUiState
import io.github.hoonex.esp32car.viewmodel.RcViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.hypot

private enum class CockpitSettingsTab(val label: String) {
    CONTROL("Control"), CAMERA("Camera"), LINK("Link"), HARDWARE("Hardware"), UPDATE("Update")
}

@Composable
fun CockpitScreen(viewModel: RcViewModel) {
    val transport by viewModel.transportMode.collectAsStateWithLifecycle()
    val speed by viewModel.speed.collectAsStateWithLifecycle()
    val light by viewModel.light.collectAsStateWithLifecycle()
    val wifiStatus by viewModel.wifiStatus.collectAsStateWithLifecycle()
    val btState by viewModel.bluetooth.connectionState.collectAsStateWithLifecycle()
    val deviceName by viewModel.bluetooth.connectedDeviceName.collectAsStateWithLifecycle()

    var settingsOpen by remember { mutableStateOf(false) }
    var trackingOpen by remember { mutableStateOf(false) }
    var cameraRetry by remember { mutableIntStateOf(0) }
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(Unit) {
        viewModel.refreshWifiStatus()
        viewModel.refreshBluetoothStatus()
    }

    LaunchedEffect(settingsOpen, trackingOpen) {
        if (settingsOpen || trackingOpen) viewModel.emergencyStop()
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.emergencyStop() }
    }

    if (trackingOpen) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AITrackingScreen(viewModel.rcClient, viewModel.settings)
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).padding(14.dp),
                shape = CircleShape,
                color = Color(0xDD10151B),
                onClick = {
                    viewModel.emergencyStop()
                    trackingOpen = false
                }
            ) {
                Icon(Icons.Default.Close, null, Modifier.padding(12.dp), tint = Color.White)
            }
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF05070A))
    ) {
        CameraLayer(
            ip = viewModel.settings.ipAddress,
            retryKey = cameraRetry,
            modifier = Modifier.fillMaxSize()
        )

        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color(0xD9000000),
                            Color(0x33000000),
                            Color(0x66000000),
                            Color(0xD9000000)
                        )
                    )
                )
        )

        CockpitTopHud(
            modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 18.dp, vertical = 12.dp),
            transport = transport,
            btState = btState,
            deviceName = deviceName,
            ip = viewModel.settings.ipAddress,
            rssi = wifiStatus?.optInt("rssi", 0) ?: 0,
            fw = wifiStatus?.optString("fw").orEmpty().ifBlank { viewModel.settings.lastFirmwareVersion },
            profile = wifiStatus?.optString("profile").orEmpty().ifBlank { "AI THINKER · 2WD" },
            telemetry = viewModel.settings.showTelemetry
        )

        DriveStick(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 28.dp, bottom = 24.dp)
                .size(250.dp),
            deadzone = viewModel.settings.controlDeadzone,
            hapticsEnabled = viewModel.settings.haptics,
            onVector = viewModel::driveVector,
            onStop = viewModel::emergencyStop
        )

        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 20.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            QuickAction(
                icon = Icons.Default.Lightbulb,
                label = if (light > 0f) "LIGHT ${light.toInt()}" else "LIGHT",
                active = light > 0f,
                onClick = {
                    if (viewModel.settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    viewModel.updateLight(if (light > 0f) 0f else 220f)
                }
            )
            QuickAction(
                icon = Icons.Default.CameraAlt,
                label = "CAMERA",
                active = false,
                onClick = { cameraRetry += 1 }
            )
            QuickAction(
                icon = Icons.Default.SmartToy,
                label = "TRACK",
                active = false,
                onClick = {
                    viewModel.emergencyStop()
                    trackingOpen = true
                }
            )
            QuickAction(
                icon = Icons.Default.Settings,
                label = "SETTINGS",
                active = settingsOpen,
                onClick = {
                    viewModel.emergencyStop()
                    settingsOpen = true
                }
            )
            Button(
                onClick = {
                    if (viewModel.settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.emergencyStop()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD83A4A)),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.width(154.dp).height(58.dp)
            ) {
                Icon(Icons.Default.Stop, null)
                Spacer(Modifier.width(8.dp))
                Text("STOP", fontWeight = FontWeight.Black, fontSize = 16.sp)
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 18.dp),
            color = Color(0xCC0B0F14),
            shape = RoundedCornerShape(22.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
        ) {
            Row(
                Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("MAX ${speed.toInt()}", color = Color.White, fontWeight = FontWeight.Bold)
                Text("TRIM ${viewModel.settings.trim.toInt()}", color = Color(0xFFA7B1BC))
                Text("DEADZONE ${(viewModel.settings.controlDeadzone * 100).toInt()}%", color = Color(0xFFA7B1BC))
                Text("450ms FAILSAFE", color = Color(0xFF78E6AA), fontWeight = FontWeight.Bold)
            }
        }

        if (settingsOpen) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.34f)))
            CockpitSettingsPanel(
                modifier = Modifier.align(Alignment.CenterEnd),
                viewModel = viewModel,
                onClose = {
                    viewModel.emergencyStop()
                    settingsOpen = false
                }
            )
        }
    }
}

@Composable
private fun CockpitTopHud(
    modifier: Modifier,
    transport: TransportMode,
    btState: ConnectionState,
    deviceName: String?,
    ip: String,
    rssi: Int,
    fw: String,
    profile: String,
    telemetry: Boolean
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color(0xB80A0E13),
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text("ESP32 CAR", color = Color.White, fontWeight = FontWeight.Black, fontSize = 16.sp)
                Text(
                    profile,
                    color = Color(0xFF8A98A6),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            HudPill(
                text = if (btState == ConnectionState.CONNECTED) (deviceName ?: "BT") else "BT OFF",
                good = btState == ConnectionState.CONNECTED,
                icon = { Icon(Icons.Default.Bluetooth, null, Modifier.size(14.dp)) }
            )
            HudPill(
                text = if (ip.isBlank()) "NO WIFI" else ip,
                good = ip.isNotBlank(),
                icon = { Icon(Icons.Default.Wifi, null, Modifier.size(14.dp)) }
            )
            HudPill(
                text = if (transport == TransportMode.BLUETOOTH) "CONTROL · BT" else "CONTROL · WIFI",
                good = true,
                icon = null
            )
            if (telemetry) {
                if (rssi != 0) HudPill("${rssi} dBm", rssi > -78, null)
                if (fw.isNotBlank()) HudPill("FW $fw", true, null)
            }
        }
    }
}

@Composable
private fun HudPill(text: String, good: Boolean, icon: (@Composable () -> Unit)?) {
    Surface(
        color = if (good) Color(0x2216D98A) else Color(0x22FF5263),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (good) Color(0x664BDEA7) else Color(0x66FF5263)
        )
    ) {
        Row(
            Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            icon?.invoke()
            Text(text, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun QuickAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (active) Color(0xFF173A34) else Color(0xD9141920),
            contentColor = if (active) Color(0xFF7AF0BC) else Color.White
        ),
        modifier = Modifier.width(154.dp).height(46.dp)
    ) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DriveStick(
    modifier: Modifier,
    deadzone: Float,
    hapticsEnabled: Boolean,
    onVector: (Float, Float) -> Unit,
    onStop: () -> Unit
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var knob by remember { mutableStateOf(Offset.Zero) }
    var active by remember { mutableStateOf(false) }
    var throttle by remember { mutableFloatStateOf(0f) }
    var steering by remember { mutableFloatStateOf(0f) }
    val haptic = LocalHapticFeedback.current

    fun update(position: Offset) {
        if (size.width == 0 || size.height == 0) return
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = minOf(size.width, size.height) * 0.39f
        val dx = position.x - center.x
        val dy = position.y - center.y
        val len = hypot(dx, dy)
        val scale = if (len > radius && len > 0f) radius / len else 1f
        knob = Offset(dx * scale, dy * scale)
        steering = (knob.x / radius).coerceIn(-1f, 1f)
        throttle = (-knob.y / radius).coerceIn(-1f, 1f)
    }

    LaunchedEffect(active, throttle, steering) {
        if (!active) {
            onStop()
            return@LaunchedEffect
        }
        while (isActive && active) {
            onVector(throttle, steering)
            delay(80)
        }
    }

    Box(
        modifier = modifier
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        active = true
                        if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        update(it)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        update(change.position)
                    },
                    onDragEnd = {
                        active = false
                        knob = Offset.Zero
                        throttle = 0f
                        steering = 0f
                        if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onStop()
                    },
                    onDragCancel = {
                        active = false
                        knob = Offset.Zero
                        throttle = 0f
                        steering = 0f
                        onStop()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = minOf(size.width, size.height) * 0.39f
            drawCircle(Color(0xAA11171E), radius = r * 1.18f)
            drawCircle(
                Color.White.copy(alpha = 0.10f),
                radius = r * 1.18f,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
            )
            drawCircle(Color(0x2226DDAA), radius = r * deadzone.coerceIn(0.02f, 0.35f))
            drawLine(Color.White.copy(alpha = 0.12f), Offset(center.x - r, center.y), Offset(center.x + r, center.y), 1.dp.toPx())
            drawLine(Color.White.copy(alpha = 0.12f), Offset(center.x, center.y - r), Offset(center.x, center.y + r), 1.dp.toPx())
        }
        Surface(
            modifier = Modifier
                .size(78.dp)
                .graphicsLayer {
                    translationX = knob.x
                    translationY = knob.y
                },
            color = if (active) Color(0xFF6DE7B5) else Color(0xFF27313B),
            shape = CircleShape,
            shadowElevation = if (active) 16.dp else 4.dp
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "DRIVE",
                    color = if (active) Color(0xFF042117) else Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }
        Text(
            "${(throttle * 100).toInt()} / ${(steering * 100).toInt()}",
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
            color = Color(0xFF93A0AD),
            fontSize = 9.sp
        )
    }
}

@Composable
private fun CameraLayer(ip: String, retryKey: Int, modifier: Modifier = Modifier) {
    var latestBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(ip, retryKey) {
        latestBitmap = null
        error = null
        if (ip.isBlank()) {
            error = "No camera target"
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val host = ip.removePrefix("http://").removePrefix("https://").substringBefore('/').substringBefore(':')
                connection = URL("http://$host:81/stream").openConnection() as HttpURLConnection
                connection.connectTimeout = 3000
                connection.readTimeout = 5000
                connection.useCaches = false
                connection.connect()
                BufferedInputStream(connection.inputStream, 64 * 1024).use { input ->
                    while (isActive) {
                        MjpegParser.readFrame(input)?.let {
                            latestBitmap = it
                            error = null
                        }
                    }
                }
            } catch (t: Throwable) {
                if (isActive) error = t.message ?: "Camera unavailable"
            } finally {
                connection?.disconnect()
            }
        }
    }

    Box(modifier.background(Color(0xFF05070A)), contentAlignment = Alignment.Center) {
        latestBitmap?.let {
            Image(it.asImageBitmap(), "ESP32 camera", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } ?: Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.VideocamOff, null, Modifier.size(42.dp), tint = Color(0xFF5C6976))
            Text(error ?: "Connecting camera…", color = Color(0xFF7F8C99), fontSize = 12.sp)
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun CockpitSettingsPanel(
    modifier: Modifier,
    viewModel: RcViewModel,
    onClose: () -> Unit
) {
    var tab by remember { mutableStateOf(CockpitSettingsTab.CONTROL) }
    val fw by viewModel.firmwareUpdate.collectAsStateWithLifecycle()
    val wifiStatus by viewModel.wifiStatus.collectAsStateWithLifecycle()
    val wifiError by viewModel.wifiError.collectAsStateWithLifecycle()
    val btState by viewModel.bluetooth.connectionState.collectAsStateWithLifecycle()
    val speed by viewModel.speed.collectAsStateWithLifecycle()
    val trim by viewModel.trim.collectAsStateWithLifecycle()
    val light by viewModel.light.collectAsStateWithLifecycle()
    val transport by viewModel.transportMode.collectAsStateWithLifecycle()

    Surface(
        modifier = modifier.fillMaxHeight().fillMaxWidth(0.47f).widthIn(min = 430.dp),
        color = Color(0xFF0A0E13),
        shadowElevation = 24.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("SETTINGS", color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp)
                    Text("AI Thinker ESP32-CAM · 2WD L298N", color = Color(0xFF7D8995), fontSize = 11.sp)
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CockpitSettingsTab.entries.forEach { item ->
                    AssistChip(
                        onClick = {
                            viewModel.emergencyStop()
                            tab = item
                        },
                        label = { Text(item.label, fontSize = 10.sp) },
                        leadingIcon = if (tab == item) ({ Icon(Icons.Default.Tune, null, Modifier.size(14.dp)) }) else null
                    )
                }
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                when (tab) {
                    CockpitSettingsTab.CONTROL -> ControlSettings(viewModel, speed, trim)
                    CockpitSettingsTab.CAMERA -> CameraSettings(viewModel, light)
                    CockpitSettingsTab.LINK -> LinkSettings(viewModel, transport, btState, wifiStatus?.optString("ssid").orEmpty(), wifiError)
                    CockpitSettingsTab.HARDWARE -> HardwareSettings(viewModel, wifiStatus)
                    CockpitSettingsTab.UPDATE -> UpdateSettings(viewModel, fw, wifiStatus)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun SettingHeader(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        subtitle?.let { Text(it, color = Color(0xFF7D8995), fontSize = 11.sp) }
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Color(0xFF7D8995), fontSize = 10.sp)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun ControlSettings(viewModel: RcViewModel, speed: Float, trim: Float) {
    var deadzone by remember { mutableFloatStateOf(viewModel.settings.controlDeadzone) }
    var gain by remember { mutableFloatStateOf(viewModel.settings.steeringGain) }
    var expo by remember { mutableFloatStateOf(viewModel.settings.steeringExpo) }
    var invertThrottle by remember { mutableStateOf(viewModel.settings.invertThrottle) }
    var invertSteering by remember { mutableStateOf(viewModel.settings.invertSteering) }
    var haptics by remember { mutableStateOf(viewModel.settings.haptics) }
    var telemetry by remember { mutableStateOf(viewModel.settings.showTelemetry) }

    SettingHeader("Drive tuning", "조이스틱 입력은 80ms마다 전송되고 ESP32에서 450ms dead-man failsafe가 작동합니다.")
    Text("Max motor output · ${speed.toInt()}", color = Color.White)
    Slider(value = speed, onValueChange = viewModel::updateSpeed, valueRange = 50f..255f)
    Text("Steering trim · ${trim.toInt()}", color = Color.White)
    Slider(value = trim, onValueChange = viewModel::updateTrim, valueRange = -50f..50f)
    Text("Deadzone · ${(deadzone * 100).toInt()}%", color = Color.White)
    Slider(value = deadzone, onValueChange = {
        deadzone = it
        viewModel.settings.controlDeadzone = it
    }, valueRange = 0.02f..0.35f)
    Text("Steering gain · ${"%.2f".format(gain)}x", color = Color.White)
    Slider(value = gain, onValueChange = {
        gain = it
        viewModel.settings.steeringGain = it
    }, valueRange = 0.5f..1.8f)
    Text("Steering expo · ${"%.2f".format(expo)}", color = Color.White)
    Slider(value = expo, onValueChange = {
        expo = it
        viewModel.settings.steeringExpo = it
    }, valueRange = 1f..2.5f)
    SettingSwitch("Invert throttle", "앞/뒤 입력 반전", invertThrottle) {
        invertThrottle = it
        viewModel.settings.invertThrottle = it
    }
    SettingSwitch("Invert steering", "좌/우 입력 반전", invertSteering) {
        invertSteering = it
        viewModel.settings.invertSteering = it
    }
    SettingSwitch("Haptics", "조작 시작/STOP에 진동 피드백", haptics) {
        haptics = it
        viewModel.settings.haptics = it
    }
    SettingSwitch("Telemetry HUD", "RSSI와 firmware 정보를 상단 HUD에 표시", telemetry) {
        telemetry = it
        viewModel.settings.showTelemetry = it
    }
}

@Composable
private fun CameraSettings(viewModel: RcViewModel, light: Float) {
    var resolution by remember { mutableStateOf(viewModel.settings.streamResolution) }
    var quality by remember { mutableFloatStateOf(viewModel.settings.streamQuality) }
    var brightness by remember { mutableFloatStateOf(viewModel.settings.cameraBrightness) }
    var contrast by remember { mutableFloatStateOf(viewModel.settings.cameraContrast) }
    var saturation by remember { mutableFloatStateOf(viewModel.settings.cameraSaturation) }
    var mirror by remember { mutableStateOf(viewModel.settings.cameraMirror) }
    var flip by remember { mutableStateOf(viewModel.settings.cameraFlip) }

    SettingHeader("Live camera", "해상도를 올리면 영상 지연과 Wi-Fi 대역폭 사용량이 증가할 수 있습니다.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("QVGA", "VGA", "SVGA").forEach { item ->
            OutlinedButton(onClick = {
                resolution = item
                viewModel.settings.streamResolution = item
            }) { Text(item) }
        }
    }
    Text("Selected · $resolution", color = Color(0xFF8A98A6), fontSize = 11.sp)
    Text("JPEG quality · ${quality.toInt()}  (낮을수록 고화질)", color = Color.White)
    Slider(value = quality, onValueChange = {
        quality = it
        viewModel.settings.streamQuality = it
    }, valueRange = 4f..20f)
    Text("Brightness · ${brightness.toInt()}", color = Color.White)
    Slider(value = brightness, onValueChange = {
        brightness = it
        viewModel.settings.cameraBrightness = it
    }, valueRange = -2f..2f, steps = 3)
    Text("Contrast · ${contrast.toInt()}", color = Color.White)
    Slider(value = contrast, onValueChange = {
        contrast = it
        viewModel.settings.cameraContrast = it
    }, valueRange = -2f..2f, steps = 3)
    Text("Saturation · ${saturation.toInt()}", color = Color.White)
    Slider(value = saturation, onValueChange = {
        saturation = it
        viewModel.settings.cameraSaturation = it
    }, valueRange = -2f..2f, steps = 3)
    SettingSwitch("Horizontal mirror", "카메라 좌우 반전", mirror) {
        mirror = it
        viewModel.settings.cameraMirror = it
    }
    SettingSwitch("Vertical flip", "AI Thinker 보드 장착 방향 보정", flip) {
        flip = it
        viewModel.settings.cameraFlip = it
    }
    Text("Flash LED · ${light.toInt()}", color = Color.White)
    Slider(value = light, onValueChange = viewModel::updateLight, valueRange = 0f..255f)
    Button(onClick = viewModel::applyCameraConfig, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.CameraAlt, null)
        Spacer(Modifier.width(8.dp))
        Text("Apply camera settings")
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun LinkSettings(
    viewModel: RcViewModel,
    transport: TransportMode,
    btState: ConnectionState,
    connectedSsid: String,
    wifiError: String?
) {
    var ip by remember { mutableStateOf(viewModel.settings.ipAddress) }
    var ssid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val devices = remember(btState) { runCatching { viewModel.pairedDevices() }.getOrDefault(emptyList()) }

    SettingHeader("Control transport", "영상은 Wi-Fi, 조종은 Bluetooth를 쓰는 조합이 가장 안정적인 편입니다.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { viewModel.setTransportMode(TransportMode.BLUETOOTH) },
            colors = ButtonDefaults.buttonColors(containerColor = if (transport == TransportMode.BLUETOOTH) Color(0xFF2252D6) else Color(0xFF1B222A))
        ) { Icon(Icons.Default.Bluetooth, null); Spacer(Modifier.width(6.dp)); Text("Bluetooth") }
        Button(
            onClick = { viewModel.setTransportMode(TransportMode.WIFI) },
            colors = ButtonDefaults.buttonColors(containerColor = if (transport == TransportMode.WIFI) Color(0xFF157C5B) else Color(0xFF1B222A))
        ) { Icon(Icons.Default.Wifi, null); Spacer(Modifier.width(6.dp)); Text("Wi-Fi") }
    }

    SettingHeader("Bluetooth", "상태 · $btState")
    if (btState == ConnectionState.DISCONNECTED) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { viewModel.reconnectLast() }) { Text("Reconnect last") }
            FilledTonalButton(onClick = viewModel::refreshBluetoothStatus) { Text("STATUS") }
        }
        devices.forEach { device: BluetoothDevice ->
            OutlinedButton(onClick = { viewModel.connect(device) }, modifier = Modifier.fillMaxWidth()) {
                Text("${device.name ?: "ESP32"} · ${device.address}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    } else {
        OutlinedButton(onClick = viewModel::disconnectBluetooth) { Text("Disconnect Bluetooth") }
    }

    SettingHeader("Wi-Fi target", if (connectedSsid.isBlank()) null else "Connected SSID · $connectedSsid")
    OutlinedTextField(
        value = ip,
        onValueChange = {
            ip = it
            viewModel.updateIp(it)
        },
        label = { Text("ESP32 IP") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = viewModel::refreshWifiStatus) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Ping / STATUS") }
        FilledTonalButton(onClick = viewModel::switchEsp32ToWifi) { Text("Start Wi-Fi camera") }
    }
    wifiError?.let { Text(it, color = Color(0xFFFF7787), fontSize = 11.sp) }

    SettingHeader("Provision Wi-Fi", "Bluetooth로 SSID/비밀번호를 ESP32 NVS에 저장합니다.")
    OutlinedTextField(value = ssid, onValueChange = { ssid = it }, label = { Text("SSID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Button(onClick = { viewModel.provisionWifi(ssid, password) }, modifier = Modifier.fillMaxWidth()) { Text("Save Wi-Fi to ESP32") }
}

@Composable
private fun HardwareSettings(viewModel: RcViewModel, wifiStatus: org.json.JSONObject?) {
    var swap by remember { mutableStateOf(viewModel.settings.swapMotors) }
    var invertLeft by remember { mutableStateOf(viewModel.settings.invertLeftMotor) }
    var invertRight by remember { mutableStateOf(viewModel.settings.invertRightMotor) }

    SettingHeader("Hardware profile", "이번 빌드는 AI Thinker ESP32-CAM + 2WD + L298N용입니다.")
    Surface(color = Color(0xFF111820), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("BOARD  · AI Thinker ESP32-CAM", color = Color.White, fontWeight = FontWeight.Bold)
            Text("MOTOR  · L298N / four-input mode", color = Color(0xFFA3AFBA), fontSize = 11.sp)
            Text("LEFT   · GPIO 13 / 12", color = Color(0xFFA3AFBA), fontSize = 11.sp)
            Text("RIGHT  · GPIO 14 / 15", color = Color(0xFFA3AFBA), fontSize = 11.sp)
            Text("FLASH  · GPIO 4", color = Color(0xFFA3AFBA), fontSize = 11.sp)
            Text("PROFILE · ${wifiStatus?.optString("profile").orEmpty().ifBlank { "AI_THINKER_ESP32_CAM_2WD_L298N" }}", color = Color(0xFF76E4AE), fontSize = 10.sp)
        }
    }
    SettingSwitch("Swap left / right motors", "배선상 좌우 모터가 뒤바뀐 경우", swap) {
        swap = it
        viewModel.settings.swapMotors = it
    }
    SettingSwitch("Reverse left motor", "왼쪽 모터 극성이 반대인 경우", invertLeft) {
        invertLeft = it
        viewModel.settings.invertLeftMotor = it
    }
    SettingSwitch("Reverse right motor", "오른쪽 모터 극성이 반대인 경우", invertRight) {
        invertRight = it
        viewModel.settings.invertRightMotor = it
    }
    Button(onClick = {
        viewModel.emergencyStop()
        viewModel.applyMotorConfig()
    }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Build, null)
        Spacer(Modifier.width(8.dp))
        Text("Apply motor wiring profile")
    }
    Text(
        "모터 방향 설정을 적용할 때는 바퀴를 바닥에서 띄운 상태가 안전합니다. 적용 중에는 STOP을 먼저 전송합니다.",
        color = Color(0xFFFFC56E),
        fontSize = 10.sp
    )
}

@Composable
private fun UpdateSettings(viewModel: RcViewModel, fw: FirmwareUpdateUiState, wifiStatus: org.json.JSONObject?) {
    val installed = wifiStatus?.optString("fw").orEmpty().ifBlank { viewModel.settings.lastFirmwareVersion.ifBlank { "unknown" } }
    SettingHeader("Firmware", "앱 OTA는 application image를 갱신합니다. 최초 설치/복구는 factory image 또는 Windows flasher를 사용합니다.")
    Surface(color = Color(0xFF111820), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Installed · $installed", color = Color.White)
            Text("Bundled · ${fw.bundledVersion}", color = Color(0xFF76E4AE), fontWeight = FontWeight.Bold)
            Text("Board · AI Thinker ESP32-CAM / 4MB", color = Color(0xFFA3AFBA), fontSize = 11.sp)
        }
    }
    if (fw.stage != FirmwareUpdateUiState.Stage.IDLE) {
        LinearProgressIndicator(progress = { fw.progress.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth())
        if (fw.message.isNotBlank()) Text(fw.message, color = if (fw.stage == FirmwareUpdateUiState.Stage.ERROR) Color(0xFFFF7787) else Color(0xFFAAB5C0), fontSize = 11.sp)
    }
    Button(onClick = viewModel::updateFirmwareFromBundled, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.SystemUpdate, null)
        Spacer(Modifier.width(8.dp))
        Text("OTA update from this app")
    }
    OutlinedButton(onClick = viewModel::startRecoveryOtaAp, modifier = Modifier.fillMaxWidth()) {
        Text("Start recovery AP · ESP32-CAR-UPDATE")
    }
    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
    SettingHeader("Device actions")
    OutlinedButton(onClick = {
        viewModel.emergencyStop()
        viewModel.rebootDevice()
    }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.PowerSettingsNew, null)
        Spacer(Modifier.width(8.dp))
        Text("Reboot ESP32")
    }
    Text("Free heap · ${wifiStatus?.optInt("heap", 0) ?: 0} bytes", color = Color(0xFF8996A3), fontSize = 10.sp)
    Text("Uptime · ${wifiStatus?.optLong("uptime_ms", 0L) ?: 0L} ms", color = Color(0xFF8996A3), fontSize = 10.sp)
}
