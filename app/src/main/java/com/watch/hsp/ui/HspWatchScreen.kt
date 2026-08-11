package com.watch.hsp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BorderStroke
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.watch.hsp.BleProtocol
import com.watch.hsp.BleUiState
import com.watch.hsp.R
import com.watch.hsp.data.PhoneNotification
import com.watch.hsp.data.WatchNotificationRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val AppCanvas = Color(0xFFF7F9F8)
private val AppSurface = Color.White
private val AppSurfaceSoft = Color(0xFFF0F4F2)
private val AppLine = Color(0xFFDCE4E0)
private val AppText = Color(0xFF1A201E)
private val AppMuted = Color(0xFF6B7671)
private val AppFaint = Color(0xFF8A948F)
private val AppPrimary = Color(0xFF146F65)
private val AppPrimarySoft = Color(0xFFE1F1ED)
private val AppGreen = Color(0xFF247C42)
private val AppGreenSoft = Color(0xFFE8F5EC)
private val AppBlue = Color(0xFF3565C8)
private val AppBlueSoft = Color(0xFFE9EFFE)
private val AppAmber = Color(0xFF9A651B)
private val AppAmberSoft = Color(0xFFFFF2D7)
private val AppRed = Color(0xFFB83B35)
private val AppRedSoft = Color(0xFFFCEAE8)
private val PanelShape = RoundedCornerShape(10.dp)

private enum class HspPage(val label: String, val icon: AppIcon) {
    Home("首页", AppIcon.Home),
    Device("设备", AppIcon.Watch),
    Sync("同步", AppIcon.Sync),
    Notifications("通知", AppIcon.Bell),
    More("更多", AppIcon.Sliders)
}

private enum class AppIcon {
    Home, Watch, Sync, Bell, Sliders, Bluetooth, Radio, Clock, Pin, Cloud,
    Music, Shield, Database, Bug, Locate
}

@Composable
fun HspWatchScreen(
    state: BleUiState,
    onRequestPermissions: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onFindWatch: () -> Unit,
    onSyncPhoneData: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onStopRinging: () -> Unit,
    showDebugDetailsInitially: Boolean = false
) {
    var selectedPage by rememberSaveable { mutableStateOf(HspPage.Home) }
    var showDebugDetails by rememberSaveable { mutableStateOf(showDebugDetailsInitially) }
    val context = LocalContext.current
    val cachedMessages = WatchNotificationRepository.snapshot(context).takeLast(3).asReversed()

    Scaffold(
        containerColor = AppCanvas,
        topBar = { AppHeader(state) },
        bottomBar = {
            AppBottomBar(
                selectedPage = selectedPage,
                onPageSelected = { selectedPage = it }
            )
        }
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            when (selectedPage) {
                HspPage.Home -> HomePage(
                    state = state,
                    onFindWatch = onFindWatch,
                    onStopRinging = onStopRinging,
                    onRequestPermissions = onRequestPermissions,
                    onEnableBluetooth = onEnableBluetooth
                )

                HspPage.Device -> DevicePage(
                    state = state,
                    onStartService = onStartService,
                    onStopService = onStopService
                )

                HspPage.Sync -> SyncPage(state = state, onSyncPhoneData = onSyncPhoneData)

                HspPage.Notifications -> NotificationsPage(
                    state = state,
                    messages = cachedMessages,
                    onOpenNotificationAccess = onOpenNotificationAccess
                )

                HspPage.More -> MorePage(
                    state = state,
                    showDebugDetails = showDebugDetails,
                    onShowDebugDetailsChange = { showDebugDetails = !showDebugDetails },
                    onRequestPermissions = onRequestPermissions,
                    onEnableBluetooth = onEnableBluetooth
                )
            }
        }
    }
}

