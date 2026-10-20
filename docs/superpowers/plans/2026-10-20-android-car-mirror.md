# Android Car Mirror Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付可安装在平板上的单 APK，经经典 TCP ADB 连接车机并用 scrcpy 实时投屏和控制。

**Architecture:** Java Android Views 应用持有一个 ADB 会话；投屏会话使用同一个 ADB 连接上传并启动固定版 scrcpy server，读取视频和控制流。`MediaCodec` 渲染到 `SurfaceView`，触摸经坐标映射和 scrcpy 协议回传。连接、投屏和 UI 使用清楚的生命周期边界。

**Tech Stack:** Android Gradle Plugin 8.5.0、Gradle 8.7、Java 17、Android SDK 36、`libadb-android:3.1.1`、scrcpy server v4.0、Android `MediaCodec`、JUnit 4。

**Spec:** `docs/superpowers/specs/2026-10-20-android-car-mirror-design.md`

## Global Constraints

- 平板最低 Android 5.0 / API 21；车机最低 Android 5.0 / API 21；现有平板 Android 12 / API 31，1600×2560。
- 默认连接项 `192.168.42.147:5555`；使用经典 TCP ADB，车机端需要 RSA 授权。
- 单 APK；平板不依赖电脑、root、系统悬浮窗权限或原生桌面 scrcpy/adb 可执行文件。
- scrcpy 客户端协议与内置 server 均固定 v4.0；H.264 视频和控制，禁用音频。
- 命令页限单设备连接状态、设备信息与 `adb shell <命令>` 输出；不提供文件管理、配对码、USB ADB、录屏。
- 图标在投屏 Activity 内可拖动；原地连续按住满 3 秒退出，拖动不触发退出。

## Review Focus

- IP 文本含空白、主机名、非法端口：拒绝连接并给出字段错误（Task 1）。
- ADB 首次授权迟迟未确认或连接中断：超时并可重试，不留下后台任务（Task 2）。
- scrcpy 服务启动后视频或控制流只建立一个：关闭已建立流并报告失败（Task 3）。
- 视频数据半包、超大包、尺寸改变：正确分包或拒绝，不导致解码器死锁（Task 4）。
- 触摸落在黑边、退出图标拖动与长按混合：黑边不控制车机，拖动不退出（Tasks 5–6）。

---

### Task 1: Android 工程与连接页

