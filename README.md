# HSP Watch

HSP Watch 是一个运行在 Android 手机端的 BLE 手表伴侣 App，用于连接 HSP 手表，实现手机与手表互相查找、手表状态展示，以及手机时间、位置、城市和天气向手表的一次性同步。

> 当前仓库只包含 Android App，不包含 LVGL/SquareLine 手表 UI 或手表固件。`BleServerService` 虽然沿用了 Server 命名，实际实现的是 Android BLE GATT Client。

## 功能概览

- 优先使用已缓存的手表 BLE 地址直连，失败后按 HSP Service UUID 扫描
- 前台服务维持 BLE 连接，并在断线、扫描失败或蓝牙重新开启后重试
- 手机查找手表：向手表发送振动提醒命令
- 手表查找手机：接收手表通知后播放系统铃声并循环振动
- 接收并展示手表电量、充电状态、步数、卡路里、距离和固件版本
- 连接成功后自动同步手机时间，并在获得定位授权后同步位置、城市和天气
- 支持用户手动触发一次手机数据同步
- 展示权限、蓝牙、扫描、连接、协议和通知通道状态
- 适配 Android 12+ 蓝牙权限、Android 13+ 通知权限和定位运行时权限

## 技术栈

- Kotlin 2.2.10
- Android Gradle Plugin 9.2.1
- Gradle Wrapper 9.4.1
- Jetpack Compose + Material 3
- Android BLE GATT API
- Android Foreground Service
- Android `LocationManager` + `Geocoder`
- `HttpURLConnection` + `org.json` + Open-Meteo API
- Kotlin `StateFlow`

## 整体架构

```text
用户操作
   |
   v
MainActivity + Compose 单页面 UI
   |  Intent action                 ^  StateFlow
   v                                |
BleServerService ----------------> BleServerStatus
   |
   +-- Android BLE GATT Client <--> HSP 手表 GATT Server
   +-- WatchPreferences ---------> 缓存手表 BLE 地址
   +-- PhoneAlertController -----> 铃声 / 振动 / WakeLock
   +-- LocationManager ----------> 一次性手机定位
   +-- Geocoder -----------------> 位置反查城市
   +-- Open-Meteo HTTPS ---------> 当前天气和当日高低温
```

App 没有自建主循环、常驻周期轮询或 WorkManager。持续运行由 Android Activity/Service 生命周期、BLE 回调和主线程 `Handler` 定时任务共同驱动；旧版 Geocoder 和天气 HTTP 请求会按需创建一次性后台线程。

## 工作流程

### 1. App 启动与前置检查

`MainActivity` 是唯一的 Launcher Activity。启动后会：

1. 创建 Compose 页面并订阅 `BleServerStatus.state`。
2. 检查设备是否支持 BLE、蓝牙是否开启，以及运行时权限是否完整。
3. 在 Activity 位于前台时监听蓝牙开关变化。
4. 将授权、开启蓝牙、启动/停止服务、查找和手机数据同步操作注入 UI。

首次打开 App 只检查和展示状态，不会自动启动 BLE 服务。用户点击“启动服务”“查找手表”或“同步手机数据”后才会建立连接。

### 2. 启动前台服务

“启动服务”“查找手表”和“同步手机数据”都会通过 Intent action 启动 `BleServerService`：

```text
Compose 按钮
  -> MainActivity 回调
  -> BleServerService.start() / findWatch() / syncPhoneData()
  -> startForegroundService()
  -> onStartCommand()
  -> 创建前台通知
  -> startBleClient()
```

服务返回 `START_STICKY`。Activity 退出后服务仍可继续维持连接；服务被系统回收后，系统可以使用空 Intent 重建服务并重新进入连接流程。App 没有开机自启动逻辑。

### 3. 扫描与连接手表

```text
检查 BLE 硬件、权限和蓝牙开关
  |
  +-- 有缓存地址 --> 直接建立 LE GATT 连接
  |                     |
  |                     +-- 失败/超时 --> 扫描回退
  |
  +-- 无缓存地址 --> 按 HSP Service UUID 低延迟扫描
                         |
                         +-- 找到设备 --> 保存地址 --> GATT 连接
```

