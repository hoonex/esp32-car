package io.github.hoonex.esp32car.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hoonex.esp32car.bluetooth.ConnectionState
import io.github.hoonex.esp32car.model.DriveDirection
import io.github.hoonex.esp32car.model.TransportMode
import io.github.hoonex.esp32car.viewmodel.RcViewModel

@Composable
fun DriveScreen(viewModel: RcViewModel) {
    val mode by viewModel.transportMode.collectAsStateWithLifecycle()
    val btState by viewModel.bluetooth.connectionState.collectAsStateWithLifecycle()
    val btName by viewModel.bluetooth.connectedDeviceName.collectAsStateWithLifecycle()
    val wifiStatus by viewModel.wifiStatus.collectAsStateWithLifecycle()
    val wifiError by viewModel.wifiError.collectAsStateWithLifecycle()
    val speed by viewModel.speed.collectAsStateWithLifecycle()
    val trim by viewModel.trim.collectAsStateWithLifecycle()
    val light by viewModel.light.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.reloadTuningFromSettings() }

    val ready = when (mode) {
        TransportMode.BLUETOOTH -> btState == ConnectionState.CONNECTED
        TransportMode.WIFI -> viewModel.settings.ipAddress.isNotBlank()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = "RC Drive",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black
            )
            Text(
                text = "ESP32-CAM 제어 허브",
                style = MaterialTheme.typography.bodyMedium,
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
                    Text("전송 방식", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = mode == TransportMode.BLUETOOTH,
                            onClick = { viewModel.setTransportMode(TransportMode.BLUETOOTH) },
                            label = { Text("Bluetooth SPP") },
                            leadingIcon = { Icon(Icons.Default.Bluetooth, null) }
                        )
                        FilterChip(
                            selected = mode == TransportMode.WIFI,
                            onClick = { viewModel.setTransportMode(TransportMode.WIFI) },
                            label = { Text("Wi-Fi") },
                            leadingIcon = { Icon(Icons.Default.Wifi, null) }
                        )
                    }

                    val statusText = when (mode) {
                        TransportMode.BLUETOOTH -> when (btState) {
                            ConnectionState.CONNECTED -> "연결됨 · ${btName ?: "ESP32"}"
                            ConnectionState.CONNECTING -> "Bluetooth 연결 중"
                            ConnectionState.DISCONNECTED -> "Bluetooth 연결 필요"
                        }
                        TransportMode.WIFI -> when {
                            wifiStatus != null -> "Wi-Fi 응답 정상 · ${viewModel.settings.ipAddress}"
                            wifiError != null -> "Wi-Fi 미확인 · ${wifiError}"
                            else -> "Wi-Fi · ${viewModel.settings.ipAddress}"
                        }
                    }
                    Text(
                        text = statusText,
                        color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        item {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val wide = maxWidth >= 720.dp
                if (wide) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        ControlPad(
                            modifier = Modifier.weight(1f),
                            enabled = ready,
                            onDrive = viewModel::drive,
                            onStop = viewModel::emergencyStop
                        )
                        TuningCard(
                            modifier = Modifier.weight(1f),
                            speed = speed,
                            trim = trim,
                            light = light,
                            onSpeed = viewModel::updateSpeed,
                            onTrim = viewModel::updateTrim,
                            onLight = viewModel::updateLight
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        ControlPad(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = ready,
                            onDrive = viewModel::drive,
                            onStop = viewModel::emergencyStop
                        )
                        TuningCard(
                            modifier = Modifier.fillMaxWidth(),
                            speed = speed,
                            trim = trim,
                            light = light,
                            onSpeed = viewModel::updateSpeed,
                            onTrim = viewModel::updateTrim,
                            onLight = viewModel::updateLight
                        )
                    }
                }
            }
        }

        item {
            Button(
                onClick = viewModel::emergencyStop,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                enabled = ready,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                shape = RoundedCornerShape(18.dp)
            ) {
                Icon(Icons.Default.Stop, null)
                Spacer(Modifier.size(8.dp))
                Text("EMERGENCY STOP", fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
private fun ControlPad(
    modifier: Modifier,
    enabled: Boolean,
    onDrive: (DriveDirection) -> Unit,
    onStop: () -> Unit
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Drive pad", fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(
                    if (enabled) "READY" else "LOCKED",
                    color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold
                )
            }

            HoldDirectionButton(
                direction = DriveDirection.FORWARD,
                icon = { Icon(Icons.Default.ArrowUpward, "Forward") },
                enabled = enabled,
                onDrive = onDrive,
                onStop = onStop
            )
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                HoldDirectionButton(
                    direction = DriveDirection.LEFT,
                    icon = { Icon(Icons.Default.ArrowBack, "Left") },
                    enabled = enabled,
                    onDrive = onDrive,
                    onStop = onStop
                )
                Box(
                    modifier = Modifier
                        .size(74.dp)
                        .background(MaterialTheme.colorScheme.errorContainer, CircleShape)
                        .pointerInput(enabled) {
                            if (enabled) detectTapGestures(onTap = { onStop() })
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Stop, "Stop", tint = MaterialTheme.colorScheme.onErrorContainer)
                }
                HoldDirectionButton(
                    direction = DriveDirection.RIGHT,
                    icon = { Icon(Icons.Default.ArrowForward, "Right") },
                    enabled = enabled,
                    onDrive = onDrive,
                    onStop = onStop
                )
            }
            HoldDirectionButton(
                direction = DriveDirection.BACKWARD,
                icon = { Icon(Icons.Default.ArrowDownward, "Backward") },
                enabled = enabled,
                onDrive = onDrive,
                onStop = onStop
            )
        }
    }
}

@Composable
private fun HoldDirectionButton(
    direction: DriveDirection,
    icon: @Composable () -> Unit,
    enabled: Boolean,
    onDrive: (DriveDirection) -> Unit,
    onStop: () -> Unit
) {
    val container = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = Modifier
            .size(74.dp)
            .background(container, CircleShape)
            .pointerInput(direction, enabled) {
                if (enabled) {
                    detectTapGestures(
                        onPress = {
                            onDrive(direction)
                            tryAwaitRelease()
                            onStop()
                        }
                    )
                }
            },
        contentAlignment = Alignment.Center
    ) {
        icon()
    }
}

@Composable
private fun TuningCard(
    modifier: Modifier,
    speed: Float,
    trim: Float,
    light: Float,
    onSpeed: (Float) -> Unit,
    onTrim: (Float) -> Unit,
    onLight: (Float) -> Unit
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Tuning", fontWeight = FontWeight.Bold)
            TuningSlider("속도", speed, 50f..255f, onSpeed)
            TuningSlider("조향 Trim", trim, -50f..50f, onTrim)
            TuningSlider("라이트", light, 0f..255f, onLight)
        }
    }
}

@Composable
private fun TuningSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Text(value.toInt().toString(), fontWeight = FontWeight.Bold)
        }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onValueChange, valueRange = range)
    }
}
