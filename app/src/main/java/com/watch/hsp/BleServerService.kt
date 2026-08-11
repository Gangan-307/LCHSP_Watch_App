package com.watch.hsp

import android.Manifest
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
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.icu.text.Transliterator
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
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.watch.hsp.data.WatchPreferences
import com.watch.hsp.data.PhoneNotification
import com.watch.hsp.data.WatchNotificationRepository
import com.watch.hsp.service.MediaLyricsMonitor
import com.watch.hsp.service.PhoneAlertController
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
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
        private const val DEFAULT_BLE_MTU = 23
        private const val REQUESTED_BLE_MTU = 247
        private const val MTU_NEGOTIATION_TIMEOUT_MS = 1_500L
        private const val LOCATION_CACHE_MAX_AGE_MS = 5 * 60_000L
        private const val LOCATION_LIVE_MAX_AGE_MS = 60_000L
        private const val LOCATION_MOCK_MAX_AGE_MS = 30_000L
        private const val LOCATION_TIMEOUT_MS = 25_000L
        private const val PHONE_SYNC_WAKE_LOCK_MS = 90_000L
        private const val WEATHER_MAX_ATTEMPTS = 2
        private const val WEATHER_RETRY_DELAY_MS = 1_000L
        private const val SYNC_WRITE_WARNING_TIMEOUT_MS = 5_000L
        private const val SYNC_WRITE_HARD_TIMEOUT_MS = 20_000L
        private const val SYNC_WRITE_START_RETRY_DELAY_MS = 40L
        private const val SYNC_WRITE_START_RETRY_LIMIT = 8
        private const val COVER_BATCH_RETRY_DELAY_MS = 1_500L
        private const val COVER_BATCH_RETRY_LIMIT = 3

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
    private val powerManager by lazy { getSystemService(PowerManager::class.java) }
    private val cityTransliterator by lazy {
        runCatching { Transliterator.getInstance("Han-Latin; Latin-ASCII") }
            .onFailure { exception -> Log.w(TAG, "City transliteration is unavailable", exception) }
            .getOrNull()
    }
    private val handler = Handler(Looper.getMainLooper())
    private val mediaLyricsMonitor by lazy {
        MediaLyricsMonitor(this, handler, object : MediaLyricsMonitor.Listener {
            override fun onLyricChanged(generation: Int, lyric: String) {
                enqueueLyric(generation, lyric)
            }

            override fun onCoverAvailable(generation: Int, jpeg: ByteArray) {
                enqueueCover(generation, jpeg)
            }
        })
    }
    private val notificationDeliveryListener: (PhoneNotification) -> Unit = { message ->
        handler.post { enqueueWatchNotification(message) }
    }

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
    private var syncPacketMaxBytes = BleProtocol.DEFAULT_SYNC_PACKET_BYTES
    private var mtuNegotiationPending = false
    private var serviceDiscoveryStarted = false
    private val syncWriteQueue = ArrayDeque<ByteArray>()
    private var syncWriteInFlight = false
    private var syncWritePacePending = false
    private var syncWriteCommandRetries = 0
    private val syncWriteTimeout = Runnable {
        if (!syncWriteInFlight) return@Runnable

        Log.w(TAG, "BLE sync write callback is slow; keeping the link alive")
        handler.postDelayed(
            syncWriteHardTimeout,
            SYNC_WRITE_HARD_TIMEOUT_MS - SYNC_WRITE_WARNING_TIMEOUT_MS
        )
    }
    private val syncWriteHardTimeout = Runnable {
        if (!syncWriteInFlight) return@Runnable

        Log.w(TAG, "BLE sync write produced no callback for $SYNC_WRITE_HARD_TIMEOUT_MS ms")
        handleLinkFailure("蓝牙数据通道长时间无响应，正在重连", scanImmediately = false)
    }
    private val pacedSyncDrain = Runnable {
        syncWritePacePending = false
        drainSyncWriteQueue()
    }
    private var latestCoverGeneration = 0
    private var latestCoverJpeg: ByteArray? = null
    private var coverBatchRetryAttempts = 0
    private var coverBatchRetryPending = false
    private val retryCoverBatch = Runnable {
        coverBatchRetryPending = false
        val jpeg = latestCoverJpeg ?: return@Runnable
        if (latestCoverGeneration == 0 || !BleServerStatus.state.value.syncChannelReady) {
            return@Runnable
        }
        Log.i(TAG, "Retrying phone cover batch $coverBatchRetryAttempts/$COVER_BATCH_RETRY_LIMIT")
        queueCoverPackets(latestCoverGeneration, jpeg)
    }
    @Volatile
    private var phoneSyncGeneration = 0L
    private var activeLocationListener: LocationListener? = null
    private var activeLocationTimeout: Runnable? = null
    private var phoneSyncWakeLock: PowerManager.WakeLock? = null
    private var phoneSyncProtectionGeneration: Long? = null

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

    private val mtuNegotiationTimeout = Runnable {
        val gatt = bluetoothGatt ?: return@Runnable
        if (!mtuNegotiationPending || serviceDiscoveryStarted) return@Runnable

        Log.w(TAG, "MTU negotiation timed out; using 20-byte SYNC packets")
        finishMtuNegotiation(gatt, null)
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
        WatchNotificationRepository.addListener(notificationDeliveryListener)
        mediaLyricsMonitor.start()
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
        startForegroundWithTypes(includeLocation = false)
        BleServerStatus.refreshPrerequisites(this)
        BleServerStatus.update { it.copy(serviceRunning = true) }
    }

    private fun startForegroundWithTypes(includeLocation: Boolean) {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (includeLocation) {
                serviceTypes = serviceTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            startForeground(NOTIFICATION_ID, notification, serviceTypes)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
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

        resetMtuNegotiation()
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
            bluetoothGatt = device.connectGatt(
                this,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK,
                handler
            )
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
                Log.i(TAG, "Connected to watch ${gatt.device.address}; requesting MTU")
                BleServerStatus.update {
                    it.copy(connected = true, commandChannelReady = false, statusChannelReady = false,
                        syncChannelReady = false,
                        watchAddress = gatt.device.address, lastMessage = "已连接手表，正在协商传输速度")
                }
                requestMtuBeforeServiceDiscovery(gatt)
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

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (gatt !== bluetoothGatt) return

            if (status == BluetoothGatt.GATT_SUCCESS && mtu >= DEFAULT_BLE_MTU) {
                syncPacketMaxBytes = (mtu - 3).coerceIn(
                    BleProtocol.DEFAULT_SYNC_PACKET_BYTES,
                    BleProtocol.MAX_SYNC_PACKET_BYTES
                )
                Log.i(TAG, "MTU negotiated: $mtu; SYNC values up to $syncPacketMaxBytes bytes")
                finishMtuNegotiation(gatt, mtu)
            } else {
                Log.w(TAG, "MTU negotiation failed: status=$status mtu=$mtu; using 20-byte SYNC packets")
                finishMtuNegotiation(gatt, null)
            }
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
                    syncSavedNotifications()
                    mediaLyricsMonitor.syncNow()
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
                    if (gatt === bluetoothGatt && syncWriteInFlight) {
                        completeSyncWrite(status)
                    }
                }
            }
        }
    }

    private fun resetMtuNegotiation() {
        handler.removeCallbacks(mtuNegotiationTimeout)
        syncPacketMaxBytes = BleProtocol.DEFAULT_SYNC_PACKET_BYTES
        mtuNegotiationPending = false
        serviceDiscoveryStarted = false
    }

    private fun requestMtuBeforeServiceDiscovery(gatt: BluetoothGatt) {
        if (gatt !== bluetoothGatt) return

        mtuNegotiationPending = true
        val started = try {
            gatt.requestMtu(REQUESTED_BLE_MTU)
        } catch (exception: SecurityException) {
            setError("蓝牙连接权限已被撤销")
            return
        } catch (exception: IllegalStateException) {
            Log.w(TAG, "Unable to request BLE MTU", exception)
            false
        }

        if (!started) {
            Log.w(TAG, "Unable to start MTU negotiation; using 20-byte SYNC packets")
            finishMtuNegotiation(gatt, null)
            return
        }

        handler.removeCallbacks(mtuNegotiationTimeout)
        handler.postDelayed(mtuNegotiationTimeout, MTU_NEGOTIATION_TIMEOUT_MS)
    }

    private fun finishMtuNegotiation(gatt: BluetoothGatt, mtu: Int?) {
        if (gatt !== bluetoothGatt || serviceDiscoveryStarted) return

        handler.removeCallbacks(mtuNegotiationTimeout)
        mtuNegotiationPending = false
        if (mtu == null) {
            syncPacketMaxBytes = BleProtocol.DEFAULT_SYNC_PACKET_BYTES
        }
        serviceDiscoveryStarted = true

        if (!gatt.discoverServices()) {
            handleLinkFailure("无法发现手表服务", scanImmediately = false)
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
            BleProtocol.PHONE_COMMAND_NOTIFICATION_CLEAR -> {
                WatchNotificationRepository.clear(this)
                Log.i(TAG, "Watch cleared all cached notifications")
            }
            BleProtocol.PHONE_COMMAND_NOTIFICATION_DELETE -> {
                if (packet.size < 4) {
                    Log.w(TAG, "Ignored malformed watch notification delete")
                    return
                }
                val id = (packet[2].toInt() and 0xff) or
                    ((packet[3].toInt() and 0xff) shl 8)
                WatchNotificationRepository.remove(this, id)
                Log.i(TAG, "Watch deleted cached notification id=$id")
            }
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
        enqueueSyncPackets(listOf(packet))
    }

    private fun enqueueSyncPackets(
        packets: List<ByteArray>,
        priority: Boolean = false,
        replaceCommands: Set<Byte> = emptySet()
    ) {
        if (Looper.myLooper() != handler.looper) {
            handler.post { enqueueSyncPackets(packets, priority, replaceCommands) }
            return
        }
        if (bluetoothGatt == null || syncCharacteristic == null ||
            !BleServerStatus.state.value.syncChannelReady) {
            return
        }

        if (replaceCommands.isNotEmpty()) {
            var index = 0
            val iterator = syncWriteQueue.iterator()
            while (iterator.hasNext()) {
                val queued = iterator.next()
                val inFlightHead = syncWriteInFlight && index == 0
                index += 1
                if (!inFlightHead && queued.firstOrNull() in replaceCommands) iterator.remove()
            }
        }

        if (priority && packets.isNotEmpty()) {
            val inFlight = if (syncWriteInFlight) syncWriteQueue.pollFirst() else null
            packets.asReversed().forEach(syncWriteQueue::addFirst)
            if (inFlight != null) syncWriteQueue.addFirst(inFlight)
        } else {
            packets.forEach(syncWriteQueue::addLast)
        }
        drainSyncWriteQueue()
    }

    private fun syncSavedNotifications() {
        WatchNotificationRepository.snapshot(this).forEach(::enqueueWatchNotification)
    }

    private fun enqueueWatchNotification(message: PhoneNotification) {
        if (Looper.myLooper() != handler.looper) {
            handler.post { enqueueWatchNotification(message) }
            return
        }
        enqueueSyncPackets(BleProtocol.buildNotificationSyncPackets(
            id = message.id,
            app = message.app,
            title = message.title,
            body = message.body,
            postedAtMillis = message.postedAtMillis
        ))
    }

    private fun enqueueLyric(generation: Int, lyric: String) {
        enqueueSyncPackets(
            packets = BleProtocol.buildLyricSyncPackets(generation, lyric),
            priority = true,
            replaceCommands = setOf(
                BleProtocol.SYNC_COMMAND_LYRIC_BEGIN,
                BleProtocol.SYNC_COMMAND_LYRIC_DATA
            )
        )
    }

    private fun enqueueCover(generation: Int, jpeg: ByteArray) {
        if (bluetoothGatt == null || syncCharacteristic == null ||
            !BleServerStatus.state.value.syncChannelReady) {
            Log.w(TAG, "Phone cover is ready but the HSP sync channel is unavailable")
            return
        }
        val previousCover = latestCoverJpeg
        val sameCover = previousCover != null && jpeg.contentEquals(previousCover)
        if (generation != latestCoverGeneration || !sameCover) {
            latestCoverGeneration = generation
            latestCoverJpeg = jpeg
            coverBatchRetryAttempts = 0
            coverBatchRetryPending = false
            handler.removeCallbacks(retryCoverBatch)
        }
        queueCoverPackets(generation, jpeg)
    }

    private fun queueCoverPackets(generation: Int, jpeg: ByteArray) {
        val packets = runCatching {
            BleProtocol.buildCoverSyncPackets(generation, jpeg, syncPacketMaxBytes)
        }
            .onFailure { Log.w(TAG, "Ignored invalid media cover", it) }
            .getOrNull() ?: return
        Log.i(TAG, "Queueing phone cover: ${jpeg.size} bytes, ${packets.size - 1} data packets, " +
            "$syncPacketMaxBytes-byte SYNC values")
        enqueueSyncPackets(
            packets = packets,
            priority = true,
            replaceCommands = setOf(
                BleProtocol.SYNC_COMMAND_COVER_BEGIN,
                BleProtocol.SYNC_COMMAND_COVER_DATA
            )
        )
    }

    /** Android permits only one outstanding GATT write, so packets are serialized here. */
    private fun drainSyncWriteQueue() {
        if (syncWriteInFlight || syncWritePacePending || syncWriteQueue.isEmpty()) return

        val gatt = bluetoothGatt
        val characteristic = syncCharacteristic
        if (gatt == null || characteristic == null ||
            !BleServerStatus.state.value.syncChannelReady) {
            pendingPhoneSync = true
            return
        }

        val packet = syncWriteQueue.peekFirst() ?: return
        val coverPacket = isCoverPacket(packet)
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
            return
        }

        if (started) {
            syncWriteCommandRetries = 0
            syncWriteInFlight = true
            handler.removeCallbacks(syncWriteTimeout)
            handler.removeCallbacks(syncWriteHardTimeout)
            handler.postDelayed(syncWriteTimeout, SYNC_WRITE_WARNING_TIMEOUT_MS)
        } else {
            if (syncWriteCommandRetries < SYNC_WRITE_START_RETRY_LIMIT) {
                syncWriteCommandRetries += 1
                syncWritePacePending = true
                handler.postDelayed(pacedSyncDrain, SYNC_WRITE_START_RETRY_DELAY_MS)
                return
            }
            syncWritePacePending = false
            syncWriteCommandRetries = 0
            handler.removeCallbacks(syncWriteTimeout)
            handler.removeCallbacks(syncWriteHardTimeout)
            if (coverPacket) {
                discardQueuedCoverPackets()
                scheduleCoverBatchRetry("Android 暂时无法启动封面写入")
            } else {
                syncWriteQueue.pollFirst()
                BleServerStatus.update { it.copy(lastMessage = "手机数据同步命令未能发送") }
            }
            drainSyncWriteQueue()
        }
    }

    private fun completeSyncWrite(status: Int) {
        if (!syncWriteInFlight) return

        handler.removeCallbacks(syncWriteTimeout)
        handler.removeCallbacks(syncWriteHardTimeout)
        syncWriteInFlight = false
        val completedPacket = syncWriteQueue.peekFirst()
        if (status != BluetoothGatt.GATT_SUCCESS) {
            if (isCoverPacket(completedPacket)) {
                discardQueuedCoverPackets()
                scheduleCoverBatchRetry("手表拒绝封面分包：$status")
            } else {
                syncWriteQueue.pollFirst()
                BleServerStatus.update { it.copy(lastMessage = "手机数据同步失败：$status") }
            }
            drainSyncWriteQueue()
            return
        }

        syncWriteQueue.pollFirst()
        if (isCoverPacket(completedPacket) &&
            syncWriteQueue.none { queued -> isCoverPacket(queued) }) {
            coverBatchRetryAttempts = 0
            Log.i(TAG, "Phone cover batch delivered and acknowledged by the watch")
        }
        drainSyncWriteQueue()
    }

    private fun isCoverPacket(packet: ByteArray?): Boolean = when (packet?.firstOrNull()) {
        BleProtocol.SYNC_COMMAND_COVER_BEGIN,
        BleProtocol.SYNC_COMMAND_COVER_DATA -> true
        else -> false
    }

    private fun discardQueuedCoverPackets() {
        val iterator = syncWriteQueue.iterator()
        while (iterator.hasNext()) {
            if (isCoverPacket(iterator.next())) iterator.remove()
        }
    }

    private fun scheduleCoverBatchRetry(reason: String) {
        Log.w(TAG, reason)
        if (coverBatchRetryPending || coverBatchRetryAttempts >= COVER_BATCH_RETRY_LIMIT) {
            if (coverBatchRetryAttempts >= COVER_BATCH_RETRY_LIMIT) {
                Log.e(TAG, "Phone cover retry limit reached")
            }
            return
        }
        coverBatchRetryAttempts += 1
        coverBatchRetryPending = true
        handler.postDelayed(retryCoverBatch, COVER_BATCH_RETRY_DELAY_MS)
    }

    private fun requestPhoneLocationAndWeather() {
        val generation = ++phoneSyncGeneration
        cancelActiveLocationRequest()
        releasePhoneSyncProtection()
        val hasFineLocation = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val hasCoarseLocation = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasFineLocation && !hasCoarseLocation) {
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

        val cachedProviders = buildList {
            if (hasFineLocation) add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
        }
        val cachedLocations = mutableListOf<Location>()
        cachedProviders.forEach { provider ->
            val location = try {
                manager.getLastKnownLocation(provider)
            } catch (exception: SecurityException) {
                Log.w(TAG, "Unable to read cached $provider location", exception)
                null
            } catch (exception: IllegalArgumentException) {
                Log.w(TAG, "Location provider $provider is unavailable", exception)
                null
            } ?: return@forEach

            logPhoneLocation("Cached", location)
            val ageMs = locationAgeMs(location)
            val maxAgeMs = if (isMockLocation(location)) {
                LOCATION_MOCK_MAX_AGE_MS
            } else {
                LOCATION_CACHE_MAX_AGE_MS
            }
            if (isValidPhoneLocation(location) && ageMs != null &&
                ageMs <= maxAgeMs) {
                cachedLocations += location
            } else {
                Log.i(TAG, "Ignored stale or invalid cached location from $provider")
            }
        }
        val cachedLocation = cachedLocations.maxByOrNull { it.elapsedRealtimeNanos }
        if (cachedLocation != null) {
            Log.i(TAG, "Using recent ${cachedLocation.provider} cached location")
            if (!beginPhoneSyncProtection(generation, includeLocation = false)) return
            handlePhoneLocation(cachedLocation, generation)
            return
        }

        val requestedProviders = buildList {
            if (hasFineLocation) add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }.filter { provider ->
            runCatching { manager.isProviderEnabled(provider) }
                .onFailure { exception ->
                    Log.w(TAG, "Unable to query $provider provider state", exception)
                }
                .getOrDefault(false)
        }
        if (requestedProviders.isEmpty()) {
            finishPhoneSyncProtection(generation)
            BleServerStatus.update {
                it.copy(lastMessage = "已同步手机时间；请开启手机定位服务")
            }
            return
        }

        if (!beginPhoneSyncProtection(generation, includeLocation = true)) return

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (generation != phoneSyncGeneration || activeLocationListener !== this) return

                logPhoneLocation("Live", location)
                val ageMs = locationAgeMs(location)
                val maxAgeMs = if (isMockLocation(location)) {
                    LOCATION_MOCK_MAX_AGE_MS
                } else {
                    LOCATION_LIVE_MAX_AGE_MS
                }
                if (!isValidPhoneLocation(location) || ageMs == null ||
                    ageMs > maxAgeMs) {
                    Log.w(TAG, "Ignored invalid or stale live location from ${location.provider}")
                    return
                }

                cancelActiveLocationRequest(this)
                handlePhoneLocation(location, generation)
            }
        }
        val locationTimeout = Runnable {
            if (generation != phoneSyncGeneration || activeLocationListener !== listener) {
                return@Runnable
            }
            cancelActiveLocationRequest(listener)
            finishPhoneSyncProtection(generation)
            BleServerStatus.update {
                it.copy(
                    lastMessage = if (hasFineLocation) {
                        "已同步手机时间；GPS 和网络定位均超时"
                    } else {
                        "已同步手机时间；网络定位超时，请开启精确位置"
                    }
                )
            }
        }
        activeLocationListener = listener
        activeLocationTimeout = locationTimeout

        var registeredProviderCount = 0
        requestedProviders.forEach { provider ->
            try {
                manager.requestLocationUpdates(
                    provider,
                    0L,
                    0f,
                    listener,
                    Looper.getMainLooper()
                )
                registeredProviderCount += 1
                Log.i(TAG, "Requested live location from $provider")
            } catch (exception: SecurityException) {
                Log.w(TAG, "Permission denied while requesting $provider location", exception)
            } catch (exception: IllegalArgumentException) {
                Log.w(TAG, "Unable to request $provider location", exception)
            }
        }
        if (registeredProviderCount == 0) {
            cancelActiveLocationRequest(listener)
            finishPhoneSyncProtection(generation)
            BleServerStatus.update {
                it.copy(lastMessage = "已同步手机时间；定位权限或定位服务不可用")
            }
            return
        }

        handler.postDelayed(locationTimeout, LOCATION_TIMEOUT_MS)
        BleServerStatus.update {
            it.copy(lastMessage = "已同步手机时间，正在获取位置和天气")
        }
    }

    private fun cancelActiveLocationRequest(expectedListener: LocationListener? = null) {
        val listener = activeLocationListener ?: return
        if (expectedListener != null && listener !== expectedListener) return

        activeLocationTimeout?.let(handler::removeCallbacks)
        activeLocationListener = null
        activeLocationTimeout = null
        try {
            locationManager?.removeUpdates(listener)
        } catch (exception: SecurityException) {
            Log.w(TAG, "Unable to remove active location listener", exception)
        } catch (exception: IllegalArgumentException) {
            Log.w(TAG, "Location listener was no longer registered", exception)
        }
    }

    private fun locationAgeMs(location: Location): Long? {
        val fixElapsedNanos = location.elapsedRealtimeNanos
        if (fixElapsedNanos <= 0L) return null
        val ageNanos = SystemClock.elapsedRealtimeNanos() - fixElapsedNanos
        if (ageNanos < 0L) return null
        return ageNanos / 1_000_000L
    }

    private fun isValidPhoneLocation(location: Location): Boolean =
        location.latitude.isFinite() && location.longitude.isFinite() &&
            location.latitude in -90.0..90.0 && location.longitude in -180.0..180.0

    @Suppress("DEPRECATION")
    private fun isMockLocation(location: Location): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) location.isMock
        else location.isFromMockProvider

    private fun logPhoneLocation(prefix: String, location: Location) {
        val age = locationAgeMs(location)?.toString() ?: "unknown"
        val accuracy = if (location.hasAccuracy()) "%.1f".format(Locale.US, location.accuracy) else "unknown"
        Log.i(
            TAG,
            "$prefix location provider=${location.provider} ageMs=$age accuracyM=$accuracy " +
                "mock=${isMockLocation(location)} lat=${"%.6f".format(Locale.US, location.latitude)} " +
                "lon=${"%.6f".format(Locale.US, location.longitude)}"
        )
    }

    private fun beginPhoneSyncProtection(generation: Long, includeLocation: Boolean): Boolean {
        try {
            startForegroundWithTypes(includeLocation)
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Unable to enable phone-data sync foreground-service types", exception)
            BleServerStatus.update {
                it.copy(
                    lastMessage = if (includeLocation) {
                        "已同步手机时间；请保持 App 在前台并允许定位"
                    } else {
                        "已同步手机时间；手机数据同步服务暂不可用"
                    }
                )
            }
            return false
        }

        phoneSyncProtectionGeneration = generation
        phoneSyncWakeLock = try {
            powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:phone-data-sync"
            )?.apply {
                setReferenceCounted(false)
                acquire(PHONE_SYNC_WAKE_LOCK_MS)
            }
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Unable to acquire phone-data sync wake lock", exception)
            null
        }
        return true
    }

    private fun finishPhoneSyncProtection(generation: Long) {
        if (phoneSyncProtectionGeneration != generation) return
        releasePhoneSyncProtection()
    }

    private fun releasePhoneSyncProtection() {
        phoneSyncProtectionGeneration = null
        val wakeLock = phoneSyncWakeLock
        phoneSyncWakeLock = null
        try {
            if (wakeLock?.isHeld == true) wakeLock.release()
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Unable to release phone-data sync wake lock", exception)
        }

        if (BleServerStatus.state.value.serviceRunning) {
            try {
                startForegroundWithTypes(includeLocation = false)
            } catch (exception: RuntimeException) {
                Log.w(TAG, "Unable to restore connected-device foreground-service type", exception)
            }
        }
    }

    private fun handlePhoneLocation(location: Location, generation: Long) {
        if (generation != phoneSyncGeneration) {
            Log.i(TAG, "Discarded stale location result generation=$generation current=$phoneSyncGeneration")
            return
        }
        if (location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) {
            BleServerStatus.update { it.copy(lastMessage = "手机定位数据无效") }
            finishPhoneSyncProtection(generation)
            return
        }

        handler.post {
            if (generation != phoneSyncGeneration) {
                Log.i(TAG, "Discarded stale location sync generation=$generation current=$phoneSyncGeneration")
                return@post
            }
            val accuracyMeters = location.accuracy.takeIf {
                location.hasAccuracy() && it.isFinite() && it >= 0f
            } ?: 0f
            enqueueSyncPacket(
                BleProtocol.buildLocationSyncPacket(
                    location.latitude,
                    location.longitude,
                    accuracyMeters
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
            address?.subLocality,
            address?.adminArea
        )
            .mapNotNull { candidate ->
                candidate?.let(::buildDisplayCityPacket)
            }
            .firstOrNull()
        if (cityPacket == null) {
            Log.w(TAG, "Reverse geocoding returned no display-safe city name")
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

    private fun buildDisplayCityPacket(city: String): ByteArray? {
        runCatching { BleProtocol.buildCitySyncPacket(city) }.getOrNull()?.let { return it }

        val romanizedCity = cityTransliterator?.transliterate(city)
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return runCatching { BleProtocol.buildCitySyncPacket(romanizedCity) }
            .onFailure { exception -> Log.w(TAG, "Unable to encode romanized city '$romanizedCity'", exception) }
            .getOrNull()
    }

    private fun fetchWeatherForLocation(location: Location, generation: Long) {
        Thread(Runnable {
            var weather: PhoneWeatherSnapshot? = null
            var lastFailure: Exception? = null
            for (attempt in 1..WEATHER_MAX_ATTEMPTS) {
                if (generation != phoneSyncGeneration) return@Runnable
                try {
                    weather = requestOpenMeteoWeather(location.latitude, location.longitude)
                    break
                } catch (exception: Exception) {
                    lastFailure = exception
                    Log.w(TAG, "Open-Meteo attempt $attempt/$WEATHER_MAX_ATTEMPTS failed", exception)
                    if (attempt == WEATHER_MAX_ATTEMPTS || !shouldRetryWeather(exception)) break
                    try {
                        Thread.sleep(WEATHER_RETRY_DELAY_MS)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        lastFailure = interrupted
                        break
                    }
                }
            }

            val completedWeather = weather
            if (completedWeather != null) {
                handler.post {
                    if (generation != phoneSyncGeneration) {
                        Log.i(TAG, "Discarded stale weather sync generation=$generation current=$phoneSyncGeneration")
                        return@post
                    }
                    enqueueSyncPacket(
                        BleProtocol.buildWeatherSyncPacket(
                            completedWeather.wmoCode,
                            completedWeather.currentCelsius,
                            completedWeather.highCelsius,
                            completedWeather.lowCelsius,
                            completedWeather.humidityPercent
                        )
                    )
                    BleServerStatus.update { it.copy(lastMessage = "已同步手机时间、位置和天气") }
                    finishPhoneSyncProtection(generation)
                }
            } else {
                val failure = lastFailure ?: IllegalStateException("Weather request ended without a result")
                handler.post {
                    if (generation != phoneSyncGeneration) {
                        Log.i(TAG, "Discarded stale weather failure generation=$generation current=$phoneSyncGeneration")
                        return@post
                    }
                    BleServerStatus.update {
                        it.copy(lastMessage = weatherFailureMessage(failure))
                    }
                    finishPhoneSyncProtection(generation)
                }
            }
        }).start()
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
                throw WeatherHttpException(connection.responseCode)
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

    private fun shouldRetryWeather(exception: Exception): Boolean = when (exception) {
        is WeatherHttpException -> exception.statusCode == 408 || exception.statusCode == 429 ||
            exception.statusCode >= 500
        is IOException -> true
        else -> false
    }

    private fun weatherFailureMessage(exception: Exception): String = when (exception) {
        is SocketTimeoutException -> "已同步手机时间和位置；天气网络请求超时"
        is UnknownHostException -> "已同步手机时间和位置；请检查手机网络"
        is WeatherHttpException -> "已同步手机时间和位置；天气服务错误 ${exception.statusCode}"
        else -> "已同步手机时间和位置；天气数据获取失败"
    }

    private data class PhoneWeatherSnapshot(
        val wmoCode: Int,
        val currentCelsius: Double,
        val highCelsius: Double,
        val lowCelsius: Double,
        val humidityPercent: Int
    )

    private class WeatherHttpException(val statusCode: Int) :
        Exception("Open-Meteo HTTP $statusCode")

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
            phoneSyncGeneration += 1
            cancelActiveLocationRequest()
            releasePhoneSyncProtection()
            bluetoothGatt = null
            controlCharacteristic = null
            stateCharacteristic = null
            syncCharacteristic = null
            deviceStatusCharacteristic = null
            isConnecting = false
            syncWriteQueue.clear()
            syncWriteInFlight = false
            syncWritePacePending = false
            syncWriteCommandRetries = 0
            handler.removeCallbacks(syncWriteTimeout)
            handler.removeCallbacks(syncWriteHardTimeout)
            handler.removeCallbacks(pacedSyncDrain)
            handler.removeCallbacks(retryCoverBatch)
            coverBatchRetryPending = false
            resetMtuNegotiation()
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
        mediaLyricsMonitor.stop()
        stopRinging("服务已停止")
        stopBleResources()
        WatchNotificationRepository.removeListener(notificationDeliveryListener)
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
