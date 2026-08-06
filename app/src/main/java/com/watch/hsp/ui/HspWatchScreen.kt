package com.watch.hsp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.watch.hsp.BleUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HspWatchScreen(
    state: BleUiState,
    onRequestPermissions: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onFindWatch: () -> Unit,
    onStopRinging: () -> Unit,
    showDebugDetailsInitially: Boolean = false
) {
    var showDebugDetails by rememberSaveable { mutableStateOf(showDebugDetailsInitially) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("HSP Watch") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(2.dp))

            ConnectionSummaryCard(state)

            PrimaryActionButton(
                state = state,
                onFindWatch = onFindWatch,
                onStopRinging = onStopRinging
            )

            if (!state.blePermissionsGranted || !state.notificationsGranted) {
                PermissionNoticeCard(
                    state = state,
                    onClick = onRequestPermissions,
                )
            }
            if (!state.bluetoothEnabled) {
                BluetoothNoticeCard(
                    onClick = onEnableBluetooth,
                    enabled = state.hasBleHardware && state.blePermissionsGranted
                )
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(
                    onClick = onStartService,
                    enabled = state.hasBleHardware && state.bluetoothEnabled &&
                        state.blePermissionsGranted && !state.serviceRunning,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("启动服务")
                }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(
                    onClick = onStopService,
                    enabled = state.serviceRunning,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("停止服务")
                }
            }

            Text(
                state.lastMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            FilledTonalButton(
                onClick = { showDebugDetails = !showDebugDetails },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (showDebugDetails) "隐藏调试信息" else "显示调试信息")
            }

            if (showDebugDetails) {
                StatusCard(state)
                Text(
                    "本应用遵循手机当前的声音和勿扰模式设置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ConnectionSummaryCard(state: BleUiState) {
    val summary = connectionSummary(state)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(containerColor = summary.containerColor)
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    summary.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = summary.contentColor
                )
                Text(
                    summary.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = summary.contentColor
                )
                Text(
                    "BLE 地址：${state.watchAddress ?: "尚未发现"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = summary.contentColor
                )
            }
            if (summary.busy) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(start = 16.dp)
                        .size(32.dp),
                    color = summary.contentColor,
                    strokeWidth = 3.dp
                )
            }
        }
    }
}

@Composable
private fun PrimaryActionButton(
    state: BleUiState,
    onFindWatch: () -> Unit,
    onStopRinging: () -> Unit
) {
    val readyForBleAction = state.hasBleHardware && state.bluetoothEnabled && state.blePermissionsGranted
    if (state.ringing) {
        Button(
            onClick = onStopRinging,
            enabled = state.serviceRunning,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
            Text("停止手机响铃")
        }
        return
    }

    Button(
        onClick = onFindWatch,
        enabled = readyForBleAction,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
    ) {
        Text(
            when {
                state.commandChannelReady -> "查找手表"
                state.serviceRunning -> "连接后查找手表"
                else -> "启动并查找手表"
            }
        )
    }
}

@Composable
private fun PermissionNoticeCard(state: BleUiState, onClick: () -> Unit) {
    NoticeCard(
        title = "需要授权",
        message = permissionNoticeText(state),
        actionText = "授予所需权限",
        onClick = onClick
    )
}

@Composable
private fun BluetoothNoticeCard(onClick: () -> Unit, enabled: Boolean) {
    NoticeCard(
        title = "蓝牙未开启",
        message = "开启蓝牙后，App 才能连接并查找手表。",
        actionText = "开启蓝牙",
        enabled = enabled,
        onClick = onClick
    )
}

@Composable
private fun NoticeCard(
    title: String,
    message: String,
    actionText: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(message, style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(actionText)
            }
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
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            color = color,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

private data class ConnectionSummary(
    val title: String,
    val message: String,
    val busy: Boolean,
    val containerColor: Color,
    val contentColor: Color
)

@Composable
private fun connectionSummary(state: BleUiState): ConnectionSummary {
    val success = Color(0xFFEAF6ED)
    val successContent = Color(0xFF1B5E2E)
    val warning = Color(0xFFFFF4D7)
    val warningContent = Color(0xFF6F4D00)
    val idle = MaterialTheme.colorScheme.surfaceContainerLow
    val idleContent = MaterialTheme.colorScheme.onSurface
    val error = MaterialTheme.colorScheme.errorContainer
    val errorContent = MaterialTheme.colorScheme.onErrorContainer

    return when {
        state.protocolIncompatible -> ConnectionSummary(
            "协议不兼容",
            "请更新手表固件后重新连接。",
            busy = false,
            containerColor = error,
            contentColor = errorContent
        )
        !state.hasBleHardware -> ConnectionSummary(
            "设备不支持 BLE",
            "当前手机无法使用低功耗蓝牙查找手表。",
            busy = false,
            containerColor = error,
            contentColor = errorContent
        )
        !state.blePermissionsGranted || !state.notificationsGranted -> ConnectionSummary(
            "需要完成授权",
            "授权后才能保持连接并响应查找请求。",
            busy = false,
            containerColor = warning,
            contentColor = warningContent
        )
        !state.bluetoothEnabled -> ConnectionSummary(
            "蓝牙未开启",
            "开启蓝牙后即可连接 HSP Watch。",
            busy = false,
            containerColor = warning,
            contentColor = warningContent
        )
        state.ringing -> ConnectionSummary(
            "手机正在响铃",
            "可在页面上停止提醒。",
            busy = false,
            containerColor = warning,
            contentColor = warningContent
        )
        state.commandChannelReady -> ConnectionSummary(
            "手表已连接",
            "查找通道已就绪，可以互相查找。",
            busy = false,
            containerColor = success,
            contentColor = successContent
        )
        state.connected -> ConnectionSummary(
            "正在准备通道",
            "已连接手表，正在订阅状态通知。",
            busy = true,
            containerColor = idle,
            contentColor = idleContent
        )
        state.scanning || state.scanRequestPending -> ConnectionSummary(
            "正在寻找手表",
            "App 会优先直连缓存地址，失败后再扫描。",
            busy = true,
            containerColor = idle,
            contentColor = idleContent
        )
        state.scanBackoff -> ConnectionSummary(
            "扫描等待中",
            "系统限制了 BLE 扫描频率，稍后会自动重试。",
            busy = true,
            containerColor = warning,
            contentColor = warningContent
        )
        state.serviceRunning -> ConnectionSummary(
            "正在连接手表",
            "服务已启动，正在建立查找链路。",
            busy = true,
            containerColor = idle,
            contentColor = idleContent
        )
        else -> ConnectionSummary(
            "服务未启动",
            "点击启动服务或直接查找手表以建立连接。",
            busy = false,
            containerColor = idle,
            contentColor = idleContent
        )
    }
}

private fun permissionNoticeText(state: BleUiState): String {
    val missing = buildList {
        if (!state.blePermissionsGranted) add("蓝牙权限")
        if (!state.notificationsGranted) add("通知权限")
    }
    return "还需要${missing.joinToString("、")}，用于连接手表并保持前台服务。"
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
