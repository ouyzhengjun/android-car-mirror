# 车机投屏 CarMirror

用一台 Android 平板直接投屏并控制车机：通过 **USB OTG** 或**无线 TCP ADB** 连接车机，内置 scrcpy 4.0 server 传输 H.264 画面，并把平板上的触摸、滑动和系统按键反向回传给车机。**不需要电脑中转、root 或系统悬浮窗权限。**

A single-APK Android app that mirrors and controls a car head unit over ADB — USB OTG or classic TCP. It bundles the official scrcpy 4.0 server, decodes H.264 with MediaCodec, and forwards touch and key input back. No PC, root, or overlay permission required.

> 当前版本 `0.1.0`，处于真机验证阶段。适用范围、已验证项和已知限制见下文。

## 功能特性

- **USB OTG 直连**：平板作为 USB Host 连接车机的 ADB 调试口，应用内置 ADB 协议与 RSA 认证实现，不依赖桌面 `adb`。
- **无线 TCP ADB**：手动输入车机 `IP:端口`，支持保存连接项并复用。
- **内置 scrcpy 4.0 server**：官方原版二进制，H.264 硬解，低延迟投屏。
- **触摸反向控制**：点击、长按、滑动、多指，以及返回 / 主页 / 最近任务按键。
- **单屏与双联屏**：全屏、仅主屏、仅副屏、自定义裁切。
- **按设备保存参数**：分辨率、码率、帧率、投屏区域按设备记忆。
- **演示模式**：前台插入 USB 即自动连接并投屏，适合展厅和验收演示。
- **诊断工具**：`adb shell`、`adb devices` / `connect` / `disconnect`、读取显示尺寸、投屏日志。

## 工作原理

```text
平板 ── USB OTG / TCP ──> 车机 adbd
  1. 与 adbd 完成 ADB 握手和 RSA 认证
  2. 推送内置 scrcpy-server 到 /data/local/tmp，
     以 app_process 启动（目录不可写时回退到共享存储）
  3. 通过 ADB localabstract 读取 H.264 视频流与控制流
  4. MediaCodec 解码到 SurfaceView
  5. 触摸坐标映射后按 scrcpy 控制协议回传
```

固定使用 scrcpy **v4.0** 的 server 与协议；升级时必须成对替换 server 和客户端协议实现。

## 环境要求

| 项目 | 要求 |
| --- | --- |
| 平板 | Android 5.0+（API 21）；USB 方式需支持 USB Host（OTG） |
| 车机 | Android 5.0+，开启 USB 调试或经典 TCP ADB（通常 5555 端口） |
| 构建 | JDK 17、Android SDK Platform 34、Build Tools 34.0.0 |
| 构建工具 | Gradle 8.7（wrapper）、Android Gradle Plugin 8.5.0 |

## 构建与安装

