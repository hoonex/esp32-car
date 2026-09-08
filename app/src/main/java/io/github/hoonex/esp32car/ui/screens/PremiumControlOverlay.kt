package io.github.hoonex.esp32car.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hoonex.esp32car.bluetooth.ConnectionState
import io.github.hoonex.esp32car.viewmodel.RcViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.roundToInt

private val HudSurface = Color(0xE60B0F13)
private val HudSurfaceSoft = Color(0xCC11171C)
private val HudLine = Color(0x2AFFFFFF)
private val HudText = Color(0xFFF4F6F7)
private val HudMuted = Color(0xFF87929A)
private val HudAccent = Color(0xFF65D6B3)
private val HudBlue = Color(0xFF63C9F2)
private val HudDanger = Color(0xFFE75D69)

enum class DriveControlMode(val storage: String, val label: String, val hint: String) {
    ARCADE("ARCADE", "RC", "왼손 가속 · 오른손 조향"),
    TANK("TANK", "TANK", "좌우 모터 독립"),
    DPAD("DPAD", "PAD", "왼손 조향 · 오른손 전후진");

    companion object {
        fun fromStorage(value: String): DriveControlMode =
            entries.firstOrNull { it.storage == value.uppercase() } ?: ARCADE
    }
}

@Composable
fun PremiumPilotCockpitScreen(viewModel: RcViewModel) {
    val btState by viewModel.bluetooth.connectionState.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        PilotCockpitScreen(viewModel)

        if (btState == ConnectionState.CONNECTED) {
            TwoHandDriveHud(
                viewModel = viewModel,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(250.dp)
            )
        }
    }
}

@Composable
private fun TwoHandDriveHud(viewModel: RcViewModel, modifier: Modifier = Modifier) {
    val speed by viewModel.speed.collectAsStateWithLifecycle()
    var mode by remember { mutableStateOf(DriveControlMode.fromStorage(viewModel.settings.controlMode)) }

    BoxWithConstraints(modifier) {
        val compact = maxWidth < 720.dp
        val sidePadding = if (compact) 18.dp else 30.dp
        val rightClearance = if (compact) 142.dp else 168.dp
        val controlSize = if (compact) 132.dp else 154.dp

        ModeDock(
            mode = mode,
            onMode = {
                viewModel.emergencyStop()
                mode = it
                viewModel.settings.controlMode = it.storage
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 4.dp)
        )

        when (mode) {
            DriveControlMode.ARCADE -> RcTwoHandControl(
                viewModel = viewModel,
                controlSize = controlSize,
                leftModifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = sidePadding, bottom = 14.dp),
                rightModifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = rightClearance, bottom = 14.dp)
            )

            DriveControlMode.TANK -> TankTwoHandControl(
                viewModel = viewModel,
                controlSize = controlSize,
                leftModifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = sidePadding, bottom = 14.dp),
                rightModifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = rightClearance, bottom = 14.dp)
            )

            DriveControlMode.DPAD -> PadTwoHandControl(
                viewModel = viewModel,
                controlSize = controlSize,
                leftModifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = sidePadding, bottom = 14.dp),
                rightModifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = rightClearance, bottom = 14.dp)
            )
        }

        PowerDock(
            speed = speed,
            onSpeed = viewModel::updateSpeed,
            onMax = { viewModel.updateSpeed(255f) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 18.dp)
                .width(if (compact) 190.dp else 230.dp)
        )
    }
}

@Composable
private fun ModeDock(mode: DriveControlMode, onMode: (DriveControlMode) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = HudSurface,
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, HudLine),
        shadowElevation = 12.dp
    ) {
        Row(
            Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DriveControlMode.entries.forEach { item ->
                val active = item == mode
                Surface(
                    onClick = { onMode(item) },
                    color = if (active) HudBlue.copy(alpha = 0.18f) else Color.Transparent,
                    contentColor = if (active) HudBlue else HudMuted,
                    shape = RoundedCornerShape(13.dp)
                ) {
                    Column(
                        Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(item.label, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                        Text(item.hint, fontSize = 5.5.sp, color = if (active) HudBlue.copy(alpha = 0.78f) else HudMuted)
                    }
                }
            }
        }
    }
}

