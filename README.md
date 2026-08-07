# HSP Watch

HSP Watch 是一个基于 Android + Jetpack Compose 的 BLE 手表伴侣应用，用于实现手机与手表之间的“互相查找”能力。

当前 Android 端作为 BLE 客户端运行：应用会优先直连已保存的手表 BLE 地址，直连失败后再通过指定 Service UUID 扫描手表，连接成功后可向手表发送查找命令，也可接收手表侧命令让手机响铃和振动。

## 功能特性

- BLE 低功耗蓝牙扫描、连接与自动重连
- 优先使用缓存手表地址直连，减少重复扫描
- 前台服务保持查找链路运行
- 手机查找手表：向手表写入查找命令
- 手表查找手机：接收手表通知后播放系统铃声并振动
- 蓝牙、通知、扫描限频、连接状态等运行状态展示
- Android 12+ 蓝牙运行时权限与 Android 13+ 通知权限适配

## 技术栈

- Kotlin
- Android Gradle Plugin 9.2.1
- Gradle Wrapper 9.4.1
- Jetpack Compose + Material 3
- Android BLE GATT API
- Foreground Service

## 项目结构

```text
.
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/watch/hsp/
│           ├── MainActivity.kt          # Compose 页面与权限入口
│           ├── BleServerService.kt      # BLE 前台服务、扫描、连接、命令收发
│           ├── BleServerStatus.kt       # UI 状态流
│           └── BleProtocol.kt           # BLE UUID 与命令字定义
├── build.gradle.kts
├── gradle/libs.versions.toml
└── settings.gradle.kts
```

## 运行环境

- Android Studio：建议使用支持 AGP 9.x 的版本
- JDK：11 或以上
- Android SDK：compileSdk 36
- Android 设备：建议使用支持 BLE 的真机
- 最低系统版本：Android 8.0，API 26

> BLE 扫描、GATT 连接、铃声、振动和前台服务等能力需要真机环境验证，模拟器通常无法完整覆盖。

## 快速开始

1. 克隆项目

```bash
git clone 
cd hsp
```

2. 使用 Android Studio 打开项目，等待 Gradle 同步完成。

3. 连接 Android 真机并运行 `app`。

4. 首次启动后按页面提示授予权限：

- Android 12 及以上：蓝牙扫描、蓝牙连接
- Android 13 及以上：通知权限
- Android 11 及以下：定位权限，用于 BLE 扫描

5. 点击“启动服务”，等待应用连接手表。

## 命令行构建

```bash
./gradlew assembleDebug
```

安装到已连接设备：

```bash
./gradlew installDebug
```

运行单元测试：

```bash
./gradlew test
```

## BLE 协议

协议常量集中定义在 `app/src/main/java/com/watch/hsp/BleProtocol.kt`。

### GATT UUID

| 名称 | UUID | 说明 |
| --- | --- | --- |
| Service | `2d6a5000-8d5c-4f6a-a9b2-1c0c9e7a1000` | HSP 手表查找服务 |
| Control Characteristic | `2d6a5001-8d5c-4f6a-a9b2-1c0c9e7a1000` | 手机写入，控制手表 |
| State Characteristic | `2d6a5002-8d5c-4f6a-a9b2-1c0c9e7a1000` | 手表通知，控制手机 |
| Watch Command Characteristic | `2d6a5003-8d5c-4f6a-a9b2-1c0c9e7a1000` | 预留手表命令通道 |
| Device Status Characteristic | `2d6a5004-8d5c-4f6a-a9b2-1c0c9e7a1000` | 手表读取/通知：电量、充电状态、固件版本 |
| CCCD | `00002902-0000-1000-8000-00805f9b34fb` | BLE 通知描述符 |

### 数据包格式

当前命令包使用 2 字节：

| 字节 | 含义 |
| --- | --- |
| Byte 0 | 命令字 |
| Byte 1 | 序号，用于区分连续命令 |

### 设备状态包

`Device Status Characteristic` 的数据包长度可变，格式如下：

| 字节 | 含义 |
| --- | --- |
| Byte 0 | 状态协议版本，当前为 `0x01` |
| Byte 1 | 标记位：bit0 BLE 已开启，bit1 App 已连接，bit2 电量有效，bit3 正在充电 |
| Byte 2 | 电量百分比；电量未准备好时为 `0xFF` |
| Byte 3 | 固件版本字符串长度 |
| Byte 4... | ASCII 固件版本字符串，例如 `0.1.0` |

