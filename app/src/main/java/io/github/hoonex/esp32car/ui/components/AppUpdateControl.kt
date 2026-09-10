package io.github.hoonex.esp32car.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hoonex.esp32car.bluetooth.ConnectionState
import io.github.hoonex.esp32car.update.AppUpdateStage
import io.github.hoonex.esp32car.update.AppUpdateState
import io.github.hoonex.esp32car.update.AppUpdater
import io.github.hoonex.esp32car.viewmodel.RcViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val UpdatePanel = Color(0xFF11151A)
private val UpdateLine = Color(0xFF29313A)
private val UpdateText = Color(0xFFF4F7FA)
private val UpdateMuted = Color(0xFF8D98A3)
private val UpdateMint = Color(0xFF55D6BE)
private val UpdateBlue = Color(0xFF61C8F2)
private val UpdateAmber = Color(0xFFF0C66A)
private val UpdateRed = Color(0xFFEF6670)

@Composable
fun AppUpdateControl(
    viewModel: RcViewModel,
    modifier: Modifier = Modifier
) {
    val update by AppUpdater.state.collectAsStateWithLifecycle()
    val connection by viewModel.bluetooth.connectionState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val scope = rememberCoroutineScope()
    var dialogOpen by remember { mutableStateOf(false) }

    val attention = update.stage == AppUpdateStage.AVAILABLE || update.stage == AppUpdateStage.READY
    val busy = update.stage == AppUpdateStage.CHECKING ||
        update.stage == AppUpdateStage.DOWNLOADING ||
        update.stage == AppUpdateStage.INSTALLING ||
        update.stage == AppUpdateStage.WAITING_PERMISSION
    val current = update.currentVersion.ifBlank { "…" }
    val latest = update.latestVersion.ifBlank { current }

    Surface(
        onClick = { dialogOpen = true },
        modifier = modifier,
        color = if (attention) UpdateAmber.copy(alpha = 0.16f) else UpdatePanel.copy(alpha = 0.95f),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (attention) UpdateAmber.copy(alpha = 0.65f) else UpdateLine
        )
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when {
                busy -> CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                attention -> Icon(Icons.Default.Download, null, Modifier.size(13.dp), tint = UpdateAmber)
                else -> Icon(Icons.Default.Refresh, null, Modifier.size(13.dp), tint = UpdateMuted)
            }
            Spacer(Modifier.width(6.dp))
            Column {
                Text("APP", color = UpdateMuted, fontSize = 6.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (update.stage == AppUpdateStage.AVAILABLE) "$current → $latest" else "v$current",
                    color = if (attention) UpdateAmber else UpdateText,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    if (dialogOpen) {
        AppUpdateDialog(
            state = update,
            carConnected = connection == ConnectionState.CONNECTED,
            onDismiss = { if (!busy) dialogOpen = false },
            onCheck = {
                val target = activity ?: return@AppUpdateDialog
                scope.launch { AppUpdater.checkForUpdate(target) }
            },
            onDownload = {
                val target = activity ?: return@AppUpdateDialog
                scope.launch { AppUpdater.downloadAvailableUpdate(target) }
            },
            onInstall = {
                val target = activity ?: return@AppUpdateDialog
                if (connection == ConnectionState.CONNECTED) {
                    scope.launch {
                        viewModel.emergencyStop()
                        delay(150)
                        viewModel.disconnectBluetooth()
                        delay(300)
                        AppUpdater.installReadyUpdate(target)
                    }
                } else {
                    AppUpdater.installReadyUpdate(target)
                }
            },
            onOpenRelease = {
                activity?.let(AppUpdater::openReleasePage)
            }
        )
    }
}

@Composable
private fun AppUpdateDialog(
    state: AppUpdateState,
    carConnected: Boolean,
    onDismiss: () -> Unit,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onOpenRelease: () -> Unit
) {
    val busy = state.stage == AppUpdateStage.CHECKING ||
        state.stage == AppUpdateStage.DOWNLOADING ||
        state.stage == AppUpdateStage.INSTALLING ||
        state.stage == AppUpdateStage.WAITING_PERMISSION
    val current = state.currentVersion.ifBlank { "확인 중" }
    val latest = when {
        state.latestVersion.isNotBlank() -> state.latestVersion
        state.stage == AppUpdateStage.CHECKING -> "확인 중"
        else -> "—"
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = UpdatePanel,
        title = { Text("앱 업데이트", color = UpdateText, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                VersionRow("현재 버전", current, UpdateText)
                VersionRow(
                    "최신 버전",
                    latest,
                    if (state.stage == AppUpdateStage.AVAILABLE || state.stage == AppUpdateStage.READY) UpdateMint else UpdateText
                )

                Text(
                    state.message,
                    color = when (state.stage) {
                        AppUpdateStage.ERROR, AppUpdateStage.SIGNATURE_MISMATCH -> UpdateRed
                        AppUpdateStage.AVAILABLE, AppUpdateStage.READY -> UpdateAmber
                        else -> UpdateMuted
                    },
                    fontSize = 9.sp
                )

                if (state.stage == AppUpdateStage.DOWNLOADING) {
                    LinearProgressIndicator(
                        progress = { state.progress.coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (state.stage == AppUpdateStage.READY && carConnected) {
                    Text(
                        "설치하면 앱이 종료되므로 주행 명령을 먼저 정지하고 Bluetooth를 끊은 뒤 Android 설치 화면을 엽니다.",
                        color = UpdateAmber,
                        fontSize = 8.sp
                    )
                }
            }
        },
        confirmButton = {
            when (state.stage) {
                AppUpdateStage.IDLE, AppUpdateStage.UP_TO_DATE, AppUpdateStage.ERROR -> {
                    Button(onClick = onCheck) { Text("업데이트 확인") }
                }
                AppUpdateStage.AVAILABLE -> {
                    Button(onClick = onDownload) { Text("v${state.latestVersion} 업데이트") }
                }
                AppUpdateStage.READY -> {
                    Button(onClick = onInstall) {
                        Text(if (carConnected) "정지 · 연결 해제 후 설치" else "v${state.latestVersion} 설치")
                    }
                }
                AppUpdateStage.SIGNATURE_MISMATCH -> {
                    Button(onClick = onOpenRelease) { Text("릴리즈 보기") }
                }
                AppUpdateStage.CHECKING -> {
                    Button(enabled = false, onClick = {}) { Text("확인 중") }
                }
                AppUpdateStage.DOWNLOADING -> {
                    Button(enabled = false, onClick = {}) { Text("다운로드 ${state.progress}%") }
                }
                AppUpdateStage.WAITING_PERMISSION -> {
                    Button(enabled = false, onClick = {}) { Text("권한 대기 중") }
                }
                AppUpdateStage.INSTALLING -> {
                    Button(enabled = false, onClick = {}) { Text("설치 준비 중") }
                }
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.releaseUrl.isNotBlank() && state.stage != AppUpdateStage.SIGNATURE_MISMATCH) {
                    TextButton(onClick = onOpenRelease) { Text("릴리즈") }
                }
                TextButton(enabled = !busy, onClick = onDismiss) { Text("닫기") }
            }
        }
    )
}

@Composable
private fun VersionRow(label: String, version: String, valueColor: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = UpdateMuted, fontSize = 9.sp, modifier = Modifier.weight(1f))
        Text("v$version", color = valueColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