@Composable
private fun PowerDock(
    speed: Float,
    onSpeed: (Float) -> Unit,
    onMax: () -> Unit,
    modifier: Modifier = Modifier
) {
    val percent = ((speed / 255f) * 100f).roundToInt().coerceIn(0, 100)

    Surface(
        modifier = modifier,
        color = HudSurface,
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, HudLine),
        shadowElevation = 10.dp
    ) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("OUTPUT", color = HudMuted, fontSize = 6.sp, letterSpacing = 1.sp)
                    Text("$percent%", color = HudText, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
                Surface(
                    onClick = onMax,
                    color = if (speed >= 254f) HudAccent.copy(alpha = 0.16f) else HudSurfaceSoft,
                    contentColor = if (speed >= 254f) HudAccent else HudText,
                    shape = RoundedCornerShape(11.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (speed >= 254f) HudAccent.copy(alpha = 0.35f) else HudLine
                    )
                ) {
                    Text(
                        "MAX",
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                        fontSize = 7.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Slider(
                value = speed,
                onValueChange = onSpeed,
                valueRange = 50f..255f,
                colors = SliderDefaults.colors(
                    thumbColor = HudAccent,
                    activeTrackColor = HudAccent,
                    inactiveTrackColor = HudLine
                )
            )
        }
    }
}

@Composable
private fun RcTwoHandControl(
    viewModel: RcViewModel,
    controlSize: androidx.compose.ui.unit.Dp,
    leftModifier: Modifier,
    rightModifier: Modifier
) {
    var throttle by remember { mutableFloatStateOf(0f) }
    var steering by remember { mutableFloatStateOf(0f) }
    var throttleActive by remember { mutableStateOf(false) }
    var steeringActive by remember { mutableStateOf(false) }

    DrivePump(viewModel, throttle, steering, throttleActive || steeringActive)

    AxisControl(
        value = throttle,
        vertical = true,
        label = "THROTTLE",
        accent = HudAccent,
        modifier = leftModifier.width(controlSize).height(controlSize + 26.dp),
        onValue = { throttle = it },
        onActive = { throttleActive = it }
    )

    AxisControl(
        value = steering,
        vertical = false,
        label = "STEER",
        accent = HudBlue,
        modifier = rightModifier.width(controlSize + 26.dp).height(controlSize),
        onValue = { steering = it },
        onActive = { steeringActive = it }
    )
}

@Composable
private fun TankTwoHandControl(
    viewModel: RcViewModel,
    controlSize: androidx.compose.ui.unit.Dp,
    leftModifier: Modifier,
    rightModifier: Modifier
) {
    var left by remember { mutableFloatStateOf(0f) }
    var right by remember { mutableFloatStateOf(0f) }
    var leftActive by remember { mutableStateOf(false) }
    var rightActive by remember { mutableStateOf(false) }

    val throttle = (left + right) * 0.5f
    val steering = (left - right) * 0.5f
    DrivePump(viewModel, throttle, steering, leftActive || rightActive)

    AxisControl(
        value = left,
        vertical = true,
        label = "LEFT MOTOR",
        accent = HudAccent,
        modifier = leftModifier.width(controlSize).height(controlSize + 26.dp),
        onValue = { left = it },
        onActive = { leftActive = it }
    )

    AxisControl(
        value = right,
        vertical = true,
        label = "RIGHT MOTOR",
        accent = HudAccent,
        modifier = rightModifier.width(controlSize).height(controlSize + 26.dp),
        onValue = { right = it },
        onActive = { rightActive = it }
    )
}

