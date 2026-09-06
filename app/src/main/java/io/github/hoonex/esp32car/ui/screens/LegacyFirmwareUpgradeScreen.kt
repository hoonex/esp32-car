package io.github.hoonex.esp32car.ui.screens

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hoonex.esp32car.viewmodel.FirmwareUpdateUiState
import io.github.hoonex.esp32car.viewmodel.RcViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.Inet4Address

private val LegacyBg = Color(0xFF070A0E)
private val LegacyPanel = Color(0xF20E141B)
private val LegacyAccent = Color(0xFFFFC857)
private val LegacyGood = Color(0xFF66E7B1)
private val LegacyMuted = Color(0xFF98A3AE)
private val LegacyDanger = Color(0xFFFF6677)

private const val RecoveryIp = "192.168.4.1"
private const val RecoverySsid = "ESP32-CAR-UPDATE"

@Composable
fun LegacyFirmwareUpgradeScreen(viewModel: RcViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by viewModel.bluetooth.btStatusResponse.collectAsStateWithLifecycle()
    val deviceIp by viewModel.bluetooth.wifiConnectedIp.collectAsStateWithLifecycle()
    val provisionedSsid by viewModel.bluetooth.wifiProvisionedSsid.collectAsStateWithLifecycle()
    val wifiStatus by viewModel.wifiStatus.collectAsStateWithLifecycle()
    val wifiError by viewModel.wifiError.collectAsStateWithLifecycle()
    val update by viewModel.firmwareUpdate.collectAsStateWithLifecycle()
    val connectedName by viewModel.bluetooth.connectedDeviceName.collectAsStateWithLifecycle()
    var probeInFlight by remember { mutableStateOf(false) }
    var routeError by remember { mutableStateOf<String?>(null) }
    var recoveryRouteReady by remember { mutableStateOf(false) }
    var lanSsid by remember { mutableStateOf("") }
    var lanPassword by remember { mutableStateOf("") }
    var lanProvisioning by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { clearRecoveryRoute(context, viewModel) }
    }

    LaunchedEffect(provisionedSsid) {
        val candidate = provisionedSsid.orEmpty().trim()
        if (lanSsid.isBlank() && candidate.isNotBlank() && candidate != RecoverySsid) {
            lanSsid = candidate
        }
    }

    val lanIp = deviceIp?.trim()?.takeIf { it.isNotBlank() && it != RecoveryIp && it != "0.0.0.0" }

    LaunchedEffect(lanIp) {
        val ip = lanIp ?: return@LaunchedEffect
        clearRecoveryRoute(context, viewModel)
        recoveryRouteReady = false
        routeError = null
        lanProvisioning = false
        probeInFlight = true
        viewModel.updateIp(ip)
        viewModel.refreshWifiStatus()
    }

    LaunchedEffect(wifiStatus, wifiError) {
        if (probeInFlight && (wifiStatus != null || wifiError != null)) {
            probeInFlight = false
        }
    }

    val legacyVersion = status?.optString("fw").orEmpty().ifBlank { "3.2.x" }
    val targetVersion = update.bundledVersion.takeUnless { it.isBlank() || it == "unknown" } ?: "3.3.0"
    val keyReady = status?.optString("ota_key").orEmpty().isNotBlank() || viewModel.settings.otaKey.isNotBlank()
    val recoveryApRequested = deviceIp == RecoveryIp
    val httpReachable = wifiStatus?.optString("fw").orEmpty().startsWith("3.2.")
    val activeIp = viewModel.settings.ipAddress.trim()
    val lanHttpReady = httpReachable && activeIp.isNotBlank() && activeIp != RecoveryIp
    val recoveryHttpReady = httpReachable && activeIp == RecoveryIp
    val migrationPathReady = lanHttpReady || recoveryHttpReady
    val updateBusy = update.stage == FirmwareUpdateUiState.Stage.PREPARING ||
        update.stage == FirmwareUpdateUiState.Stage.UPLOADING ||
        update.stage == FirmwareUpdateUiState.Stage.REBOOTING

    val probeDetail = when {
        lanHttpReady -> "공유기 LAN $activeIp · FW v${wifiStatus?.optString("fw")} · HTTP OTA 준비됨"
        recoveryHttpReady -> "$RecoverySsid · FW v${wifiStatus?.optString("fw")} · HTTP OTA 준비됨"
        probeInFlight -> "ESP32 HTTP :80 응답 확인 중"
        routeError != null -> routeError!!
        wifiError != null && activeIp == RecoveryIp -> "복구 AP의 HTTP :80 미응답 · 공유기 LAN 경로를 사용하세요."
        wifiError != null -> wifiError!!
        lanIp != null -> "ESP32가 $lanIp 로 연결됨 · HTTP 확인 필요"
        else -> "먼저 공유기 Wi-Fi 경로를 시도하세요."
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(LegacyBg)) {
        val compact = maxHeight < 420.dp
        val outer = if (compact) 12.dp else 22.dp
        val gap = if (compact) 12.dp else 20.dp

        Row(
            modifier = Modifier.fillMaxSize().padding(outer),
            horizontalArrangement = Arrangement.spacedBy(gap)
        ) {
            Column(
                modifier = Modifier.weight(0.42f).fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(if (compact) 9.dp else 14.dp)) {
                    Surface(color = Color(0x28FFC857), shape = RoundedCornerShape(999.dp)) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            Icon(Icons.Default.SystemUpdate, null, tint = LegacyAccent)
                            Text("LEGACY FIRMWARE MIGRATION", color = LegacyAccent, fontSize = 10.sp, fontWeight = FontWeight.Black)
                        }
                    }

                    Text("펌웨어 업데이트 필요", color = Color.White, fontSize = if (compact) 25.sp else 36.sp, fontWeight = FontWeight.Black)
                    Text(
                        "$connectedName · FW v$legacyVersion\nBluetooth는 정상입니다. 이 3.2.x 빌드의 ArduinoOTA 역접속 문제를 피해서 HTTP OTA로 v$targetVersion까지 올립니다.",
                        color = LegacyMuted,
                        fontSize = if (compact) 10.sp else 13.sp,
                        lineHeight = if (compact) 14.sp else 18.sp
                    )

                    Surface(color = Color(0x2019D790), shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Bluetooth, null, tint = LegacyGood)
                                Spacer(Modifier.width(7.dp))
                                Text("구형 SPP 링크 확인됨", color = LegacyGood, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                            Text("업데이트 중 주행은 차단하고 STATUS / U / W: / X만 허용합니다.", color = LegacyMuted, fontSize = 8.sp)
                        }
                    }
                }

                OutlinedButton(onClick = viewModel::disconnectBluetooth, enabled = !updateBusy) {
                    Text("취소하고 Bluetooth 연결 해제", fontSize = 9.sp)
                }
            }

            Surface(
                modifier = Modifier.weight(0.58f).fillMaxHeight(),
                color = LegacyPanel,
                shape = RoundedCornerShape(if (compact) 20.dp else 28.dp)
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(if (compact) 14.dp else 20.dp),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)
                ) {
                    Text("USB 없이 v$targetVersion 설치", color = Color.White, fontSize = if (compact) 16.sp else 21.sp, fontWeight = FontWeight.Black)
                    Text(
                        "우선 ESP32를 휴대폰과 같은 공유기 Wi-Fi에 붙여 /api/ota로 직접 전송합니다. 복구 AP는 HTTP가 실제로 열릴 때만 보조 경로로 씁니다.",
                        color = LegacyMuted,
                        fontSize = 9.sp
                    )

                    StepCard(
                        "1",
                        "권장 · 공유기 Wi-Fi로 ESP32 연결",
                        lanIp?.let { "ESP32 LAN IP $it" } ?: "휴대폰이 현재 연결된 2.4GHz Wi-Fi 정보를 입력"
                    , lanIp != null) {
                        OutlinedTextField(
                            value = lanSsid,
                            onValueChange = { lanSsid = it },
                            label = { Text("Wi-Fi SSID") },
                            singleLine = true,
                            enabled = !updateBusy && !lanProvisioning,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = lanPassword,
                            onValueChange = { lanPassword = it },
                            label = { Text("Wi-Fi 비밀번호") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            enabled = !updateBusy && !lanProvisioning,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                val ssid = lanSsid.trim()
                                if (ssid.isBlank()) {
                                    routeError = "Wi-Fi SSID를 입력하세요."
                                    return@Button
                                }
                                if (ssid.contains(',') || ssid.contains('\n') || ssid.contains('\r')) {
                                    routeError = "구형 펌웨어에서는 SSID에 쉼표/줄바꿈을 사용할 수 없습니다."
                                    return@Button
                                }
                                if (lanPassword.contains('\n') || lanPassword.contains('\r')) {
                                    routeError = "Wi-Fi 비밀번호에 줄바꿈을 사용할 수 없습니다."
                                    return@Button
                                }

                                clearRecoveryRoute(context, viewModel)
                                recoveryRouteReady = false
                                routeError = null
                                probeInFlight = false
                                lanProvisioning = true
                                viewModel.updateIp("")
                                viewModel.bluetooth.sendLegacyUpgradeCommand("W:$ssid,$lanPassword")
                                scope.launch {
                                    delay(450)
                                    viewModel.bluetooth.sendLegacyUpgradeCommand("X")
                                    delay(8_000)
                                    if (lanProvisioning && lanIp == null) {
                                        lanProvisioning = false
                                        routeError = "ESP32가 공유기 Wi-Fi에 연결되지 않았습니다. SSID/비밀번호와 2.4GHz 지원을 확인하세요."
                                    }
                                }
                            },
                            enabled = keyReady && !updateBusy && !lanProvisioning,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Wifi, null)
                            Spacer(Modifier.width(7.dp))
                            Text(if (lanProvisioning) "ESP32 Wi-Fi 연결 중..." else "ESP32를 이 Wi-Fi에 연결", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Text("휴대폰도 같은 Wi-Fi에 연결되어 있어야 합니다.", color = LegacyMuted, fontSize = 8.sp)
                    }

                    StepCard(
                        "2",
                        "HTTP OTA 연결 확인",
                        probeDetail,
                        migrationPathReady
                    ) {
                        OutlinedButton(
                            onClick = {
                                routeError = null
                                probeInFlight = true
                                when {
                                    lanIp != null -> {
                                        clearRecoveryRoute(context, viewModel)
                                        recoveryRouteReady = false
                                        viewModel.updateIp(lanIp)
                                        viewModel.refreshWifiStatus()
                                    }
                                    recoveryApRequested -> {
                                        viewModel.updateIp(RecoveryIp)
                                        recoveryRouteReady = routeRecoveryWifi(context, viewModel)
                                        if (!recoveryRouteReady) {
                                            probeInFlight = false
                                            routeError = "$RecoverySsid 의 로컬 IPv4 경로를 찾지 못했습니다."
                                            return@OutlinedButton
                                        }
                                        viewModel.refreshWifiStatus()
                                    }
                                    else -> {
                                        probeInFlight = false
                                        routeError = "먼저 공유기 Wi-Fi 연결을 완료하거나 복구 AP를 여세요."
                                    }
                                }
                            },
                            enabled = !updateBusy && !probeInFlight,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.CheckCircle, null)
                            Spacer(Modifier.width(7.dp))
                            Text(if (probeInFlight) "확인 중..." else "HTTP 연결 확인", fontSize = 10.sp)
                        }
                        if (routeError != null) Text(routeError!!, color = LegacyDanger, fontSize = 8.sp)
                        if (wifiError != null && routeError == null) {
                            Text(
                                if (activeIp == RecoveryIp) "복구 AP :80이 닫혀 있습니다. 위 공유기 LAN 경로를 사용하세요." else wifiError!!,
                                color = LegacyDanger,
                                fontSize = 8.sp
                            )
                        }
                    }

                    StepCard(
                        "3",
                        "보조 · ESP32-CAR-UPDATE 복구 AP",
                        if (recoveryApRequested) "복구 AP 요청됨" else "공유기 경로가 안 될 때만 사용",
                        recoveryHttpReady
                    ) {
                        Button(
                            onClick = {
                                routeError = null
                                probeInFlight = false
                                recoveryRouteReady = false
                                clearRecoveryRoute(context, viewModel)
                                viewModel.updateIp(RecoveryIp)
                                viewModel.bluetooth.sendLegacyUpgradeCommand("U")
                                scope.launch {
                                    delay(900)
                                    runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
                                }
                            },
                            enabled = keyReady && !updateBusy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Wifi, null)
                            Spacer(Modifier.width(7.dp))
                            Text("복구 AP 열고 Wi-Fi 설정으로", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "주의: 현재 v3.2.0의 ArduinoOTA 3232 역접속은 이미 실패가 확인됐으므로, 이 경로도 HTTP :80이 열릴 때만 설치합니다.",
                            color = LegacyAccent,
                            fontSize = 8.sp
                        )
                    }

                    StepCard(
                        "4",
                        "v$targetVersion 설치",
                        if (migrationPathReady) "HTTP /api/ota 직접 전송 후 재부팅 확인" else "HTTP OTA 연결 확인이 먼저 필요",
                        update.stage == FirmwareUpdateUiState.Stage.SUCCESS
                    ) {
                        if (updateBusy || update.stage == FirmwareUpdateUiState.Stage.SUCCESS || update.stage == FirmwareUpdateUiState.Stage.ERROR) {
                            if (update.stage == FirmwareUpdateUiState.Stage.UPLOADING || update.stage == FirmwareUpdateUiState.Stage.REBOOTING) {
                                LinearProgressIndicator(
                                    progress = { update.progress.coerceIn(0, 100) / 100f },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            if (update.message.isNotBlank()) {
                                Text(
                                    update.message,
                                    color = if (update.stage == FirmwareUpdateUiState.Stage.ERROR) LegacyDanger else LegacyMuted,
                                    fontSize = 8.sp
                                )
                            }
                        }

                        Button(
                            onClick = {
                                routeError = null
                                if (!migrationPathReady) {
                                    routeError = "HTTP OTA 경로가 준비되지 않았습니다."
                                    return@Button
                                }
                                if (activeIp == RecoveryIp) {
                                    recoveryRouteReady = routeRecoveryWifi(context, viewModel)
                                    if (!recoveryRouteReady) {
                                        routeError = "복구 Wi-Fi 연결이 끊겼습니다."
                                        return@Button
                                    }
                                } else {
                                    clearRecoveryRoute(context, viewModel)
                                    recoveryRouteReady = false
                                }
                                viewModel.updateFirmwareFromBundled()
                            },
                            enabled = migrationPathReady && keyReady && !updateBusy && update.stage != FirmwareUpdateUiState.Stage.SUCCESS,
                            colors = ButtonDefaults.buttonColors(containerColor = LegacyGood, contentColor = Color(0xFF062218)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.SystemUpdate, null)
                            Spacer(Modifier.width(7.dp))
                            Text("HTTP로 최신 펌웨어 설치", fontSize = 10.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }
            }
        }
    }
}

private fun routeRecoveryWifi(context: Context, viewModel: RcViewModel): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val recoveryNetwork = findRecoveryWifiNetwork(manager) ?: return false
    val localAddress = manager.getLinkProperties(recoveryNetwork)
        ?.linkAddresses
        ?.map { it.address }
        ?.filterIsInstance<Inet4Address>()
        ?.firstOrNull() ?: return false

    viewModel.rcClient.networkSocketFactoryOverride = recoveryNetwork.socketFactory
    viewModel.rcClient.recoveryNetwork = recoveryNetwork
    viewModel.rcClient.recoveryLocalAddress = localAddress
    runCatching { manager.bindProcessToNetwork(recoveryNetwork) }
    return true
}

