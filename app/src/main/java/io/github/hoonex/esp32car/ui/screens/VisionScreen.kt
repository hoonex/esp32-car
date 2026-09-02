package io.github.hoonex.esp32car.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hoonex.esp32car.network.MjpegParser
import io.github.hoonex.esp32car.viewmodel.RcViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL

@Composable
fun VisionScreen(viewModel: RcViewModel) {
    var showTracking by remember { mutableStateOf(false) }

    if (showTracking) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            AITrackingScreen(viewModel.rcClient, viewModel.settings)

            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(14.dp),
                onClick = {
                    viewModel.emergencyStop()
                    showTracking = false
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
            ) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = "Back to live view",
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
        return
    }

    val light by viewModel.light.collectAsStateWithLifecycle()
    val ip = viewModel.settings.ipAddress

    var latestBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var streamError by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableIntStateOf(0) }
    var frameCount by remember { mutableIntStateOf(0) }
    var fps by remember { mutableIntStateOf(0) }

    LaunchedEffect(ip, retryKey) {
        latestBitmap = null
        streamError = null
        frameCount = 0

        if (ip.isBlank()) {
            streamError = "No ESP32 Wi-Fi address"
            return@LaunchedEffect
        }

        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val host = ip
                    .removePrefix("http://")
                    .removePrefix("https://")
                    .substringBefore('/')
                    .substringBefore(':')
                connection = URL("http://$host:81/stream").openConnection() as HttpURLConnection
                connection.connectTimeout = 3500
                connection.readTimeout = 5000
                connection.useCaches = false
                connection.connect()

                val input = BufferedInputStream(connection.inputStream, 64 * 1024)
                input.use {
                    while (isActive) {
                        val frame = MjpegParser.readFrame(it)
                        if (frame != null) {
                            latestBitmap = frame
                            frameCount += 1
                            streamError = null
                        }
                    }
                }
            } catch (t: Throwable) {
                if (isActive) {
                    streamError = t.message ?: "Camera stream unavailable"
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    LaunchedEffect(ip, retryKey) {
        while (isActive) {
            delay(1000)
            fps = frameCount
            frameCount = 0
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val frame = latestBitmap
        if (frame != null) {
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = "ESP32 camera",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    if (streamError == null) Icons.Default.Videocam else Icons.Default.VideocamOff,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    streamError ?: "Connecting to camera…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        VisionHud(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(14.dp),
            ip = ip,
            fps = fps,
            error = streamError,
            onRetry = { retryKey += 1 }
        )

        VisionControls(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(14.dp),
            light = light,
            onLight = viewModel::updateLight,
            onTracking = {
                viewModel.emergencyStop()
                showTracking = true
            }
        )
    }
}

@Composable
private fun VisionHud(
    modifier: Modifier,
    ip: String,
    fps: Int,
    error: String?,
    onRetry: () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .background(
                        if (error == null && ip.isNotBlank()) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        CircleShape
                    )
            )
            Spacer(Modifier.width(9.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "VISION",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    if (ip.isBlank()) "No target" else "$ip · ${fps} FPS",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onRetry) {
                Icon(Icons.Default.Refresh, contentDescription = "Reconnect stream")
            }
        }
    }
}

@Composable
private fun VisionControls(
    modifier: Modifier,
    light: Float,
    onLight: (Float) -> Unit,
    onTracking: () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shadowElevation = 6.dp
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Videocam,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Live camera",
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(Modifier.weight(1f))
                FilledTonalButton(onClick = onTracking) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Tracking")
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Light",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = light.coerceIn(0f, 255f),
                    onValueChange = onLight,
                    valueRange = 0f..255f,
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp)
                )
                Text(
                    "${(light / 255f * 100f).toInt().coerceIn(0, 100)}%",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
