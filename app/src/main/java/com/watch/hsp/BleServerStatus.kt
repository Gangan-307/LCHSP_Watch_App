package com.watch.hsp

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class WatchDeviceStatus(
    val bleEnabled: Boolean = false,
    val companionConnected: Boolean = false,
    val batteryValid: Boolean = false,
    val batteryPercent: Int? = null,
    val charging: Boolean = false,
    val firmwareVersion: String? = null,
    val activityValid: Boolean = false,
    val steps: Long? = null,
    val caloriesKcal: Int? = null,
    val distanceMeters: Long? = null,
    val receivedAtMillis: Long? = null
)

data class BleUiState(
    val hasBleHardware: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val blePermissionsGranted: Boolean = false,
    val locationPermissionGranted: Boolean = false,
    val photoLibraryPermissionGranted: Boolean = false,
    val notificationsGranted: Boolean = true,
    val messageNotificationAccessEnabled: Boolean = false,
    val serviceRunning: Boolean = false,
    val scanning: Boolean = false,
    val scanRequestPending: Boolean = false,
    val scanBackoff: Boolean = false,
    val protocolIncompatible: Boolean = false,
    val connected: Boolean = false,
    val commandChannelReady: Boolean = false,
    val statusChannelReady: Boolean = false,
    val syncChannelReady: Boolean = false,
    val watchAddress: String? = null,
    val watchStatus: WatchDeviceStatus = WatchDeviceStatus(),
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
        val locationPermissionGranted = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        val photoLibraryPermissionGranted = if (Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
        val messageNotificationAccessEnabled =
            NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)

        update {
            it.copy(
                hasBleHardware = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE),
                bluetoothEnabled = adapter?.isEnabled == true,
                blePermissionsGranted = blePermissionGranted,
                locationPermissionGranted = locationPermissionGranted,
                photoLibraryPermissionGranted = photoLibraryPermissionGranted,
                notificationsGranted = notificationGranted,
                messageNotificationAccessEnabled = messageNotificationAccessEnabled
            )
        }
    }
}