@Composable
private fun AppHeader(state: BleUiState) {
    val connected = state.connected && state.commandChannelReady
    val statusText = when {
        connected -> "手表已连接"
        state.scanning || state.scanRequestPending -> "正在查找"
        state.serviceRunning -> "正在连接"
        else -> "手表未连接"
    }
    val statusColor = if (connected) AppGreen else AppMuted

    Surface(color = AppCanvas) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(70.dp)
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(34.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = AppSurface,
                    border = BorderStroke(1.dp, AppLine)
                ) {
                    androidx.compose.foundation.Image(
                        painter = painterResource(R.mipmap.ic_launcher_watch),
                        contentDescription = null,
                        modifier = Modifier.padding(4.dp),
                        contentScale = ContentScale.Fit
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text("HSP Watch", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = AppText)
                Spacer(Modifier.weight(1f))
                ConnectionDot(statusColor)
                Spacer(Modifier.width(7.dp))
                Text(statusText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = statusColor)
            }
            HorizontalDivider(color = AppLine)
        }
    }
}

@Composable
private fun ConnectionDot(color: Color) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .drawBehind { drawCircle(color = color, radius = size.minDimension / 2f) }
    )
}

@Composable
private fun AppBottomBar(selectedPage: HspPage, onPageSelected: (HspPage) -> Unit) {
    Surface(color = AppSurface) {
        Column {
            HorizontalDivider(color = AppLine)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .height(74.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HspPage.entries.forEach { page ->
                    val selected = page == selectedPage
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onPageSelected(page) }
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        AppLineIcon(
                            icon = page.icon,
                            color = if (selected) AppPrimary else AppFaint,
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            page.label,
                            color = if (selected) AppPrimary else AppFaint,
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomePage(
    state: BleUiState,
    onFindWatch: () -> Unit,
    onStopRinging: () -> Unit,
    onRequestPermissions: () -> Unit,
    onEnableBluetooth: () -> Unit
) {
    PageColumn {
        ScreenTitle("今日概览", "你的 HSP Watch 已准备就绪。")

        if (!state.blePermissionsGranted || !state.notificationsGranted || !state.locationPermissionGranted) {
            CompactNotice(
                icon = AppIcon.Shield,
                title = "还需要完成授权",
                detail = permissionNoticeText(state),
                action = "检查权限",
                onClick = onRequestPermissions
            )
            Spacer(Modifier.height(16.dp))
        } else if (!state.bluetoothEnabled) {
            CompactNotice(
                icon = AppIcon.Bluetooth,
                title = "手机蓝牙未开启",
                detail = "开启蓝牙后，App 才能连接并查找手表。",
                action = "开启蓝牙",
                onClick = onEnableBluetooth
            )
            Spacer(Modifier.height(16.dp))
        }

        WatchOverviewCard(state, onFindWatch, onStopRinging)
        Spacer(Modifier.height(28.dp))
        SectionHeader("今日活动", "来自手表")
        ActivityMetrics(state)
    }
}

@Composable
private fun WatchOverviewCard(state: BleUiState, onFindWatch: () -> Unit, onStopRinging: () -> Unit) {
    val watchStatus = state.watchStatus
    val battery = watchStatus.batteryPercent?.let { "$it%" } ?: "--"
    val firmware = watchStatus.firmwareVersion ?: "等待版本信息"
    val charging = when {
        !watchStatus.batteryValid -> "等待状态"
        watchStatus.charging -> "充电中"
        else -> "未充电"
    }
    val buttonText = when {
        state.ringing -> "停止手机响铃"
        state.commandChannelReady -> "查找手表"
        state.serviceRunning -> "连接后查找手表"
        else -> "启动并查找手表"
    }
    val enabled = state.ringing || (state.hasBleHardware && state.bluetoothEnabled && state.blePermissionsGranted)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = PanelShape,
        color = AppSurface,
        border = BorderStroke(1.dp, AppLine)
    ) {
        Row(modifier = Modifier.height(207.dp)) {
            Box(
                modifier = Modifier
                    .width(98.dp)
                    .fillMaxSize()
                    .border(width = 1.dp, color = AppLine, shape = RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier.size(68.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = AppSurface,
                    border = BorderStroke(1.dp, AppLine)
                ) {
                    androidx.compose.foundation.Image(
                        painter = painterResource(R.mipmap.ic_launcher_watch),
                        contentDescription = null,
                        modifier = Modifier.padding(6.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text("已连接设备", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AppFaint)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("HSP Watch", modifier = Modifier.weight(1f), fontSize = 21.sp, fontWeight = FontWeight.Bold, color = AppText)
                    Text(battery, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppGreen)
                }
                Text("$charging  ·  固件 $firmware", fontSize = 13.sp, color = AppMuted)
                Text(state.lastMessage.ifBlank { "等待手表状态更新" }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = AppFaint)
                Spacer(Modifier.weight(1f))
                PrimaryButton(
                    label = buttonText,
                    icon = AppIcon.Locate,
                    enabled = enabled,
                    danger = state.ringing,
                    onClick = { if (state.ringing) onStopRinging() else onFindWatch() }
                )
            }
        }
    }
}

@Composable
private fun ActivityMetrics(state: BleUiState) {
    val activity = state.watchStatus
    val steps = if (activity.activityValid) (activity.steps ?: 0).toString() else "--"
    val calories = if (activity.activityValid) (activity.caloriesKcal ?: 0).toString() else "--"
    val distance = if (activity.activityValid) {
        String.format(Locale.US, "%.1f km", (activity.distanceMeters ?: 0) / 1000.0)
    } else {
        "--"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = PanelShape,
        color = AppSurface,
        border = BorderStroke(1.dp, AppLine)
    ) {
        Row(modifier = Modifier.height(84.dp)) {
            ActivityMetric(steps, "步数", Modifier.weight(1f))
            VerticalRule()
            ActivityMetric(calories, "kcal", Modifier.weight(1f))
            VerticalRule()
            ActivityMetric(distance, "距离", Modifier.weight(1f))
        }
    }
}

@Composable
private fun ActivityMetric(value: String, label: String, modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(value, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = AppText)
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 12.sp, color = AppMuted)
    }
}

@Composable
private fun VerticalRule() {
    Box(modifier = Modifier.width(1.dp).height(84.dp).drawBehind { drawLine(AppLine, Offset.Zero, Offset(0f, size.height)) })
}

@Composable
private fun DevicePage(state: BleUiState, onStartService: () -> Unit, onStopService: () -> Unit) {
    val canStartService = state.hasBleHardware && state.bluetoothEnabled && state.blePermissionsGranted
    val serviceEnabled = state.serviceRunning
    val battery = state.watchStatus.batteryPercent?.let { "$it%" } ?: "--"
    val charging = when {
        !state.watchStatus.batteryValid -> "等待状态"
        state.watchStatus.charging -> "充电中"
        else -> "未充电"
    }

    PageColumn {
        ScreenTitle("设备", "连接与硬件信息。")
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = PanelShape,
            color = AppSurface,
            border = BorderStroke(1.dp, AppLine)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("后台连接服务", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppText)
                    Spacer(Modifier.height(4.dp))
                    Text("自动直连与断线重连", fontSize = 12.sp, color = AppMuted)
                }
                Switch(
                    checked = serviceEnabled,
                    onCheckedChange = { checked -> if (checked) onStartService() else onStopService() },
                    enabled = serviceEnabled || canStartService,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = AppSurface,
                        checkedTrackColor = AppPrimary,
                        uncheckedThumbColor = AppSurface,
                        uncheckedTrackColor = AppFaint
                    )
                )
            }
        }

        Spacer(Modifier.height(28.dp))
        SectionHeader("设备信息", if (state.connected) "已连接" else "未连接", if (state.connected) AppGreen else AppMuted)
        DetailList {
            DetailRow(
                icon = AppIcon.Watch,
                iconBackground = AppSurfaceSoft,
                iconColor = AppMuted,
                title = "HSP Watch",
                subtitle = "固件版本 ${state.watchStatus.firmwareVersion ?: "等待信息"}",
                value = "$battery · $charging",
                valueColor = if (state.watchStatus.batteryValid) AppGreen else AppMuted
            )
            DetailRow(
                icon = AppIcon.Bluetooth,
                iconBackground = AppBlueSoft,
                iconColor = AppBlue,
                title = "BLE 地址",
                subtitle = state.watchAddress ?: "尚未发现手表",
                value = if (state.serviceRunning) "运行中" else "已停止",
                valueColor = if (state.serviceRunning) AppGreen else AppMuted
            )
        }

        Spacer(Modifier.height(28.dp))
        SectionHeader("连接通道", "已初始化")
        DetailList {
            DetailRow(
                icon = AppIcon.Radio,
                iconBackground = AppGreenSoft,
                iconColor = AppGreen,
                title = "设备状态",
                subtitle = "电量、充电与运动数据",
                value = if (state.statusChannelReady) "已订阅" else "等待连接",
                valueColor = if (state.statusChannelReady) AppGreen else AppMuted
            )
            DetailRow(
                icon = AppIcon.Sync,
                iconBackground = AppBlueSoft,
                iconColor = AppBlue,
                title = "数据同步",
                subtitle = "手机数据与通知写入",
                value = if (state.syncChannelReady) "已就绪" else "等待连接",
                valueColor = if (state.syncChannelReady) AppGreen else AppMuted
            )
        }
    }
}

@Composable
private fun SyncPage(state: BleUiState, onSyncPhoneData: () -> Unit) {
    val readyForSync = state.hasBleHardware && state.bluetoothEnabled && state.blePermissionsGranted
    val title = when {
        state.syncChannelReady -> "同步已就绪"
        state.serviceRunning -> "正在准备同步"
        else -> "等待连接手表"
    }
    val subtitle = when {
        state.syncChannelReady -> "可将手机信息发送到手表"
        else -> state.lastMessage
    }

    PageColumn {
        ScreenTitle("数据同步", "将手机信息发送到手表。")
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = PanelShape,
            color = AppSurface,
            border = BorderStroke(1.dp, AppLine)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(AppIcon.Sync, AppBlueSoft, AppBlue, Modifier.size(44.dp), iconSize = 23.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = AppText)
                        Spacer(Modifier.height(3.dp))
                        Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = AppMuted)
                    }
                }
                Spacer(Modifier.height(16.dp))
                PrimaryButton("立即同步", AppIcon.Sync, readyForSync, onClick = onSyncPhoneData)
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .drawBehind { drawRoundRect(color = AppLine, cornerRadius = CornerRadius(size.height / 2f)) }
                )
            }
        }

        Spacer(Modifier.height(28.dp))
        SectionHeader("本次内容", "自动更新")
        DetailList {
            DetailRow(
                icon = AppIcon.Clock,
                iconBackground = AppBlueSoft,
                iconColor = AppBlue,
                title = "时间与时区",
                subtitle = "连接后自动同步",
                value = if (state.syncChannelReady) "已同步" else "等待连接",
                valueColor = if (state.syncChannelReady) AppGreen else AppMuted
            )
            DetailRow(
                icon = AppIcon.Pin,
                iconBackground = AppBlueSoft,
                iconColor = AppBlue,
                title = "位置与城市",
                subtitle = "单次定位，不持续跟踪",
                value = if (state.locationPermissionGranted) "已允许" else "需授权",
                valueColor = if (state.locationPermissionGranted) AppGreen else AppAmber
            )
            DetailRow(
                icon = AppIcon.Cloud,
                iconBackground = AppAmberSoft,
                iconColor = AppAmber,
                title = "天气",
                subtitle = "当前天气与当日高低温",
                value = if (state.locationPermissionGranted) "可用" else "需定位",
                valueColor = if (state.locationPermissionGranted) AppGreen else AppAmber
            )
            DetailRow(
                icon = AppIcon.Music,
                iconBackground = AppSurfaceSoft,
                iconColor = AppMuted,
                title = "歌词与封面",
                subtitle = "播放时自动更新",
                value = if (state.messageNotificationAccessEnabled) "等待播放" else "需通知读取",
                valueColor = AppMuted
            )
        }
    }
}

