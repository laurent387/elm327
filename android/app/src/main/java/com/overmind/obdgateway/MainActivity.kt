package com.overmind.obdgateway

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.overmind.obdgateway.ui.MainViewModel
import com.overmind.obdgateway.ui.screens.ConnectionScreen
import com.overmind.obdgateway.ui.screens.DashboardScreen
import com.overmind.obdgateway.ui.screens.DtcScreen
import com.overmind.obdgateway.ui.screens.HistoryScreen
import com.overmind.obdgateway.ui.screens.LiveDataScreen
import com.overmind.obdgateway.ui.screens.SettingsScreen
import com.overmind.obdgateway.ui.theme.OvermindColors
import com.overmind.obdgateway.ui.theme.OvermindTheme

private enum class Tab(val label: String, val icon: ImageVector) {
    DASHBOARD("Tableau", Icons.Filled.Home),
    CONNECTION("ELM327", Icons.Filled.Build),
    LIVE("Direct", Icons.Filled.List),
    DTC("Défauts", Icons.Filled.Warning),
    HISTORY("Historique", Icons.Filled.DateRange),
    SETTINGS("Réglages", Icons.Filled.Settings),
}

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private fun btPermissionGranted(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OvermindTheme {
                var tab by rememberSaveable { mutableStateOf(Tab.DASHBOARD) }
                var btGranted by remember { mutableStateOf(btPermissionGranted()) }
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                    btGranted = btPermissionGranted()
                    vm.refreshDevices(btGranted)
                }
                val request = {
                    val perms = buildList {
                        if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
                        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    if (perms.isNotEmpty()) launcher.launch(perms.toTypedArray())
                }
                androidx.compose.runtime.LaunchedEffect(Unit) { if (!btGranted) request() }
                Scaffold(
                    containerColor = OvermindColors.Bg,
                    bottomBar = {
                        NavigationBar(containerColor = OvermindColors.Surface) {
                            Tab.entries.forEach { t ->
                                NavigationBarItem(
                                    selected = tab == t, onClick = { tab = t },
                                    icon = { Icon(t.icon, contentDescription = t.label) },
                                    label = { Text(t.label, fontSize = 10.sp) },
                                )
                            }
                        }
                    },
                ) { pad ->
                    Box(Modifier.fillMaxSize().padding(pad)) {
                        when (tab) {
                            Tab.DASHBOARD -> DashboardScreen(vm)
                            Tab.CONNECTION -> ConnectionScreen(vm, btGranted, request)
                            Tab.LIVE -> LiveDataScreen(vm)
                            Tab.DTC -> DtcScreen(vm)
                            Tab.HISTORY -> HistoryScreen(vm)
                            Tab.SETTINGS -> SettingsScreen(vm)
                        }
                    }
                }
            }
        }
    }
}
