# HSP Watch Web Design

这是基于当前 Android App 功能制作的零依赖多页面高保真原型，可直接通过浏览器打开，无需构建或启动开发服务器。

## 页面

- `index.html`：设备总览、今日活动、查找手表、快速同步
- `device.html`：设备详情、连接链路、后台服务与通道状态
- `sync.html`：时间/位置/城市/天气、歌词和封面同步
- `notifications.html`：通知使用权、支持来源与最近 5 条缓存
- `settings.html`：系统权限、服务控制和 BLE 高级诊断

## 预览

直接打开 `index.html`。桌面宽度下使用左侧导航，手机宽度下自动切换为底部五栏导航。

交互状态通过浏览器 `localStorage` 在页面间共享，包括后台服务、蓝牙、权限、通知使用权和最近同步状态。页面中的设备地址、活动数据和消息内容为设计展示数据，不代表 App 已持久化这些 UI 数据。

## 设计依据

- 当前源码中的 BLE 连接、双向查找和状态同步
- 时间、位置、城市、Open-Meteo 天气同步
- 短信、微信、QQ/TIM 通知缓存与补发
- LRCLIB 歌词和 128x128 JPEG 封面同步
- Android 权限、通知使用权和前台服务状态

品牌图像复用仓库中的 `docs/icon.png` 和 Android Launcher 图标。界面图标使用本地内嵌的 Lucide 图标子集，以保证 `file://` 离线预览。
