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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import io.github.hoonex.esp32car.ui.screens.CockpitScreen
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
                    CockpitScreen(rcViewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
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

@Composable
private fun BluetoothPermissionGate(
    onPermissionsReady: () -> Unit,
    content: @Composable () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val latestOnReady = rememberUpdatedState(onPermissionsReady)
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) latestOnReady.value()
    }

    LaunchedEffect(Unit) {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    add(Manifest.permission.BLUETOOTH_SCAN)
                }
                if (
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }
        }

        if (needed.isEmpty()) latestOnReady.value()
        else launcher.launch(needed.toTypedArray())
    }

    content()
}