@Composable
private fun NotificationsPage(
    state: BleUiState,
    messages: List<PhoneNotification>,
    onOpenNotificationAccess: () -> Unit
) {
    PageColumn {
        ScreenTitle("消息通知", "新消息会自动发送到手表。")
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = PanelShape,
            color = AppSurface,
            border = BorderStroke(1.dp, AppLine)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("通知读取", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppText)
                        Spacer(Modifier.height(4.dp))
                        Text("短信、微信和 QQ / TIM", fontSize = 12.sp, color = AppMuted)
                    }
                    StatusBadge(if (state.messageNotificationAccessEnabled) "已开启" else "未开启", state.messageNotificationAccessEnabled)
                }
                Spacer(Modifier.height(16.dp))
                SecondaryButton("管理通知读取", AppIcon.Sliders, onOpenNotificationAccess)
            }
        }

        Spacer(Modifier.height(28.dp))
        SectionHeader("支持来源", "3 个应用")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SourceChip("短", "短信", AppBlueSoft, AppBlue)
            SourceChip("微", "微信", AppGreenSoft, AppGreen)
            SourceChip("Q", "QQ / TIM", AppRedSoft, AppRed)
        }

        Spacer(Modifier.height(28.dp))
        SectionHeader("最近缓存", "最多 5 条")
        if (messages.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = PanelShape,
                color = AppSurface,
                border = BorderStroke(1.dp, AppLine)
            ) {
                Text(
                    "暂无已缓存消息",
                    modifier = Modifier.padding(18.dp),
                    color = AppMuted,
                    fontSize = 13.sp
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                messages.forEach { MessageCard(it) }
            }
        }
    }
}