```sh
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

APK 输出在 `app/build/outputs/apk/debug/app-debug.apk`。首次构建需要联网拉取依赖；依赖已缓存时可加 `--offline`。

不提供正式签名配置，请自行在 `app/build.gradle` 中补充 release signing；当前产物为 debug APK。

## 使用

应用锁定横屏，界面按大屏平板设计。首页只有两个入口：**演示模式**和**设置**；同一时间只保留一个 ADB / 投屏会话，切换目标会关闭旧会话。

### 方式一：USB OTG 直连

1. 在车机开启 USB 调试，准备在车机屏幕确认首次 RSA 授权。
2. 平板须支持 USB Host，用可传输数据的 OTG 线连接车机**实际提供 ADB device 的调试口**；车机普通 USB Host 口或仅充电线不可用。
3. 在应用内选择 USB 候选，点击「选择设备并连接」。应用只识别带 bulk IN/OUT 的 ADB 接口 `0xff/0x42/0x01`。
4. 先在平板允许系统 USB 设备访问，再在车机允许 RSA 调试授权 —— 这是两个独立授权，前者通过不代表 ADB 已连接。
5. 连接成功后，在「投屏设置」读取显示尺寸、选择区域和参数，再开始投屏。

### 方式二：无线 TCP ADB

1. 车机开启经典 TCP ADB，并保证与平板网络互通；在应用中输入车机 IPv4 地址与端口并连接，在车机允许首次 RSA 授权。
2. 可保存连接项，下次选中后直接连接。应用**不扫描局域网、不自动连接**无线目标。
3. 连接后支持 `adb shell <命令>`、`adb devices`、`adb connect`、`adb disconnect`。

提示 `Connection refused` 时，请确认车机 adbd 正在目标端口监听；能 ping 通不代表 ADB 可用。应用不支持 Android 11+ 的配对码式无线调试。

### 演示模式

在「模式选择」打开「USB 演示模式（仅应用前台）」：

- 只有一个 USB 候选时请求授权、连接并自动投屏；多个候选时等待选择；无设备时显示等待 OTG。
- 应用退到后台即停止监听，不会从后台拉起投屏；返回前台后重新检查设备。
- 拔线会结束会话，重新插入可再次触发。投屏中可拖动 `×` 图标，**原地按住 3 秒**退出，避免仍插着线时立刻重新投屏。

### 退出投屏

投屏页按 cover 铺满整屏，不叠加任何控件。从屏幕边缘内滑（系统返回）退出，首次进入会有 3 秒一次性提示；演示模式下也可长按 `×` 退出。退出后 ADB 连接可继续使用。

## 投屏区域与参数

分辨率默认**跟随车机实测尺寸**：连接后自动读取 `wm size`，按原分辨率编码，不额外缩放。单屏设备整幅投放；双联屏（如 `5120×1600`，左右各 `2560×1600`）可选择仅主屏 / 仅副屏，画面按铺满显示，比例不一致时只裁不缩。

| 区域 | 说明 / server crop |
| --- | --- |
| 全屏 | 完整画面，不传 crop |
| 仅主屏（左） | 以 `5120×1600` 双联屏为例：`2560:1600:0:0` |
| 仅副屏（右） | `2560:1600:2560:0` |
| 自定义 | 输入宽、高、x、y；宽高为正偶数，区域不得超出原始显示尺寸 |

| 参数 | 默认 | 范围 |
| --- | --- | --- |
| 显示器 ID | 0 | 0–255 |
| 最长边 | 2560 | 256–8192，16 的倍数 |
| 视频码率 | 8 Mbps | 1–50 Mbps |
| 帧率 | 30 | 1–60 |

参数按设备保存。同型号 USB 设备若无可用序列号，可能共享设置，但连接选择仍按 USB 路径区分。裁切在 server 侧完成，触控坐标也由 server 映射，客户端不额外加副屏偏移。

## 已验证范围

- **平板（Lenovo TB522FU，API 36）**：安装启动、横屏界面、无线 TCP ADB 全链路（连接、RSA 授权、shell 命令、读取尺寸、server 启动、解码渲染、触控、退出）、按设备持久化设置。
- **车机（5120×1600 双联屏，API 34）**：无线连接、全屏 / 仅左主屏 / 仅右副屏三种画面、触控落点、长按退出。
- **平板 USB OTG → 车机调试口**：系统 USB 授权、ADB 握手、server 上传、视频投屏与触控完整链路。
- **单元测试**：139 个 JVM 测试通过，0 failures。

尚未验证：API 21–35 实机运行、多台 USB 设备与 hub 选择、自定义裁切边界、缩放与旋转、弱网和断线重连。

详细记录：

- [USB 演示与双联屏真机验证（2026-10-27）](docs/verification/2026-10-27-t26fl-usb-demo.md)
- [无线连接真机验证（2026-10-20）](docs/verification/2026-10-20-device-check.md)
- [原始设计规格](docs/superpowers/specs/2026-10-20-android-car-mirror-design.md)
- [双联屏与 USB 演示设计](docs/superpowers/specs/2026-10-27-usb-demo-dual-screen-design.md)

## 已知限制

- 不提供音频、录屏、文件管理、后台常驻服务或开机自启。
- 不支持配对码式无线调试，仅支持经典 TCP ADB。
- 首次连接必须在车机上人工确认 RSA 授权；车机需已开启 adbd。
- 仅面向大屏横屏设计，手机竖屏体验未优化。
- `minSdk 21` 只代表可以安装，API 21–35 的实际兼容性仍待验证。

## 安全说明

- ADB 私钥在应用私有目录生成并保存，不写入日志。
- 只连接用户手动输入的地址，不扫描局域网、不自动连接未知设备。
- 不使用 root，不请求系统悬浮窗权限。
- 车机 adbd 是高权限调试接口，请仅在受信任网络中使用，不要暴露到公网。

## 项目结构

```text
app/src/main/java/com/example/carmirror/
├── adb/        ADB 协议、RSA 密钥、USB OTG 传输
├── scrcpy/     server 启动、视频解析、控制消息、触摸映射
└── ui/         主页、设置、设备管理、演示模式、显示探测
app/src/test/   139 个 JVM 单元测试
docs/           设计规格、UI 稿、真机验证记录
tools/icons/    应用图标生成脚本
```

## 第三方组件

| 组件 | 用途 | 许可 |
| --- | --- | --- |
| [scrcpy](https://github.com/Genymobile/scrcpy) server 4.0 | 车机端采集编码与控制协议 | Apache-2.0，声明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) |
| [libadb-android](https://github.com/MuntashirAkon/libadb-android) | ADB 协议实现 | GPL-3.0+ / Apache-2.0 双许可 |
| [Bouncy Castle](https://www.bouncycastle.org/) | RSA 密钥与签名 | Bouncy Castle Licence |
| [Conscrypt](https://github.com/google/conscrypt) | TLS | Apache-2.0 |

内置的 `scrcpy-server-v4.0` 为官方原版二进制，未做修改；来源、大小与 SHA-256 校验值见[第三方声明](THIRD_PARTY_NOTICES.md)。

## 许可证

本项目以 [Apache License 2.0](LICENSE) 发布，Copyright 2026 Ouyang Zhenjun。仓库内置的第三方组件另按其各自许可证分发，详见[第三方声明](THIRD_PARTY_NOTICES.md)。
