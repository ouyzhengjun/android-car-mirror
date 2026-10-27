# USB ADB、演示模式与双联屏 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 Android 单 APK 中增加平板 OTG 直连车机的 USB ADB、前台 USB 演示模式、三块主页以及 T26FL 全屏/主屏/副屏投屏参数。

**Architecture:** 保留现有 TCP `libadb-android` 后端；新增纯 Java USB ADB 包/流实现与 Android `UsbManager` 适配层，共用 `AdbSession` 的命令、sync 和 scrcpy 上层。主页维护设备候选与活动连接；演示状态机只跟踪 USB，连接成功后以经过校验的投屏设置启动 `MirrorActivity`。scrcpy v4 server 执行裁切并负责触控反向映射。

**Tech Stack:** Android Java Views、API 21+、Gradle 8.7/AGP 8.5、`UsbManager`、`libadb-android:3.1.1`（仅 TCP）、scrcpy server 4.0、JUnit 4。

**Spec:** `docs/superpowers/specs/2026-10-27-usb-demo-dual-screen-design.md`

## Global Constraints

- 单 APK；无电脑中转、root、系统悬浮窗权限、NDK 原生 ABI、后台常驻服务或开机自启。
- USB 模式由平板 USB Host 直连车机的 USB ADB device 接口 `0xff/0x42/0x01`；必须分别处理系统 USB 授权和车机 RSA 授权。
- 演示模式仅自动监测 USB，且仅在应用前台运行；无线 TCP 仍由用户手动选择/连接，不扫描局域网。
- 同时只有一个活动 ADB/投屏会话；发现多个 USB 候选时先让用户选择。
- T26FL display 0 为 `5120×1600`，主左副右，各 `2560×1600`；其他 HDMI/虚拟 display 不等于副屏。
- 区域为全屏/仅主屏/仅副屏/自定义；server v4.0 的 `crop` 负责触控坐标反向映射，客户端不添加手工偏移。
- 默认 `max_size=2560`、`video_bit_rate=8000000`、`max_fps=30`，display ID 0；数值与裁切边界遵循设计文档。
- 保留手动 TCP 连接、命令、投屏、可拖动并长按 3 秒退出；演示中用户长按退出同时关闭演示，拔线则回到监听。

## Review Focus

- 插入无 ADB 接口的 USB 设备：只显示普通/不支持提示，不发 ADB 包；Task 3 测试。
- USB bulkTransfer 部分读写、零字节读、拔线和 ADB AUTH 延迟：正确重组或有界失败，不阻塞 UI/残留资源；Tasks 2–3 测试。
- 同时发现两个 USB ADB 设备：演示模式等待用户选择，不随机连接；Task 5 测试。
- 5120×1600 画面主/副裁切、自定义矩形越界和尺寸变化：参数准确或拒绝，触控不落到另一半；Tasks 1、6 测试。
- 长按退出或应用退后台时仍有 USB 插入：不得马上重新投屏或后台拉起页面；Task 5 测试。

---

### Task 1: 投屏参数与服务端裁切

**Files:** `app/src/main/java/com/example/carmirror/scrcpy/MirrorSettings.java`、`MirrorOptions.java`、`CropRect.java`、`ScrcpyLauncher.java`；`app/src/test/java/com/example/carmirror/scrcpy/MirrorSettingsTest.java`、`ScrcpyLauncherTest.java`。

**Interfaces:** `MirrorSettings.defaultForDisplay(int width,int height): MirrorSettings` 在 5120×1600 首次默认主屏；`resolve(int displayWidth,int displayHeight): MirrorOptions` 产出不可变 `MirrorOptions(displayId,crop,maxSize,bitRate,maxFps)`；`ScrcpyLauncher(Context,MirrorOptions)` 将数值参数传给 v4 server，同时保留现有测试用构造器。

- [ ] 写失败测试：5120×1600 主屏产出 `crop=2560:1600:0:0`、副屏产出 `crop=2560:1600:2560:0`、全屏不传 crop；自定义越界/奇数宽高/非法码率帧率被拒；T26FL 首次默认主屏，其他尺寸默认全屏。
- [ ] 运行定向测试，确认新增断言失败。
- [ ] 实现设置模型与 scrcpy 启动参数快照；不在客户端给副屏触控加 x 偏移。
- [ ] 运行定向测试和 `./gradlew testDebugUnitTest assembleDebug --offline`，确认通过。
- [ ] 提交 `feat: configure full and cropped mirror sessions`。

### Task 2: USB ADB 协议核心

**Files:** `app/src/main/java/com/example/carmirror/adb/usb/UsbIo.java`、`AdbPacket.java`、`UsbAdbProtocol.java`、`UsbAdbStream.java`、`UsbAdbTransport.java`；对应 `app/src/test/java/com/example/carmirror/adb/usb/*Test.java`。

**Interfaces:** `UsbIo.read/write(byte[],offset,length,timeoutMs)` 返回实际字节数；`UsbAdbTransport` 实现 `AdbSession.Transport` 的 `connect(timeoutMs)`、`open(service): AdbChannel`、`close()`，构造时接受 `UsbIo`、应用 RSA credentials。一个收包线程分发多个 ADB stream，逐包验证 command/magic/长度/checksum/stream ID。

- [ ] 用假 `UsbIo` 写失败测试：CNXN/AUTH token 签名与公钥回退、OPEN/OKAY/WRTE/CLSE、两个同时打开的 stream、部分包和零进度、流控/超时/取消、异常包、拔线清理。
- [ ] 运行定向测试，确认核心协议测试先失败。
- [ ] 实现纯 Java 协议、ADB 公钥格式与有界读写；保持协议层不依赖 `UsbManager`，方便 JVM 测试。可参考官方 AOSP 规范，代码原创实现。
- [ ] 运行协议测试和完整测试构建，确认通过。
- [ ] 提交 `feat: add USB ADB protocol transport`。