@Composable
private fun MorePage(
    state: BleUiState,
    showDebugDetails: Boolean,
    onShowDebugDetailsChange: () -> Unit,
    onRequestPermissions: () -> Unit,
    onEnableBluetooth: () -> Unit
) {
    val permissionsReady = state.blePermissionsGranted && state.locationPermissionGranted && state.notificationsGranted
    PageColumn {
        ScreenTitle("更多", "系统权限与连接诊断。")
        SectionHeader("系统状态", if (permissionsReady && state.bluetoothEnabled) "运行正常" else "需要处理")
        DetailList {
            DetailRow(
                icon = AppIcon.Bluetooth,
                iconBackground = AppGreenSoft,
                iconColor = AppGreen,
                title = "手机蓝牙",
                subtitle = "用于扫描和连接手表",
                value = if (state.bluetoothEnabled) "已开启" else "未开启",
                valueColor = if (state.bluetoothEnabled) AppGreen else AppAmber
            )
            DetailRow(
                icon = AppIcon.Shield,
                iconBackground = AppGreenSoft,
                iconColor = AppGreen,
                title = "所需权限",
                subtitle = "蓝牙、位置与通知权限",
                value = if (permissionsReady) "已允许" else "需授权",
                valueColor = if (permissionsReady) AppGreen else AppAmber
            )
            DetailRow(
                icon = AppIcon.Database,
                iconBackground = AppSurfaceSoft,
                iconColor = AppMuted,
                title = "本地数据",
                subtitle = "BLE 地址和最多 5 条消息",
                value = "设备内",
                valueColor = AppMuted
            )
        }

        Spacer(Modifier.height(28.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(PanelShape)
                .clickable { onShowDebugDetailsChange() },
            shape = PanelShape,
            color = AppSurface,
            border = BorderStroke(1.dp, AppLine)
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppLineIcon(AppIcon.Bug, AppText, Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("BLE 诊断", modifier = Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppText)
                    Text(if (showDebugDetails) "收起" else "查看", fontSize = 13.sp, color = AppMuted)
                }
                if (showDebugDetails) {
                    HorizontalDivider(color = AppLine)
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        DiagnosticRow("前台服务", if (state.serviceRunning) "运行中" else "已停止", state.serviceRunning)
                        DiagnosticRow("扫描状态", scanStatusText(state), state.scanning || state.scanRequestPending)
                        DiagnosticRow("GATT 连接", if (state.connected) "已连接" else "未连接", state.connected)
                        DiagnosticRow("状态通道", if (state.statusChannelReady) "已订阅" else "不可用", state.statusChannelReady)
                        DiagnosticRow("同步通道", if (state.syncChannelReady) "已就绪" else "不可用", state.syncChannelReady)
                        DiagnosticRow("BLE 地址", state.watchAddress ?: "尚未发现", state.watchAddress != null)
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        SecondaryButton("检查所需权限", AppIcon.Shield, onRequestPermissions)
        if (!state.bluetoothEnabled) {
            Spacer(Modifier.height(12.dp))
            PrimaryButton("开启蓝牙", AppIcon.Bluetooth, state.hasBleHardware && state.blePermissionsGranted, onClick = onEnableBluetooth)
        }
    }
}

@Composable
private fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        content = content
    )
}

@Composable
private fun ScreenTitle(title: String, subtitle: String) {
    Text(title, fontSize = 29.sp, fontWeight = FontWeight.Bold, color = AppText)
    Spacer(Modifier.height(7.dp))
    Text(subtitle, fontSize = 14.sp, color = AppMuted)
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun SectionHeader(title: String, trailing: String, trailingColor: Color = AppMuted) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppText)
        Text(trailing, fontSize = 12.sp, color = trailingColor)
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun DetailList(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, AppLine), PanelShape)
            .clip(PanelShape)
            .padding(horizontal = 14.dp),
        content = content
    )
}

