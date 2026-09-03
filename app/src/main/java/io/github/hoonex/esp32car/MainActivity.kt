package io.github.hoonex.esp32car

import android.Manifest
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
import androidx.compose.runtime.rememberUpdatedState
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
import io.github.hoonex.esp32car.ui.screens.ReliableCockpitScreen
import io.github.hoonex.esp32car.ui.theme.MyApplicationTheme
import io.github.hoonex.esp32car.update.AppUpdater
import io.github.hoonex.esp32car.viewmodel.RcViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val rcViewModel: RcViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        lifecycleScope.launch {
            AppUpdater.checkForUpdate(this@MainActivity, installWhenReady = true)
        }

        setContent {
            MyApplicationTheme {
                BluetoothPermissionGate(
                    onPermissionsReady = {
                        rcViewModel.bluetooth.connectPreferredOrDiscover("ESP32_CAM_RC")
                    }
                ) {
                    ReliableCockpitScreen(rcViewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppUpdater.resumePendingInstall(this)
        if (hasBluetoothRuntimePermissions()) {
            rcViewModel.bluetooth.connectPreferredOrDiscover("ESP32_CAM_RC")
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onStop() {
        rcViewModel.emergencyStop()
        super.onStop()
    }

    private fun hasBluetoothRuntimePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

@Composable
private fun BluetoothPermissionGate(
    onPermissionsReady: () -> Unit,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val latestOnReady = rememberUpdatedState(onPermissionsReady)

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
    ) { result ->
        requestedOnce = true
        granted = requiredPermissions().all { permission -> result[permission] == true || ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED }
        if (granted) latestOnReady.value()
    }

    LaunchedEffect(Unit) {
        granted = allGranted()
        if (granted) latestOnReady.value()
        else launcher.launch(requiredPermissions())
    }

    Box(Modifier.fillMaxSize()) {
        if (granted) {
            content()
        } else {
            Column(
                modifier = Modifier.fillMaxSize().background(Color(0xFF05070A)).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("BLUETOOTH PERMISSION REQUIRED", color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp)
                Text(
                    if (requestedOnce) "Nearby devices 권한이 거부되어 ESP32_CAM_RC를 검색하거나 연결할 수 없습니다." else "ESP32_CAM_RC 검색과 연결을 위해 Nearby devices 권한이 필요합니다.",
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = Color(0xFF9BA7B2),
                    fontSize = 12.sp
                )
                Button(onClick = { launcher.launch(requiredPermissions()) }) {
                    Text("권한 허용")
                }
            }
        }
    }
}
