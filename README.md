# 车机投屏

可安装在 Android 平板上的单 APK。平板直接通过经典 TCP ADB 连接车机，上传并启动内置的 scrcpy 4.0 server，以 H.264 投屏并回传触摸。无需电脑中转、root 或系统悬浮窗权限。

## 使用

1. 在车机上启用经典 TCP ADB，确认平板与车机网络互通，并准备在车机上允许首次 RSA 调试授权。
2. 安装 APK，打开“车机投屏”。输入车机的 IPv4 地址和端口，点击“连接”。默认填写 `192.168.42.147:5555`，可修改并保存多个连接项。
3. 状态显示“已连接”后，可以输入 `adb shell <命令>` 并点击“执行命令”；也支持 `adb devices`、`adb connect [IP:端口]` 和 `adb disconnect`。
4. 点击“开始投屏”。可直接点击、滑动或多指操作车机。屏幕上的 × 图标可以拖动；原地按住满 3 秒退出投屏并回到连接页。退出投屏后 ADB 连接可继续使用。

本应用仅支持经典 TCP ADB，不支持 Android 11 以后的配对码式无线调试。音频、录屏、文件管理和 USB ADB 不在当前功能范围内。

若连接提示被拒绝，请先确认车机上的 adbd 已监听输入的 TCP 端口；能 ping 通 IP 并不能证明 ADB 端口已开放。首次连接还需要在车机屏幕上允许本应用生成的 RSA 调试密钥。

部分车机把 `/data/local/tmp` 设为 shell 用户不可写。应用会先尝试该目录，失败后自动改用共享存储上传 scrcpy server，因此不需要 root 或放宽系统权限。

## 构建和安装

需要 JDK 17、Android SDK Platform 34、Build Tools 34.0.0。Gradle wrapper 为 8.7，Android Gradle Plugin 为 8.5.0。

```sh
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。最低系统版本为 Android 5.0（API 21）。目前代码使用 Android 公共 API 和系统 H.264 解码器，构建不依赖设备 CPU 架构；实际兼容情况请看 [真机验证记录](docs/verification/2026-10-20-device-check.md)。

Manifest 仅申请 `INTERNET` 权限；可拖动的退出图标是投屏页面内的控件。ADB 私钥保存在应用私有目录。scrcpy server 的版本、来源、校验值和许可证见 [第三方声明](THIRD_PARTY_NOTICES.md)。
