package io.github.hoonex.esp32car.ui.screens

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hoonex.esp32car.bluetooth.ConnectionState
import io.github.hoonex.esp32car.viewmodel.RcViewModel

private val AppBg = Color(0xFF070A0D)
private val AppPanel = Color(0xFF0E1318)
private val AppPanelSoft = Color(0xFF141B21)
private val AppLine = Color(0x22FFFFFF)
private val AppText = Color(0xFFF2F5F6)
private val AppMuted = Color(0xFF849099)
private val AppAccent = Color(0xFF65D6B3)
private val AppBlue = Color(0xFF63C9F2)
private val AppDanger = Color(0xFFE66A75)

@Composable
fun PremiumAppScreen(viewModel: RcViewModel) {
    val btState by viewModel.bluetooth.connectionState.collectAsStateWithLifecycle()
    if (btState == ConnectionState.CONNECTED) {
        PremiumPilotCockpitScreen(viewModel)
    } else {
        PremiumConnectionScreen(viewModel, btState)
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun PremiumConnectionScreen(viewModel: RcViewModel, btState: ConnectionState) {
    val context = LocalContext.current
    val discovered by viewModel.bluetooth.discoveredDevices.collectAsStateWithLifecycle()
    val discovering by viewModel.bluetooth.isDiscovering.collectAsStateWithLifecycle()
    val error by viewModel.bluetooth.lastError.collectAsStateWithLifecycle()
    val paired = runCatching { viewModel.pairedDevices() }.getOrDefault(emptyList())
    val devices = remember(discovered, paired) {
        (paired + discovered)
            .associateBy { runCatching { it.address }.getOrElse { it.hashCode().toString() } }
            .values
            .sortedWith(
                compareByDescending<BluetoothDevice> { runCatching { it.name.equals("ESP32_CAM_RC", true) }.getOrDefault(false) }
                    .thenBy { runCatching { it.name.orEmpty() }.getOrDefault("") }
            )
    }
    val target = devices.firstOrNull { runCatching { it.name.equals("ESP32_CAM_RC", true) }.getOrDefault(false) }

    BoxWithConstraints(Modifier.fillMaxSize().background(AppBg)) {
        val compact = maxHeight < 430.dp
        val edge = if (compact) 16.dp else 24.dp
        val gap = if (compact) 14.dp else 22.dp

        Column(Modifier.fillMaxSize().padding(edge)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("ESP32 CAR", color = AppText, fontSize = if (compact) 23.sp else 30.sp, fontWeight = FontWeight.Black)
                    Text("Bluetooth control  ·  Wi-Fi vision", color = AppMuted, fontSize = if (compact) 8.sp else 10.sp)
                }

                if (!viewModel.bluetooth.isBluetoothEnabled()) {
                    Button(onClick = {
                        runCatching { context.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                    }) {
                        Text("Bluetooth 켜기", fontSize = 9.sp)
                    }
                } else {
                    Surface(
                        onClick = {
                            if (discovering) viewModel.stopBluetoothScan() else viewModel.scanBluetooth()
                        },
                        color = if (discovering) AppBlue.copy(alpha = 0.16f) else AppPanel,
                        contentColor = if (discovering) AppBlue else AppText,
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (discovering) AppBlue.copy(alpha = 0.35f) else AppLine)
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Refresh, null, Modifier.size(15.dp))
                            Text(if (discovering) "검색 중" else "기기 검색", fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            if (discovering || btState == ConnectionState.CONNECTING) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
            }

            error?.takeIf { it.isNotBlank() }?.let {
                Surface(
                    modifier = Modifier.padding(top = 10.dp),
                    color = AppDanger.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AppDanger.copy(alpha = 0.22f))
                ) {
                    Text(
                        it,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        color = AppDanger,
                        fontSize = 8.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxSize().padding(top = gap),
                horizontalArrangement = Arrangement.spacedBy(gap)
            ) {
                Column(
                    modifier = Modifier.weight(0.38f).fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("차량 연결", color = AppText, fontSize = if (compact) 18.sp else 24.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "조종은 Bluetooth로, 카메라와 OTA는 Wi-Fi로 분리되어 동작합니다.",
                            color = AppMuted,
                            fontSize = if (compact) 9.sp else 11.sp,
                            lineHeight = if (compact) 13.sp else 16.sp
                        )

                        StatusLine("CONTROL", "Bluetooth SPP")
                        StatusLine("VISION", "Wi-Fi LAN")
                        StatusLine("FIRMWARE", "Wi-Fi OTA")
                    }

                    Surface(
                        color = AppPanel,
                        shape = RoundedCornerShape(18.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AppLine)
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("연결 순서", color = AppMuted, fontSize = 6.sp, letterSpacing = 1.sp)
                            Text("1. ESP32_CAM_RC 선택", color = AppText, fontSize = 9.sp)
                            Text("2. Bluetooth 연결 확인", color = AppText, fontSize = 9.sp)
                            Text("3. 필요할 때 Wi-Fi 연결", color = AppText, fontSize = 9.sp)
                        }
                    }
                }

                Surface(
                    modifier = Modifier.weight(0.62f).fillMaxHeight(),
                    color = AppPanel,
                    shape = RoundedCornerShape(if (compact) 20.dp else 24.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AppLine)
                ) {
                    Column(Modifier.fillMaxSize().padding(if (compact) 14.dp else 18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Devices", color = AppText, fontSize = if (compact) 14.sp else 18.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    if (target != null) "ESP32_CAM_RC가 준비되었습니다." else "ESP32_CAM_RC를 검색하세요.",
                                    color = if (target != null) AppAccent else AppMuted,
                                    fontSize = 8.sp
                                )
                            }
                            Surface(color = AppPanelSoft, shape = RoundedCornerShape(999.dp)) {
                                Text(
                                    "${devices.size}",
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    color = AppMuted,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(Modifier.width(1.dp).padding(top = 10.dp))

                        if (devices.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Surface(color = AppPanelSoft, shape = CircleShape) {
                                        Icon(Icons.Default.Bluetooth, null, Modifier.padding(14.dp).size(24.dp), tint = AppMuted)
                                    }
                                    Text("검색을 눌러 차량을 찾으세요", color = AppMuted, fontSize = 9.sp)
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize().padding(top = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(
                                    items = devices,
                                    key = { runCatching { it.address }.getOrElse { it.hashCode().toString() } }
                                ) { device ->
                                    PremiumDeviceRow(
                                        device = device,
                                        enabled = btState == ConnectionState.DISCONNECTED,
                                        onClick = {
                                            val bonded = runCatching { device.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)
                                            if (bonded) viewModel.connect(device) else viewModel.pairAndConnect(device)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(72.dp), color = AppMuted, fontSize = 6.sp, letterSpacing = 0.8.sp)
        Text(value, color = AppText, fontSize = 9.sp, fontWeight = FontWeight.Medium)
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun PremiumDeviceRow(device: BluetoothDevice, enabled: Boolean, onClick: () -> Unit) {
    val name = runCatching { device.name }.getOrNull().orEmpty().ifBlank { "Unknown device" }
    val address = runCatching { device.address }.getOrNull().orEmpty()
    val bonded = runCatching { device.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)
    val target = name.equals("ESP32_CAM_RC", true)

    Surface(
        color = if (target) AppAccent.copy(alpha = 0.10f) else AppPanelSoft,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (target) AppAccent.copy(alpha = 0.32f) else AppLine)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(color = if (target) AppAccent.copy(alpha = 0.16f) else Color(0xFF1B232A), shape = CircleShape) {
                Icon(
                    Icons.Default.Bluetooth,
                    null,
                    modifier = Modifier.padding(10.dp).size(18.dp),
                    tint = if (target) AppAccent else AppMuted
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = AppText, fontSize = 11.sp, fontWeight = if (target) FontWeight.Bold else FontWeight.SemiBold)
                Text(
                    "${if (bonded) "Paired" else "Not paired"}${if (address.isNotBlank()) "  ·  $address" else ""}",
                    color = AppMuted,
                    fontSize = 7.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Surface(
                onClick = onClick,
                enabled = enabled,
                color = if (target) AppBlue else Color(0xFF202830),
                contentColor = if (target) Color(0xFF071116) else AppText,
                shape = RoundedCornerShape(13.dp)
            ) {
                Text(
                    if (bonded) "CONNECT" else "PAIR",
                    modifier = Modifier.padding(horizontal = 15.dp, vertical = 9.dp),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
