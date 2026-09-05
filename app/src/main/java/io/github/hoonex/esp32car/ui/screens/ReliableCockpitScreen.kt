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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hoonex.esp32car.bluetooth.ConnectionState
import io.github.hoonex.esp32car.network.MjpegParser
import io.github.hoonex.esp32car.update.AppUpdateStage
import io.github.hoonex.esp32car.update.AppUpdateState
import io.github.hoonex.esp32car.update.AppUpdater
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

private val ScreenBg = Color(0xFF06090D)
private val Panel = Color(0xF20C1117)
private val PanelSoft = Color(0xE8121820)
private val Accent = Color(0xFF66E7B1)
private val Danger = Color(0xFFFF6677)
private val Muted = Color(0xFF8C98A4)

@SuppressLint("MissingPermission")
@Composable
fun ReliableCockpitScreen(viewModel: RcViewModel) {
    val btState by viewModel.bluetooth.connectionState.collectAsStateWithLifecycle()
    val connectedName by viewModel.bluetooth.connectedDeviceName.collectAsStateWithLifecycle()
    val discovered by viewModel.bluetooth.discoveredDevices.collectAsStateWithLifecycle()
    val discovering by viewModel.bluetooth.isDiscovering.collectAsStateWithLifecycle()
    val btError by viewModel.bluetooth.lastError.collectAsStateWithLifecycle()
    val updateState by AppUpdater.state.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        onDispose { viewModel.emergencyStop() }
    }

    if (btState == ConnectionState.CONNECTED) {
        ConnectedCockpit(
            viewModel = viewModel,
            connectedName = connectedName ?: "ESP32_CAM_RC",
            updateState = updateState
        )
    } else {
        ManualConnectionScreen(
            viewModel = viewModel,
            btState = btState,
            discovered = discovered,
            discovering = discovering,
            btError = btError,
            updateState = updateState
        )
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun ManualConnectionScreen(
    viewModel: RcViewModel,
    btState: ConnectionState,
    discovered: List<BluetoothDevice>,
    discovering: Boolean,
    btError: String?,
    updateState: AppUpdateState
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val paired = runCatching { viewModel.pairedDevices() }.getOrDefault(emptyList())
    val allDevices = remember(discovered, paired) {
        (paired + discovered)
            .associateBy { runCatching { it.address }.getOrNull() ?: it.hashCode().toString() }
            .values
            .sortedWith(
                compareByDescending<BluetoothDevice> { it.safeName().equals("ESP32_CAM_RC", true) }
                    .thenBy { it.safeName() }
            )
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(ScreenBg)) {
        val compact = maxHeight < 420.dp
        val outer = if (compact) 12.dp else 22.dp
        val gap = if (compact) 12.dp else 20.dp
        val titleSize = if (compact) 26.sp else 38.sp

        Row(
            modifier = Modifier.fillMaxSize().padding(outer),
            horizontalArrangement = Arrangement.spacedBy(gap)
        ) {
            Column(
                modifier = Modifier.weight(0.42f).fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
                    Surface(
                        color = Color(0x2019D790),
                        shape = RoundedCornerShape(999.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Accent.copy(alpha = 0.35f))
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            Icon(Icons.Default.Bluetooth, null, Modifier.size(15.dp), tint = Accent)
                            Text("MANUAL BLUETOOTH", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Text("ESP32 CAR", color = Color.White, fontSize = titleSize, fontWeight = FontWeight.Black)
                    Text(
                        "연결 전에는 조종 화면을 띄우지 않습니다.\n검색하고 ESP32_CAM_RC를 직접 선택하세요.",
                        color = Muted,
                        fontSize = if (compact) 10.sp else 13.sp,
                        lineHeight = if (compact) 14.sp else 18.sp
                    )

                    if (btState == ConnectionState.CONNECTING) {
                        Surface(color = Color(0x2219D790), shape = RoundedCornerShape(14.dp)) {
                            Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("VERIFYING ESP32", color = Accent, fontWeight = FontWeight.Black, fontSize = 11.sp)
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Text("RFCOMM 연결 후 STATUS profile handshake 확인 중", color = Muted, fontSize = 9.sp)
                            }
                        }
                    }
                }

                UpdateCard(
                    state = updateState,
                    compact = compact,
                    onCheck = {
                        val activity = context as? Activity
                        if (activity != null) scope.launch { AppUpdater.checkForUpdate(activity, installWhenReady = true) }
                    },
                    onAction = {
                        val activity = context as? Activity
                        if (activity != null) {
                            when (updateState.stage) {
                                AppUpdateStage.READY -> AppUpdater.installReadyUpdate(activity)
                                AppUpdateStage.SIGNATURE_MISMATCH -> AppUpdater.openReleasePage(activity)
                                else -> scope.launch { AppUpdater.checkForUpdate(activity, installWhenReady = true) }
                            }
                        }
                    }
                )
            }

            Surface(
                modifier = Modifier.weight(0.58f).fillMaxHeight(),
                color = Panel,
                shape = RoundedCornerShape(if (compact) 20.dp else 28.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(if (compact) 13.dp else 20.dp),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Bluetooth 연결",
                                color = Color.White,
                                fontWeight = FontWeight.Black,
                                fontSize = if (compact) 15.sp else 20.sp
                            )
                            Text("자동연결 없음 · 직접 검색/선택 · ESP32 profile 검증", color = Muted, fontSize = 9.sp)
                        }

                        if (!viewModel.bluetooth.isBluetoothEnabled()) {
                            Button(onClick = {
                                runCatching { context.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                            }) { Text("Bluetooth 켜기", fontSize = 9.sp) }
                        } else {
                            Button(onClick = {
                                if (discovering) viewModel.stopBluetoothScan() else viewModel.scanBluetooth()
                            }) {
                                Icon(Icons.Default.Refresh, null, Modifier.size(15.dp))
                                Spacer(Modifier.width(5.dp))
                                Text(if (discovering) "검색 중지" else "검색", fontSize = 9.sp)
                            }
                        }
                    }

                    if (discovering) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

                    btError?.let {
                        Surface(color = Color(0x331E0D12), shape = RoundedCornerShape(12.dp)) {
                            Text(it, Modifier.padding(9.dp), color = Color(0xFFFFA0AA), fontSize = 9.sp)
                        }
                    }

                    if (allDevices.isEmpty() && !discovering) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.Bluetooth, null, Modifier.size(if (compact) 32.dp else 44.dp), tint = Color(0xFF4E5A65))
                                Text("검색 버튼을 눌러 ESP32_CAM_RC를 찾으세요", color = Muted, fontSize = 10.sp)
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            items(
                                items = allDevices,
                                key = { runCatching { it.address }.getOrDefault(it.hashCode().toString()) }
                            ) { device ->
                                val bonded = runCatching { device.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)
                                DeviceConnectRow(
                                    device = device,
                                    bonded = bonded,
                                    compact = compact,
                                    enabled = btState == ConnectionState.DISCONNECTED,
                                    onClick = { if (bonded) viewModel.connect(device) else viewModel.pairAndConnect(device) }
                                )
                            }
                        }
                    }

                    Text(
                        "폰 Bluetooth 목록에 보이는데 앱에서 안 보이면 검색을 중지한 뒤 다시 시작하세요.",
                        color = Color(0xFF66727D),
                        fontSize = 8.sp
                    )
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun DeviceConnectRow(
    device: BluetoothDevice,
    bonded: Boolean,
    compact: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val name = device.safeName()
    val target = name.equals("ESP32_CAM_RC", true)

    Surface(
        color = if (target) Color(0x2419D790) else PanelSoft,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (target) Accent.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.06f)
        )
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = if (compact) 7.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(color = if (target) Color(0x3319D790) else Color(0xFF1B232C), shape = CircleShape) {
                Icon(Icons.Default.Bluetooth, null, Modifier.padding(8.dp).size(16.dp), tint = if (target) Accent else Color(0xFF9CA7B1))
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Text(
                    "${if (bonded) "페어링됨" else "새 기기"} · ${device.safeAddress()}",
                    color = Muted,
                    fontSize = 8.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Button(onClick = onClick, enabled = enabled) {
                Text(if (bonded) "CONNECT" else "PAIR & CONNECT", fontSize = 8.sp)
            }
        }
    }
}