当前连接和重试参数：

| 场景 | 等待时间 | 行为 |
| --- | ---: | --- |
| GATT 连接超时 | 10 秒 | 关闭当前连接并进入扫描或重连 |
| BLE 扫描超时 | 15 秒 | 停止扫描，3 秒后重试 |
| 普通断线 | 3 秒 | 自动重连 |
| 一般扫描失败 | 5 秒 | 再次尝试连接/扫描 |
| 系统判定扫描过于频繁 | 30 秒 | 进入扫描限频等待 |
| 扫描激活宽限 | 1 秒 | 延迟将 UI 标记为“扫描中” |

扫描结果中的 BLE 地址会写入 `SharedPreferences`，供下次启动优先直连。缓存地址不可用时，App 会自动回退到 Service UUID 扫描。

### 4. GATT 通道初始化

连接成功后的顺序为：

1. 调用 `discoverServices()`。
2. 获取 HSP Service 下必需的 `CONTROL`、`STATE`、`DEVICE_STATUS` 特征，以及可选的 `SYNC` 特征。
3. 先写 `STATE` 的 CCCD，订阅查找手机命令。
4. `STATE` 订阅成功后，再写 `DEVICE_STATUS` 的 CCCD。
5. 两个订阅都成功后，将查找命令和设备状态通道标记为就绪；存在 `SYNC` 时再将手机同步通道标记为就绪。
6. 如果此前有排队的“查找手表”请求，此时自动补发。
7. 每次连接完成时自动发起一次手机数据同步。

缺少 `CONTROL`、`STATE` 或 `DEVICE_STATUS` 任一必需特征时，App 会将设备标记为协议不兼容并停止自动重试；用户再次点击启动、查找或同步后才会重新检查协议。缺少可选的 `SYNC` 特征不会影响互相查找和设备状态上报，页面只会提示更新手表固件，手机数据同步不可用。

### 5. 手机查找手表

用户点击“查找手表”后：

1. App 设置 `pendingFindWatch = true`。
2. 如果服务未启动，则先启动前台服务。
3. 如果 GATT 通道尚未就绪，则等待连接和订阅完成。
4. 向 `CONTROL` 特征写入 `[0x11, sequence]`。
5. GATT 写回调更新页面上的最近状态消息。

`sequence` 每次发送递增，用于区分连续命令。当前没有业务 ACK，因此 GATT 写成功只表示 Android 写操作完成，不能单独证明手表已经执行提醒。

### 6. 手表查找手机

手表通过 `STATE` 特征发送 Notify：

- `[0x01, sequence]`：手机开始响铃和振动
- `[0x02, sequence]`：手机停止响铃和振动

收到开始命令后，`PhoneAlertController` 会播放系统默认来电铃声、循环执行 `650 ms` 振动/`650 ms` 停顿，并申请最长 10 分钟的 Partial WakeLock。

响铃可以通过以下路径停止：

- 手表发送 `0x02`
- App 页面上的“停止手机响铃”按钮
- 前台通知中的“停止响铃”操作
- 蓝牙被关闭
- BLE 服务被停止或销毁

WakeLock 的 10 分钟超时不等于响铃自动停止。铃声和循环振动仍需要通过上述停止路径结束。

### 7. 手表状态同步

App 只订阅 `DEVICE_STATUS` Notify，不会定时读取特征，也不会从手机传感器生成步数、卡路里或距离。所有手表状态都依赖手表固件主动上报。

状态包解码成功后会写入 `BleServerStatus`，Compose 随 `StateFlow` 更新自动重组，页面展示：

- GATT 连接和状态订阅情况
- 电量和充电状态
- 今日步数
- 估算卡路里
- 估算距离
- 固件版本

为了让 App 在刚完成订阅时立即显示数据，手表固件应在 `DEVICE_STATUS` 订阅成功后主动发送一次完整状态快照，并在数据变化后继续 Notify。

### 8. 手机数据同步

`DEVICE_STATUS` 订阅成功后，App 会自动同步一次；用户也可以点击页面按钮手动触发。手动操作在服务或连接尚未就绪时会先启动服务并排队请求。

