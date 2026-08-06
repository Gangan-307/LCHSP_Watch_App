package com.watch.hsp

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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

@OptIn(ExperimentalMaterial3Api::class)
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
                it.copy(lastMessage = "部分权限被拒绝，启动服务需要蓝牙权限")
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

    Scaffold(
        topBar = { TopAppBar(title = { Text("HSP Watch") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(2.dp))
            Text("互相查找", style = MaterialTheme.typography.headlineSmall)
            Text(
                "App 优先直连已保存的手表地址，失败时才扫描；连接完成后可互相查找。",
                style = MaterialTheme.typography.bodyMedium
            )

            StatusCard(state)

            if (!state.blePermissionsGranted || !state.notificationsGranted) {
                Button(
                    onClick = { permissionLauncher.launch(requiredRuntimePermissions()) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("授予所需权限")
                }
            }
            if (!state.bluetoothEnabled) {
                Button(
                    onClick = {
                        enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.hasBleHardware && state.blePermissionsGranted
                ) {
                    Text("开启蓝牙")
                }
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { BleServerService.start(context) },
                    enabled = state.hasBleHardware && state.bluetoothEnabled && state.blePermissionsGranted && !state.serviceRunning,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("启动服务")
                }
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = { BleServerService.stop(context) },
                    enabled = state.serviceRunning,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("停止服务")
                }
            }
            Button(
                onClick = { BleServerService.findWatch(context) },
                enabled = state.serviceRunning && state.hasBleHardware && state.bluetoothEnabled &&
                    state.blePermissionsGranted,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("查找手表")
            }
            Button(
                onClick = { BleServerService.stopRinging(context) },
                enabled = state.serviceRunning && state.ringing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("停止手机响铃")
            }
            Text(
                "${state.lastMessage}。本应用遵循手机当前的声音和勿扰模式设置。",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun StatusCard(state: BleUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusRow("BLE 硬件", state.hasBleHardware)
            StatusRow("蓝牙", state.bluetoothEnabled)
            StatusRow("蓝牙权限", state.blePermissionsGranted)
            StatusRow("通知权限", state.notificationsGranted)
            HorizontalDivider()
            StatusRow("前台服务", state.serviceRunning)
            StatusDetailRow("扫描回退", scanStatusText(state), scanStatusColor(state))
            StatusRow("手表连接", state.connected)
            StatusRow("手表命令通道", state.commandChannelReady)
            if (state.protocolIncompatible) {
                StatusDetailRow("手表协议", "不兼容", MaterialTheme.colorScheme.error)
            }
            StatusRow("响铃", state.ringing)
            Text("BLE 地址：${state.watchAddress ?: "尚未发现"}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StatusRow(label: String, enabled: Boolean) {
    StatusDetailRow(
        label,
        if (enabled) "正常" else "不可用",
        if (enabled) Color(0xFF207A3B) else MaterialTheme.colorScheme.error
    )
}

@Composable
private fun StatusDetailRow(label: String, value: String, color: Color) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            color = color,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private fun scanStatusText(state: BleUiState): String = when {
    state.protocolIncompatible -> "协议不兼容"
    state.scanBackoff -> "限频等待"
    state.scanRequestPending -> "请求中"
    state.scanning -> "扫描中"
    else -> "空闲"
}

@Composable
private fun scanStatusColor(state: BleUiState): Color = when {
    state.protocolIncompatible -> MaterialTheme.colorScheme.error
    state.scanBackoff -> Color(0xFF9A6700)
    state.scanRequestPending || state.scanning -> Color(0xFF207A3B)
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun requiredRuntimePermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.POST_NOTIFICATIONS
    )
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT
    )
    else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}