**Files:** `settings.gradle`, `build.gradle`, `gradle/wrapper/*`, `app/build.gradle`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/example/carmirror/MainActivity.java`, `app/src/main/java/com/example/carmirror/Endpoint.java`, `app/src/test/java/com/example/carmirror/EndpointTest.java`, `README.md`

**Interfaces:** `Endpoint.parse(String ip, String portText): Endpoint`；实例公开 `host(): String`、`port(): int`、`serial(): String`。`MainActivity` 显示并保存连接项，连接动作先通过 `Endpoint.parse` 校验。

- [ ] 写 `EndpointTest`：合法默认地址得 `192.168.42.147:5555`；空白、主机名、八位组越界、端口 0/65536 均失败。
- [ ] 运行 `./gradlew testDebugUnitTest --tests '*EndpointTest'`，确认新测试失败。
- [ ] 建立可构建的 Java Android 工程、`Endpoint` 与连接页；Manifest 只声明所需 `INTERNET` 权限，UI 预填默认地址。
- [ ] 运行 `./gradlew testDebugUnitTest --tests '*EndpointTest'` 与 `./gradlew assembleDebug`，确认通过。
- [ ] 提交 `feat: create Android connection app`。

### Task 2: ADB 连接、认证与命令

**Files:** `app/src/main/java/com/example/carmirror/adb/AdbSession.java`, `app/src/main/java/com/example/carmirror/adb/AdbKeyStore.java`, `app/src/main/java/com/example/carmirror/adb/AdbGateway.java`, `app/src/main/java/com/example/carmirror/adb/ConnectionState.java`, `app/src/main/java/com/example/carmirror/MainActivity.java`, `app/src/test/java/com/example/carmirror/adb/AdbSessionTest.java`

**Interfaces:** `AdbGateway.connect(Endpoint, Listener): void`、`disconnect(): void`、`shell(String command, long timeoutMs): CommandResult`、`openService(String destination): AdbChannel`、`push(InputStream source, long size, String remotePath): void`。`AdbChannel` 提供 `InputStream input()`、`OutputStream output()` 和幂等 `close()`；`CommandResult` 提供 `output()`、`exitCode()` 与 `timedOut()`。`Listener.onState(ConnectionState state, String message)` 供 UI 显示。包装 `libadb-android`，保证所有阻塞调用不在主线程；密钥存应用私有目录。

- [ ] 写会话测试：初次连接触发授权等待；超时和中途断开关闭流；重复连接替换旧会话；命令输出与失败状态回到 UI。
- [ ] 运行 `./gradlew testDebugUnitTest --tests '*AdbSessionTest'`，确认失败。
- [ ] 实现 RSA 密钥持久化、ADB 连接状态机、`shell/sync/localabstract` 适配、命令页；命令输入支持 `adb shell ...`、`adb devices`、`adb connect`、`adb disconnect` 的本地映射。
- [ ] 运行 ADB 单元测试及 `./gradlew assembleDebug`，确认通过。
- [ ] 提交 `feat: connect and manage wireless ADB devices`。

### Task 3: scrcpy server 启动与会话清理

**Files:** `app/src/main/assets/scrcpy-server-v4.0`, `app/src/main/java/com/example/carmirror/scrcpy/ScrcpySession.java`, `app/src/main/java/com/example/carmirror/scrcpy/ScrcpyLauncher.java`, `app/src/test/java/com/example/carmirror/scrcpy/ScrcpyLauncherTest.java`, `THIRD_PARTY_NOTICES.md`

**Interfaces:** `ScrcpySession.start(AdbGateway adb): Streams` 返回 `Streams.video()` 与 `Streams.control()` 两个 `AdbChannel`；`stop(): void` 幂等。官方 v4.0 server 与客户端版本完全一致；使用随机 `scid`、`tunnel_forward=true`、`audio=false`；顺序连接 video 再 control。记录 server 来源和 SHA-256。

- [ ] 写启动测试：推送、shell 启动参数、两个通道的建立顺序；其中一个通道超时则关闭全部资源；`stop()` 可重复调用。
- [ ] 运行 `./gradlew testDebugUnitTest --tests '*ScrcpyLauncherTest'`，确认失败。
- [ ] 加入官方 server 资源、许可证说明，完成推送、启动、轮询连接、超时和清理。
- [ ] 运行 scrcpy 启动测试和 `./gradlew assembleDebug`，确认通过。
- [ ] 提交 `feat: launch bundled scrcpy server`。

### Task 4: 视频协议与解码

**Files:** `app/src/main/java/com/example/carmirror/scrcpy/VideoPacketReader.java`, `app/src/main/java/com/example/carmirror/scrcpy/VideoDecoder.java`, `app/src/main/java/com/example/carmirror/scrcpy/VideoGeometry.java`, `app/src/test/java/com/example/carmirror/scrcpy/VideoPacketReaderTest.java`, `app/src/test/java/com/example/carmirror/scrcpy/VideoGeometryTest.java`

**Interfaces:** `VideoPacketReader.readNext(InputStream): VideoPacket` 读取 v4.0 元数据/帧并限制包大小；`VideoDecoder.attach(Surface)`、`offer(VideoPacket)`、`close()`；`VideoGeometry` 持有流尺寸与 View 矩形，供 Task 5 坐标映射。

- [ ] 写解析测试：分段输入、配置帧、关键帧、尺寸变更、零长度与超大包；几何测试覆盖横竖屏及黑边。
- [ ] 运行两个测试类，确认失败。
- [ ] 实现 scrcpy v4.0 帧协议、MediaCodec H.264 解码、Surface 重建和低延迟队列。
- [ ] 运行两个测试类及构建，确认通过。
- [ ] 提交 `feat: render scrcpy video on Android`。

### Task 5: 控制协议与触摸映射

**Files:** `app/src/main/java/com/example/carmirror/scrcpy/ControlMessageWriter.java`, `app/src/main/java/com/example/carmirror/scrcpy/TouchController.java`, `app/src/test/java/com/example/carmirror/scrcpy/ControlMessageWriterTest.java`, `app/src/test/java/com/example/carmirror/scrcpy/TouchControllerTest.java`

**Interfaces:** `TouchController.onTouch(TouchSample sample, VideoGeometry geometry): boolean`，其中 `TouchSample` 是从 Android `MotionEvent` 转换的纯 Java 值对象（动作、指针 ID、坐标、压力）；`ControlMessageWriter.touch(...)` 与 `keycode(int action, int keyCode)` 按 v4.0 序列化到控制流。事件映射包含 DOWN、MOVE、UP、CANCEL 和多指 ID。

- [ ] 写协议字节断言、横竖屏映射、黑边忽略和多指释放测试。
- [ ] 运行两个测试类，确认失败。
- [ ] 实现控制消息写入和触摸映射；仅投屏有效区域向车机发送事件。
- [ ] 运行两个测试类及构建，确认通过。
- [ ] 提交 `feat: control mirrored device by touch`。

### Task 6: 投屏 Activity 与 3 秒退出图标

**Files:** `app/src/main/java/com/example/carmirror/MirrorActivity.java`, `app/src/main/java/com/example/carmirror/ui/ExitHandle.java`, `app/src/main/java/com/example/carmirror/ui/ExitGestureState.java`, `app/src/main/AndroidManifest.xml`, `app/src/test/java/com/example/carmirror/ui/ExitGestureStateTest.java`

**Interfaces:** `ExitGestureState.onDown(long t, float x, float y)`、`onMove(long t, float x, float y)`、`onUp(long t)`、`shouldExit(long t): boolean`；`MirrorActivity` 持有 `ScrcpySession`、`VideoDecoder`、`TouchController`，退出走单一 `closeSession()`。

- [ ] 写状态测试：2999ms 不退出、3000ms 退出、提前抬手取消、超过触摸阈值改为拖动且不退出、重复调用只退出一次。
- [ ] 运行 `./gradlew testDebugUnitTest --tests '*ExitGestureStateTest'`，确认失败。
- [ ] 实现全屏投屏、返回/主页/最近任务控件、图标拖动和进度提示；断流显示错误并关闭会话。
- [ ] 运行退出状态测试、完整单元测试和构建，确认通过。
- [ ] 提交 `feat: add full-screen mirror and hold-to-exit control`。

### Task 7: 真机验收与交付

**Files:** `README.md`, `docs/verification/2026-10-20-device-check.md`, `app/src/main/java/com/example/carmirror/**`（仅依据真机失败修复）

**Interfaces:** 输出 `app/build/outputs/apk/debug/app-debug.apk`。记录车机型号/API、连接授权、命令输出、视频、触摸、按键、图标拖动与 3 秒退出的实际结果。

- [ ] 运行 `./gradlew testDebugUnitTest assembleDebug`，保存结果。
- [ ] 用 `adb -s 2MGBB21814205382 install -r app/build/outputs/apk/debug/app-debug.apk` 安装到平板并启动。
- [ ] 在平板应用内连接 `192.168.42.147:5555`；需要时在车机确认授权；逐项执行端到端验收并记录客观结果。
- [ ] 修复真实失败，重跑相关测试与真机流程；确认退出后会话资源关闭。
- [ ] 更新 README 使用步骤、兼容实测范围、APK 路径和第三方许可证；提交 `docs: record tablet and car-unit validation`。
