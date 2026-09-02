package io.github.hoonex.esp32car

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.hoonex.esp32car.ui.screens.DeviceScreen
import io.github.hoonex.esp32car.ui.screens.DriveScreen
import io.github.hoonex.esp32car.ui.screens.VisionScreen
import io.github.hoonex.esp32car.ui.theme.MyApplicationTheme
import io.github.hoonex.esp32car.viewmodel.RcViewModel

enum class AppTab(
    val title: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    CONTROL("Control", Icons.Default.SportsEsports),
    VISION("Vision", Icons.Default.Videocam),
    SYSTEM("System", Icons.Default.Settings)
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
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = buildList {
                if (
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
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
    var selectedTab by remember { mutableStateOf(AppTab.CONTROL) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp
                ) {
                    AppTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = {
                                if (selectedTab != tab) {
                                    viewModel.emergencyStop()
                                    selectedTab = tab
                                }
                            },
                            icon = { Icon(tab.icon, tab.title) },
                            label = { Text(tab.title) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (selectedTab) {
                    AppTab.CONTROL -> DriveScreen(
                        viewModel = viewModel,
                        onOpenVision = { selectedTab = AppTab.VISION }
                    )
                    AppTab.VISION -> VisionScreen(viewModel)
                    AppTab.SYSTEM -> DeviceScreen(viewModel)
                }
            }
        }
    }
}
