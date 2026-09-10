package io.github.hoonex.esp32car

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import io.github.hoonex.esp32car.bluetooth.ConnectionState
import io.github.hoonex.esp32car.protocol.RcProtocol
import io.github.hoonex.esp32car.ui.screens.FreshCarScreen
import io.github.hoonex.esp32car.ui.theme.MyApplicationTheme
import io.github.hoonex.esp32car.update.AppUpdateStage
import io.github.hoonex.esp32car.update.AppUpdater
import io.github.hoonex.esp32car.viewmodel.RcViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val rcViewModel: RcViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        // A previously downloaded official APK is restored first. If no staged update exists,
        // GitHub releases are checked and a newer APK is downloaded + validated in the background.
        // Installation is only started while Bluetooth is disconnected so an app update can never
        // tear down the controller in the middle of a drive.
        val restoredStagedUpdate = AppUpdater.restoreStagedUpdate(this)
        if (!restoredStagedUpdate) {
            lifecycleScope.launch {
                AppUpdater.checkForUpdate(this@MainActivity, installWhenReady = false)
            }
        }

        lifecycleScope.launch {
            combine(AppUpdater.state, rcViewModel.bluetooth.connectionState) { update, connection ->
                update to connection
            }.collect { (update, connection) ->
                if (update.stage == AppUpdateStage.READY && connection == ConnectionState.DISCONNECTED) {
                    AppUpdater.installReadyUpdate(this@MainActivity)
                }
            }
        }

        setContent {
            MyApplicationTheme {
                BluetoothPermissionGate {
                    ControllerRoot(rcViewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        // Android 8+ asks once whether this app may install downloaded APKs. Returning from that
        // settings page resumes the already-staged update without requiring a download link.
        AppUpdater.resumePendingInstall(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onStop() {
        rcViewModel.emergencyStop()
        super.onStop()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun ControllerRoot(viewModel: RcViewModel) {
    // Give a fast release check a short priority window before connecting the car. A completed
    // update opens the installer before Bluetooth is touched; a slow/offline update check never
    // blocks driving for more than a few seconds and can finish in the background instead.
    LaunchedEffect(Unit) {
        delay(150)

        var checkingWaitMs = 0L
        while (AppUpdater.state.value.stage == AppUpdateStage.CHECKING && checkingWaitMs < 900L) {
            delay(100)
            checkingWaitMs += 100
        }

        var downloadWaitMs = 0L
        while (AppUpdater.state.value.stage == AppUpdateStage.DOWNLOADING && downloadWaitMs < 5_000L) {
            delay(100)
            downloadWaitMs += 100
        }

        when (AppUpdater.state.value.stage) {
            AppUpdateStage.READY,
            AppUpdateStage.WAITING_PERMISSION,
            AppUpdateStage.INSTALLING -> return@LaunchedEffect
            else -> Unit
        }

        var attempted = viewModel.reconnectLast()
        if (!attempted) {
            val pairedCar = viewModel.pairedDevices().firstOrNull { device ->
                runCatching { device.name }.getOrNull()?.equals(RcProtocol.DEVICE_NAME, ignoreCase = true) == true
            }
            if (pairedCar != null) {
                viewModel.pairAndConnect(pairedCar)
                attempted = true
            }
        }

        if (!attempted) {
            viewModel.scanBluetooth()
        } else {
            // RFCOMM connect + STATUS handshake has a bounded timeout. Recover from a stale saved
            // device address automatically instead of leaving the app stranded on a dead spinner.
            delay(5_000)
            if (viewModel.bluetooth.connectionState.value == ConnectionState.DISCONNECTED) {
                viewModel.scanBluetooth()
            }
        }
    }
    FreshCarScreen(viewModel)
}

@Composable
private fun BluetoothPermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current

    fun requiredPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        else -> emptyArray()
    }

    fun allGranted(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    var granted by remember { mutableStateOf(allGranted()) }
    var requestedOnce by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        requestedOnce = true
        granted = allGranted()
    }

    LaunchedEffect(Unit) {
        granted = allGranted()
        if (!granted) launcher.launch(requiredPermissions())
    }

    if (granted) {
        content()
    } else {
        Box(Modifier.fillMaxSize().background(Color(0xFF080A0D))) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("ESP32 CAR", color = Color.White, fontWeight = FontWeight.Black, fontSize = 22.sp)
                Text(
                    if (requestedOnce) "Bluetooth 권한을 허용해야 ESP32_CAM_RC에 연결할 수 있습니다."
                    else "ESP32_CAM_RC 검색과 연결에 Bluetooth 권한이 필요합니다.",
                    color = Color(0xFF8D98A3),
                    fontSize = 11.sp
                )
                Button(onClick = { launcher.launch(requiredPermissions()) }) {
                    Text("Bluetooth 권한 허용")
                }
            }
        }
    }
}