@Composable
private fun PadTwoHandControl(
    viewModel: RcViewModel,
    controlSize: androidx.compose.ui.unit.Dp,
    leftModifier: Modifier,
    rightModifier: Modifier
) {
    var leftPressed by remember { mutableStateOf(false) }
    var rightPressed by remember { mutableStateOf(false) }
    var forwardPressed by remember { mutableStateOf(false) }
    var reversePressed by remember { mutableStateOf(false) }

    val steering = when {
        leftPressed && !rightPressed -> -1f
        rightPressed && !leftPressed -> 1f
        else -> 0f
    }
    val throttle = when {
        forwardPressed && !reversePressed -> 1f
        reversePressed && !forwardPressed -> -1f
        else -> 0f
    }
    val engaged = leftPressed || rightPressed || forwardPressed || reversePressed
    DrivePump(viewModel, throttle, steering, engaged)

    Surface(
        modifier = leftModifier.width(controlSize + 30.dp).height(controlSize),
        color = HudSurface,
        shape = RoundedCornerShape(26.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, HudLine),
        shadowElevation = 10.dp
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("STEER", color = HudMuted, fontSize = 6.sp, letterSpacing = 1.sp)
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PressTile("‹", leftPressed, { leftPressed = it }, Modifier.weight(1f))
                PressTile("›", rightPressed, { rightPressed = it }, Modifier.weight(1f))
            }
        }
    }

    Surface(
        modifier = rightModifier.width(controlSize).height(controlSize + 30.dp),
        color = HudSurface,
        shape = RoundedCornerShape(26.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, HudLine),
        shadowElevation = 10.dp
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("THROTTLE", color = HudMuted, fontSize = 6.sp, letterSpacing = 1.sp)
            PressTile("▲", forwardPressed, { forwardPressed = it }, Modifier.weight(1f).fillMaxWidth())
            PressTile("▼", reversePressed, { reversePressed = it }, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun DrivePump(viewModel: RcViewModel, throttle: Float, steering: Float, engaged: Boolean) {
    LaunchedEffect(throttle, steering, engaged) {
        if (!engaged) {
            viewModel.emergencyStop()
            return@LaunchedEffect
        }
        while (isActive && engaged) {
            viewModel.driveVector(throttle, steering)
            delay(45)
        }
    }
}

@Composable
private fun AxisControl(
    value: Float,
    vertical: Boolean,
    label: String,
    accent: Color,
    modifier: Modifier,
    onValue: (Float) -> Unit,
    onActive: (Boolean) -> Unit
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val travelPx = remember(size, density, vertical) {
        with(density) {
            if (vertical) ((size.height / density.density) * 0.34f).dp.toPx()
            else ((size.width / density.density) * 0.34f).dp.toPx()
        }
    }

    fun update(position: Offset) {
        if (size.width <= 0 || size.height <= 0) return
        val raw = if (vertical) {
            1f - (position.y / size.height.toFloat()) * 2f
        } else {
            (position.x / size.width.toFloat()) * 2f - 1f
        }
        val deadzoned = if (abs(raw) < 0.045f) 0f else raw.coerceIn(-1f, 1f)
        onValue(deadzoned)
    }

    Surface(
        modifier = modifier
            .onSizeChanged { size = it }
            .pointerInput(vertical) {
                detectDragGestures(
                    onDragStart = {
                        onActive(true)
                        update(it)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        update(change.position)
                    },
                    onDragEnd = {
                        onValue(0f)
                        onActive(false)
                    },
                    onDragCancel = {
                        onValue(0f)
                        onActive(false)
                    }
                )
            },
        color = HudSurface,
        shape = RoundedCornerShape(28.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, HudLine),
        shadowElevation = 10.dp
    ) {
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize()) {
                val pad = 18.dp.toPx()
                if (vertical) {
                    drawLine(HudLine, Offset(center.x, pad), Offset(center.x, size.height - pad), 2.dp.toPx())
                    drawLine(HudLine, Offset(center.x - 12.dp.toPx(), center.y), Offset(center.x + 12.dp.toPx(), center.y), 1.dp.toPx())
                } else {
                    drawLine(HudLine, Offset(pad, center.y), Offset(size.width - pad, center.y), 2.dp.toPx())
                    drawLine(HudLine, Offset(center.x, center.y - 12.dp.toPx()), Offset(center.x, center.y + 12.dp.toPx()), 1.dp.toPx())
                }
            }

            Text(
                label,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
                color = HudMuted,
                fontSize = 6.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp
            )

            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(if (vertical) 54.dp else 58.dp)
                    .graphicsLayer {
                        if (vertical) translationY = -value * travelPx else translationX = value * travelPx
                    },
                color = if (value == 0f) Color(0xFF263038) else accent,
                contentColor = if (value == 0f) HudText else Color(0xFF081411),
                shape = CircleShape,
                shadowElevation = if (value == 0f) 2.dp else 8.dp
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (vertical) {
                            when {
                                value > 0.05f -> "▲"
                                value < -0.05f -> "▼"
                                else -> "•"
                            }
                        } else {
                            when {
                                value > 0.05f -> "›"
                                value < -0.05f -> "‹"
                                else -> "•"
                            }
                        },
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Text(
                when {
                    value > 0.05f -> "+${(value * 100).roundToInt()}"
                    value < -0.05f -> "${(value * 100).roundToInt()}"
                    else -> "0"
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                color = if (value == 0f) HudMuted else accent,
                fontSize = 6.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun PressTile(
    text: String,
    active: Boolean,
    onPressed: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .pointerInput(text) {
                detectTapGestures(
                    onPress = {
                        onPressed(true)
                        try {
                            tryAwaitRelease()
                        } finally {
                            onPressed(false)
                        }
                    }
                )
            },
        color = if (active) HudAccent else HudSurfaceSoft,
        contentColor = if (active) Color(0xFF071511) else HudText,
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (active) HudAccent.copy(alpha = 0.45f) else HudLine)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text, textAlign = TextAlign.Center, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
    }
}
