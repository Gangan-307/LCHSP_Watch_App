package com.watch.hsp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.watch.hsp.data.WatchPreferences
import com.watch.hsp.service.PhoneAlertController
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque
import java.util.Locale
import org.json.JSONObject

/**
 * Foreground BLE client for the watch-owned HSP companion service.
 *
 * The app reconnects to its last observed BLE address first.  A filtered scan
 * is only the fallback path, so normal classic Bluetooth links do not get
 * disturbed by a watch-side scan/reconnect loop.
 */
class BleServerService : Service() {
    companion object {
        private const val TAG = "HspBleClient"
        private const val CHANNEL_ID = "hsp_watch_service"
        private const val NOTIFICATION_ID = 1001

        private const val ACTION_START = "com.watch.hsp.action.START"
        private const val ACTION_FIND_WATCH = "com.watch.hsp.action.FIND_WATCH"
        private const val ACTION_STOP_RINGING = "com.watch.hsp.action.STOP_RINGING"
        private const val ACTION_SYNC_PHONE_DATA = "com.watch.hsp.action.SYNC_PHONE_DATA"

        private const val SCAN_TIMEOUT_MS = 15_000L
        private const val SCAN_ACTIVATION_GRACE_MS = 1_000L
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val SCAN_FAILURE_RETRY_MS = 5_000L
        private const val SCAN_TOO_FREQUENTLY_RETRY_MS = 30_000L

        fun start(context: Context) = sendForegroundCommand(context, ACTION_START)

        fun findWatch(context: Context) = sendForegroundCommand(context, ACTION_FIND_WATCH)

        fun syncPhoneData(context: Context) = sendForegroundCommand(context, ACTION_SYNC_PHONE_DATA)

        fun stop(context: Context) {
            context.stopService(Intent(context, BleServerService::class.java))
        }

        fun stopRinging(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BleServerService::class.java).setAction(ACTION_STOP_RINGING)
            )
        }

        private fun sendForegroundCommand(context: Context, action: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BleServerService::class.java).setAction(action)
            )
        }
    }

    private val bluetoothManager by lazy { getSystemService(BluetoothManager::class.java) }
    private val phoneAlertController by lazy { PhoneAlertController(this) }
    private val watchPreferences by lazy { WatchPreferences(this) }
    private val locationManager by lazy { getSystemService(LocationManager::class.java) }
    private val handler = Handler(Looper.getMainLooper())

    private var scanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var controlCharacteristic: BluetoothGattCharacteristic? = null
    private var stateCharacteristic: BluetoothGattCharacteristic? = null
    private var syncCharacteristic: BluetoothGattCharacteristic? = null
    private var deviceStatusCharacteristic: BluetoothGattCharacteristic? = null
    private var isScanning = false
    private var scanRequestPending = false
    private var scanBlockedUntilMs = 0L
    private var protocolIncompatible = false
    private var pendingScanFallbackReason: String? = null
    private var isConnecting = false
    private var connectingFromCachedAddress = false
    private var pendingFindWatch = false
    private var pendingPhoneSync = false
    private var watchSequence: Byte = 0
    private val syncWriteQueue = ArrayDeque<ByteArray>()
    private var syncWriteInFlight = false
    private var phoneSyncGeneration = 0L

    private val scanTimeout = Runnable {
        if (!isScanning && !scanRequestPending) return@Runnable
        stopScan()
        scheduleReconnect("未找到手表，稍后重试")
    }

    private val scanActivation = Runnable {
        if (!scanRequestPending || isScanning) return@Runnable

        scanRequestPending = false
        isScanning = true
        BleServerStatus.update {
            it.copy(scanning = true, scanRequestPending = false, scanBackoff = false,
                lastMessage = "正在扫描手表")
        }
        Log.i(TAG, "Service-filtered BLE scan is active")
    }

    private val connectionTimeout = Runnable {
        if (!isConnecting) return@Runnable
        val source = if (connectingFromCachedAddress) "缓存地址直连超时" else "扫描后连接超时"
        handleLinkFailure(source, scanImmediately = connectingFromCachedAddress)
    }

    private val reconnectTask = Runnable {
        val scanReason = pendingScanFallbackReason
        pendingScanFallbackReason = null
        if (!BleServerStatus.state.value.serviceRunning || protocolIncompatible) return@Runnable

        if (scanReason != null) startScan(scanReason) else startBleClient()
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            Log.i(TAG, "Bluetooth adapter state changed: $state")
            BleServerStatus.refreshPrerequisites(this@BleServerService)
            if (state == BluetoothAdapter.STATE_ON && BleServerStatus.state.value.serviceRunning) {
                startBleClient()
            } else if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                stopRinging("蓝牙已关闭")
                stopBleResources()
                BleServerStatus.update {
                    it.copy(scanning = false, scanRequestPending = false, scanBackoff = false,
                        connected = false, commandChannelReady = false, statusChannelReady = false,
                        syncChannelReady = false,
                        lastMessage = "蓝牙已关闭")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(bluetoothStateReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_RINGING -> {
                if (BleServerStatus.state.value.serviceRunning) stopRinging("通知或界面操作")
            }
            ACTION_FIND_WATCH -> {
                pendingFindWatch = true
                ensureForeground()
                startBleClient(userInitiated = true)
                sendFindWatchCommand()
            }
            ACTION_SYNC_PHONE_DATA -> {
                pendingPhoneSync = true
                ensureForeground()
                startBleClient(userInitiated = true)
                requestPhoneSync()
            }
            else -> {
                ensureForeground()
                startBleClient(userInitiated = true)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureForeground() {
        startForeground(NOTIFICATION_ID, buildNotification())
        BleServerStatus.refreshPrerequisites(this)
        BleServerStatus.update { it.copy(serviceRunning = true) }
    }

    private fun startBleClient(userInitiated: Boolean = false) {
        if (userInitiated && protocolIncompatible) {
            protocolIncompatible = false
            BleServerStatus.update {
                it.copy(protocolIncompatible = false, lastMessage = "正在重新检查手表协议")
            }
        }

        BleServerStatus.refreshPrerequisites(this)
        val prerequisites = BleServerStatus.state.value
        when {
            !prerequisites.hasBleHardware -> {
                setError("此手机不支持低功耗蓝牙")
                return
            }
            !prerequisites.blePermissionsGranted -> {
                setError("需要蓝牙扫描和连接权限")
                return
            }
            !prerequisites.bluetoothEnabled -> {
                setError("请开启蓝牙后再连接手表")
                return
            }
            protocolIncompatible -> return
            bluetoothGatt != null || isScanning || scanRequestPending || isConnecting -> return
        }

        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            setError("蓝牙适配器不可用")
            return
        }

        val cachedAddress = watchPreferences.watchAddress
        if (cachedAddress != null) {
            try {
                connectToWatch(adapter.getRemoteDevice(cachedAddress), fromCachedAddress = true)
                return
            } catch (exception: IllegalArgumentException) {
                watchPreferences.clearWatchAddress()
                Log.w(TAG, "Discarded invalid cached BLE address: $cachedAddress", exception)
            } catch (exception: SecurityException) {
                setError("蓝牙连接权限已被撤销")
                return
            }
        }

        startScan("未缓存手表地址，正在扫描")
    }

    private fun connectToWatch(device: BluetoothDevice, fromCachedAddress: Boolean) {
        if (bluetoothGatt != null || isConnecting) return

        connectingFromCachedAddress = fromCachedAddress
        isConnecting = true
        BleServerStatus.update {
            it.copy(scanning = false, scanRequestPending = false, scanBackoff = false,
                connected = false, commandChannelReady = false, statusChannelReady = false,
                syncChannelReady = false,
                watchAddress = device.address,
                lastMessage = if (fromCachedAddress) "正在直连已保存的手表" else "正在连接扫描到的手表")
        }

        try {
            bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (bluetoothGatt == null) {
                handleLinkFailure("无法创建 BLE 连接", scanImmediately = fromCachedAddress)
                return
            }
            handler.removeCallbacks(connectionTimeout)
            handler.postDelayed(connectionTimeout, 10_000L)
        } catch (exception: SecurityException) {
            setError("蓝牙连接权限已被撤销")
            closeGatt()
        } catch (exception: IllegalStateException) {
            handleLinkFailure("蓝牙系统暂不可用", scanImmediately = fromCachedAddress)
        }
    }

    private fun startScan(reason: String) {
        if (protocolIncompatible || isScanning || scanRequestPending || bluetoothGatt != null || isConnecting) return

        val remainingBackoffMs = scanBlockedUntilMs - SystemClock.elapsedRealtime()
        if (remainingBackoffMs > 0L) {
            scheduleReconnect("BLE 扫描限频", remainingBackoffMs, scanBackoff = true)
            return
        }
        scanBlockedUntilMs = 0L

        val adapter = bluetoothManager?.adapter
        scanner = adapter?.bluetoothLeScanner
        val activeScanner = scanner
        if (activeScanner == null) {
            scheduleReconnect("BLE 扫描器不可用")
            return
        }

        try {
            val filter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BleProtocol.SERVICE_UUID))
                .build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            activeScanner.startScan(listOf(filter), settings, scanCallback)
            scanRequestPending = true
            BleServerStatus.update {
                it.copy(scanning = false, scanRequestPending = true, scanBackoff = false,
                    lastMessage = "$reason，正在请求扫描")
            }
            handler.removeCallbacks(scanTimeout)
            handler.removeCallbacks(scanActivation)
            handler.postDelayed(scanActivation, SCAN_ACTIVATION_GRACE_MS)
            handler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
            Log.i(TAG, "Requested service-filtered BLE scan")
        } catch (exception: SecurityException) {
            setError("蓝牙扫描权限已被撤销")
        } catch (exception: IllegalStateException) {
            scheduleReconnect("蓝牙扫描暂不可用")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            if (protocolIncompatible || bluetoothGatt != null || isConnecting) return

            Log.i(TAG, "Found HSP watch ${device.address}, RSSI=${result.rssi}")
            watchPreferences.saveWatchAddress(device.address)
            stopScan()
            connectToWatch(device, fromCachedAddress = false)
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            scanRequestPending = false
            handler.removeCallbacks(scanTimeout)
            handler.removeCallbacks(scanActivation)

            val retryDelay = if (errorCode == ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY) {
                SCAN_TOO_FREQUENTLY_RETRY_MS
            } else {
                SCAN_FAILURE_RETRY_MS
            }
            val limited = errorCode == ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY
            if (limited) scanBlockedUntilMs = SystemClock.elapsedRealtime() + retryDelay
            scheduleReconnect("BLE 扫描失败：$errorCode", retryDelay, scanBackoff = limited)
        }
    }

    private fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        handler.removeCallbacks(scanActivation)
        if (!isScanning && !scanRequestPending) return
        try {
            scanner?.stopScan(scanCallback)
        } catch (exception: SecurityException) {
            Log.w(TAG, "Unable to stop BLE scan after permission change", exception)
        }
        isScanning = false
        scanRequestPending = false
        BleServerStatus.update { it.copy(scanning = false, scanRequestPending = false) }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (gatt !== bluetoothGatt) {
                gatt.close()
                return
            }
            handler.removeCallbacks(connectionTimeout)
            isConnecting = false

            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                handler.removeCallbacks(reconnectTask)
                pendingScanFallbackReason = null
                Log.i(TAG, "Connected to watch ${gatt.device.address}; discovering services")
                BleServerStatus.update {
                    it.copy(connected = true, commandChannelReady = false, statusChannelReady = false,
                        syncChannelReady = false,
                        watchAddress = gatt.device.address, lastMessage = "已连接手表，正在发现服务")
                }
                if (!gatt.discoverServices()) {
                    handleLinkFailure("无法发现手表服务", scanImmediately = false)
                }
                return
            }

            val useScanFallback = connectingFromCachedAddress
            Log.w(TAG, "GATT disconnected status=$status state=$newState")
            closeGatt(gatt)
            BleServerStatus.update {
                it.copy(connected = false, commandChannelReady = false, statusChannelReady = false,
                    syncChannelReady = false,
                    lastMessage = "手表连接已断开：$status")
            }
            if (useScanFallback) scheduleScanFallback("缓存地址不可用，正在扫描手表")
            else scheduleReconnect("手表连接已断开")
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (gatt !== bluetoothGatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handleLinkFailure("手表服务发现失败：$status", scanImmediately = false)
                return
            }

            val service = gatt.getService(BleProtocol.SERVICE_UUID)
            controlCharacteristic = service?.getCharacteristic(BleProtocol.CONTROL_UUID)
            stateCharacteristic = service?.getCharacteristic(BleProtocol.STATE_UUID)
            syncCharacteristic = service?.getCharacteristic(BleProtocol.SYNC_UUID)
            deviceStatusCharacteristic = service?.getCharacteristic(BleProtocol.DEVICE_STATUS_UUID)
            if (controlCharacteristic == null || stateCharacteristic == null ||
                deviceStatusCharacteristic == null) {
                handleProtocolIncompatibility()
                return
            }

            enableStateNotifications(gatt, stateCharacteristic!!)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (gatt !== bluetoothGatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handleLinkFailure("无法订阅手表状态：$status", scanImmediately = false)
                return
            }

            when (descriptor.characteristic.uuid) {
                BleProtocol.STATE_UUID -> {
                    BleServerStatus.update {
                        it.copy(connected = true, commandChannelReady = false,
                            statusChannelReady = false, watchAddress = gatt.device.address,
                            syncChannelReady = false,
                            lastMessage = "正在订阅手表状态")
                    }
                    Log.i(TAG, "HSP STATE notification subscribed")
                    enableDeviceStatusNotifications(gatt, deviceStatusCharacteristic!!)
                }
                BleProtocol.DEVICE_STATUS_UUID -> {
                    BleServerStatus.update {
                        it.copy(connected = true, commandChannelReady = true,
                            statusChannelReady = true, watchAddress = gatt.device.address,
                            syncChannelReady = syncCharacteristic != null,
                            lastMessage = if (syncCharacteristic != null) {
                                "手表已连接，状态与手机同步已就绪"
                            } else {
                                "手表已连接，请更新固件以同步时间和天气"
                            })
                    }
                    Log.i(TAG, "HSP device status notification subscribed")
                    if (pendingFindWatch) sendFindWatchCommand()
                    pendingPhoneSync = true
                    requestPhoneSync()
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (gatt !== bluetoothGatt) return
            when (characteristic.uuid) {
                BleProtocol.STATE_UUID -> handleWatchState(characteristic.value ?: return)
                BleProtocol.DEVICE_STATUS_UUID -> handleDeviceStatus(characteristic.value ?: return)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (gatt !== bluetoothGatt) return
            when (characteristic.uuid) {
                BleProtocol.STATE_UUID -> handleWatchState(value)
                BleProtocol.DEVICE_STATUS_UUID -> handleDeviceStatus(value)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (gatt !== bluetoothGatt) return
            when (characteristic.uuid) {
                BleProtocol.CONTROL_UUID -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        BleServerStatus.update { it.copy(lastMessage = "已发送查找手表命令") }
                    } else {
                        BleServerStatus.update { it.copy(lastMessage = "查找手表命令发送失败：$status") }
                    }
                }
                BleProtocol.SYNC_UUID -> handler.post {
                    if (gatt === bluetoothGatt) completeSyncWrite(status)
                }
            }
        }
    }

    private fun enableStateNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        val descriptor = characteristic.getDescriptor(BleProtocol.CCCD_UUID)
        if (descriptor == null || !gatt.setCharacteristicNotification(characteristic, true)) {
            handleLinkFailure("手表状态通知不可用", scanImmediately = false)
            return
        }

        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
        if (!started) handleLinkFailure("无法请求手表状态通知", scanImmediately = false)
    }

    private fun enableDeviceStatusNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        val descriptor = characteristic.getDescriptor(BleProtocol.CCCD_UUID)
        if (descriptor == null || !gatt.setCharacteristicNotification(characteristic, true)) {
            handleLinkFailure("手表设备状态通知不可用", scanImmediately = false)
            return
        }

        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
        if (!started) handleLinkFailure("无法请求手表设备状态通知", scanImmediately = false)
    }

    private fun handleWatchState(packet: ByteArray) {
        if (packet.isEmpty()) return
        val sequence = packet.getOrElse(1) { 0 }
        when (packet[0]) {
            BleProtocol.PHONE_COMMAND_FIND_START -> startRinging(sequence)
            BleProtocol.PHONE_COMMAND_FIND_STOP -> stopRinging("手表停止命令")
            else -> Log.w(TAG, "Ignored unknown watch state: 0x%02X".format(packet[0].toInt() and 0xff))
        }
    }

    private fun handleDeviceStatus(packet: ByteArray) {
        val status = BleProtocol.decodeDeviceStatus(packet)
        if (status == null) {
            Log.w(TAG, "Ignored malformed watch device status packet")
            return
        }

        BleServerStatus.update {
            it.copy(
                watchStatus = WatchDeviceStatus(
                    bleEnabled = status.bleEnabled,
                    companionConnected = status.companionConnected,
                    batteryValid = status.batteryValid,
                    batteryPercent = status.batteryPercent,
                    charging = status.charging,
                    firmwareVersion = status.firmwareVersion,
                    activityValid = status.activityValid,
                    steps = status.steps,
                    caloriesKcal = status.caloriesKcal,
                    distanceMeters = status.distanceMeters,
                    receivedAtMillis = System.currentTimeMillis()
                )
            )
        }
        Log.i(TAG, "Watch status: battery=${status.batteryPercent}, charging=${status.charging}, steps=${status.steps}, firmware=${status.firmwareVersion}")
    }

    /** Queue time immediately, then use one phone location fix to obtain weather. */
    private fun requestPhoneSync() {
        handler.post {
            val gatt = bluetoothGatt
            if (gatt == null || !BleServerStatus.state.value.connected) {
                pendingPhoneSync = true
                return@post
            }
            if (syncCharacteristic == null && !BleServerStatus.state.value.statusChannelReady) {
                pendingPhoneSync = true
                return@post
            }
            if (syncCharacteristic == null) {
                pendingPhoneSync = false
                BleServerStatus.update {
                    it.copy(syncChannelReady = false,
                        lastMessage = "此手表固件不支持时间、位置和天气同步")
                }
                return@post
            }

            pendingPhoneSync = false
            enqueueSyncPacket(BleProtocol.buildTimeSyncPacket())
            requestPhoneLocationAndWeather()
        }
    }

    private fun enqueueSyncPacket(packet: ByteArray) {
        if (bluetoothGatt == null || syncCharacteristic == null ||
            !BleServerStatus.state.value.connected) {
            return
        }
        syncWriteQueue.addLast(packet)
        drainSyncWriteQueue()
    }

    /** Android permits only one outstanding GATT write, so packets are serialized here. */
    private fun drainSyncWriteQueue() {
        if (syncWriteInFlight || syncWriteQueue.isEmpty()) return

        val gatt = bluetoothGatt
        val characteristic = syncCharacteristic
        if (gatt == null || characteristic == null || !BleServerStatus.state.value.connected) {
            pendingPhoneSync = true
            return
        }

        val packet = syncWriteQueue.peekFirst()
        val started = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    characteristic,
                    packet,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = packet
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
        } catch (exception: SecurityException) {
            setError("蓝牙连接权限已被撤销")
            false
        }

        if (started) {
            syncWriteInFlight = true
        } else {
            syncWriteQueue.clear()
            BleServerStatus.update { it.copy(lastMessage = "手机数据同步命令未能发送") }
        }
    }

    private fun completeSyncWrite(status: Int) {
        syncWriteInFlight = false
        if (status != BluetoothGatt.GATT_SUCCESS) {
            syncWriteQueue.clear()
            BleServerStatus.update { it.copy(lastMessage = "手机数据同步失败：$status") }
            return
        }

        syncWriteQueue.pollFirst()
        drainSyncWriteQueue()
    }

    private fun requestPhoneLocationAndWeather() {
        val generation = ++phoneSyncGeneration
        if (!BleServerStatus.state.value.locationPermissionGranted) {
            BleServerStatus.update {
                it.copy(lastMessage = "已同步手机时间；允许定位后可同步位置和天气")
            }
            return
        }

        val manager = locationManager
        if (manager == null) {
            BleServerStatus.update { it.copy(lastMessage = "已同步手机时间；定位服务不可用") }
            return
        }

        val providers = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        val lastLocation = try {
            providers.mapNotNull { provider -> manager.getLastKnownLocation(provider) }
                .maxByOrNull { location -> location.time }
        } catch (exception: SecurityException) {
            BleServerStatus.update { it.copy(lastMessage = "定位权限已被撤销") }
            return
        }
        val now = System.currentTimeMillis()
        if (lastLocation != null && now - lastLocation.time <= 5 * 60_000L) {
            handlePhoneLocation(lastLocation, generation)
            return
        }

        val provider = providers.firstOrNull { candidate ->
            try {
                manager.isProviderEnabled(candidate)
            } catch (exception: SecurityException) {
                false
            }
        }
        if (provider == null) {
            BleServerStatus.update { it.copy(lastMessage = "已同步手机时间；请开启手机定位服务") }
            return
        }

        var locationDelivered = false
        lateinit var locationTimeout: Runnable
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (locationDelivered) return
                locationDelivered = true
                handler.removeCallbacks(locationTimeout)
                try {
                    manager.removeUpdates(this)
                } catch (exception: SecurityException) {
                    Log.w(TAG, "Unable to remove one-shot location listener", exception)
                }
                handlePhoneLocation(location, generation)
            }
        }
        locationTimeout = Runnable {
            if (locationDelivered) return@Runnable
            locationDelivered = true
            try {
                manager.removeUpdates(listener)
            } catch (exception: SecurityException) {
                Log.w(TAG, "Unable to remove timed-out location listener", exception)
            }
            if (generation != phoneSyncGeneration) {
                Log.i(TAG, "Discarded stale location timeout generation=$generation current=$phoneSyncGeneration")
                return@Runnable
            }
            BleServerStatus.update {
                it.copy(lastMessage = "已同步手机时间；定位超时，未更新位置和天气")
            }
        }
        try {
            @Suppress("DEPRECATION")
            manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            handler.postDelayed(locationTimeout, 20_000L)
            BleServerStatus.update { it.copy(lastMessage = "已同步手机时间，正在获取位置和天气") }
        } catch (exception: SecurityException) {
            BleServerStatus.update { it.copy(lastMessage = "定位权限已被撤销") }
        } catch (exception: IllegalArgumentException) {
            BleServerStatus.update { it.copy(lastMessage = "手机定位提供者暂不可用") }
        }
    }

    private fun handlePhoneLocation(location: Location, generation: Long) {
        if (generation != phoneSyncGeneration) {
            Log.i(TAG, "Discarded stale location result generation=$generation current=$phoneSyncGeneration")
            return
        }
        if (location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) {
            BleServerStatus.update { it.copy(lastMessage = "手机定位数据无效") }
            return
        }

        handler.post {
            if (generation != phoneSyncGeneration) {
                Log.i(TAG, "Discarded stale location sync generation=$generation current=$phoneSyncGeneration")
                return@post
            }
            enqueueSyncPacket(
                BleProtocol.buildLocationSyncPacket(
                    location.latitude,
                    location.longitude,
                    location.accuracy
                )
            )
            BleServerStatus.update { it.copy(lastMessage = "已同步手机时间和位置，正在获取天气") }
        }
        fetchCityForLocation(location, generation)
        fetchWeatherForLocation(location, generation)
    }

    private fun fetchCityForLocation(location: Location, generation: Long) {
        if (!Geocoder.isPresent()) {
            Log.w(TAG, "No reverse-geocoding service is available")
            return
        }

        val geocoder = Geocoder(this, Locale.ENGLISH)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                geocoder.getFromLocation(
                    location.latitude,
                    location.longitude,
                    1,
                    object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            syncResolvedCity(addresses.firstOrNull(), generation)
                        }

                        override fun onError(errorMessage: String?) {
                            Log.w(TAG, "Unable to resolve city: ${errorMessage ?: "unknown error"}")
                        }
                    }
                )
            } catch (exception: Exception) {
                Log.w(TAG, "Unable to start reverse geocoding", exception)
            }
            return
        }

        Thread {
            try {
                @Suppress("DEPRECATION")
                val address = geocoder.getFromLocation(
                    location.latitude,
                    location.longitude,
                    1
                )?.firstOrNull()
                syncResolvedCity(address, generation)
            } catch (exception: Exception) {
                Log.w(TAG, "Unable to resolve city", exception)
            }
        }.start()
    }

    private fun syncResolvedCity(address: Address?, generation: Long) {
        val cityPacket = sequenceOf(
            address?.locality,
            address?.subAdminArea,
            address?.adminArea,
            address?.countryName
        )
            .mapNotNull { candidate ->
                candidate?.let { name ->
                    runCatching { BleProtocol.buildCitySyncPacket(name) }.getOrNull()
                }
            }
            .firstOrNull()
        if (cityPacket == null) {
            Log.w(TAG, "Reverse geocoding returned no display-safe English place name")
            return
        }

        handler.post {
            if (generation != phoneSyncGeneration) {
                Log.i(TAG, "Discarded stale city sync generation=$generation current=$phoneSyncGeneration")
                return@post
            }
            enqueueSyncPacket(cityPacket)
            val city = cityPacket.copyOfRange(1, cityPacket.size).toString(Charsets.US_ASCII)
            Log.i(TAG, "Queued city sync: $city")
        }
    }

    private fun fetchWeatherForLocation(location: Location, generation: Long) {
        Thread {
            try {
                val weather = requestOpenMeteoWeather(location.latitude, location.longitude)
                handler.post {
                    if (generation != phoneSyncGeneration) {
                        Log.i(TAG, "Discarded stale weather sync generation=$generation current=$phoneSyncGeneration")
                        return@post
                    }
                    enqueueSyncPacket(
                        BleProtocol.buildWeatherSyncPacket(
                            weather.wmoCode,
                            weather.currentCelsius,
                            weather.highCelsius,
                            weather.lowCelsius,
                            weather.humidityPercent
                        )
                    )
                    BleServerStatus.update { it.copy(lastMessage = "已同步手机时间、位置和天气") }
                }
            } catch (exception: Exception) {
                Log.w(TAG, "Unable to request Open-Meteo weather", exception)
                handler.post {
                    if (generation != phoneSyncGeneration) {
                        Log.i(TAG, "Discarded stale weather failure generation=$generation current=$phoneSyncGeneration")
                        return@post
                    }
                    BleServerStatus.update {
                        it.copy(lastMessage = "已同步手机时间和位置，天气获取失败")
                    }
                }
            }
        }.start()
    }

    private fun requestOpenMeteoWeather(latitude: Double, longitude: Double): PhoneWeatherSnapshot {
        val endpoint = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.6f&longitude=%.6f" +
                "&current=temperature_2m,relative_humidity_2m,weather_code" +
                "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1",
            latitude,
            longitude
        )
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "application/json")
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Open-Meteo HTTP ${connection.responseCode}")
            }
            val json = connection.inputStream.bufferedReader().use { reader -> JSONObject(reader.readText()) }
            val current = json.getJSONObject("current")
            val daily = json.getJSONObject("daily")
            return PhoneWeatherSnapshot(
                wmoCode = current.getInt("weather_code"),
                currentCelsius = current.getDouble("temperature_2m"),
                highCelsius = daily.getJSONArray("temperature_2m_max").getDouble(0),
                lowCelsius = daily.getJSONArray("temperature_2m_min").getDouble(0),
                humidityPercent = current.getInt("relative_humidity_2m").coerceIn(0, 100)
            )
        } finally {
            connection.disconnect()
        }
    }

    private data class PhoneWeatherSnapshot(
        val wmoCode: Int,
        val currentCelsius: Double,
        val highCelsius: Double,
        val lowCelsius: Double,
        val humidityPercent: Int
    )

    private fun sendFindWatchCommand() {
        if (!BleServerStatus.state.value.commandChannelReady) {
            BleServerStatus.update { it.copy(lastMessage = "等待手表连接后发送查找命令") }
            return
        }
        val gatt = bluetoothGatt
        val characteristic = controlCharacteristic
        if (gatt == null || characteristic == null) {
            BleServerStatus.update { it.copy(lastMessage = "手表命令通道暂不可用") }
            return
        }

        watchSequence = (watchSequence + 1).toByte()
        val packet = BleProtocol.packet(BleProtocol.WATCH_COMMAND_FIND_START, watchSequence)
        pendingFindWatch = false
        val started = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    characteristic,
                    packet,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = packet
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
        } catch (exception: SecurityException) {
            setError("蓝牙连接权限已被撤销")
            false
        }
        if (!started) {
            pendingFindWatch = true
            BleServerStatus.update { it.copy(lastMessage = "查找手表命令未能发送") }
        }
    }

    private fun startRinging(sequence: Byte) {
        if (phoneAlertController.isRinging) {
            BleServerStatus.update { it.copy(ringing = true, lastMessage = "手机正在响铃") }
            return
        }

        phoneAlertController.start()
            .onSuccess {
                BleServerStatus.update {
                    it.copy(ringing = true, lastMessage = "手机正在响铃（序号 ${sequence.toInt() and 0xff}）")
                }
                Log.i(TAG, "Ringing started by watch")
            }
            .onFailure { exception ->
                Log.e(TAG, "Unable to start ringing", exception)
                BleServerStatus.update { it.copy(ringing = false, lastMessage = "无法响铃：${exception.message}") }
            }
    }

    private fun stopRinging(reason: String) {
        if (phoneAlertController.isRinging) Log.i(TAG, "Ringing stopped: $reason")
        phoneAlertController.stop()
        BleServerStatus.update { it.copy(ringing = false, lastMessage = "空闲：$reason") }
    }

    private fun handleLinkFailure(message: String, scanImmediately: Boolean) {
        Log.w(TAG, message)
        closeGatt()
        BleServerStatus.update {
            it.copy(connected = false, commandChannelReady = false, statusChannelReady = false,
                syncChannelReady = false,
                lastMessage = message)
        }
        if (scanImmediately) scheduleScanFallback("$message，正在扫描回退") else scheduleReconnect(message)
    }

    private fun handleProtocolIncompatibility() {
        val message = "手表查找协议不兼容，请更新固件后重新连接"

        Log.e(TAG, message)
        protocolIncompatible = true
        pendingFindWatch = false
        pendingScanFallbackReason = null
        handler.removeCallbacks(reconnectTask)
        stopScan()
        closeGatt()
        BleServerStatus.update {
            it.copy(scanning = false, scanRequestPending = false, scanBackoff = false,
                protocolIncompatible = true, connected = false, commandChannelReady = false,
                statusChannelReady = false, syncChannelReady = false,
                lastMessage = message)
        }
    }

    private fun scheduleScanFallback(message: String) {
        pendingScanFallbackReason = message
        scheduleReconnect(message, RECONNECT_DELAY_MS, preserveScanFallback = true)
    }

    private fun scheduleReconnect(
        message: String,
        delayMs: Long = RECONNECT_DELAY_MS,
        scanBackoff: Boolean = false,
        preserveScanFallback: Boolean = false
    ) {
        if (!BleServerStatus.state.value.serviceRunning || protocolIncompatible) return
        if (!preserveScanFallback) pendingScanFallbackReason = null
        handler.removeCallbacks(reconnectTask)
        val delaySeconds = (delayMs + 999L) / 1_000L
        BleServerStatus.update {
            it.copy(scanning = false, scanRequestPending = false, scanBackoff = scanBackoff,
                connected = false, commandChannelReady = false, statusChannelReady = false,
                syncChannelReady = false,
                lastMessage = "$message，${delaySeconds} 秒后重试")
        }
        handler.postDelayed(reconnectTask, delayMs)
    }

    private fun closeGatt(expectedGatt: BluetoothGatt? = null) {
        handler.removeCallbacks(connectionTimeout)
        val gatt = expectedGatt ?: bluetoothGatt
        if (expectedGatt == null || expectedGatt === bluetoothGatt) {
            bluetoothGatt = null
            controlCharacteristic = null
            stateCharacteristic = null
            syncCharacteristic = null
            deviceStatusCharacteristic = null
            isConnecting = false
            syncWriteQueue.clear()
            syncWriteInFlight = false
        }
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (exception: SecurityException) {
            Log.w(TAG, "Unable to close GATT after permission change", exception)
        }
    }

    private fun setError(message: String) {
        Log.e(TAG, message)
        BleServerStatus.update { it.copy(scanning = false, scanRequestPending = false,
            scanBackoff = false, connected = false, commandChannelReady = false,
            statusChannelReady = false, syncChannelReady = false, lastMessage = message) }
    }

    private fun stopBleResources() {
        handler.removeCallbacks(scanTimeout)
        handler.removeCallbacks(scanActivation)
        handler.removeCallbacks(connectionTimeout)
        handler.removeCallbacks(reconnectTask)
        pendingScanFallbackReason = null
        scanBlockedUntilMs = 0L
        stopScan()
        closeGatt()
        scanner = null
    }

    override fun onDestroy() {
        Log.i(TAG, "BLE client service stopped")
        stopRinging("服务已停止")
        stopBleResources()
        unregisterReceiver(bluetoothStateReceiver)
        BleServerStatus.update {
            it.copy(serviceRunning = false, scanning = false, scanRequestPending = false,
                scanBackoff = false, protocolIncompatible = false, connected = false,
                commandChannelReady = false, statusChannelReady = false,
                syncChannelReady = false,
                ringing = false, lastMessage = "服务已停止")
        }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopRinging = PendingIntent.getService(
            this,
            1,
            Intent(this, BleServerService::class.java).setAction(ACTION_STOP_RINGING),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_hsp)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.notification_stop_ringing), stopRinging)
            .setOngoing(true)
            .build()
    }
}
