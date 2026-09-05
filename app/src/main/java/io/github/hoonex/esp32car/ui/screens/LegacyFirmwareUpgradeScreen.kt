package io.github.hoonex.esp32car.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hoonex.esp32car.viewmodel.FirmwareUpdateUiState
import io.github.hoonex.esp32car.viewmodel.RcViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val LegacyBg = Color(0xFF070A0E)
private val LegacyPanel = Color(0xF20E141B)
private val LegacyAccent = Color(0xFFFFC857)
private val LegacyGood = Color(0xFF66E7B1)
private val LegacyMuted = Color(0xFF98A3AE)
private val LegacyDanger = Color(0xFFFF6677)

@Composable
fun LegacyFirmwareUpgradeScreen(viewModel: RcViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by viewModel.bluetooth.btStatusResponse.collectAsStateWithLifecycle()
    val wifiStatus by viewModel.wifiStatus.collectAsStateWithLifecycle()
    val wifiError by viewModel.wifiError.collectAsStateWithLifecycle()
    val update by viewModel.firmwareUpdate.collectAsStateWithLifecycle()
    val connectedName by viewModel.bluetooth.connectedDeviceName.collectAsStateWithLifecycle()

    val legacyVersion = status?.optString("fw").orEmpty().ifBlank { "3.2.x" }
    val keyReady = status?.optString("ota_key").orEmpty().isNotBlank() || viewModel.settings.otaKey.isNotBlank()
    val recoveryReachable = wifiStatus?.optString("fw").orEmpty().startsWith("3.2.")
    val updateBusy = update.stage == FirmwareUpdateUiState.Stage.PREPARING ||
        update.stage == FirmwareUpdateUiState.Stage.UPLOADING ||
        update.stage == FirmwareUpdateUiState.Stage.REBOOTING

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
                    Surface(
                        color = Color(0x28FFC857),
                        shape = RoundedCornerShape(999.dp)
                    ) {
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
                        "$connectedName · FW v$legacyVersion\nBluetooth는 정상적으로 열렸고, 이 버전은 앱 OTA로 v3.3.0까지 올릴 수 있습니다.",
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
                            Text("업데이트 중에는 주행 명령을 차단하고 STATUS/U만 허용합니다.", color = LegacyMuted, fontSize = 8.sp)
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
                    Modifier.fillMaxSize().padding(if (compact) 14.dp else 20.dp),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)
                ) {
                    Text("USB 없이 v3.3.0 설치", color = Color.White, fontSize = if (compact) 16.sp else 21.sp, fontWeight = FontWeight.Black)
                    Text("한 번만 복구 Wi-Fi에 연결하면 APK 안에 포함된 최신 펌웨어를 ESP32가 직접 받아 설치합니다.", color = LegacyMuted, fontSize = 9.sp)

                    StepCard(
                        number = "1",
                        title = "업데이트 Wi-Fi 열기",
                        detail = "ESP32-CAR-UPDATE / 비밀번호 esp32car",
                        done = keyReady
                    ) {
                        Button(
                            onClick = {
                                viewModel.updateIp("192.168.4.1")
                                viewModel.bluetooth.sendLegacyUpgradeCommand("U")
                                scope.launch {
                                    delay(650)
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
                    }

                    StepCard(
                        number = "2",
                        title = "ESP32-CAR-UPDATE 연결 확인",
                        detail = if (recoveryReachable) "FW v${wifiStatus?.optString("fw")} 응답 확인됨" else "Wi-Fi 연결 후 앱으로 돌아와 확인",
                        done = recoveryReachable
                    ) {
                        OutlinedButton(
                            onClick = {
                                viewModel.updateIp("192.168.4.1")
                                viewModel.refreshWifiStatus()
                            },
                            enabled = !updateBusy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.CheckCircle, null)
                            Spacer(Modifier.width(7.dp))
                            Text("연결 확인", fontSize = 10.sp)
                        }
                        wifiError?.let { Text(it, color = LegacyDanger, fontSize = 8.sp) }
                    }

                    StepCard(
                        number = "3",
                        title = "v3.3.0 설치",
                        detail = "업로드 후 재부팅된 실제 FW 버전까지 확인",
                        done = update.stage == FirmwareUpdateUiState.Stage.SUCCESS
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
                            onClick = viewModel::updateFirmwareFromBundled,
                            enabled = recoveryReachable && keyReady && !updateBusy && update.stage != FirmwareUpdateUiState.Stage.SUCCESS,
                            colors = ButtonDefaults.buttonColors(containerColor = LegacyGood, contentColor = Color(0xFF062218)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.SystemUpdate, null)
                            Spacer(Modifier.width(7.dp))
                            Text("최신 펌웨어 설치", fontSize = 10.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepCard(
    number: String,
    title: String,
    detail: String,
    done: Boolean,
    content: @Composable Column.() -> Unit
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
