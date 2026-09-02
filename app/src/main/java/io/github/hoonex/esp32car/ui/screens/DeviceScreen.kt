package io.github.hoonex.esp32car.ui.screens

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hoonex.esp32car.bluetooth.ConnectionState
import io.github.hoonex.esp32car.viewmodel.RcViewModel

@SuppressLint("MissingPermission")
@Composable
fun DeviceScreen(viewModel: RcViewModel) {
    val connectionState by viewModel.bluetooth.connectionState.collectAsStateWithLifecycle()
    val connectedName by viewModel.bluetooth.connectedDeviceName.collectAsStateWithLifecycle()
    val connectedAddress by viewModel.bluetooth.connectedDeviceAddress.collectAsStateWithLifecycle()
    val lastError by viewModel.bluetooth.lastError.collectAsStateWithLifecycle()
    val btStatus by viewModel.bluetooth.btStatusResponse.collectAsStateWithLifecycle()
    val wifiStatus by viewModel.wifiStatus.collectAsStateWithLifecycle()
    val wifiError by viewModel.wifiError.collectAsStateWithLifecycle()

    var ip by remember { mutableStateOf(viewModel.settings.ipAddress) }
    var refreshKey by remember { mutableStateOf(0) }
    var showProvisionDialog by remember { mutableStateOf(false) }
    val pairedDevices = remember(refreshKey, connectionState) { viewModel.pairedDevices() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Device", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
            Text(
                "연결, Wi-Fi 프로비저닝, ESP32 상태",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row {
                        Icon(Icons.Default.Bluetooth, null)
                        Text("  Bluetooth SPP", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { refreshKey++ }) { Icon(Icons.Default.Refresh, "Refresh") }
                    }

                    Text(
                        when (connectionState) {
                            ConnectionState.CONNECTED -> "연결됨 · ${connectedName ?: connectedAddress.orEmpty()}"
                            ConnectionState.CONNECTING -> "연결 중 · ${connectedName.orEmpty()}"
                            ConnectionState.DISCONNECTED -> "연결 안 됨"
                        },
                        color = if (connectionState == ConnectionState.CONNECTED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (lastError != null) {
                        Text(lastError.orEmpty(), color = MaterialTheme.colorScheme.error)
                    }

                    if (connectionState == ConnectionState.CONNECTED) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = viewModel::refreshBluetoothStatus) { Text("STATUS") }
                            Button(onClick = viewModel::disconnectBluetooth) { Text("연결 해제") }
                        }
                    } else {
                        val hasLast = viewModel.bluetooth.lastDeviceAddress() != null
                        if (hasLast) {
                            FilledTonalButton(onClick = { viewModel.reconnectLast() }) {
                                Icon(Icons.Default.Link, null)
                                Text("  마지막 기기 재연결")
                            }
                        }
                    }

                    if (pairedDevices.isEmpty()) {
                        Text(
                            "페어링된 기기가 없습니다. Android Bluetooth 설정에서 ESP32_CAM_RC를 먼저 페어링하세요.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text("페어링된 기기", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        items(pairedDevices, key = { it.address }) { device ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(device.name ?: "Bluetooth device", fontWeight = FontWeight.Bold)
                        Text(device.address, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FilledTonalButton(
                        onClick = { viewModel.connect(device) },
                        enabled = !(connectionState == ConnectionState.CONNECTED && connectedAddress == device.address)
                    ) {
                        Text(if (connectedAddress == device.address && connectionState == ConnectionState.CONNECTED) "연결됨" else "연결")
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row {
                        Icon(Icons.Default.Wifi, null)
                        Text("  Wi-Fi controller", fontWeight = FontWeight.Bold)
                    }
                    OutlinedTextField(
                        value = ip,
                        onValueChange = { ip = it },
                        label = { Text("ESP32 IP") },
                        placeholder = { Text("192.168.4.1") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            viewModel.updateIp(ip)
                            viewModel.refreshWifiStatus()
                        }) { Text("저장 + 확인") }
                        if (connectionState == ConnectionState.CONNECTED) {
                            FilledTonalButton(onClick = { showProvisionDialog = true }) { Text("Wi-Fi 설정 전송") }
                        }
                    }

                    when {
                        wifiStatus != null -> Text(
                            "응답 정상 · mode=${wifiStatus?.optString("mode", "?")}",
                            color = MaterialTheme.colorScheme.primary
                        )
                        wifiError != null -> Text(wifiError.orEmpty(), color = MaterialTheme.colorScheme.error)
                    }

                    if (connectionState == ConnectionState.CONNECTED) {
                        FilledTonalButton(onClick = viewModel::switchEsp32ToWifi) {
                            Text("ESP32를 Wi-Fi 모드로 전환")
                        }
                    }

                    if (btStatus != null) {
                        Text(
                            "ESP32 STATUS · ${btStatus.toString()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Protocol", fontWeight = FontWeight.Bold)
                    Text("Classic Bluetooth SPP · UUID 00001101-0000-1000-8000-00805F9B34FB")
                    Text("Drive: F / B / L / R / S · Speed: V50..255 · Trim: T-50..50 · Light: H0..255")
                    Text("Wi-Fi provisioning: W:SSID,PASSWORD · Switch to Wi-Fi: X · Status: STATUS")
                }
            }
        }
    }

    if (showProvisionDialog) {
        WifiProvisionDialog(
            onDismiss = { showProvisionDialog = false },
            onSubmit = { ssid, password ->
                viewModel.provisionWifi(ssid, password)
                showProvisionDialog = false
            }
        )
    }
}

@Composable
private fun WifiProvisionDialog(
    onDismiss: () -> Unit,
    onSubmit: (String, String) -> Unit
) {
    var ssid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wi-Fi 프로비저닝") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("ESP32가 접속할 Wi-Fi 또는 휴대폰 핫스팟 정보를 전송합니다.")
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
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(ssid, password) }, enabled = ssid.isNotBlank()) { Text("전송") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}
