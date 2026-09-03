package io.github.hoonex.esp32car.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.Wifi
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hoonex.esp32car.bluetooth.ConnectionState
import io.github.hoonex.esp32car.model.TransportMode
import io.github.hoonex.esp32car.network.MjpegParser
import io.github.hoonex.esp32car.update.AppUpdater
import io.github.hoonex.esp32car.viewmodel.FirmwareUpdateUiState
import io.github.hoonex.esp32car.viewmodel.RcViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.hypot

private val PanelBackground = Color(0xF20A0E13)
private val Good = Color(0xFF65E6AE)
private val Bad = Color(0xFFFF6E7C)

@SuppressLint("MissingPermission")
@Composable
fun ReliableCockpitScreen(viewModel: RcViewModel) {
    val btState by viewModel.bluetooth.connectionState.collectAsStateWithLifecycle()
    val connectedName by viewModel.bluetooth.connectedDeviceName.collectAsStateWithLifecycle()
    val discovered by viewModel.bluetooth.discoveredDevices.collectAsStateWithLifecycle()
    val discovering by viewModel.bluetooth.isDiscovering.collectAsStateWithLifecycle()
    val btError by viewModel.bluetooth.lastError.collectAsStateWithLifecycle()
    val transport by viewModel.transportMode.collectAsStateWithLifecycle()
    val speed by viewModel.speed.collectAsStateWithLifecycle()
    val light by viewModel.light.collectAsStateWithLifecycle()
    val wifiStatus by viewModel.wifiStatus.collectAsStateWithLifecycle()

    var settingsOpen by remember { mutableStateOf(false) }
    var cameraRetry by remember { mutableIntStateOf(0) }

    val controlReady = when (transport) {
        TransportMode.BLUETOOTH -> btState == ConnectionState.CONNECTED
        TransportMode.WIFI -> viewModel.settings.ipAddress.isNotBlank()
    }

    LaunchedEffect(Unit) {
        viewModel.refreshBluetoothStatus()
        if (viewModel.settings.ipAddress.isNotBlank()) viewModel.refreshWifiStatus()
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.emergencyStop() }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF05070A))) {
        ReliableCameraLayer(
            ip = viewModel.settings.ipAddress,
            retryKey = cameraRetry,
            bluetoothReady = btState == ConnectionState.CONNECTED,
            modifier = Modifier.fillMaxSize()
        )

        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    listOf(Color(0xD9000000), Color(0x22000000), Color(0x55000000), Color(0xE0000000))
                )
            )
        )

        ReliableTopHud(
            modifier = Modifier.align(Alignment.TopCenter).padding(14.dp),
            btState = btState,
            connectedName = connectedName,
            discovering = discovering,
            transport = transport,
            ip = viewModel.settings.ipAddress,
            firmware = wifiStatus?.optString("fw").orEmpty().ifBlank { viewModel.settings.lastFirmwareVersion },
            onScan = {
                if (discovering) viewModel.stopBluetoothScan() else viewModel.scanBluetooth()
            }
        )

        ReliableDrivePad(
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 30.dp, bottom = 28.dp).size(240.dp),
            enabled = controlReady,
            deadzone = viewModel.settings.controlDeadzone,
            onVector = viewModel::driveVector,
            onStop = viewModel::emergencyStop
        )

        Column(
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 22.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!controlReady) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xE61B1215),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Bad.copy(alpha = 0.5f))
                ) {
                    Text(
                        when {
                            btState == ConnectionState.CONNECTING -> "BLUETOOTH CONNECTING"
                            discovering -> "SEARCHING ESP32_CAM_RC"
                            transport == TransportMode.BLUETOOTH -> "CONNECT BLUETOOTH FIRST"
                            else -> "SET WI-FI TARGET FIRST"
                        },
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        color = Bad,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                }
            }

            FilledTonalButton(
                onClick = {
                    if (controlReady) viewModel.updateLight(if (light > 0f) 0f else 220f)
                },
                enabled = controlReady,
                modifier = Modifier.width(166.dp).height(48.dp)
            ) {
                Icon(Icons.Default.Lightbulb, null)
                Spacer(Modifier.width(8.dp))
                Text(if (light > 0f) "LIGHT ${light.toInt()}" else "LIGHT")
            }

            FilledTonalButton(
                onClick = {
                    if (btState == ConnectionState.CONNECTED) viewModel.switchEsp32ToWifi()
                    cameraRetry += 1
                },
                enabled = btState == ConnectionState.CONNECTED || viewModel.settings.ipAddress.isNotBlank(),
                modifier = Modifier.width(166.dp).height(48.dp)
            ) {
                Icon(Icons.Default.Videocam, null)
                Spacer(Modifier.width(8.dp))
                Text("CAMERA")
            }

            FilledTonalButton(
                onClick = {
                    viewModel.emergencyStop()
                    settingsOpen = true
                },
                modifier = Modifier.width(166.dp).height(48.dp)
            ) {
                Icon(Icons.Default.Settings, null)
                Spacer(Modifier.width(8.dp))
                Text("SETTINGS")
            }

            Button(
                onClick = viewModel::emergencyStop,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD83A4A)),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.width(166.dp).height(62.dp)
            ) {
                Icon(Icons.Default.Stop, null)
                Spacer(Modifier.width(8.dp))
                Text("STOP", fontWeight = FontWeight.Black, fontSize = 16.sp)
            }
        }

        if (btError != null && !settingsOpen) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp).widthIn(max = 520.dp),
                color = Color(0xE61A0E12),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Bad.copy(alpha = 0.45f))
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(btError.orEmpty(), Modifier.weight(1f), color = Color(0xFFFFA2AB), fontSize = 11.sp)
                    OutlinedButton(onClick = viewModel::scanBluetooth) { Text("RETRY") }
                }
            }
        }

        if (settingsOpen) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.42f)))
            ReliableSettingsPanel(
                modifier = Modifier.align(Alignment.CenterEnd),
                viewModel = viewModel,
                btState = btState,
                discovered = discovered,
                discovering = discovering,
                btError = btError,
                transport = transport,
                onClose = {
                    viewModel.emergencyStop()
                    settingsOpen = false
                }
            )
        }
    }
}