private fun findRecoveryWifiNetwork(manager: ConnectivityManager): Network? {
    val wifiNetworks = manager.allNetworks.filter { network ->
        manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }
    if (wifiNetworks.isEmpty()) return null

    return wifiNetworks.firstOrNull { network ->
        manager.getLinkProperties(network)
            ?.linkAddresses
            ?.any { address -> address.address.hostAddress?.startsWith("192.168.4.") == true } == true
    } ?: wifiNetworks.first()
}

private fun clearRecoveryRoute(context: Context, viewModel: RcViewModel) {
    viewModel.rcClient.networkSocketFactoryOverride = null
    viewModel.rcClient.recoveryNetwork = null
    viewModel.rcClient.recoveryLocalAddress = null
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
    runCatching { manager.bindProcessToNetwork(null) }
}

@Composable
private fun StepCard(
    number: String,
    title: String,
    detail: String,
    done: Boolean,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        color = if (done) Color(0x1E19D790) else Color(0xFF141B23),
        shape = RoundedCornerShape(15.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(11.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = if (done) LegacyGood else LegacyAccent, shape = RoundedCornerShape(999.dp)) {
                    Text(number, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = Color(0xFF07100D), fontSize = 9.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(detail, color = LegacyMuted, fontSize = 8.sp)
                }
                if (done) Icon(Icons.Default.CheckCircle, null, tint = LegacyGood)
            }
            content()
        }
    }
}
