package io.github.hoonex.esp32car.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hoonex.esp32car.bluetooth.ConnectionState
import io.github.hoonex.esp32car.model.DriveDirection
import io.github.hoonex.esp32car.model.TransportMode
import io.github.hoonex.esp32car.viewmodel.RcViewModel
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

@Composable
fun DriveScreen(
    viewModel: RcViewModel,
    onOpenVision: () -> Unit
) {
    val mode by viewModel.transportMode.collectAsStateWithLifecycle()
    val btState by viewModel.bluetooth.connectionState.collectAsStateWithLifecycle()
    val btName by viewModel.bluetooth.connectedDeviceName.collectAsStateWithLifecycle()
    val wifiStatus by viewModel.wifiStatus.collectAsStateWithLifecycle()
    val wifiError by viewModel.wifiError.collectAsStateWithLifecycle()
    val speed by viewModel.speed.collectAsStateWithLifecycle()
    val trim by viewModel.trim.collectAsStateWithLifecycle()
    val light by viewModel.light.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.reloadTuningFromSettings()
    }
    LaunchedEffect(mode) {
        if (mode == TransportMode.WIFI) {
            viewModel.refreshWifiStatus()
        }
    }

    val ready = when (mode) {
        TransportMode.BLUETOOTH -> btState == ConnectionState.CONNECTED
        TransportMode.WIFI -> wifiStatus != null
    }

    val connectionTitle = when (mode) {
        TransportMode.BLUETOOTH -> when (btState) {
            ConnectionState.CONNECTED -> btName ?: "ESP32 Car"
            ConnectionState.CONNECTING -> "Connecting…"
            ConnectionState.DISCONNECTED -> "No Bluetooth link"
        }
        TransportMode.WIFI -> when {
            wifiStatus != null -> viewModel.settings.ipAddress
            wifiError != null -> "Wi-Fi unavailable"
            else -> viewModel.settings.ipAddress.ifBlank { "No Wi-Fi target" }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(horizontal = 16.dp, vertical = 14.dp)),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ControlHeader(
            ready = ready,
            mode = mode,
            connectionTitle = connectionTitle,
            firmware = viewModel.settings.lastFirmwareVersion.ifBlank { "—" },
            onModeChange = viewModel::setTransportMode,
            onOpenVision = onOpenVision
        )

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            if (maxWidth >= 720.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    JoystickCard(
                        modifier = Modifier.weight(1.15f),
                        enabled = ready,
                        onDrive = viewModel::drive,
                        onStop = viewModel::emergencyStop
                    )
                    QuickTuningCard(
                        modifier = Modifier.weight(0.85f),
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
                    JoystickCard(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = ready,
                        onDrive = viewModel::drive,
                        onStop = viewModel::emergencyStop
                    )
                    QuickTuningCard(
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

        EmergencyStopButton(
            enabled = ready,
            onStop = viewModel::emergencyStop
        )

        Text(
            text = if (ready) {
                "Hold and drag inside the joystick. Releasing your finger always sends STOP."
            } else {
                "Connect the car in System before driving."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ControlHeader(
    ready: Boolean,
    mode: TransportMode,
    connectionTitle: String,
    firmware: String,
    onModeChange: (TransportMode) -> Unit,
    onOpenVision: () -> Unit
) {
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "CONTROL",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "ESP32 Car",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black
                    )
                }

                Surface(
                    onClick = onOpenVision,
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Videocam, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("VISION", fontWeight = FontWeight.Bold)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusDot(ready)
                Spacer(Modifier.width(9.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (ready) "READY" else "NOT READY",
                        fontWeight = FontWeight.ExtraBold,
                        color = if (ready) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                    Text(
                        connectionTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "FW $firmware",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            TransportSwitch(
                selected = mode,
                onSelected = onModeChange
            )
        }
    }
}

@Composable
private fun StatusDot(ready: Boolean) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(
                if (ready) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.error,
                CircleShape
            )
    )
}

@Composable
private fun TransportSwitch(
    selected: TransportMode,
    onSelected: (TransportMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(18.dp)
            )
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        TransportOption(
            modifier = Modifier.weight(1f),
            selected = selected == TransportMode.BLUETOOTH,
            label = "Bluetooth",
            icon = { Icon(Icons.Default.Bluetooth, null, modifier = Modifier.size(18.dp)) },
            onClick = { onSelected(TransportMode.BLUETOOTH) }
        )
        TransportOption(
            modifier = Modifier.weight(1f),
            selected = selected == TransportMode.WIFI,
            label = "Wi-Fi",
            icon = { Icon(Icons.Default.Wifi, null, modifier = Modifier.size(18.dp)) },
            onClick = { onSelected(TransportMode.WIFI) }
        )
    }
}

@Composable
private fun TransportOption(
    modifier: Modifier,
    selected: Boolean,
    label: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier,
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            Color.Transparent
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon()
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium
            )
        }
    }
}