@Composable
private fun ReliableTopHud(
    modifier: Modifier,
    btState: ConnectionState,
    connectedName: String?,
    discovering: Boolean,
    transport: TransportMode,
    ip: String,
    firmware: String,
    onScan: () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color(0xC90A0E13),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.09f))
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text("ESP32 CAR", color = Color.White, fontWeight = FontWeight.Black, fontSize = 17.sp)
                Text("AI Thinker ESP32-CAM · Classic Bluetooth SPP", color = Color(0xFF8995A0), fontSize = 10.sp)
            }
            StatusPill(
                when (btState) {
                    ConnectionState.CONNECTED -> connectedName ?: "ESP32_CAM_RC"
                    ConnectionState.CONNECTING -> "BT CONNECTING"
                    ConnectionState.DISCONNECTED -> if (discovering) "BT SCANNING" else "BT DISCONNECTED"
                },
                btState == ConnectionState.CONNECTED
            )
            StatusPill(if (ip.isBlank()) "CAMERA OFF" else ip, ip.isNotBlank())
            StatusPill(if (transport == TransportMode.BLUETOOTH) "CONTROL · BT" else "CONTROL · WIFI", true)
            if (firmware.isNotBlank()) StatusPill("FW $firmware", true)
            if (btState == ConnectionState.DISCONNECTED) {
                OutlinedButton(onClick = onScan) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(if (discovering) "STOP" else "SCAN", fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, good: Boolean) {
    Surface(
        color = if (good) Color(0x2419D790) else Color(0x28FF5364),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (good) Good.copy(alpha = 0.35f) else Bad.copy(alpha = 0.35f))
    ) {
        Text(text, Modifier.padding(horizontal = 9.dp, vertical = 6.dp), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ReliableDrivePad(
    modifier: Modifier,
    enabled: Boolean,
    deadzone: Float,
    onVector: (Float, Float) -> Unit,
    onStop: () -> Unit
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var knob by remember { mutableStateOf(Offset.Zero) }
    var active by remember { mutableStateOf(false) }
    var throttle by remember { mutableFloatStateOf(0f) }
    var steering by remember { mutableFloatStateOf(0f) }

    fun reset() {
        active = false
        knob = Offset.Zero
        throttle = 0f
        steering = 0f
        onStop()
    }

    fun update(position: Offset) {
        if (!enabled || size.width == 0 || size.height == 0) return
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

    LaunchedEffect(enabled) {
        if (!enabled) reset()
    }

    LaunchedEffect(active, throttle, steering, enabled) {
        if (!active || !enabled) return@LaunchedEffect
        while (isActive && active && enabled) {
            onVector(throttle, steering)
            delay(80)
        }
    }

    Box(
        modifier.onSizeChanged { size = it }.pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectDragGestures(
                onDragStart = {
                    active = true
                    update(it)
                },
                onDrag = { change, _ ->
                    change.consume()
                    update(change.position)
                },
                onDragEnd = { reset() },
                onDragCancel = { reset() }
            )
        },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = minOf(size.width, size.height) * 0.39f
            drawCircle(if (enabled) Color(0xAA11171E) else Color(0x99100D10), radius = r * 1.18f)
            drawCircle(Color.White.copy(alpha = 0.10f), radius = r * 1.18f, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            drawCircle(Color(0x2226DDAA), radius = r * deadzone.coerceIn(0.02f, 0.35f))
        }
        Surface(
            modifier = Modifier.size(78.dp).graphicsLayer {
                translationX = knob.x
                translationY = knob.y
            },
            color = when {
                !enabled -> Color(0xFF34262A)
                active -> Good
                else -> Color(0xFF27313B)
            },
            shape = CircleShape,
            shadowElevation = if (active) 14.dp else 3.dp
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (enabled) "DRIVE" else "NO LINK",
                    color = if (active) Color(0xFF042117) else Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun ReliableSettingsPanel(
    modifier: Modifier,
    viewModel: RcViewModel,
    btState: ConnectionState,
    discovered: List<BluetoothDevice>,
    discovering: Boolean,
    btError: String?,
    transport: TransportMode,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fw by viewModel.firmwareUpdate.collectAsStateWithLifecycle()
    val wifiStatus by viewModel.wifiStatus.collectAsStateWithLifecycle()
    val wifiError by viewModel.wifiError.collectAsStateWithLifecycle()
    val speed by viewModel.speed.collectAsStateWithLifecycle()
    val trim by viewModel.trim.collectAsStateWithLifecycle()

    var ip by remember { mutableStateOf(viewModel.settings.ipAddress) }
    var ssid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var deadzone by remember { mutableFloatStateOf(viewModel.settings.controlDeadzone) }

    val paired = runCatching { viewModel.pairedDevices() }.getOrDefault(emptyList())
    val pairedAddresses = paired.mapNotNull { runCatching { it.address }.getOrNull() }.toSet()
    val newDevices = discovered.filter { runCatching { it.address }.getOrNull() !in pairedAddresses }

    Surface(
        modifier = modifier.fillMaxHeight().fillMaxWidth(0.50f).widthIn(min = 460.dp),
        color = PanelBackground,
        shadowElevation = 28.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("CONNECTION & CONTROL", color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp)
                    Text("상태가 실제로 연결된 경우에만 조종 입력이 활성화됩니다.", color = Color(0xFF8995A0), fontSize = 10.sp)
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SectionTitle("Bluetooth", "ESP32_CAM_RC · Classic SPP · 상태 $btState")

                if (!viewModel.bluetooth.isBluetoothEnabled()) {
                    Text("휴대폰 Bluetooth가 꺼져 있습니다.", color = Bad, fontWeight = FontWeight.Bold)
                    Button(onClick = {
                        runCatching { context.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                    }) {
                        Icon(Icons.Default.Bluetooth, null)
                        Spacer(Modifier.width(7.dp))
                        Text("Bluetooth 켜기")
                    }
                }

                btError?.let {
                    Surface(color = Color(0x331F0B10), shape = RoundedCornerShape(12.dp)) {
                        Text(it, Modifier.padding(11.dp), color = Color(0xFFFFA2AB), fontSize = 11.sp)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (discovering) viewModel.stopBluetoothScan() else viewModel.scanBluetooth()
                    }) {
                        Icon(Icons.Default.Refresh, null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (discovering) "검색 중지" else "주변 기기 검색")
                    }
                    OutlinedButton(onClick = {
                        if (!viewModel.reconnectLast()) viewModel.scanBluetooth()
                    }) { Text("마지막 기기 재연결") }
                    if (btState == ConnectionState.CONNECTED) {
                        OutlinedButton(onClick = viewModel::disconnectBluetooth) { Text("연결 해제") }
                    }
                }

                if (discovering) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("ESP32_CAM_RC 검색 중…", color = Color(0xFF9CA8B3), fontSize = 10.sp)
                }

                if (newDevices.isNotEmpty()) {
                    Text("새로 발견됨", color = Good, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    newDevices.forEach { device ->
                        DeviceRow(device, "PAIR & CONNECT", highlighted = device.safeName().equals("ESP32_CAM_RC", true)) {
                            viewModel.pairAndConnect(device)
                        }
                    }
                }

                if (paired.isNotEmpty()) {
                    Text("페어링됨", color = Color(0xFFA6B1BC), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    paired.forEach { device ->
                        DeviceRow(device, if (btState == ConnectionState.CONNECTED) "CONNECTED / SWITCH" else "CONNECT", highlighted = device.safeName().equals("ESP32_CAM_RC", true)) {
                            viewModel.connect(device)
                        }
                    }
                }

                if (!discovering && newDevices.isEmpty() && paired.isEmpty() && btState == ConnectionState.DISCONNECTED) {
                    Text("기기 목록이 비어 있습니다. '주변 기기 검색'을 누르세요.", color = Color(0xFF8A96A2), fontSize = 11.sp)
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                SectionTitle("Control path", "영상은 Wi-Fi, 조종은 Bluetooth 조합을 권장")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.setTransportMode(TransportMode.BLUETOOTH) },
                        colors = ButtonDefaults.buttonColors(containerColor = if (transport == TransportMode.BLUETOOTH) Color(0xFF2456D8) else Color(0xFF1C232B))
                    ) {
                        Icon(Icons.Default.Bluetooth, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Bluetooth control")
                    }
                    Button(
                        onClick = { viewModel.setTransportMode(TransportMode.WIFI) },
                        colors = ButtonDefaults.buttonColors(containerColor = if (transport == TransportMode.WIFI) Color(0xFF157C5B) else Color(0xFF1C232B))
                    ) {
                        Icon(Icons.Default.Wifi, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Wi-Fi control")
                    }
                }

                Text("Max motor output · ${speed.toInt()}", color = Color.White)
                Slider(speed, viewModel::updateSpeed, valueRange = 50f..255f)
                Text("Steering trim · ${trim.toInt()}", color = Color.White)
                Slider(trim, viewModel::updateTrim, valueRange = -50f..50f)
                Text("Joystick deadzone · ${(deadzone * 100).toInt()}%", color = Color.White)
                Slider(deadzone, onValueChange = {
                    deadzone = it
                    viewModel.settings.controlDeadzone = it
                }, valueRange = 0.02f..0.35f)

                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                SectionTitle("Wi-Fi camera", "Bluetooth로 공유기 정보를 저장한 뒤 카메라 서버 시작")
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
                    label = { Text("Wi-Fi Password") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.provisionWifi(ssid, password) },
                        enabled = btState == ConnectionState.CONNECTED && ssid.isNotBlank()
                    ) { Text("1. Wi-Fi 저장") }
                    Button(
                        onClick = viewModel::switchEsp32ToWifi,
                        enabled = btState == ConnectionState.CONNECTED
                    ) { Text("2. 카메라 시작") }
                }

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
                    FilledTonalButton(onClick = viewModel::refreshWifiStatus, enabled = ip.isNotBlank()) {
                        Icon(Icons.Default.Refresh, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Wi-Fi STATUS")
                    }
                    wifiStatus?.let {
                        Text("${it.optString("ssid")} · ${it.optString("ip")}", color = Good, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterVertically))
                    }
                }
                wifiError?.let { Text(it, color = Bad, fontSize = 11.sp) }

                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                SectionTitle("Updates", "ESP32 firmware와 Android 앱 업데이트")
                Text("Bundled firmware · ${fw.bundledVersion}", color = Good, fontWeight = FontWeight.Bold)
                if (fw.stage != FirmwareUpdateUiState.Stage.IDLE) {
                    LinearProgressIndicator(progress = { fw.progress.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth())
                    Text(fw.message, color = if (fw.stage == FirmwareUpdateUiState.Stage.ERROR) Bad else Color(0xFFAAB4BE), fontSize = 11.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::updateFirmwareFromBundled, enabled = viewModel.settings.ipAddress.isNotBlank()) {
                        Icon(Icons.Default.SystemUpdate, null)
                        Spacer(Modifier.width(6.dp))
                        Text("ESP32 OTA")
                    }
                    OutlinedButton(onClick = {
                        val activity = context as? Activity ?: return@OutlinedButton
                        scope.launch { AppUpdater.checkForUpdate(activity, installWhenReady = true) }
                    }) { Text("앱 업데이트 확인") }
                }

                OutlinedButton(onClick = viewModel::rebootDevice, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.PowerSettingsNew, null)
                    Spacer(Modifier.width(7.dp))
                    Text("ESP32 Reboot")
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun DeviceRow(device: BluetoothDevice, action: String, highlighted: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (highlighted) Color(0x2417C58A) else Color(0xFF111820),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (highlighted) Good.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.06f))
    ) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Bluetooth, null, tint = if (highlighted) Good else Color(0xFF8C99A5))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(device.safeName(), color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(runCatching { device.address }.getOrDefault("unknown"), color = Color(0xFF7F8B96), fontSize = 9.sp)
            }
            OutlinedButton(onClick = onClick) { Text(action, fontSize = 9.sp) }
        }
    }
}

@SuppressLint("MissingPermission")
private fun BluetoothDevice.safeName(): String = runCatching { name }.getOrNull().orEmpty().ifBlank { "Unknown Bluetooth device" }

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(subtitle, color = Color(0xFF818E99), fontSize = 10.sp)
    }
}

@Composable
private fun ReliableCameraLayer(
    ip: String,
    retryKey: Int,
    bluetoothReady: Boolean,
    modifier: Modifier = Modifier
) {
    var latestBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(ip, retryKey) {
        latestBitmap = null
        error = null
        if (ip.isBlank()) return@LaunchedEffect

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
            Icon(
                if (bluetoothReady) Icons.Default.Bluetooth else Icons.Default.VideocamOff,
                null,
                Modifier.size(44.dp),
                tint = if (bluetoothReady) Good.copy(alpha = 0.55f) else Color(0xFF53606C)
            )
            Text(
                when {
                    error != null -> "Camera: $error"
                    ip.isNotBlank() -> "Connecting camera…"
                    bluetoothReady -> "Bluetooth control ready · Settings에서 Wi-Fi camera를 시작하세요"
                    else -> "ESP32_CAM_RC를 연결하세요"
                },
                color = Color(0xFF7F8C99),
                fontSize = 12.sp
            )
        }
    }
}