同步流程为：

```text
SYNC 通道就绪
  -> 立即排队发送手机时间和 UTC 偏移
  -> 有定位权限？
       |-- 否 --> 结束，时间同步仍然有效
       |
       +-- 是 --> 复用 5 分钟内的最近定位，或请求一次最长 20 秒的定位
                    |
                    +-- 写入经纬度和精度
                    +-- Geocoder 反查城市并写入手表
                    +-- HTTPS 请求 Open-Meteo 并写入天气
```

Android 同一时刻只允许一个未完成的 GATT write，因此时间、位置、城市和天气包会进入队列串行写入。城市反查和天气请求异步执行，两者完成及入队的先后顺序不保证。同步失败不会自动重试；下一次连接或用户手动操作会重新发起整次同步。

| 同步环节 | 当前参数 |
| --- | ---: |
| 最近定位可复用时间 | 5 分钟 |
| 单次新定位超时 | 20 秒 |
| Open-Meteo 连接超时 | 10 秒 |
| Open-Meteo 读取超时 | 10 秒 |

这些都是单次操作的固定超时，没有指数退避。定位、城市反查、天气请求或 `SYNC` GATT 写失败后均不会在后台自动重试。

定位权限不是 Android 12+ BLE 扫描的前置条件。拒绝定位权限后，互相查找、手表状态和时间同步仍可使用，只是不发送位置、城市和天气。

### 9. 停止与资源清理

用户点击“停止服务”后，App 会停止手机提醒、取消所有扫描/连接/重连定时任务、停止 BLE 扫描、断开并关闭 GATT，然后将服务和连接状态复位。

缓存的手表 BLE 地址不会随停止服务被清除。

## UI 与状态管理

运行时只有一个 `HspWatchScreen`，没有 Fragment、`NavHost` 或页面返回栈。页面主要包括：

- 连接状态摘要
- 手表状态卡
- “查找手表”或“停止手机响铃”主操作按钮
- 手机时间、位置和天气同步按钮
- 蓝牙、定位、通知权限和蓝牙开关提示
- 启动/停止服务按钮
- 最近状态消息
- 可展开的扫描、连接、查找、状态和手机同步通道调试状态

`BleServerStatus` 是进程内 `MutableStateFlow`，负责在 Service 和 Compose 之间传递状态。进程重启后，连接状态和最近一次设备状态会丢失，只有 `SharedPreferences` 中的 BLE 地址能够恢复。

`HspWatchPreview.kt` 中的电量、步数等数据只用于 Android Studio Compose Preview，不会进入正式运行流程。

## 项目结构

```text
.
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   └── java/com/watch/hsp/
│       │       ├── MainActivity.kt                 # Activity、权限和 Compose 入口
│       │       ├── BleServerService.kt             # BLE 连接、提醒、定位和天气同步流程
│       │       ├── BleServerStatus.kt              # UI StateFlow 和设备状态模型
│       │       ├── BleProtocol.kt                  # UUID、查找/同步包和状态包解析
│       │       ├── data/WatchPreferences.kt        # 手表 BLE 地址缓存
│       │       ├── service/PhoneAlertController.kt # 手机铃声、振动和 WakeLock
│       │       └── ui/
│       │           ├── HspWatchScreen.kt           # 单页面 Compose UI
│       │           └── HspWatchPreview.kt          # Android Studio 预览数据
│       └── test/java/com/watch/hsp/
│           └── BleProtocolTest.kt              # 城市同步包单元测试
├── build.gradle.kts
├── gradle/libs.versions.toml
└── settings.gradle.kts
```

## BLE 协议

协议常量和解析逻辑集中在 `app/src/main/java/com/watch/hsp/BleProtocol.kt`。

### GATT UUID

