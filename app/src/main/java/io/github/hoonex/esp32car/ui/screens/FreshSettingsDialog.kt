package io.github.hoonex.esp32car.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hoonex.esp32car.viewmodel.RcViewModel
import kotlin.math.roundToInt

@Composable
fun FreshSettingsDialog(
    viewModel: RcViewModel,
    onDismiss: () -> Unit
) {
    var trim by remember { mutableFloatStateOf(viewModel.settings.trim) }
    var deadzone by remember { mutableFloatStateOf(viewModel.settings.controlDeadzone) }
    var steeringGain by remember { mutableFloatStateOf(viewModel.settings.steeringGain) }
    var steeringExpo by remember { mutableFloatStateOf(viewModel.settings.steeringExpo) }
    var invertThrottle by remember { mutableStateOf(viewModel.settings.invertThrottle) }
    var invertSteering by remember { mutableStateOf(viewModel.settings.invertSteering) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF101419),
        title = {
            Column {
                Text("Settings", color = Color(0xFFF3F5F7), fontWeight = FontWeight.Bold)
                Text("주행 입력 보정", color = Color(0xFF8D98A3), fontSize = 9.sp)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                SettingSlider(
                    label = "Trim",
                    valueText = trim.roundToInt().toString(),
                    value = trim,
                    range = -50f..50f,
                    onValueChange = { trim = it }
                )
                SettingSlider(
                    label = "Deadzone",
                    valueText = "${(deadzone * 100f).roundToInt()}%",
                    value = deadzone,
                    range = 0.02f..0.35f,
                    onValueChange = { deadzone = it }
                )
                SettingSlider(
                    label = "Steering gain",
                    valueText = "${(steeringGain * 100f).roundToInt()}%",
                    value = steeringGain,
                    range = 0.5f..1.8f,
                    onValueChange = { steeringGain = it }
                )
                SettingSlider(
                    label = "Steering curve",
                    valueText = String.format("%.2f", steeringExpo),
                    value = steeringExpo,
                    range = 1f..2.5f,
                    onValueChange = { steeringExpo = it }
                )
                SettingSwitch("Throttle reverse", invertThrottle) { invertThrottle = it }
                SettingSwitch("Steering reverse", invertSteering) { invertSteering = it }
                Text(
                    "정지는 별도 버튼이 아니라 조이스틱 릴리즈, Bluetooth 끊김, 앱 백그라운드 전환, ESP32 deadman으로 자동 처리됩니다.",
                    color = Color(0xFF8D98A3),
                    fontSize = 8.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    viewModel.settings.controlDeadzone = deadzone
                    viewModel.settings.steeringGain = steeringGain
                    viewModel.settings.steeringExpo = steeringExpo
                    viewModel.settings.invertThrottle = invertThrottle
                    viewModel.settings.invertSteering = invertSteering
                    viewModel.updateTrim(trim)
                    viewModel.reloadTuningFromSettings()
                    onDismiss()
                }
            ) {
                Text("저장")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        }
    )
}

@Composable
private fun SettingSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color(0xFFF3F5F7), fontSize = 9.sp, modifier = Modifier.weight(1f))
            Text(valueText, color = Color(0xFF65C9F3), fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range)
    }
}

@Composable
private fun SettingSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color(0xFFF3F5F7), fontSize = 9.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.width(50.dp))
    }
}
