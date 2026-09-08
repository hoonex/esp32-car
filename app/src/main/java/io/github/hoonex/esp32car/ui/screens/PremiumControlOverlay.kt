package io.github.hoonex.esp32car.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hoonex.esp32car.bluetooth.ConnectionState
import io.github.hoonex.esp32car.model.DriveDirection
import io.github.hoonex.esp32car.viewmodel.RcViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.hypot
import kotlin.math.roundToInt

private val DeckBg = Color(0xF20A0D10)
private val DeckPanel = Color(0xFF11161B)
private val DeckLine = Color(0x24FFFFFF)
private val DeckText = Color(0xFFF3F5F6)
private val DeckMuted = Color(0xFF8A949D)
private val DeckAccent = Color(0xFF65D6B3)
private val DeckBlue = Color(0xFF65C8F2)
private val DeckDanger = Color(0xFFE45C67)

enum class DriveControlMode(val storage: String, val label: String) {
    ARCADE("ARCADE", "조이스틱"),
    TANK("TANK", "듀얼"),
    DPAD("DPAD", "버튼");

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
            PremiumControlDeck(
                viewModel = viewModel,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 14.dp, bottom = 12.dp)
            )
        }
    }
}

@Composable
private fun PremiumControlDeck(viewModel: RcViewModel, modifier: Modifier = Modifier) {
    val speed by viewModel.speed.collectAsStateWithLifecycle()
    var mode by remember {
        mutableStateOf(DriveControlMode.fromStorage(viewModel.settings.controlMode))
    }

    BoxWithConstraints(modifier) {
        val compact = maxHeight < 430.dp
        val deckWidth = if (compact) 360.dp else 410.dp
        val deckHeight = if (compact) 205.dp else 236.dp
        val controlSize = if (compact) 122.dp else 146.dp

        Surface(
            modifier = Modifier
                .width(deckWidth)
                .height(deckHeight)
                .pointerInput(Unit) {
                    // Own this hit-test region so the legacy pad underneath cannot send a second drive command.
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent()
                    }
                },
            color = DeckBg,
            shape = RoundedCornerShape(24.dp),
            shadowElevation = 18.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, DeckLine)
        ) {
            Column(
                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("DRIVE", color = DeckText, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text("Bluetooth control", color = DeckMuted, fontSize = 7.sp)
                    }
                    ModeSelector(
                        selected = mode,
                        onSelected = {
                            viewModel.emergencyStop()
                            mode = it
                            viewModel.settings.controlMode = it.storage
                        }
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        Modifier.size(controlSize),
                        contentAlignment = Alignment.Center
                    ) {
                        when (mode) {
                            DriveControlMode.ARCADE -> ArcadeControl(viewModel, Modifier.fillMaxSize())
                            DriveControlMode.TANK -> TankControl(viewModel, Modifier.fillMaxSize())
                            DriveControlMode.DPAD -> DpadControl(viewModel, Modifier.fillMaxSize())
                        }
                    }

                    PowerControl(
                        speed = speed,
                        compact = compact,
                        onSpeed = viewModel::updateSpeed,
                        onMax = { viewModel.updateSpeed(255f) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeSelector(selected: DriveControlMode, onSelected: (DriveControlMode) -> Unit) {
    Surface(color = DeckPanel, shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp, DeckLine)) {
        Row(Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            DriveControlMode.entries.forEach { mode ->
                val active = mode == selected
                Surface(
                    onClick = { onSelected(mode) },
                    color = if (active) DeckBlue.copy(alpha = 0.18f) else Color.Transparent,
                    contentColor = if (active) DeckBlue else DeckMuted,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        mode.label,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        fontSize = 7.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun PowerControl(
    speed: Float,
    compact: Boolean,
    onSpeed: (Float) -> Unit,
    onMax: () -> Unit,
    modifier: Modifier = Modifier
) {
    val percent = ((speed / 255f) * 100f).roundToInt().coerceIn(0, 100)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("POWER", color = DeckMuted, fontSize = 7.sp, letterSpacing = 0.8.sp)
                Text("$percent%", color = DeckText, fontSize = if (compact) 22.sp else 26.sp, fontWeight = FontWeight.Bold)
            }
            Surface(
                onClick = onMax,
                color = if (speed >= 254f) DeckAccent.copy(alpha = 0.17f) else DeckPanel,
                contentColor = if (speed >= 254f) DeckAccent else DeckText,
                shape = RoundedCornerShape(13.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, if (speed >= 254f) DeckAccent.copy(alpha = 0.35f) else DeckLine)
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(Icons.Default.Bolt, null, Modifier.size(14.dp))
                    Text("MAX", fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Slider(
            value = speed,
            onValueChange = onSpeed,
            valueRange = 50f..255f,
            colors = SliderDefaults.colors(
                thumbColor = DeckAccent,
                activeTrackColor = DeckAccent,
                inactiveTrackColor = DeckLine
            )
        )

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Tune, null, Modifier.size(12.dp), tint = DeckMuted)
            Text(
                when {
                    speed >= 245f -> "최대 출력"
                    speed >= 205f -> "빠름"
                    speed >= 150f -> "보통"
                    else -> "정밀"
                },
                color = if (speed >= 245f) DeckAccent else DeckMuted,
                fontSize = 7.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun ArcadeControl(viewModel: RcViewModel, modifier: Modifier = Modifier) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var knob by remember { mutableStateOf(Offset.Zero) }
    var throttle by remember { mutableFloatStateOf(0f) }
    var steering by remember { mutableFloatStateOf(0f) }
    var active by remember { mutableStateOf(false) }

    fun reset() {
        active = false
        knob = Offset.Zero
        throttle = 0f
        steering = 0f
        viewModel.emergencyStop()
    }

    fun update(position: Offset) {
        if (size.width <= 0 || size.height <= 0) return
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = minOf(size.width, size.height) * 0.40f
        val dx = position.x - center.x
        val dy = position.y - center.y
        val length = hypot(dx, dy)
        val scale = if (length > radius && length > 0f) radius / length else 1f
        knob = Offset(dx * scale, dy * scale)
        steering = (knob.x / radius).coerceIn(-1f, 1f)
        throttle = (-knob.y / radius).coerceIn(-1f, 1f)
    }

    LaunchedEffect(active, throttle, steering) {
        if (!active) return@LaunchedEffect
        while (isActive && active) {
            viewModel.driveVector(throttle, steering)
            delay(60)
        }
    }

    Box(
        modifier
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { active = true; update(it) },
                    onDrag = { change, _ -> change.consume(); update(change.position) },
                    onDragEnd = { reset() },
                    onDragCancel = { reset() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = minOf(size.width, size.height) * 0.40f
            drawCircle(DeckPanel, radius = r * 1.12f)
            drawCircle(DeckLine, radius = r * 1.12f, style = Stroke(1.dp.toPx()))
            drawLine(DeckLine, Offset(center.x, center.y - r), Offset(center.x, center.y + r), 1.dp.toPx())
            drawLine(DeckLine, Offset(center.x - r, center.y), Offset(center.x + r, center.y), 1.dp.toPx())
            drawCircle(DeckAccent.copy(alpha = 0.11f), radius = r * 0.16f)
        }
        Surface(
            modifier = Modifier
                .size(48.dp)
                .graphicsLayerTranslation(knob),
            color = if (active) DeckAccent else Color(0xFF273038),
            contentColor = if (active) Color(0xFF071A15) else DeckText,
            shape = CircleShape,
            shadowElevation = if (active) 8.dp else 2.dp
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Gamepad, null, Modifier.size(18.dp))
            }
        }
    }
}

private fun Modifier.graphicsLayerTranslation(offset: Offset): Modifier =
    this.then(
        Modifier.graphicsLayer {
            translationX = offset.x
            translationY = offset.y
        }
    )

@Composable
private fun TankControl(viewModel: RcViewModel, modifier: Modifier = Modifier) {
    var left by remember { mutableFloatStateOf(0f) }
    var right by remember { mutableFloatStateOf(0f) }
    var leftActive by remember { mutableStateOf(false) }
    var rightActive by remember { mutableStateOf(false) }

    LaunchedEffect(left, right, leftActive, rightActive) {
        if (!leftActive && !rightActive) return@LaunchedEffect
        while (isActive && (leftActive || rightActive)) {
            val throttle = (left + right) * 0.5f
            val steering = (left - right) * 0.5f
            viewModel.driveVector(throttle, steering)
            delay(60)
        }
    }

    Row(modifier, horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        TankLane(
            label = "L",
            value = left,
            onValue = { left = it },
            onActive = { leftActive = it; if (!it && !rightActive) viewModel.emergencyStop() }
        )
        TankLane(
            label = "R",
            value = right,
            onValue = { right = it },
            onActive = { rightActive = it; if (!it && !leftActive) viewModel.emergencyStop() }
        )
    }
}

@Composable
private fun TankLane(
    label: String,
    value: Float,
    onValue: (Float) -> Unit,
    onActive: (Boolean) -> Unit
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val thumbTravelPx = remember(density) { with(density) { 48.dp.toPx() } }

    fun update(y: Float) {
        if (size.height <= 0) return
        val normalized = (1f - (y / size.height) * 2f).coerceIn(-1f, 1f)
        onValue(if (kotlin.math.abs(normalized) < 0.06f) 0f else normalized)
    }

    Surface(
        modifier = Modifier
            .width(48.dp)
            .height(130.dp)
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { onActive(true); update(it.y) },
                    onDrag = { change, _ -> change.consume(); update(change.position.y) },
                    onDragEnd = { onValue(0f); onActive(false) },
                    onDragCancel = { onValue(0f); onActive(false) }
                )
            },
        color = DeckPanel,
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, DeckLine)
    ) {
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize()) {
                drawLine(DeckLine, Offset(center.x, 14.dp.toPx()), Offset(center.x, size.height - 14.dp.toPx()), 2.dp.toPx())
                drawLine(DeckLine, Offset(10.dp.toPx(), center.y), Offset(size.width - 10.dp.toPx(), center.y), 1.dp.toPx())
            }
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(width = 34.dp, height = 24.dp)
                    .graphicsLayer { translationY = -value * thumbTravelPx },
                color = if (value == 0f) Color(0xFF2A333B) else DeckAccent,
                shape = RoundedCornerShape(9.dp)
            ) {}
            Text(label, Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp), color = DeckMuted, fontSize = 6.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DpadControl(viewModel: RcViewModel, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        HoldDriveButton(
            direction = DriveDirection.FORWARD,
            onDrive = viewModel::drive,
            onStop = viewModel::emergencyStop,
            modifier = Modifier.align(Alignment.TopCenter)
        ) { Icon(Icons.Default.KeyboardArrowUp, null, Modifier.size(22.dp)) }
        HoldDriveButton(
            direction = DriveDirection.LEFT,
            onDrive = viewModel::drive,
            onStop = viewModel::emergencyStop,
            modifier = Modifier.align(Alignment.CenterStart)
        ) { Icon(Icons.Default.KeyboardArrowLeft, null, Modifier.size(22.dp)) }
        Surface(
            onClick = viewModel::emergencyStop,
            modifier = Modifier.size(42.dp),
            color = DeckDanger.copy(alpha = 0.16f),
            contentColor = DeckDanger,
            shape = CircleShape,
            border = androidx.compose.foundation.BorderStroke(1.dp, DeckDanger.copy(alpha = 0.35f))
        ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("STOP", fontSize = 6.sp, fontWeight = FontWeight.Bold) } }
        HoldDriveButton(
            direction = DriveDirection.RIGHT,
            onDrive = viewModel::drive,
            onStop = viewModel::emergencyStop,
            modifier = Modifier.align(Alignment.CenterEnd)
        ) { Icon(Icons.Default.KeyboardArrowRight, null, Modifier.size(22.dp)) }
        HoldDriveButton(
            direction = DriveDirection.BACKWARD,
            onDrive = viewModel::drive,
            onStop = viewModel::emergencyStop,
            modifier = Modifier.align(Alignment.BottomCenter)
        ) { Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(22.dp)) }
    }
}

@Composable
private fun HoldDriveButton(
    direction: DriveDirection,
    onDrive: (DriveDirection) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier
            .size(42.dp)
            .pointerInput(direction) {
                detectTapGestures(
                    onPress = {
                        onDrive(direction)
                        tryAwaitRelease()
                        onStop()
                    }
                )
            },
        color = DeckPanel,
        contentColor = DeckText,
        shape = RoundedCornerShape(13.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, DeckLine)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}