| 名称 | UUID | App 当前用途 |
| --- | --- | --- |
| HSP Service | `2d6a5000-8d5c-4f6a-a9b2-1c0c9e7a1000` | 扫描过滤和服务发现 |
| Control Characteristic | `2d6a5001-8d5c-4f6a-a9b2-1c0c9e7a1000` | 手机写入查找手表命令 |
| State Characteristic | `2d6a5002-8d5c-4f6a-a9b2-1c0c9e7a1000` | 手表 Notify 查找手机开始/停止命令 |
| Sync Characteristic | `2d6a5003-8d5c-4f6a-a9b2-1c0c9e7a1000` | 手机写入时间、位置、城市和天气快照；可选兼容能力 |
| Device Status Characteristic | `2d6a5004-8d5c-4f6a-a9b2-1c0c9e7a1000` | 手表 Notify 设备和运动状态 |
| CCCD | `00002902-0000-1000-8000-00805f9b34fb` | 开启 Notify |

### 查找命令包

查找命令包为 2 字节：

| 偏移 | 含义 |
| ---: | --- |
| Byte 0 | 命令字 |
| Byte 1 | 序号，用于区分连续命令 |

| 方向 | 命令 | 值 | App 当前状态 |
| --- | --- | ---: | --- |
| 手表 -> 手机 | `PHONE_COMMAND_FIND_START` | `0x01` | 已处理，启动手机提醒 |
| 手表 -> 手机 | `PHONE_COMMAND_FIND_STOP` | `0x02` | 已处理，停止手机提醒 |
| 手机 -> 手表 | `WATCH_COMMAND_FIND_START` | `0x11` | 已发送，启动手表提醒 |
| 手机 -> 手表 | `WATCH_COMMAND_FIND_STOP` | `0x12` | 已定义，但没有 Service action 或 UI 入口 |

当前 App 也兼容只包含命令字的 1 字节 `STATE` Notify，此时缺失的 sequence 按 `0` 处理；规范发送端仍应使用完整的 2 字节包。

### 手机数据同步包

同步包写入 `SYNC` 特征，首字节为同步命令字。多字节数字均采用小端序。

| 命令 | 值 | 总长度 | 数据格式 |
| --- | ---: | ---: | --- |
| `SYNC_COMMAND_TIME` | `0x21` | 7 字节 | UTC 秒 `uint32 LE` + 手机 UTC 偏移分钟 `int16 LE` |
| `SYNC_COMMAND_LOCATION` | `0x22` | 11 字节 | 纬度 E7 `int32 LE` + 经度 E7 `int32 LE` + 水平精度米 `uint16 LE` |
| `SYNC_COMMAND_WEATHER` | `0x23` | 13 字节 | WMO code `uint8` + 当前/最高/最低温各 `int16 LE`，单位 0.1 C + 湿度 `uint8` + 更新时间 UTC 秒 `uint32 LE` |
| `SYNC_COMMAND_CITY` | `0x24` | 2-20 字节 | 最多 19 字节可打印 ASCII 城市名 |

#### 时间包 `0x21`

| 偏移 | 长度 | 含义 |
| ---: | ---: | --- |
| Byte 0 | 1 | `0x21` |
| Byte 1 | 4 | Unix UTC 秒，`uint32 LE` |
| Byte 5 | 2 | 当前时区相对 UTC 的分钟偏移，`int16 LE` |

#### 位置包 `0x22`

| 偏移 | 长度 | 含义 |
| ---: | ---: | --- |
| Byte 0 | 1 | `0x22` |
| Byte 1 | 4 | 纬度乘以 `10^7`，`int32 LE` |
| Byte 5 | 4 | 经度乘以 `10^7`，`int32 LE` |
| Byte 9 | 2 | 水平精度，单位米，范围限制到 `0..65535` |

#### 天气包 `0x23`

| 偏移 | 长度 | 含义 |
| ---: | ---: | --- |
| Byte 0 | 1 | `0x23` |
| Byte 1 | 1 | WMO weather code |
| Byte 2 | 2 | 当前温度乘以 10，`int16 LE` |
| Byte 4 | 2 | 当日最高温乘以 10，`int16 LE` |
| Byte 6 | 2 | 当日最低温乘以 10，`int16 LE` |
| Byte 8 | 1 | 相对湿度百分比 `0..100` |
| Byte 9 | 4 | 包生成时的 Unix UTC 秒，`uint32 LE` |

#### 城市包 `0x24`