App 通过 GATT 连接回调维护连接状态，并先订阅 `State Characteristic`、再订阅 `Device Status Characteristic`。订阅成功后手表立即发送状态快照；之后仅在电量或充电状态变化时更新。

### 命令字

| 方向 | 命令 | 值 | 说明 |
| --- | --- | --- | --- |
| 手表 -> 手机 | `PHONE_COMMAND_FIND_START` | `0x01` | 请求手机开始响铃和振动 |
| 手表 -> 手机 | `PHONE_COMMAND_FIND_STOP` | `0x02` | 请求手机停止响铃和振动 |
| 手机 -> 手表 | `WATCH_COMMAND_FIND_START` | `0x11` | 请求手表开始提醒 |
| 手机 -> 手表 | `WATCH_COMMAND_FIND_STOP` | `0x12` | 请求手表停止提醒 |

## 后续接口预留

这里用于后续补充 App、手表固件、调试工具或服务端之间的接口说明。

### App 对外能力

| 接口/能力 | 当前状态 | 说明 |
| --- | --- | --- |
| 启动 BLE 查找服务 | 已实现 | `BleServerService.start(context)` |
| 停止 BLE 查找服务 | 已实现 | `BleServerService.stop(context)` |
| 查找手表 | 已实现 | `BleServerService.findWatch(context)` |
| 停止手机响铃 | 已实现 | `BleServerService.stopRinging(context)` |
| 查看手表状态 | 已实现 | 显示 BLE 连接、电量、充电状态与固件版本 |
| 绑定/解绑手表 | TODO | 预留设备管理入口 |
| 手表固件版本读取 | 已实现 | 通过 Device Status Characteristic 接收 |
| 电量/状态同步 | 已实现 | 通过 Device Status Characteristic 接收 |
| OTA 升级 | TODO | 预留固件升级流程 |

### 固件侧接口

| 模块 | 当前状态 | 说明 |
| --- | --- | --- |
| BLE 广播 | 已实现 | 广播 HSP Service UUID 并支持 App 扫描/直连 |
| GATT 服务 | 已实现 | 提供 Control、State、Device Status 等特征 |
| 查找手表响应 | 已实现 | 收到 `0x11` 后启动振动提醒 |
| 查找手机请求 | 已实现 | 通过 State Notify 发送 `0x01` |
| 停止查找 | 已实现 | 支持 `0x02`/`0x12` 停止提醒 |
| 状态同步 | 已实现 | 推送电量、充电状态和固件版本 |

### 调试接口

| 接口 | 当前状态 | 说明 |
| --- | --- | --- |
| BLE 日志导出 | TODO | 预留连接、扫描、命令收发日志导出 |
| 协议抓包说明 | TODO | 预留 nRF Connect、Android logcat 等调试步骤 |
| 自动化测试脚本 | TODO | 预留 BLE mock 或仪器测试入口 |

## 权限说明

| 权限 | 用途 |
| --- | --- |
| `BLUETOOTH_SCAN` | Android 12+ 扫描 BLE 手表 |
| `BLUETOOTH_CONNECT` | Android 12+ 连接 BLE GATT 设备 |
| `POST_NOTIFICATIONS` | Android 13+ 显示前台服务通知 |
| `ACCESS_FINE_LOCATION` | Android 11 及以下 BLE 扫描要求 |
| `FOREGROUND_SERVICE` | 运行前台服务 |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Android 14+ 连接设备类型前台服务 |
| `VIBRATE` | 手表查找手机时触发振动 |
| `WAKE_LOCK` | 查找手机时短时间保持提醒过程 |

## 开发计划

- 完善手表固件侧协议文档
- 增加绑定、解绑和设备信息页面
- 增加命令 ACK、超时和错误码定义
- 增加手表电量、固件版本等状态同步
- 补充 BLE mock 测试和真机调试说明
- 整理发布 APK 的构建与签名流程

## 贡献

欢迎通过 Issue 或 Pull Request 补充协议、修复问题、完善文档和增加测试。提交改动前建议先运行：

```bash
./gradlew test
./gradlew assembleDebug
```

## License

当前项目暂未声明开源许可证。正式发布前请根据项目用途补充 `LICENSE` 文件。