### Task 3: Android USB Host 枚举与授权

**Files:** `app/src/main/java/com/example/carmirror/adb/usb/AndroidUsbIo.java`、`UsbDeviceRepository.java`、`UsbCandidate.java`；`app/src/main/AndroidManifest.xml`；`app/src/test/java/com/example/carmirror/adb/usb/UsbCandidateTest.java`。

**Interfaces:** `UsbDeviceRepository.scan(): List<UsbCandidate>` 只识别有 `0xff/0x42/0x01` 接口及 bulk IN/OUT 端点；`requestPermission(candidate,callback)` 经系统 USB 授权；`open(candidate): UsbIo` claim interface 并返回可关闭 IO。接入/拔出/授权广播只更新候选与对应资源。

- [ ] 写失败测试：接口过滤、两个同型号设备区分、缺少 bulk 端点、权限拒绝、拔线前后候选状态与资源关闭。
- [ ] 运行定向测试，确认失败。
- [ ] 实现 `UsbManager` 适配、动态 BroadcastReceiver 和 API 21–36 兼容的 PendingIntent/receiver flags；Manifest 声明 USB Host 可选 feature。
- [ ] 运行定向测试及完整构建，确认通过；在 TB522FU 上确认 Host feature 与无车机 USB 时的等待状态。
- [ ] 提交 `feat: discover and authorize USB ADB devices`。

### Task 4: 统一设备与活动连接

**Files:** `app/src/main/java/com/example/carmirror/adb/AdbSession.java`、`ConnectionOwner.java`、`DeviceEntry.java`、`DeviceCatalog.java`；相关连接测试。

**Interfaces:** `AdbSession.connect(String label, TransportSupplier supplier, Listener)` 复用现有超时/取消/流管理；现有 `connect(Endpoint,Listener)` 仍可用。`ConnectionOwner.connectUsb(UsbCandidate,UsbIo)` 与 `connectTcp(Endpoint)` 只保留一个活动会话；`DeviceCatalog` 给 UI 提供 USB 候选和已保存 TCP 条目、选择及状态。

- [ ] 写失败测试：TCP/USB 切换关闭旧流、USB 拔线清空状态、同一时刻只选中一台、USB 授权与 ADB 授权分别显示、已保存 TCP 条目不自动连接。
- [ ] 运行定向测试，确认失败。
- [ ] 在不复制 shell/sync 逻辑的前提下接入 USB transport，保留原 TCP 行为与连接超时。
- [ ] 运行定向测试和完整测试构建，确认通过。
- [ ] 提交 `feat: manage TCP and USB ADB targets`。

### Task 5: 三块主页与演示模式

**Files:** `app/src/main/java/com/example/carmirror/MainActivity.java`、`ui/DemoModeController.java`、`ui/DeviceSection.java`、`ui/MirrorSettingsSection.java`、`MirrorActivity.java`；`app/src/test/java/com/example/carmirror/ui/DemoModeControllerTest.java`。

**Interfaces:** `DemoModeController` 消费前台状态、USB 候选变化、授权/连接结果和投屏退出原因，产出等待/选设备/连接/投屏动作；`MainActivity` 显示设备、模式、设置三块并保存每设备设置。`MirrorActivity` 接受 `MirrorOptions` 快照，长按退出回传用户退出结果。

- [ ] 写失败测试：一个 USB 自动连接投屏、多个 USB 停在选择、拒绝授权可重试、无线候选不触发演示、应用后台不拉起、长按关闭演示、防止重复启动、拔线后重新等待。
- [ ] 运行定向测试，确认失败。
- [ ] 重构三块主页，接入 USB 广播、手动 TCP/USB 选择和模式切换；仅在前台执行演示动作，连接/投屏失败提供可读状态；投屏设置与已选设备关联。
- [ ] 运行定向测试与完整测试构建，确认通过；在新平板上检查横竖屏排版与无设备状态。
- [ ] 提交 `feat: add device dashboard and foreground demo mode`。

### Task 6: T26FL 与 OTG 真机验收

**Files:** `README.md`、`THIRD_PARTY_NOTICES.md`、`docs/verification/2026-10-27-t26fl-usb-demo.md`，以及真机发现问题所需的最小源码修改。

**Interfaces:** 交付 `app/build/outputs/apk/debug/app-debug.apk`；记录当前平板/车机 API、USB 枚举/授权、无线与 USB 命令、主/副/全屏画面及触控、演示模式多设备与长按退出的真实结果。

- [ ] 运行 `./gradlew testDebugUnitTest assembleDebug --offline`，记录测试数量、APK 大小与校验值。
- [ ] 安装到 TB522FU；先走 `192.168.43.160:5555` 无线连接，验证三块主页、设置持久化、全屏/左主屏/右副屏及对应触控落点。
- [ ] 将平板从开发电脑 USB 换接 OTG 与车机 USB 调试口，确认枚举、系统 USB 授权和车机 RSA 授权；用平板无线 ADB 观察命令、视频、触控、拔线和重连。若车机端口不暴露 USB ADB 接口，记录实际枚举证据与硬件限制，保留可用无线模式。
- [ ] 用 USB hub 或可控假设备验证多候选选择；没有第二台真实 USB ADB 设备时明确标记未实测，不宣称通过。
- [ ] 修复真实失败并重跑相关测试/真机流程；更新 README、许可证和验收记录，提交 `docs: verify T26FL mirror modes and USB demo flow`。
