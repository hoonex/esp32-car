package io.github.hoonex.esp32car.ui.screens

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hoonex.esp32car.bluetooth.ConnectionState
import io.github.hoonex.esp32car.viewmodel.FirmwareUpdateUiState
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
    val firmwareUpdate by viewModel.firmwareUpdate.collectAsStateWithLifecycle()

    var ip by remember { mutableStateOf(viewModel.settings.ipAddress) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var showProvisionDialog by remember { mutableStateOf(false) }

    val pairedDevices = remember(refreshKey, connectionState) {
        viewModel.pairedDevices()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "SYSTEM",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "Car setup",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black
                )
                Text(
                    "Connection, network and firmware. Nothing else.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            ConnectionCard(
                connectionState = connectionState,
                connectedName = connectedName,
                connectedAddress = connectedAddress,
                lastError = lastError,
                hasLastDevice = viewModel.bluetooth.lastDeviceAddress() != null,
                onReconnect = { viewModel.reconnectLast() },
                onDisconnect = viewModel::disconnectBluetooth,
                onRefresh = {
                    refreshKey += 1
                    viewModel.refreshBluetoothStatus()
                }
            )
        }

        if (connectionState != ConnectionState.CONNECTED) {
            if (pairedDevices.isEmpty()) {
                item {
                    EmptyPairedDeviceCard()
                }
            } else {
                item {
                    SectionLabel("Paired devices")
                }
                items(pairedDevices, key = { it.address }) { device ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Bluetooth,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    device.name ?: "Bluetooth device",
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    device.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            FilledTonalButton(
                                onClick = { viewModel.connect(device) }
                            ) {
                                Text("Connect")
                            }
                        }
                    }
                }
            }
        }

        item {
            NetworkCard(
                ip = ip,
                onIpChange = { ip = it },
                connectionState = connectionState,
                wifiReady = wifiStatus != null,
                wifiError = wifiError,
                onSaveAndCheck = {
                    viewModel.updateIp(ip)
                    viewModel.refreshWifiStatus()
                },
                onProvision = { showProvisionDialog = true },
                onSwitchToWifi = viewModel::switchEsp32ToWifi
            )
        }

        item {
            val currentFirmware = wifiStatus
                ?.optString("fw")
                ?.takeIf { it.isNotBlank() }
                ?: btStatus
                    ?.optString("fw")
                    ?.takeIf { it.isNotBlank() }
                ?: viewModel.settings.lastFirmwareVersion.ifBlank { "unknown" }

            FirmwareCard(
                currentFirmware = currentFirmware,
                bundledFirmware = firmwareUpdate.bundledVersion,
                hasOtaKey = viewModel.settings.otaKey.isNotBlank(),
                state = firmwareUpdate,
                bluetoothConnected = connectionState == ConnectionState.CONNECTED,
                onUpdate = viewModel::updateFirmwareFromBundled,
                onRecoveryAp = viewModel::startRecoveryOtaAp
            )
        }

        item {
            DiagnosticsCard(
                bluetoothState = connectionState,
                wifiReady = wifiStatus != null,
                ip = viewModel.settings.ipAddress,
                firmware = viewModel.settings.lastFirmwareVersion
            )
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
private fun ConnectionCard(
    connectionState: ConnectionState,
    connectedName: String?,
    connectedAddress: String?,
    lastError: String?,
    hasLastDevice: Boolean,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRefresh: () -> Unit
) {
    val connected = connectionState == ConnectionState.CONNECTED

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(26.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = if (connected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                ) {
                    Icon(
                        Icons.Default.Bluetooth,
                        contentDescription = null,
                        tint = if (connected) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(12.dp)
                    )
                }

                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        when (connectionState) {
                            ConnectionState.CONNECTED -> connectedName ?: "ESP32 Car"
                            ConnectionState.CONNECTING -> "Connecting…"
                            ConnectionState.DISCONNECTED -> "Bluetooth offline"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        connectedAddress ?: "Classic Bluetooth SPP",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                }
            }

            if (lastError != null) {
                Text(
                    lastError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (connected) {
                    FilledTonalButton(onClick = onRefresh) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Status")
                    }
                    Button(onClick = onDisconnect) {
                        Text("Disconnect")
                    }
                } else if (hasLastDevice) {
                    Button(onClick = onReconnect) {
                        Icon(
                            Icons.Default.Link,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Reconnect")
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyPairedDeviceCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            "No paired controller found. Pair ESP32_CAM_RC in Android Bluetooth settings, then return here.",
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun NetworkCard(
    ip: String,
    onIpChange: (String) -> Unit,
    connectionState: ConnectionState,
    wifiReady: Boolean,
    wifiError: String?,
    onSaveAndCheck: () -> Unit,
    onProvision: () -> Unit,
    onSwitchToWifi: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(26.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Router,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    "Network",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (wifiReady) "ONLINE" else "OFFLINE",
                    color = if (wifiReady) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = FontWeight.ExtraBold
                )
            }

            OutlinedTextField(
                value = ip,
                onValueChange = onIpChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("ESP32 address") },
                placeholder = { Text("192.168.4.1") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri
                )
            )

            if (wifiError != null) {
                Text(
                    wifiError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSaveAndCheck) {
                    Icon(
                        Icons.Default.Wifi,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Save + check")
                }

                if (connectionState == ConnectionState.CONNECTED) {
                    FilledTonalButton(onClick = onProvision) {
                        Text("Provision")
                    }
                }
            }

            if (connectionState == ConnectionState.CONNECTED) {
                FilledTonalButton(
                    onClick = onSwitchToWifi,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Switch ESP32 to Wi-Fi mode")
                }
            }
        }
    }
}

@Composable
private fun FirmwareCard(
    currentFirmware: String,
    bundledFirmware: String,
    hasOtaKey: Boolean,
    state: FirmwareUpdateUiState,
    bluetoothConnected: Boolean,
    onUpdate: () -> Unit,
    onRecoveryAp: () -> Unit
) {
    val busy = state.stage in setOf(
        FirmwareUpdateUiState.Stage.PREPARING,
        FirmwareUpdateUiState.Stage.UPLOADING,
        FirmwareUpdateUiState.Stage.REBOOTING
    )

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(26.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.SystemUpdateAlt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    "Firmware",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                VersionTile(
                    modifier = Modifier.weight(1f),
                    label = "CAR",
                    version = currentFirmware
                )
                VersionTile(
                    modifier = Modifier.weight(1f),
                    label = "BUNDLED",
                    version = bundledFirmware
                )
            }

            Text(
                if (hasOtaKey) {
                    "OTA authorization ready"
                } else {
                    "Connect by Bluetooth and refresh Status once to get the OTA key."
                },
                color = if (hasOtaKey) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.error
                },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold
            )

            if (busy) {
                LinearProgressIndicator(
                    progress = { state.progress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (state.message.isNotBlank()) {
                Text(
                    state.message,
                    color = if (state.stage == FirmwareUpdateUiState.Stage.ERROR) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Button(
                onClick = onUpdate,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (busy) "Updating…" else "Update firmware from this phone",
                    fontWeight = FontWeight.Bold
                )
            }

            if (bluetoothConnected) {
                FilledTonalButton(
                    onClick = onRecoveryAp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Start offline recovery Wi-Fi")
                }
                Text(
                    "Recovery AP: ESP32-CAR-UPDATE · password esp32car · 192.168.4.1",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun VersionTile(
    modifier: Modifier,
    label: String,
    version: String
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold
            )
            Text(
                version,
                fontWeight = FontWeight.Black
            )
        }
    }
}

@Composable
private fun DiagnosticsCard(
    bluetoothState: ConnectionState,
    wifiReady: Boolean,
    ip: String,
    firmware: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Memory,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Quick diagnostics",
                    fontWeight = FontWeight.Bold
                )
            }
            DiagnosticRow(
                label = "Bluetooth",
                value = bluetoothState.name.lowercase().replaceFirstChar { it.uppercase() }
            )
            DiagnosticRow(
                label = "Wi-Fi",
                value = if (wifiReady) ip.ifBlank { "Online" } else "Offline"
            )
            DiagnosticRow(
                label = "Last firmware",
                value = firmware.ifBlank { "Unknown" }
            )
        }
    }
}

@Composable
private fun DiagnosticRow(
    label: String,
    value: String
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        Text(
            value,
            fontWeight = FontWeight.SemiBold
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
        title = { Text("Wi-Fi provisioning") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Send the Wi-Fi or phone-hotspot credentials that the ESP32 should join."
                )
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
            TextButton(
                onClick = { onSubmit(ssid, password) },
                enabled = ssid.isNotBlank()
            ) {
                Text("Send")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