@Composable
private fun DetailRow(
    icon: AppIcon,
    iconBackground: Color,
    iconColor: Color,
    title: String,
    subtitle: String,
    value: String,
    valueColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTile(icon, iconBackground, iconColor, Modifier.size(36.dp), iconSize = 20.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppText)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = AppMuted)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            value,
            modifier = Modifier.widthIn(max = 104.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = valueColor
        )
    }
    HorizontalDivider(color = AppLine)
}

@Composable
private fun DiagnosticRow(label: String, value: String, good: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 12.sp, color = AppMuted)
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (good) AppGreen else AppMuted)
    }
    HorizontalDivider(color = AppLine)
}

@Composable
private fun IconTile(icon: AppIcon, background: Color, color: Color, modifier: Modifier, iconSize: androidx.compose.ui.unit.Dp) {
    Surface(modifier = modifier, shape = RoundedCornerShape(8.dp), color = background) {
        Box(contentAlignment = Alignment.Center) {
            AppLineIcon(icon, color, Modifier.size(iconSize))
        }
    }
}

@Composable
private fun StatusBadge(label: String, success: Boolean) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (success) AppGreenSoft else AppAmberSoft
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (success) AppGreen else AppAmber
        )
    }
}

@Composable
private fun PrimaryButton(
    label: String,
    icon: AppIcon,
    enabled: Boolean,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(51.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (danger) AppRed else AppPrimary,
            contentColor = AppSurface,
            disabledContainerColor = AppLine,
            disabledContentColor = AppMuted
        )
    ) {
        AppLineIcon(icon, AppSurface, Modifier.size(21.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SecondaryButton(label: String, icon: AppIcon, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(51.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = AppSurface,
            contentColor = AppText,
            disabledContainerColor = AppSurface,
            disabledContentColor = AppMuted
        ),
        border = BorderStroke(1.dp, AppLine)
    ) {
        AppLineIcon(icon, AppText, Modifier.size(21.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CompactNotice(icon: AppIcon, title: String, detail: String, action: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = PanelShape,
        color = AppAmberSoft,
        border = BorderStroke(1.dp, Color(0xFFEFD9A8))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppLineIcon(icon, AppAmber, Modifier.size(20.dp))
                Spacer(Modifier.width(9.dp))
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppAmber)
            }
            Spacer(Modifier.height(7.dp))
            Text(detail, fontSize = 12.sp, color = AppAmber)
            Spacer(Modifier.height(10.dp))
            Text(
                action,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onClick() }
                    .padding(vertical = 3.dp),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = AppAmber
            )
        }
    }
}

@Composable
private fun SourceChip(mark: String, label: String, markBackground: Color, markColor: Color) {
    Surface(shape = RoundedCornerShape(7.dp), color = AppSurface, border = BorderStroke(1.dp, AppLine)) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = RoundedCornerShape(5.dp), color = markBackground) {
                Text(mark, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = markColor)
            }
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AppMuted)
        }
    }
}