@Composable
private fun JoystickCard(
    modifier: Modifier,
    enabled: Boolean,
    onDrive: (DriveDirection) -> Unit,
    onStop: () -> Unit
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(30.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "DRIVE",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (enabled) "LIVE" else "LOCKED",
                    color = if (enabled) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = FontWeight.ExtraBold
                )
            }

            DriveJoystick(
                enabled = enabled,
                onDrive = onDrive,
                onStop = onStop
            )
        }
    }
}

@Composable
private fun DriveJoystick(
    enabled: Boolean,
    onDrive: (DriveDirection) -> Unit,
    onStop: () -> Unit
) {
    var activeDirection by remember { mutableStateOf(DriveDirection.STOP) }
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .sizeIn(maxWidth = 330.dp)
            .aspectRatio(1f)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var direction = directionFromPosition(
                        position = down.position,
                        width = size.width.toFloat(),
                        height = size.height.toFloat()
                    )

                    if (direction != DriveDirection.STOP) {
                        activeDirection = direction
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDrive(direction)
                    }

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break

                        val next = directionFromPosition(
                            position = change.position,
                            width = size.width.toFloat(),
                            height = size.height.toFloat()
                        )

                        if (next != direction) {
                            direction = next
                            activeDirection = next
                            if (next == DriveDirection.STOP) {
                                onStop()
                            } else {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onDrive(next)
                            }
                        }
                        change.consume()
                    }

                    activeDirection = DriveDirection.STOP
                    onStop()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val outline = MaterialTheme.colorScheme.outline
        val primary = MaterialTheme.colorScheme.primary
        val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = min(size.width, size.height) / 2f
            drawCircle(
                color = surfaceVariant,
                radius = radius
            )
            drawCircle(
                color = outline,
                radius = radius * 0.98f,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
            )

            val c = center
            val arm = radius * 0.54f
            drawLine(
                color = outline,
                start = Offset(c.x, c.y - arm),
                end = Offset(c.x, c.y + arm),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawLine(
                color = outline,
                start = Offset(c.x - arm, c.y),
                end = Offset(c.x + arm, c.y),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawCircle(
                color = if (enabled) primary.copy(alpha = 0.14f) else outline.copy(alpha = 0.2f),
                radius = radius * 0.36f
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                when (activeDirection) {
                    DriveDirection.FORWARD -> "FORWARD"
                    DriveDirection.BACKWARD -> "REVERSE"
                    DriveDirection.LEFT -> "LEFT"
                    DriveDirection.RIGHT -> "RIGHT"
                    DriveDirection.STOP -> if (enabled) "HOLD + DRAG" else "LOCKED"
                },
                fontWeight = FontWeight.Black,
                color = when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                    activeDirection == DriveDirection.STOP -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.primary
                }
            )
            Text(
                "↑   ←  •  →   ↓",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun directionFromPosition(
    position: Offset,
    width: Float,
    height: Float
): DriveDirection {
    val dx = position.x - width / 2f
    val dy = position.y - height / 2f
    val deadZone = min(width, height) * 0.17f

    if (hypot(dx.toDouble(), dy.toDouble()) < deadZone.toDouble()) {
        return DriveDirection.STOP
    }

    return if (abs(dx) > abs(dy)) {
        if (dx < 0f) DriveDirection.LEFT else DriveDirection.RIGHT
    } else {
        if (dy < 0f) DriveDirection.FORWARD else DriveDirection.BACKWARD
    }
}

@Composable
private fun QuickTuningCard(
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
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(30.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Bolt, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    "OUTPUT",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            MetricSlider(
                label = "Speed",
                value = speed,
                valueRange = 50f..255f,
                displayValue = "${((speed - 50f) / 205f * 100f).toInt().coerceIn(0, 100)}%",
                onValueChange = onSpeed
            )

            MetricSlider(
                label = "Light",
                value = light,
                valueRange = 0f..255f,
                displayValue = "${(light / 255f * 100f).toInt().coerceIn(0, 100)}%",
                onValueChange = onLight
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Tune,
                    null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Steering trim",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Text(
                    trim.toInt().toString(),
                    fontWeight = FontWeight.Bold
                )
            }
            Slider(
                value = trim.coerceIn(-50f, 50f),
                onValueChange = onTrim,
                valueRange = -50f..50f
            )
        }
    }
}

@Composable
private fun MetricSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    displayValue: String,
    onValueChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            Text(
                displayValue,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black
            )
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange
        )
    }
}

@Composable
private fun EmergencyStopButton(
    enabled: Boolean,
    onStop: () -> Unit
) {
    val haptic = LocalHapticFeedback.current

    Button(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onStop()
        },
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(62.dp),
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
            disabledContainerColor = MaterialTheme.colorScheme.errorContainer,
            disabledContentColor = MaterialTheme.colorScheme.onErrorContainer
        )
    ) {
        Icon(Icons.Default.Stop, null)
        Spacer(Modifier.width(10.dp))
        Text(
            "EMERGENCY STOP",
            fontWeight = FontWeight.Black
        )
    }
}
