package com.watch.hsp.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.watch.hsp.BleUiState
import com.watch.hsp.WatchDeviceStatus
import com.watch.hsp.ui.theme.HspTheme

@Preview(name = "需要授权", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun HspWatchNeedsPermissionPreview() {
    HspWatchPreview(
        state = BleUiState(
            hasBleHardware = true,
            bluetoothEnabled = true,
            blePermissionsGranted = false,
            notificationsGranted = false,
            lastMessage = "等待授予权限"
        )
    )
}

@Preview(name = "手表已连接", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun HspWatchConnectedPreview() {
    HspWatchPreview(
        state = BleUiState(
            hasBleHardware = true,
            bluetoothEnabled = true,
            blePermissionsGranted = true,
            notificationsGranted = true,
            serviceRunning = true,
            connected = true,
            commandChannelReady = true,
            statusChannelReady = true,
            syncChannelReady = true,
            watchAddress = "AA:BB:CC:12:34:56",
            watchStatus = WatchDeviceStatus(
                batteryValid = true,
                batteryPercent = 82,
                activityValid = true,
                steps = 5820,
                caloriesKcal = 233,
                distanceMeters = 4074,
                firmwareVersion = "0.1.0"
            ),
            lastMessage = "已直连手表，查找通道已就绪"
        )
    )
}

@Preview(name = "手机响铃中", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun HspWatchRingingPreview() {
    HspWatchPreview(
        state = BleUiState(
            hasBleHardware = true,
            bluetoothEnabled = true,
            blePermissionsGranted = true,
            notificationsGranted = true,
            serviceRunning = true,
            connected = true,
            commandChannelReady = true,
            watchAddress = "AA:BB:CC:12:34:56",
            ringing = true,
            lastMessage = "手机正在响铃（序号 1）"
        ),
        showDebugDetails = true
    )
}

@Composable
private fun HspWatchPreview(state: BleUiState, showDebugDetails: Boolean = false) {
    HspTheme(dynamicColor = false) {
        HspWatchScreen(
            state = state,
            onRequestPermissions = {},
            onEnableBluetooth = {},
            onStartService = {},
            onStopService = {},
            onFindWatch = {},
            onSyncPhoneData = {},
            onOpenRemoteCamera = {},
            onOpenNotificationAccess = {},
            onClearNotifications = {},
            onStopRinging = {},
            showDebugDetailsInitially = showDebugDetails
        )
    }
}