城市名会先做 NFKD 规范化、合并空白并过滤为可打印 ASCII，再截取最多 19 字节。结果必须至少包含一个 ASCII 字母或数字；无法转换的名称不会发送。该限制用于保证整个包不超过一次默认的 20 字节 GATT write。

### Device Status 状态包

状态包采用可变长度格式：

| 偏移 | 长度 | 含义 |
| ---: | ---: | --- |
| Byte 0 | 1 | 协议版本，当前为 `0x01` |
| Byte 1 | 1 | 状态标志位 |
| Byte 2 | 1 | 电量百分比；电量无效时通常为 `0xFF` |
| Byte 3 | 1 | 固件版本字符串长度 `N` |
| Byte 4... | `N` | ASCII 固件版本字符串，例如 `0.1.0` |
| Byte `4 + N`... | 10，可选 | 运动数据扩展 |

状态标志：

| 位 | 含义 |
| ---: | --- |
| bit 0 | 手表 BLE 已开启 |
| bit 1 | 手表认为伴侣 App 已连接 |
| bit 2 | 电量字段有效 |
| bit 3 | 手表正在充电 |
| bit 4 | 运动数据扩展有效 |

bit 4 有效且数据长度足够时，固件版本字符串后紧跟 10 字节小端序运动数据：

| 相对运动数据起点 | 长度 | 类型 | 含义 |
| ---: | ---: | --- | --- |
| `+0` | 4 | `uint32 LE` | 步数 |
| `+4` | 2 | `uint16 LE` | 卡路里，单位 kcal |
| `+6` | 4 | `uint32 LE` | 距离，单位米 |

协议版本不为 `1`、头部不足或固件版本长度越界时，App 会丢弃整个状态包。电量有效位已设置但数值超过 `100` 时，只将电量标记为无效；bit 4 已设置但运动扩展不足 10 字节时，也只将运动数据标记为无效，包中的其他字段仍会保留。未知的 `STATE` 命令会被忽略并写入 logcat。

## 权限

| 系统版本 | 运行时权限 | 用途 |
| --- | --- | --- |
| Android 13+ | `BLUETOOTH_SCAN`、`BLUETOOTH_CONNECT`、`POST_NOTIFICATIONS`、`ACCESS_FINE_LOCATION`、`ACCESS_COARSE_LOCATION` | BLE 扫描/连接、前台服务通知和手机位置/天气同步 |
| Android 12 | `BLUETOOTH_SCAN`、`BLUETOOTH_CONNECT`、`ACCESS_FINE_LOCATION`、`ACCESS_COARSE_LOCATION` | BLE 扫描/连接和手机位置/天气同步 |
| Android 11 及以下 | `ACCESS_FINE_LOCATION`、`ACCESS_COARSE_LOCATION` | 旧版 BLE 扫描要求及手机位置/天气同步 |

Android 12+ 的定位权限只用于位置、城市和天气同步，不是 BLE 扫描或互相查找的前置条件。精确或大致位置任一权限获批后，当前实现都会尝试获取一次位置。

Manifest 还声明：

- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_CONNECTED_DEVICE`
- `VIBRATE`
- `WAKE_LOCK`
- `INTERNET`，用于请求 Open-Meteo 天气
- `ACCESS_NETWORK_STATE`，已声明，但当前实现尚未在请求前主动检查网络状态
- Android 11 及以下使用的旧版蓝牙权限

## 数据流与隐私

获得定位权限后，每次 BLE 连接就绪时以及每次手动同步时，App 会进行以下处理：

- 将手机时间、时区、经纬度和定位精度通过 BLE 写入已连接的手表
- 将经纬度交给 Android `Geocoder` 反查英文城市名；具体实现可能使用设备配置的网络服务
- 将保留 6 位小数的经纬度通过 HTTPS 发送到 `api.open-meteo.com`，查询当前天气、湿度和当日高低温
- 将城市和天气结果通过 BLE 写入手表

App 没有账号体系、自建后端或持续云同步，也不会把位置、城市和天气写入本地数据库。当前唯一持久化的业务数据是手表 BLE 地址。`ACCESS_NETWORK_STATE` 虽已声明，当前代码未主动读取它。

## 运行环境

- Android Studio：支持 AGP 9.x 的版本
- Gradle Daemon JDK：21；项目已通过 daemon JVM criteria 固定该版本
- Java/Kotlin 源码兼容级别：Java 11
- Android SDK：compileSdk 36.1，targetSdk 36
- 最低系统版本：Android 8.0，API 26
- 运行设备：支持 BLE 的 Android 真机
- 可选同步条件：手机定位服务和互联网连接
- 配套设备：广播 HSP Service UUID 并实现本文 GATT 协议的手表固件

如果本机没有 JDK 21，项目配置的 Foojay resolver 可以联网供应对应工具链；离线构建时应预先安装 JDK 21。

BLE 扫描、GATT 连接、铃声和振动需要真机验证，模拟器通常无法完整覆盖。

## 快速开始

1. 使用 Android Studio 打开项目并等待 Gradle 同步完成。
2. 将 App 安装到支持 BLE 的 Android 手机。
3. 启动 HSP 手表，并确保手表正在广播 HSP Service UUID。
4. 首次启动 App 后，根据页面提示授予权限并开启蓝牙。定位权限是位置/城市/天气同步的可选条件。
5. 点击“启动服务”等待连接，或直接点击“启动并查找手表”。
6. 连接摘要显示“手表已连接”后，即可测试双向查找、手表状态和手机数据同步。

## 构建与测试

构建 Debug APK：

```bash
./gradlew assembleDebug
```

安装到已连接设备：

```bash
./gradlew installDebug
```

运行 JVM 单元测试：

```bash
./gradlew test
```

`BleProtocolTest` 当前覆盖城市同步包的 ASCII 转换和 19 字节截断。状态包解码、时间/位置/天气包、BLE 重连状态机、同步写队列、Open-Meteo JSON、权限分支和 Compose 业务流程仍未覆盖；仓库中也保留了 Android 模板测试。完整行为仍需 Android BLE 真机联调。

## 当前实现边界

- 仓库不包含手表固件，因此无法从本仓库验证广播、GATT Server 和传感器数据生成逻辑
- 只持久化扫描到的 BLE 地址，没有设备身份校验、绑定、解绑或手动清除缓存入口
- 扫描发现设备后会在协议验证完成前保存其地址
- 没有业务 ACK、命令超时、错误码或重复 sequence 去重
- App 没有发送 `WATCH_COMMAND_FIND_STOP (0x12)` 的入口
- 手机数据只在每次连接就绪时自动同步一次，或由用户手动触发，没有周期刷新
- 定位、城市、天气和 `SYNC` 写入失败后没有自动重试；城市与天气异步完成的顺序不保证
- `SYNC` 是可选特征，旧固件仍可使用互相查找和设备状态，但不能同步手机数据
- 没有 `readCharacteristic()` 状态兜底，初始状态完全依赖手表主动 Notify
- 断线或停止服务时不会清空上一次手表状态，页面可能保留旧电量和运动数据
- `bleEnabled`、`companionConnected` 和状态接收时间已解析，但正式 UI 尚未展示
- 没有 Room/SQLite、运动历史、自建后端、账号体系、持久化云同步或 OTA 流程；网络仅用于一次性天气和可能的城市查询
- 没有 BLE 日志导出、抓包教程或 BLE mock/仪器自动化测试

## 后续计划

- 增加停止手表提醒操作并发送 `0x12`
- 增加命令 ACK、业务超时、错误码和重试规则
- 增加手表绑定、解绑、身份校验和缓存管理
- 为定位、天气和同步写入增加可控重试及刷新策略
- 展示状态更新时间，并在断线后标记或清除陈旧数据
- 为其余协议包、状态解析、连接状态机、同步队列和 Compose 交互补充自动化测试
- 补充 BLE 日志导出、抓包和真机联调文档
- 设计并实现 OTA 升级流程

## 贡献

提交改动前建议运行：

```bash
./gradlew test
./gradlew assembleDebug
```

涉及 BLE 协议的修改应同步更新本文档，并与手表固件共同验证。

## License

当前项目暂未声明开源许可证。正式发布前请根据项目用途补充 `LICENSE` 文件。
