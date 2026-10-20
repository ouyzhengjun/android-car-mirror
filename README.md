# Car Mirror

原生 Java Android 车机投屏客户端，最低 Android 5.0（API 21）。当前实现设备连接页和 IPv4/端口校验；ADB 会话与投屏由后续任务接入。

连接页默认 `192.168.42.147:5555`，可编辑、保存多个连接项并切换。点击连接前会校验地址；本阶段显示地址校验成功提示，不会声称已连接车机。连接项保存在应用私有 SharedPreferences 中，重启后恢复最近保存的地址。

## 构建

安装 JDK 17+、Android SDK Platform 34 和 Build Tools 34.0.0，使用 `ANDROID_HOME` 或本地 `local.properties` 配置 SDK。

```sh
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

调试安装包：`app/build/outputs/apk/debug/app-debug.apk`。Gradle wrapper 固定为 8.7，Android Gradle Plugin 为 8.5.0。Manifest 仅请求 INTERNET，无系统悬浮窗权限。
