package com.watch.hsp

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.watch.hsp.ui.HspWatchScreen
import com.watch.hsp.ui.theme.HspTheme

class MainActivity : ComponentActivity() {
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            BleServerStatus.refreshPrerequisites(this@MainActivity)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HspTheme {
                HspWatchApp()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(bluetoothStateReceiver, filter)
        }
        BleServerStatus.refreshPrerequisites(this)
    }

    override fun onStop() {
        unregisterReceiver(bluetoothStateReceiver)
        super.onStop()
    }
}

@Composable
private fun HspWatchApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by BleServerStatus.state.collectAsState()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        BleServerStatus.refreshPrerequisites(context)
        if (result.values.any { !it }) {
            BleServerStatus.update {
                it.copy(lastMessage = "部分权限被拒绝，定位和天气同步可能不可用")
            }
        }
    }
    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        BleServerStatus.refreshPrerequisites(context)
        if (!BleServerStatus.state.value.bluetoothEnabled) {
            BleServerStatus.update { current -> current.copy(lastMessage = "蓝牙仍未开启") }
        }
    }

    LaunchedEffect(Unit) { BleServerStatus.refreshPrerequisites(context) }

    HspWatchScreen(
        state = state,
        onRequestPermissions = { permissionLauncher.launch(requiredRuntimePermissions()) },
        onEnableBluetooth = {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        },
        onStartService = { BleServerService.start(context) },
        onStopService = { BleServerService.stop(context) },
        onFindWatch = { BleServerService.findWatch(context) },
        onSyncPhoneData = { BleServerService.syncPhoneData(context) },
        onOpenNotificationAccess = {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        },
        onStopRinging = { BleServerService.stopRinging(context) }
    )
}

private fun requiredRuntimePermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )
    else -> arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )
}