@Composable
private fun MessageCard(message: PhoneNotification) {
    val (mark, background, color) = when (message.app) {
        BleProtocol.NOTIFICATION_APP_WECHAT -> Triple("微", AppGreenSoft, AppGreen)
        BleProtocol.NOTIFICATION_APP_QQ -> Triple("Q", AppRedSoft, AppRed)
        else -> Triple("短", AppBlueSoft, AppBlue)
    }
    val time = if (message.postedAtMillis > 0) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.postedAtMillis))
    } else {
        "--:--"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = PanelShape,
        color = AppSurface,
        border = BorderStroke(1.dp, AppLine)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            IconTile(AppIcon.Bell, background, color, Modifier.size(38.dp), iconSize = 18.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(message.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppText)
                Spacer(Modifier.height(2.dp))
                Text(time, fontSize = 11.sp, color = AppFaint)
                Spacer(Modifier.height(4.dp))
                Text(message.body, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = AppMuted)
            }
            Spacer(Modifier.width(8.dp))
            Surface(shape = RoundedCornerShape(999.dp), color = AppBlueSoft) {
                Text("已缓存", modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = AppBlue)
            }
        }
    }
}

@Composable
private fun AppLineIcon(icon: AppIcon, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val thinStroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val w = size.width
        val h = size.height
        fun point(x: Float, y: Float) = Offset(w * x, h * y)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, point(x1, y1), point(x2, y2), strokeWidth = stroke.width, cap = StrokeCap.Round)

        when (icon) {
            AppIcon.Home -> {
                val path = Path().apply { moveTo(w * .14f, h * .45f); lineTo(w * .5f, h * .15f); lineTo(w * .86f, h * .45f); lineTo(w * .78f, h * .45f); lineTo(w * .78f, h * .84f); lineTo(w * .22f, h * .84f); lineTo(w * .22f, h * .45f); close() }
                drawPath(path, color, style = stroke)
                line(.43f, .84f, .43f, .60f); line(.57f, .84f, .57f, .60f)
            }
            AppIcon.Watch -> {
                drawRoundRect(color, point(.27f, .25f), Size(w * .46f, h * .5f), CornerRadius(w * .12f), style = stroke)
                line(.38f, .25f, .42f, .07f); line(.62f, .25f, .58f, .07f)
                line(.38f, .75f, .42f, .93f); line(.62f, .75f, .58f, .93f)
                drawCircle(color, radius = w * .1f, center = point(.5f, .5f), style = thinStroke)
            }
            AppIcon.Sync -> {
                drawArc(color, -55f, 250f, false, point(.16f, .16f), Size(w * .68f, h * .68f), style = stroke)
                line(.72f, .12f, .86f, .16f); line(.72f, .12f, .78f, .27f)
                line(.28f, .88f, .14f, .84f); line(.28f, .88f, .22f, .73f)
            }
            AppIcon.Bell -> {
                drawArc(color, 195f, 150f, false, point(.22f, .18f), Size(w * .56f, h * .63f), style = stroke)
                line(.22f, .60f, .16f, .72f); line(.16f, .72f, .84f, .72f); line(.84f, .72f, .78f, .60f)
                drawCircle(color, radius = w * .07f, center = point(.5f, .86f), style = stroke)
            }
            AppIcon.Sliders -> {
                line(.17f, .28f, .83f, .28f); line(.17f, .5f, .83f, .5f); line(.17f, .72f, .83f, .72f)
                drawCircle(color, radius = w * .09f, center = point(.38f, .28f), style = stroke)
                drawCircle(color, radius = w * .09f, center = point(.66f, .5f), style = stroke)
                drawCircle(color, radius = w * .09f, center = point(.45f, .72f), style = stroke)
            }
            AppIcon.Bluetooth -> {
                line(.50f, .08f, .50f, .92f); line(.50f, .08f, .78f, .33f); line(.78f, .33f, .22f, .67f); line(.22f, .33f, .78f, .67f); line(.78f, .67f, .50f, .92f)
            }
            AppIcon.Radio -> {
                drawCircle(color, radius = w * .10f, center = point(.5f, .5f), style = stroke)
                drawArc(color, 225f, 270f, false, point(.25f, .25f), Size(w * .5f, h * .5f), style = stroke)
                drawArc(color, 225f, 270f, false, point(.08f, .08f), Size(w * .84f, h * .84f), style = thinStroke)
            }
            AppIcon.Clock -> {
                drawCircle(color, radius = w * .36f, center = point(.5f, .5f), style = stroke); line(.5f, .30f, .5f, .52f); line(.5f, .52f, .66f, .62f)
            }
            AppIcon.Pin -> {
                val path = Path().apply { moveTo(w * .5f, h * .88f); cubicTo(w * .22f, h * .60f, w * .20f, h * .20f, w * .5f, h * .15f); cubicTo(w * .80f, h * .20f, w * .78f, h * .60f, w * .5f, h * .88f) }
                drawPath(path, color, style = stroke); drawCircle(color, radius = w * .10f, center = point(.5f, .42f), style = stroke)
            }
            AppIcon.Cloud -> {
                drawCircle(color, radius = w * .13f, center = point(.40f, .52f), style = stroke); drawCircle(color, radius = w * .17f, center = point(.56f, .48f), style = stroke); drawCircle(color, radius = w * .12f, center = point(.70f, .57f), style = stroke)
                line(.22f, .70f, .78f, .70f); line(.26f, .25f, .26f, .10f); line(.15f, .18f, .07f, .10f)
            }
            AppIcon.Music -> {
                line(.38f, .20f, .38f, .72f); line(.38f, .20f, .78f, .12f); line(.78f, .12f, .78f, .64f)
                drawCircle(color, radius = w * .12f, center = point(.27f, .75f), style = stroke); drawCircle(color, radius = w * .12f, center = point(.67f, .67f), style = stroke)
            }
            AppIcon.Shield -> {
                val path = Path().apply { moveTo(w * .5f, h * .09f); lineTo(w * .80f, h * .21f); lineTo(w * .75f, h * .61f); quadraticBezierTo(w * .64f, h * .82f, w * .5f, h * .91f); quadraticBezierTo(w * .36f, h * .82f, w * .25f, h * .61f); lineTo(w * .20f, h * .21f); close() }
                drawPath(path, color, style = stroke); line(.35f, .48f, .46f, .59f); line(.46f, .59f, .68f, .36f)
            }
            AppIcon.Database -> {
                drawOval(color, point(.18f, .12f), Size(w * .64f, h * .24f), style = stroke); line(.18f, .24f, .18f, .72f); line(.82f, .24f, .82f, .72f)
                drawArc(color, 0f, 180f, false, point(.18f, .48f), Size(w * .64f, h * .24f), style = stroke); drawArc(color, 0f, 180f, false, point(.18f, .60f), Size(w * .64f, h * .24f), style = stroke)
            }
            AppIcon.Bug -> {
                drawRoundRect(color, point(.30f, .27f), Size(w * .40f, h * .48f), CornerRadius(w * .14f), style = stroke)
                line(.40f, .27f, .34f, .10f); line(.60f, .27f, .66f, .10f); line(.30f, .43f, .10f, .35f); line(.30f, .57f, .10f, .65f); line(.70f, .43f, .90f, .35f); line(.70f, .57f, .90f, .65f)
                line(.5f, .27f, .5f, .75f)
            }
            AppIcon.Locate -> {
                drawCircle(color, radius = w * .28f, center = point(.5f, .5f), style = stroke); drawCircle(color, radius = w * .08f, center = point(.5f, .5f), style = stroke)
                line(.5f, .05f, .5f, .20f); line(.5f, .80f, .5f, .95f); line(.05f, .5f, .20f, .5f); line(.80f, .5f, .95f, .5f)
            }
        }
    }
}

private fun permissionNoticeText(state: BleUiState): String {
    val missing = buildList {
        if (!state.blePermissionsGranted) add("蓝牙权限")
        if (!state.locationPermissionGranted) add("定位权限")
        if (!state.notificationsGranted) add("通知权限")
    }
    return "还需要${missing.joinToString("、")}，用于连接手表及同步位置和天气。"
}

private fun scanStatusText(state: BleUiState): String = when {
    state.protocolIncompatible -> "协议不兼容"
    state.scanBackoff -> "限频等待"
    state.scanRequestPending -> "请求中"
    state.scanning -> "扫描中"
    else -> "空闲"
}
