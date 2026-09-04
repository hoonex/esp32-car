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
import androidx.compose.foundation.layout.weight
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
import androidx.lifecycle.lifecycleScope
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
import java.io.BufferedInputStream
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

    when (btState) {
        ConnectionState.CONNECTED -> ConnectedCockpit(
            viewModel = viewModel,
            connectedName = connectedName ?: "ESP32_CAM_RC",
            updateState = updateState
        )

        ConnectionState.CONNECTING,
        ConnectionState.DISCONNECTED -> ManualConnectionScreen(
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
        val titleSize = if (compact) 28.sp else 38.sp

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
                        fontSize = if (compact) 11.sp else 13.sp,
                        lineHeight = if (compact) 15.sp else 18.sp
                    )

                    if (btState == ConnectionState.CONNECTING) {
                        Surface(color = Color(0x2219D790), shape = RoundedCornerShape(14.dp)) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text("CONNECTING", color = Accent, fontWeight = FontWeight.Black, fontSize = 12.sp)
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Text("선택한 기기에 Classic Bluetooth SPP 연결 중", color = Muted, fontSize = 10.sp)
                            }
                        }
                    }
                }

                UpdateCard(
                    state = updateState,
                    compact = compact,
                    onCheck = {
                        val activity = context as? Activity ?: return@UpdateCard
                        activity.lifecycleScopeSafe { AppUpdater.checkForUpdate(activity, installWhenReady = true) }
                    },
                    onAction = {
                        val activity = context as? Activity ?: return@UpdateCard
                        when (updateState.stage) {
                            AppUpdateStage.READY -> AppUpdater.installReadyUpdate(activity)
                            AppUpdateStage.SIGNATURE_MISMATCH -> AppUpdater.openReleasePage(activity)
                            else -> activity.lifecycleScopeSafe { AppUpdater.checkForUpdate(activity, installWhenReady = true) }
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
                    modifier = Modifier.fillMaxSize().padding(if (compact) 14.dp else 20.dp),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 9.dp else 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Bluetooth 연결", color = Color.White, fontWeight = FontWeight.Black, fontSize = if (compact) 16.sp else 20.sp)
                            Text("자동연결 없음 · 직접 검색/선택", color = Muted, fontSize = 10.sp)
                        }

                        if (!viewModel.bluetooth.isBluetoothEnabled()) {
                            Button(onClick = {
                                runCatching { context.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                            }) {
                                Text("Bluetooth 켜기", fontSize = 10.sp)
                            }
                        } else {
                            Button(
                                onClick = {
                                    if (discovering) viewModel.stopBluetoothScan() else viewModel.scanBluetooth()
                                }
                            ) {
                                Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (discovering) "검색 중지" else "검색", fontSize = 10.sp)
                            }
                        }
                    }

                    if (discovering) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    btError?.let {
                        Surface(color = Color(0x331E0D12), shape = RoundedCornerShape(12.dp)) {
                            Text(it, Modifier.padding(10.dp), color = Color(0xFFFFA0AA), fontSize = 10.sp)
                        }
                    }

                    if (allDevices.isEmpty() && !discovering) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.Bluetooth, null, Modifier.size(if (compact) 34.dp else 44.dp), tint = Color(0xFF4E5A65))
                                Text("검색 버튼을 눌러 ESP32_CAM_RC를 찾으세요", color = Muted, fontSize = 11.sp)
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(allDevices, key = { runCatching { it.address }.getOrDefault(it.hashCode().toString()) }) { device ->
                                val bonded = runCatching { device.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)
                                DeviceConnectRow(
                                    device = device,
                                    bonded = bonded,
                                    compact = compact,
                                    enabled = btState == ConnectionState.DISCONNECTED,
                                    onClick = {
                                        if (bonded) viewModel.connect(device) else viewModel.pairAndConnect(device)
                                    }
                                )
                            }
                        }
                    }

                    Text(
                        "폰 Bluetooth 목록에 ESP32_CAM_RC가 보이는데 여기서 안 보이면 검색을 한 번 중지한 뒤 다시 시작하세요.",
                        color = Color(0xFF66727D),
                        fontSize = 9.sp
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
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = if (compact) 8.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(color = if (target) Color(0x3319D790) else Color(0xFF1B232C), shape = CircleShape) {
                Icon(
                    Icons.Default.Bluetooth,
                    null,
                    Modifier.padding(9.dp).size(17.dp),
                    tint = if (target) Accent else Color(0xFF9CA7B1)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(
                    "${if (bonded) "페어링됨" else "새 기기"} · ${device.safeAddress()}",
                    color = Muted,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Button(onClick = onClick, enabled = enabled) {
                Text(if (bonded) "CONNECT" else "PAIR & CONNECT", fontSize = 9.sp)
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
        val actionHeight = if (compact) 40.dp else 48.dp

        ReliableCameraLayer(
            ip = viewModel.settings.ipAddress,
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
                Modifier.padding(horizontal = if (compact) 12.dp else 16.dp, vertical = if (compact) 7.dp else 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 10.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text("ESP32 CAR", color = Color.White, fontWeight = FontWeight.Black, fontSize = if (compact) 14.sp else 17.sp)
                    Text(connectedName, color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                SmallPill("BT CONNECTED", true)
                if (!compact) SmallPill(if (viewModel.settings.ipAddress.isBlank()) "CAMERA OFF" else viewModel.settings.ipAddress, viewModel.settings.ipAddress.isNotBlank())
                if (updateState.stage == AppUpdateStage.DOWNLOADING) {
                    SmallPill("UPDATE ${updateState.progress}%", true)
                } else if (updateState.stage == AppUpdateStage.SIGNATURE_MISMATCH) {
                    SmallPill("UPDATE ACTION", false)
                }
            }
        }

        ReliableDrivePad(
            modifier = Modifier.align(Alignment.BottomStart).padding(start = edge + 6.dp, bottom = edge + 4.dp).size(stickSize),
            deadzone = viewModel.settings.controlDeadzone,
            onVector = viewModel::driveVector,
            onStop = viewModel::emergencyStop
        )

        Column(
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = edge),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 9.dp)
        ) {
            CockpitAction(
                width = actionWidth,
                height = actionHeight,
                icon = { Icon(Icons.Default.Lightbulb, null, Modifier.size(17.dp)) },
                label = if (light > 0f) "LIGHT ${light.toInt()}" else "LIGHT",
                onClick = { viewModel.updateLight(if (light > 0f) 0f else 220f) }
            )
            CockpitAction(
                width = actionWidth,
                height = actionHeight,
                icon = { Icon(Icons.Default.Videocam, null, Modifier.size(17.dp)) },
                label = "CAMERA",
                onClick = {
                    viewModel.switchEsp32ToWifi()
                    cameraRetry += 1
                }
            )
            CockpitAction(
                width = actionWidth,
                height = actionHeight,
                icon = { Icon(Icons.Default.Settings, null, Modifier.size(17.dp)) },
                label = "SETTINGS",
                onClick = {
                    viewModel.emergencyStop()
                    settingsOpen = true
                }
            )
            Button(
                onClick = viewModel::emergencyStop,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD83A4A)),
                shape = RoundedCornerShape(15.dp),
                modifier = Modifier.width(actionWidth).height(if (compact) 46.dp else 56.dp)
            ) {
                Icon(Icons.Default.Stop, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("STOP", fontWeight = FontWeight.Black, fontSize = if (compact) 12.sp else 14.sp)
            }
        }

        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = edge),
            color = Color(0xB80A0E13),
            shape = RoundedCornerShape(999.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.07f))
        ) {
            Row(
                Modifier.padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 5.dp else 7.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("MAX ${speed.toInt()}", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                if (!compact) {
                    Text("FW ${wifiStatus?.optString("fw").orEmpty().ifBlank { viewModel.settings.lastFirmwareVersion }}", color = Muted, fontSize = 9.sp)
                    Text("FAILSAFE 450ms", color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
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
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SmallPill(text: String, good: Boolean) {
    Surface(
        color = if (good) Color(0x2219D790) else Color(0x28FF5364),
        shape = RoundedCornerShape(999.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (good) Accent.copy(alpha = 0.35f) else Danger.copy(alpha = 0.35f))
    ) {
        Text(text, Modifier.padding(horizontal = 9.dp, vertical = 5.dp), color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ReliableDrivePad(
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
        modifier = modifier.onSizeChanged { size = it }.pointerInput(Unit) {
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
            drawCircle(Color(0xA811171E), radius = r * 1.18f)
            drawCircle(
                Color.White.copy(alpha = 0.11f),
                radius = r * 1.18f,
                style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx())
            )
            drawCircle(Color(0x2219D790), radius = r * deadzone.coerceIn(0.02f, 0.35f))
        }

        val knobSize = if (size.height > 0) (minOf(size.width, size.height) * 0.28f).coerceAtLeast(52f) else 64f
        Surface(
            modifier = Modifier.size(with(androidx.compose.ui.platform.LocalDensity.current) { knobSize.toDp() }).graphicsLayer {
                translationX = knob.x
                translationY = knob.y
            },
            color = if (active) Accent else Color(0xFF29333D),
            shape = CircleShape,
            shadowElevation = if (active) 12.dp else 3.dp
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("DRIVE", color = if (active) Color(0xFF042117) else Color.White, fontSize = 9.sp, fontWeight = FontWeight.Black)
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
    val connectedDeviceName by viewModel.bluetooth.connectedDeviceName.collectAsStateWithLifecycle()

    var deadzone by remember { mutableFloatStateOf(viewModel.settings.controlDeadzone) }
    var ssid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var ip by remember { mutableStateOf(viewModel.settings.ipAddress) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFA080C11)
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (compact) 8.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("SETTINGS", color = Color.White, fontWeight = FontWeight.Black, fontSize = if (compact) 16.sp else 20.sp)
                    Text("가로 화면 전용 · 연결은 수동", color = Muted, fontSize = 9.sp)
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, "Close", tint = Color.White)
                }
            }

            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        SettingsSection("Bluetooth", "현재 연결: ${connectedDeviceName ?: "ESP32_CAM_RC"}") {
                            OutlinedButton(onClick = viewModel::disconnectBluetooth, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Bluetooth, null)
                                Spacer(Modifier.width(6.dp))
                                Text("연결 해제 후 연결 화면으로")
                            }
                        }
                    }
                    item {
                        SettingsSection("Drive tuning", "조종 감도와 출력") {
                            Text("Max motor output · ${speed.toInt()}", color = Color.White, fontSize = 11.sp)
                            Slider(value = speed, onValueChange = viewModel::updateSpeed, valueRange = 50f..255f)
                            Text("Steering trim · ${trim.toInt()}", color = Color.White, fontSize = 11.sp)
                            Slider(value = trim, onValueChange = viewModel::updateTrim, valueRange = -50f..50f)
                            Text("Deadzone · ${(deadzone * 100).toInt()}%", color = Color.White, fontSize = 11.sp)
                            Slider(
                                value = deadzone,
                                onValueChange = {
                                    deadzone = it
                                    viewModel.settings.controlDeadzone = it
                                },
                                valueRange = 0.02f..0.35f
                            )
                        }
                    }
                    item {
                        SettingsSection("ESP32 firmware", "Bundled ${fw.bundledVersion}") {
                            if (fw.stage != FirmwareUpdateUiState.Stage.IDLE) {
                                LinearProgressIndicator(progress = { fw.progress.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth())
                                Text(fw.message, color = if (fw.stage == FirmwareUpdateUiState.Stage.ERROR) Danger else Muted, fontSize = 10.sp)
                            }
                            Button(
                                onClick = viewModel::updateFirmwareFromBundled,
                                enabled = viewModel.settings.ipAddress.isNotBlank(),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.SystemUpdate, null)
                                Spacer(Modifier.width(6.dp))
                                Text("ESP32 OTA")
                            }
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        SettingsSection("Wi-Fi camera", "Bluetooth 조종과 별도로 카메라 연결") {
                            OutlinedTextField(
                                value = ssid,
                                onValueChange = { ssid = it },
                                label = { Text("SSID") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text("Password") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { viewModel.provisionWifi(ssid, password) }, enabled = ssid.isNotBlank()) {
                                    Text("Wi-Fi 저장")
                                }
                                Button(onClick = viewModel::switchEsp32ToWifi) {
                                    Text("카메라 시작")
                                }
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
                            FilledTonalButton(onClick = viewModel::refreshWifiStatus, enabled = ip.isNotBlank()) {
                                Icon(Icons.Default.Wifi, null)
                                Spacer(Modifier.width(6.dp))
                                Text("Wi-Fi STATUS")
                            }
                            wifiStatus?.let {
                                Text("${it.optString("ssid")} · ${it.optString("ip")}", color = Accent, fontSize = 10.sp)
                            }
                            wifiError?.let { Text(it, color = Danger, fontSize = 10.sp) }
                        }
                    }

                    item {
                        SettingsSection("Android app update", "시작할 때 자동으로 최신 릴리즈를 확인") {
                            UpdateCard(
                                state = updateState,
                                compact = compact,
                                onCheck = {
                                    val activity = context as? Activity ?: return@UpdateCard
                                    scope.launch { AppUpdater.checkForUpdate(activity, installWhenReady = true) }
                                },
                                onAction = {
                                    val activity = context as? Activity ?: return@UpdateCard
                                    when (updateState.stage) {
                                        AppUpdateStage.READY -> AppUpdater.installReadyUpdate(activity)
                                        AppUpdateStage.SIGNATURE_MISMATCH -> AppUpdater.openReleasePage(activity)
                                        else -> scope.launch { AppUpdater.checkForUpdate(activity, installWhenReady = true) }
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
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(subtitle, color = Muted, fontSize = 9.sp)
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
        Column(
            Modifier.fillMaxWidth().padding(if (compact) 11.dp else 13.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SystemUpdate, null, Modifier.size(16.dp), tint = if (problem) Danger else Accent)
                Spacer(Modifier.width(7.dp))
                Text("APP UPDATE", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.weight(1f))
                if (state.currentVersion.isNotBlank()) {
                    Text("v${state.currentVersion}", color = Muted, fontSize = 9.sp)
                }
            }

            Text(state.message, color = if (problem) Color(0xFFFFA2AB) else Color(0xFFB3BDC6), fontSize = 9.sp)

            if (state.stage == AppUpdateStage.DOWNLOADING) {
                LinearProgressIndicator(progress = { state.progress.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth())
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCheck, enabled = !active) {
                    Text(if (active) "확인 중" else "다시 확인", fontSize = 9.sp)
                }
                when (state.stage) {
                    AppUpdateStage.READY -> Button(onClick = onAction) { Text("설치", fontSize = 9.sp) }
                    AppUpdateStage.SIGNATURE_MISMATCH -> Button(onClick = onAction) { Text("릴리즈 열기", fontSize = 9.sp) }
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun ReliableCameraLayer(
    ip: String,
    retryKey: Int,
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

    Box(modifier.background(ScreenBg), contentAlignment = Alignment.Center) {
        latestBitmap?.let {
            Image(it.asImageBitmap(), "ESP32 camera", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } ?: Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(Icons.Default.Videocam, null, Modifier.size(34.dp), tint = Color(0xFF4D5964))
            Text(
                if (ip.isBlank()) "Bluetooth connected · Camera off" else (error ?: "Connecting camera…"),
                color = Color(0xFF73808B),
                fontSize = 10.sp
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

private fun Activity.lifecycleScopeSafe(block: suspend () -> Unit) {
    lifecycleScope.launch { block() }
}
