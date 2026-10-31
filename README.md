# USB 摄像头（Android / UVC）v2.0

一个直接装到安卓手机 / 平板 / 车机上的 USB 摄像头 App：插上免驱 UVC 摄像头即可全屏预览、拍照、录像，并支持**悬浮窗**和**键盘快捷开/关**。

- Kotlin（Gradle 8.9 + AGP 8.7.3 + Kotlin 2.0.21）
- minSdk 24（Android 7.0）/ targetSdk 34 / compileSdk 35
- 核心依赖：`com.herohan:UVCAndroid:1.0.13`（libuvc + libusb + libjpeg-turbo，Apache-2.0，Maven Central）
- 不依赖系统 Camera2 是否支持"外接相机"，走 USB 原生视频流，兼容性最好

## 功能

| 功能 | 说明 |
| --- | --- |
| 全屏预览 | 启动即进入沉浸式全屏（横屏），隐藏状态栏 / 导航栏；轻点屏幕唤出控件，3 秒自动淡出 |
| 拍照 | JPG，质量 95 |
| 录像 | MP4（H.264 + AAC；没有麦克风权限时自动录无声视频），红点计时 |
| 分辨率 / 格式切换 | 列出摄像头真实支持的 `格式(MJPEG/YUYV) × 分辨率 × 帧率`，热切换 |
| 旋转 / 镜像 | 菜单里 90° 步进旋转、水平/垂直镜像 |
| 多摄像头 | 插多个 UVC 摄像头时在菜单"摄像头"里切换 |
| 悬浮窗 | 在导航、桌面等应用上层显示画面，单指拖动、双指缩放，点 × 关闭 |
| 键盘快捷开/关 | 全局热键：按一下拉起 App 全屏，再按一下退到后台露出原来的界面 |
| 后台常驻 | 前台服务 + 通知，退到后台不杀进程（悬浮窗场景必需） |
| 开机自启 | 开机后发一条"已就绪"通知，点通知或按热键进入 |
| 文件保存 | 先写 App 私有目录；Android 10+ 自动复制进相册 `Pictures/USBCam`、`Movies/USBCam` |

## v2.0 新增的三个权限（都只需授权一次）

| 能力 | 需要什么 | 怎么开 |
| --- | --- | --- |
| 悬浮窗 | 「显示在其他应用的上层」 | 菜单 → 悬浮窗 → 弹窗里点"去设置" |
| 快捷键 | 「无障碍服务」 | 菜单 → 无障碍服务 → 系统设置里打开本应用 |
| 后台常驻 | 通知权限（Android 13+） | 首次启动随相机权限一起申请 |

> **为什么要无障碍？** Android 不允许普通应用在后台读取硬件键盘按键，只有 AccessibilityService 能申请"过滤按键"。它只读取按键，不读取屏幕内容。不开的话，快捷键只在 App 前台有效。

## 键盘快捷键怎么用

1. 菜单 → 「快捷键开关」（或底部工具条的 ⌨ 按钮）
2. 弹窗显示 10 秒倒计时，此时按下想要的键（可配合 Ctrl / Alt / Shift，默认 F8）
3. 如果还没开无障碍，会提示"去开启"，打开开关即可
4. 之后在任何界面按键：
   - App 不在前台 → 拉起 App 并全屏出画面
   - App 在前台 → 退到后台，露出按下之前的界面（导航 / 桌面）

## 悬浮窗怎么用（车载场景）

1. 菜单 → 打开「悬浮窗」→ 首次会要求悬浮窗权限
2. 小窗出现在屏幕右上方，单指拖动位置、双指缩放大小（位置和大小会记住）
3. 此时切到导航 App，小窗浮在导航上层，摄像头继续出流
4. 点小窗右上角 × 关闭；关闭后若 App 在后台，摄像头会停流释放 USB

## 目录结构

```
usbcam/
├── app/src/main/
│   ├── AndroidManifest.xml              # 权限、前台服务、无障碍服务、开机广播
│   ├── java/com/usbcam/
│   │   ├── App.kt                       # 初始化 libuvc
│   │   ├── MainActivity.kt              # 全屏界面 + 工具条 + 菜单 + 各类弹窗
│   │   ├── CameraCore.kt                # 摄像头单例（唯一持有 CameraHelper）
│   │   ├── CameraService.kt             # 前台服务 / 常驻通知
│   │   ├── FloatWindow.kt               # 悬浮窗（拖动 / 缩放）
│   │   ├── HotkeyService.kt             # 无障碍热键
│   │   ├── BootReceiver.kt              # 开机提示
│   │   ├── Prefs.kt                     # 偏好存储
│   │   ├── AppState.kt                  # 前后台状态
│   │   └── MediaStoreExport.kt          # 存相册
│   └── res/
│       ├── xml/accessibility_service_config.xml
│       ├── xml/device_filter.xml
│       ├── drawable/ic_launcher.xml
│       └── values/{strings,themes}.xml
├── design/menu-preview.html             # 界面原型（浏览器打开）
└── .github/workflows/build-apk.yml      # 云端出 APK
```

## 拿到 APK

推到 GitHub → Actions 里 `Build APK` 跑完 → 运行页最底部 **Artifacts** → `usbcam-debug-apk` → 解压得 `app-debug.apk`。

## 使用步骤

1. OTG 转接头插手机，接 USB 摄像头（可同时插 USB 键盘，需要 Hub）
2. 打开 App → 允许相机 / 麦克风 / 通知
3. 系统弹"是否允许访问该 USB 设备"→ 勾选"默认用于此设备"→ 允许
4. 画面出来后：轻点屏幕 → 工具条；点"菜单"进功能面板

## 常见问题

| 现象 | 处理 |
| --- | --- |
| 快捷键没反应 | 检查菜单里「无障碍服务」是否"已开启"；部分国产 ROM 会杀掉无障碍服务，需在系统里加白名单 |
| 按热键唤不起 App | 需要悬浮窗权限（Android 10+ 后台启动 Activity 的限制靠它豁免） |
| 悬浮窗不出来 | 检查「显示在其他应用的上层」；部分 ROM 还要允许"后台弹出界面" |
| 退后台后画面黑 | 这是有意设计：无悬浮窗时停流省电，按热键回来会自动重开（约 1 秒） |
| 开机没提示 | 检查菜单「开机自启」；部分 ROM 需要允许"自启动" |
| 一直"等待 USB 摄像头" | 手机不支持 USB Host 或 OTG 未开；换线/换机 |
| 提示 busy / 黑屏 | 摄像头被系统相机占用，关掉系统相机再回 App，或拔插一次 |
| 卡顿掉帧 | 菜单里切 MJPEG 或降低分辨率 |

## 协议与授权

- 本项目代码：MIT
- `UVCAndroid`（libuvc / libusb / libjpeg-turbo 绑定）：Apache-2.0
