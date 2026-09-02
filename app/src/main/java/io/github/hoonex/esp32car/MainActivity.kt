package io.github.hoonex.esp32car

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import io.github.hoonex.esp32car.ui.screens.AITrackingScreen
import io.github.hoonex.esp32car.ui.screens.DeviceScreen
import io.github.hoonex.esp32car.ui.screens.DriveScreen
import io.github.hoonex.esp32car.ui.screens.WebControllerScreen
import io.github.hoonex.esp32car.ui.theme.MyApplicationTheme
import io.github.hoonex.esp32car.viewmodel.RcViewModel

enum class AppTab(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    DRIVE("Drive", Icons.Default.DirectionsCar),
    CAMERA("Camera", Icons.Default.PhotoCamera),
    AUTO("Auto", Icons.Default.SmartToy),
    DEVICE("Device", Icons.Default.Memory)
}

class MainActivity : ComponentActivity() {
    private val rcViewModel: RcViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                BluetoothPermissionGate {
                    MainAppScreen(rcViewModel)
                }
            }
        }
    }

    override fun onStop() {
        rcViewModel.emergencyStop()
        super.onStop()
    }
}

@Composable
private fun BluetoothPermissionGate(content: @Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = buildList {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                }
            }
            if (needed.isNotEmpty()) launcher.launch(needed.toTypedArray())
        }
    }
    content()
}

@Composable
fun MainAppScreen(viewModel: RcViewModel) {
    var selectedTab by remember { mutableStateOf(AppTab.DRIVE) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = {
                            if (selectedTab != tab) viewModel.emergencyStop()
                            selectedTab = tab
                        },
                        icon = { Icon(tab.icon, tab.title) },
                        label = { Text(tab.title) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (selectedTab) {
                AppTab.DRIVE -> DriveScreen(viewModel)
                AppTab.CAMERA -> WebControllerScreen(viewModel.rcClient, viewModel.settings)
                AppTab.AUTO -> AITrackingScreen(viewModel.rcClient, viewModel.settings)
                AppTab.DEVICE -> DeviceScreen(viewModel)
            }
        }
    }
}
