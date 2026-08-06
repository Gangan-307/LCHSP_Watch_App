package com.watch.hsp

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BleUiState(
    val hasBleHardware: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val blePermissionsGranted: Boolean = false,
    val notificationsGranted: Boolean = true,
    val serviceRunning: Boolean = false,
    val scanning: Boolean = false,
    val scanRequestPending: Boolean = false,
    val scanBackoff: Boolean = false,
    val protocolIncompatible: Boolean = false,
    val connected: Boolean = false,
    val commandChannelReady: Boolean = false,
    val watchAddress: String? = null,
    val ringing: Boolean = false,
    val lastMessage: String = "等待启动服务"
)

/** Process-local state exposed by the foreground service to the Compose activity. */
object BleServerStatus {
    private val mutableState = MutableStateFlow(BleUiState())
    val state: StateFlow<BleUiState> = mutableState.asStateFlow()

    fun update(transform: (BleUiState) -> BleUiState) {
        mutableState.value = transform(mutableState.value)
    }

    fun refreshPrerequisites(context: Context) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val blePermissionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            ).all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        } else {
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
        val notificationGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

        update {
            it.copy(
                hasBleHardware = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE),
                bluetoothEnabled = adapter?.isEnabled == true,
                blePermissionsGranted = blePermissionGranted,
                notificationsGranted = notificationGranted
            )
        }
    }
}