@Composable
private fun ConnectedCockpit(
    viewModel: RcViewModel,
    connectedName: String,
    updateState: AppUpdateState
) {
    val speed by viewModel.speed.collectAsStateWithLifecycle()
    val light by viewModel.light.collectAsStateWithLifecycle()
    val wifiStatus by viewModel.wifiStatus.collectAsStateWithLifecycle()
    val btStatus by viewModel.bluetooth.btStatusResponse.collectAsStateWithLifecycle()
    var settingsOpen by remember { mutableStateOf(false) }
    var cameraRetry by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        viewModel.refreshBluetoothStatus()
        if (viewModel.settings.ipAddress.isNotBlank()) viewModel.refreshWifiStatus()
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(ScreenBg)) {
        val compact = maxHeight < 420.dp
        val edge = if (compact) 10.dp else 16.dp
        val stickSize = minOf(maxHeight * if (compact) 0.48f else 0.52f, maxWidth * 0.23f)
        val actionWidth = minOf(maxWidth * 0.18f, if (compact) 132.dp else 152.dp)
        val actionHeight = if (compact) 39.dp else 48.dp

        CameraLayer(
            ip = viewModel.settings.ipAddress,
            controlKey = viewModel.settings.otaKey,
            retryKey = cameraRetry,
            modifier = Modifier.fillMaxSize()
        )

        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    listOf(Color(0xD8000000), Color(0x18000000), Color(0x43000000), Color(0xD5000000))
                )
            )
        )

        Surface(
            modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = edge, vertical = edge).fillMaxWidth(),
            color = Color(0xC90A0E13),
            shape = RoundedCornerShape(if (compact) 14.dp else 18.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
        ) {
            Row(
                Modifier.padding(horizontal = if (compact) 11.dp else 16.dp, vertical = if (compact) 6.dp else 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text("ESP32 CAR", color = Color.White, fontWeight = FontWeight.Black, fontSize = if (compact) 13.sp else 17.sp)
                    Text(connectedName, color = Accent, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
                SmallPill("BT VERIFIED", true)
                btStatus?.optInt("protocol", 1)?.let { if (!compact) SmallPill("P$it", it >= 2) }
                if (!compact) {
                    SmallPill(
                        if (viewModel.settings.ipAddress.isBlank()) "CAMERA OFF" else viewModel.settings.ipAddress,
                        viewModel.settings.ipAddress.isNotBlank()
                    )
                }
                when (updateState.stage) {
                    AppUpdateStage.DOWNLOADING -> SmallPill("UPDATE ${updateState.progress}%", true)
                    AppUpdateStage.SIGNATURE_MISMATCH,
                    AppUpdateStage.ERROR -> SmallPill("UPDATE ACTION", false)
                    else -> Unit
                }
            }
        }

        DrivePad(
            modifier = Modifier.align(Alignment.BottomStart).padding(start = edge + 6.dp, bottom = edge + 4.dp).size(stickSize),
            deadzone = viewModel.settings.controlDeadzone,
            onVector = viewModel::driveVector,
            onStop = viewModel::emergencyStop
        )

        Column(
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = edge),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 9.dp)
        ) {
            CockpitAction(
                width = actionWidth,
                height = actionHeight,
                icon = { Icon(Icons.Default.Lightbulb, null, Modifier.size(16.dp)) },
                label = if (light > 0f) "LIGHT ${light.toInt()}" else "LIGHT",
                onClick = { viewModel.updateLight(if (light > 0f) 0f else 220f) }
            )
            CockpitAction(
                width = actionWidth,
                height = actionHeight,
                icon = { Icon(Icons.Default.Videocam, null, Modifier.size(16.dp)) },
                label = "CAMERA",
                onClick = {
                    if (viewModel.settings.ipAddress.isBlank()) viewModel.switchEsp32ToWifi()
                    cameraRetry += 1
                }
            )
            CockpitAction(
                width = actionWidth,
                height = actionHeight,
                icon = { Icon(Icons.Default.Settings, null, Modifier.size(16.dp)) },
                label = "SETTINGS",
                onClick = {
                    viewModel.emergencyStop()
                    settingsOpen = true
                }
            )
            Button(
                onClick = viewModel::emergencyStop,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD83A4A)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.width(actionWidth).height(if (compact) 45.dp else 56.dp)
            ) {
                Icon(Icons.Default.Stop, null, Modifier.size(17.dp))
                Spacer(Modifier.width(5.dp))
                Text("STOP", fontWeight = FontWeight.Black, fontSize = if (compact) 11.sp else 14.sp)
            }
        }

        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = edge),
            color = Color(0xB80A0E13),
            shape = RoundedCornerShape(999.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.07f))
        ) {
            Row(
                Modifier.padding(horizontal = if (compact) 9.dp else 14.dp, vertical = if (compact) 4.dp else 7.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("MAX ${speed.toInt()}", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                if (!compact) {
                    val fw = btStatus?.optString("fw").orEmpty().ifBlank {
                        wifiStatus?.optString("fw").orEmpty().ifBlank { viewModel.settings.lastFirmwareVersion }
                    }
                    Text("FW $fw", color = Muted, fontSize = 8.sp)
                    Text("FAILSAFE ${btStatus?.optInt("deadman_ms", 450) ?: 450}ms", color = Accent, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    btStatus?.let {
                        Text("HEAP ${it.optInt("heap") / 1024}K", color = Muted, fontSize = 8.sp)
                        Text("TRIPS ${it.optInt("deadman_trips")}", color = if (it.optInt("deadman_trips") > 0) Danger else Muted, fontSize = 8.sp)
                    }
                }
            }
        }

        if (settingsOpen) {
            SettingsOverlay(
                viewModel = viewModel,
                updateState = updateState,
                compact = compact,
                onClose = {
                    viewModel.emergencyStop()
                    settingsOpen = false
                }
            )
        }
    }
}

@Composable
private fun CockpitAction(
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.width(width).height(height),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xD9141A21), contentColor = Color.White)
    ) {
        icon()
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SmallPill(text: String, good: Boolean) {
    Surface(
        color = if (good) Color(0x2219D790) else Color(0x28FF5364),
        shape = RoundedCornerShape(999.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (good) Accent.copy(alpha = 0.35f) else Danger.copy(alpha = 0.35f))
    ) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DrivePad(
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
        if (!active) return@LaunchedEffect
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
                    onDragStart = { active = true; update(it) },
                    onDrag = { change, _ -> change.consume(); update(change.position) },
                    onDragEnd = { reset() },
                    onDragCancel = { reset() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = minOf(size.width, size.height) * 0.39f
            drawCircle(Color(0xA811171E), radius = r * 1.18f)
            drawCircle(Color.White.copy(alpha = 0.11f), radius = r * 1.18f, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            drawCircle(Color(0x2219D790), radius = r * deadzone.coerceIn(0.02f, 0.35f))
        }

        val knobPixels = if (size.height > 0) (minOf(size.width, size.height) * 0.28f).coerceAtLeast(52f) else 64f
        val knobDp = with(density) { knobPixels.toDp() }
        Surface(
            modifier = Modifier.size(knobDp).graphicsLayer { translationX = knob.x; translationY = knob.y },
            color = if (active) Accent else Color(0xFF29333D),
            shape = CircleShape,
            shadowElevation = if (active) 12.dp else 3.dp
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("DRIVE", color = if (active) Color(0xFF042117) else Color.White, fontSize = 8.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
private fun SettingsOverlay(
    viewModel: RcViewModel,
    updateState: AppUpdateState,
    compact: Boolean,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val speed by viewModel.speed.collectAsStateWithLifecycle()
    val trim by viewModel.trim.collectAsStateWithLifecycle()
    val fw by viewModel.firmwareUpdate.collectAsStateWithLifecycle()
    val wifiStatus by viewModel.wifiStatus.collectAsStateWithLifecycle()
    val wifiError by viewModel.wifiError.collectAsStateWithLifecycle()
    val btStatus by viewModel.bluetooth.btStatusResponse.collectAsStateWithLifecycle()
    val connectedDeviceName by viewModel.bluetooth.connectedDeviceName.collectAsStateWithLifecycle()

    var deadzone by remember { mutableFloatStateOf(viewModel.settings.controlDeadzone) }
    var steeringGain by remember { mutableFloatStateOf(viewModel.settings.steeringGain) }
    var steeringExpo by remember { mutableFloatStateOf(viewModel.settings.steeringExpo) }
    var invertThrottle by remember { mutableStateOf(viewModel.settings.invertThrottle) }
    var invertSteering by remember { mutableStateOf(viewModel.settings.invertSteering) }

    var swapMotors by remember { mutableStateOf(viewModel.settings.swapMotors) }
    var invertLeft by remember { mutableStateOf(viewModel.settings.invertLeftMotor) }
    var invertRight by remember { mutableStateOf(viewModel.settings.invertRightMotor) }

    var streamResolution by remember { mutableStateOf(viewModel.settings.streamResolution) }
    var streamQuality by remember { mutableFloatStateOf(viewModel.settings.streamQuality) }
    var streamFps by remember { mutableFloatStateOf(viewModel.settings.streamFps) }
    var brightness by remember { mutableFloatStateOf(viewModel.settings.cameraBrightness) }
    var mirror by remember { mutableStateOf(viewModel.settings.cameraMirror) }
    var flip by remember { mutableStateOf(viewModel.settings.cameraFlip) }

    var ssid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var ip by remember { mutableStateOf(viewModel.settings.ipAddress) }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFA080C11)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (compact) 7.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("SETTINGS + DIAGNOSTICS", color = Color.White, fontWeight = FontWeight.Black, fontSize = if (compact) 15.sp else 20.sp)
                    Text("실제 ESP32 profile/telemetry 기반 · 가로 2열", color = Muted, fontSize = 8.sp)
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
            }

            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(11.dp)
                ) {
                    item {
                        SettingsSection("Bluetooth", "현재 연결: ${connectedDeviceName ?: "ESP32_CAM_RC"}") {
                            btStatus?.let {
                                Text(
                                    "FW ${it.optString("fw")} · protocol ${it.optInt("protocol", 1)} · ${it.optString("profile")}",
                                    color = Accent,
                                    fontSize = 9.sp
                                )
                            }
                            OutlinedButton(onClick = viewModel::disconnectBluetooth, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Bluetooth, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("연결 해제 후 연결 화면으로", fontSize = 10.sp)
                            }
                        }
                    }

                    item {
                        SettingsSection("Drive tuning", "조이스틱 → 좌/우 PWM 변환") {
                            Text("Max motor output · ${speed.toInt()}", color = Color.White, fontSize = 10.sp)
                            Slider(value = speed, onValueChange = viewModel::updateSpeed, valueRange = 50f..255f)
                            Text("Steering trim · ${trim.toInt()}", color = Color.White, fontSize = 10.sp)
                            Slider(value = trim, onValueChange = viewModel::updateTrim, valueRange = -50f..50f)
                            Text("Deadzone · ${(deadzone * 100).toInt()}%", color = Color.White, fontSize = 10.sp)
                            Slider(value = deadzone, onValueChange = { deadzone = it; viewModel.settings.controlDeadzone = it }, valueRange = 0.02f..0.35f)
                            Text("Steering gain · ${"%.2f".format(steeringGain)}", color = Color.White, fontSize = 10.sp)
                            Slider(value = steeringGain, onValueChange = { steeringGain = it; viewModel.settings.steeringGain = it }, valueRange = 0.5f..1.8f)
                            Text("Steering expo · ${"%.2f".format(steeringExpo)}", color = Color.White, fontSize = 10.sp)
                            Slider(value = steeringExpo, onValueChange = { steeringExpo = it; viewModel.settings.steeringExpo = it }, valueRange = 1f..2.5f)
                            ToggleRow("Throttle 반전", invertThrottle) { invertThrottle = it; viewModel.settings.invertThrottle = it }
                            ToggleRow("Steering 반전", invertSteering) { invertSteering = it; viewModel.settings.invertSteering = it }
                        }
                    }

                    item {
                        SettingsSection("Motor wiring calibration", "배선은 그대로 두고 논리 방향 보정") {
                            ToggleRow("좌/우 모터 교환", swapMotors) { swapMotors = it; viewModel.settings.swapMotors = it }
                            ToggleRow("Left 방향 반전", invertLeft) { invertLeft = it; viewModel.settings.invertLeftMotor = it }
                            ToggleRow("Right 방향 반전", invertRight) { invertRight = it; viewModel.settings.invertRightMotor = it }
                            Button(onClick = viewModel::applyMotorConfig, modifier = Modifier.fillMaxWidth()) {
                                Text("정지 후 모터 설정 적용", fontSize = 9.sp)
                            }
                        }
                    }

                    item {
                        SettingsSection("ESP32 firmware", "Bundled ${fw.bundledVersion}") {
                            if (fw.stage != FirmwareUpdateUiState.Stage.IDLE || fw.message.isNotBlank()) {
                                if (fw.stage == FirmwareUpdateUiState.Stage.UPLOADING || fw.stage == FirmwareUpdateUiState.Stage.REBOOTING) {
                                    LinearProgressIndicator(progress = { fw.progress.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth())
                                }
                                Text(fw.message, color = if (fw.stage == FirmwareUpdateUiState.Stage.ERROR) Danger else Muted, fontSize = 9.sp)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = viewModel::startRecoveryOtaAp) { Text("Recovery AP", fontSize = 9.sp) }
                                Button(
                                    onClick = viewModel::updateFirmwareFromBundled,
                                    enabled = viewModel.settings.ipAddress.isNotBlank()
                                ) {
                                    Icon(Icons.Default.SystemUpdate, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text("ESP32 OTA", fontSize = 9.sp)
                                }
                            }
                            Text("업로드 성공만으로 완료 처리하지 않고 재부팅 후 실행 FW 버전을 확인합니다.", color = Muted, fontSize = 8.sp)
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(11.dp)
                ) {
                    item {
                        SettingsSection("Wi-Fi camera", "Bluetooth는 주행, Wi-Fi는 영상/OTA") {
                            OutlinedTextField(value = ssid, onValueChange = { ssid = it }, label = { Text("SSID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { viewModel.provisionWifi(ssid, password) }, enabled = ssid.isNotBlank()) { Text("Wi-Fi 저장", fontSize = 9.sp) }
                                Button(onClick = viewModel::switchEsp32ToWifi) { Text("Wi-Fi 시작", fontSize = 9.sp) }
                            }
                            OutlinedTextField(
                                value = ip,
                                onValueChange = { ip = it; viewModel.updateIp(it) },
                                label = { Text("ESP32 IP") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            FilledTonalButton(onClick = viewModel::refreshWifiStatus, enabled = ip.isNotBlank()) {
                                Icon(Icons.Default.Wifi, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Wi-Fi STATUS", fontSize = 9.sp)
                            }
                            wifiStatus?.let { Text("${it.optString("mode")} · ${it.optString("ssid")} · ${it.optString("ip")}", color = Accent, fontSize = 9.sp) }
                            wifiError?.let { Text(it, color = Danger, fontSize = 9.sp) }
                        }
                    }

                    item {
                        SettingsSection("Camera sensor", "OV2640 live settings · 기본 12 FPS") {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("QQVGA", "QVGA", "VGA").forEach { value ->
                                    OutlinedButton(
                                        onClick = { streamResolution = value; viewModel.settings.streamResolution = value },
                                        enabled = streamResolution != value
                                    ) { Text(value, fontSize = 8.sp) }
                                }
                            }
                            Text("JPEG quality · ${streamQuality.toInt()} (낮을수록 고화질)", color = Color.White, fontSize = 9.sp)
                            Slider(value = streamQuality, onValueChange = { streamQuality = it; viewModel.settings.streamQuality = it }, valueRange = 4f..20f)
                            Text("Stream FPS · ${streamFps.toInt()}", color = Color.White, fontSize = 9.sp)
                            Slider(value = streamFps, onValueChange = { streamFps = it; viewModel.settings.streamFps = it }, valueRange = 5f..20f, steps = 14)
                            Text("Brightness · ${brightness.toInt()}", color = Color.White, fontSize = 9.sp)
                            Slider(value = brightness, onValueChange = { brightness = it; viewModel.settings.cameraBrightness = it }, valueRange = -2f..2f, steps = 3)
                            ToggleRow("Mirror", mirror) { mirror = it; viewModel.settings.cameraMirror = it }
                            ToggleRow("Vertical flip", flip) { flip = it; viewModel.settings.cameraFlip = it }
                            Button(onClick = viewModel::applyCameraConfig, enabled = viewModel.settings.ipAddress.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                                Text("카메라 설정 적용", fontSize = 9.sp)
                            }
                        }
                    }

                    item {
                        SettingsSection("Live diagnostics", "ESP32가 실제 STATUS로 보고한 값") {
                            DiagnosticRows(btStatus)
                            OutlinedButton(onClick = viewModel::refreshBluetoothStatus, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Refresh, null, Modifier.size(15.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("Telemetry 새로고침", fontSize = 9.sp)
                            }
                        }
                    }

                    item {
                        SettingsSection("Android app update", "자동 확인/다운로드 · 설치는 Android 시스템 승인 필요") {
                            UpdateCard(
                                state = updateState,
                                compact = compact,
                                onCheck = {
                                    val activity = context as? Activity
                                    if (activity != null) scope.launch { AppUpdater.checkForUpdate(activity, installWhenReady = true) }
                                },
                                onAction = {
                                    val activity = context as? Activity
                                    if (activity != null) {
                                        when (updateState.stage) {
                                            AppUpdateStage.READY -> AppUpdater.installReadyUpdate(activity)
                                            AppUpdateStage.SIGNATURE_MISMATCH -> AppUpdater.openReleasePage(activity)
                                            else -> scope.launch { AppUpdater.checkForUpdate(activity, installWhenReady = true) }
                                        }
                                    }
                                }
                            )
                        }
                    }
                    item { Spacer(Modifier.height(18.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 9.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun DiagnosticRows(status: JSONObject?) {
    if (status == null) {
        Text("STATUS 수신 전", color = Muted, fontSize = 9.sp)
        return
    }
    val rows = listOf(
        "Firmware" to status.optString("fw"),
        "Protocol" to status.optInt("protocol", 1).toString(),
        "Mode" to status.optString("mode"),
        "Heap" to "${status.optInt("heap") / 1024} KB / min ${status.optInt("min_heap") / 1024} KB",
        "PSRAM free" to "${status.optInt("psram_free") / 1024} KB",
        "Motor PWM" to "L ${status.optInt("left_pwm")} / R ${status.optInt("right_pwm")}",
        "Deadman" to "${status.optInt("deadman_ms", 450)} ms · trips ${status.optInt("deadman_trips")}",
        "Camera" to "${if (status.optBoolean("camera")) "ready" else "off"} · ${status.optInt("stream_fps", 0)} FPS",
        "Wi-Fi RSSI" to if (status.optLong("rssi") == 0L) "n/a" else "${status.optLong("rssi")} dBm"
    )
    rows.forEach { (label, value) ->
        Row(Modifier.fillMaxWidth()) {
            Text(label, color = Muted, fontSize = 8.sp, modifier = Modifier.weight(1f))
            Text(value.ifBlank { "-" }, color = Color.White, fontSize = 8.sp)
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        color = PanelSoft,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
    ) {
        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(subtitle, color = Muted, fontSize = 8.sp)
            content()
        }
    }
}

@Composable
private fun UpdateCard(
    state: AppUpdateState,
    compact: Boolean,
    onCheck: () -> Unit,
    onAction: () -> Unit
) {
    val active = state.stage == AppUpdateStage.CHECKING || state.stage == AppUpdateStage.DOWNLOADING
    val problem = state.stage == AppUpdateStage.ERROR || state.stage == AppUpdateStage.SIGNATURE_MISMATCH

    Surface(
        color = if (problem) Color(0x2FFF5364) else Color(0x1E19D790),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (problem) Danger.copy(alpha = 0.35f) else Accent.copy(alpha = 0.28f))
    ) {
        Column(Modifier.fillMaxWidth().padding(if (compact) 10.dp else 13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SystemUpdate, null, Modifier.size(15.dp), tint = if (problem) Danger else Accent)
                Spacer(Modifier.width(6.dp))
                Text("APP UPDATE", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.weight(1f))
                if (state.currentVersion.isNotBlank()) Text("v${state.currentVersion}", color = Muted, fontSize = 8.sp)
            }

            Text(
                state.message,
                color = if (problem) Color(0xFFFFA2AB) else Color(0xFFB3BDC6),
                fontSize = 8.sp,
                maxLines = if (compact) 2 else 3,
                overflow = TextOverflow.Ellipsis
            )

            if (state.stage == AppUpdateStage.DOWNLOADING) {
                LinearProgressIndicator(progress = { state.progress.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth())
            }

            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                OutlinedButton(onClick = onCheck, enabled = !active) { Text(if (active) "확인 중" else "다시 확인", fontSize = 8.sp) }
                when (state.stage) {
                    AppUpdateStage.READY -> Button(onClick = onAction) { Text("설치", fontSize = 8.sp) }
                    AppUpdateStage.SIGNATURE_MISMATCH -> Button(onClick = onAction) { Text("릴리즈 열기", fontSize = 8.sp) }
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun CameraLayer(
    ip: String,
    controlKey: String,
    retryKey: Int,
    modifier: Modifier = Modifier
) {
    var latestBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(ip, controlKey, retryKey) {
        latestBitmap = null
        error = null
        if (ip.isBlank()) return@LaunchedEffect

        var retry = 0
        while (isActive) {
            var connection: HttpURLConnection? = null
            try {
                withContext(Dispatchers.IO) {
                    val host = ip.removePrefix("http://").removePrefix("https://").substringBefore('/').substringBefore(':')
                    connection = URL("http://$host:81/stream").openConnection() as HttpURLConnection
                    connection?.connectTimeout = 2500
                    connection?.readTimeout = 7000
                    connection?.useCaches = false
                    if (controlKey.isNotBlank()) connection?.setRequestProperty("X-ESP32-Control-Key", controlKey)
                    connection?.connect()
                    val response = connection?.responseCode ?: -1
                    if (response !in 200..299) throw IOException("Camera HTTP $response")

                    BufferedInputStream(connection?.inputStream, 64 * 1024).use { input ->
                        retry = 0
                        while (isActive) {
                            val frame = MjpegParser.readFrame(input) ?: throw IOException("Camera stream ended")
                            withContext(Dispatchers.Main) {
                                latestBitmap = frame
                                error = null
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                if (!isActive) break
                error = t.message ?: "Camera unavailable"
                retry = (retry + 1).coerceAtMost(5)
                delay((500L shl retry).coerceAtMost(5000L))
            } finally {
                connection?.disconnect()
            }
        }
    }

    Box(modifier.background(ScreenBg), contentAlignment = Alignment.Center) {
        latestBitmap?.let {
            Image(it.asImageBitmap(), "ESP32 camera", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } ?: Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(Icons.Default.Videocam, null, Modifier.size(32.dp), tint = Color(0xFF4D5964))
            Text(
                if (ip.isBlank()) "Bluetooth connected · Camera off" else (error ?: "Connecting camera…"),
                color = Color(0xFF73808B),
                fontSize = 9.sp
            )
        }
    }
}

@SuppressLint("MissingPermission")
private fun BluetoothDevice.safeName(): String =
    runCatching { name }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Unknown Bluetooth device"

@SuppressLint("MissingPermission")
private fun BluetoothDevice.safeAddress(): String =
    runCatching { address }.getOrNull().orEmpty()
