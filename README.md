# Vrc_realtimeheartbeat

Samsung Galaxy Watch6 心率采集与 VRChat OSC 中转项目。包含三个可独立运行的组件：

- `app`：Wear OS 应用，保留 `MeasureClient` 探针和由健康类型 `ForegroundService` 持有的 `ExerciseClient` 测试；真实样本通过 Wear OS Data Layer 发往附近手机。
- `mobile`：Android 手机伴侣应用，使用 Material 3，接收手表消息并通过局域网 UDP 转发到电脑。
- `pc-bridge`：无需额外 SDK 的 Windows 接收器，发送回执并转为 VRChat 本机 OSC。

实时链路为 `Watch (Data Layer / Bluetooth) → Phone (UDP / LAN) → Windows → VRChat OSC`。手表和手机使用相同包名及签名，数据层只选择 `isNearby` 手机节点。链路测试包不会作为真实心率发送给 VRChat。

## 在 Android Studio 中打开

1. 用 Android Studio 打开克隆后的项目根目录。
2. Gradle JDK 选择 Android Studio 默认 JBR。
3. 等待 Gradle Sync 完成。手表运行配置选择 `app`；手机运行配置选择 `mobile`。

项目固定使用 Gradle Wrapper 8.13；Wrapper 下载包带 SHA-256 校验。当前构建参数为 compileSdk 36.1、targetSdk 36、minSdk 30。

## 命令行构建

在 PowerShell 中：

```powershell
$env:JAVA_HOME = 'PATH_TO_JDK'
$env:ANDROID_SDK_ROOT = 'PATH_TO_ANDROID_SDK'
.\gradlew.bat :app:assembleDebug :mobile:assembleDebug
```

生成的 APK：

```text
app\build\outputs\apk\debug\app-debug.apk
mobile\build\outputs\apk\debug\mobile-debug.apk
```

## 安装和启动

```powershell
$adb = Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe'
& $adb devices -l
& $adb -s WATCH_SERIAL install -r 'app\build\outputs\apk\debug\app-debug.apk'
& $adb -s PHONE_SERIAL install -r 'mobile\build\outputs\apk\debug\mobile-debug.apk'
```

首次运行后选择 `MeasureClient Probe` 或 `ExerciseClient Screen-off Test`，再点击 `Start selected test`。API 35 及以下使用身体传感器权限；API 36 及以上使用 `READ_HEART_RATE`，Exercise 模式还会请求 `READ_HEALTH_DATA_IN_BACKGROUND`。Exercise 会话只会在用户点击 Stop 后正常结束，返回表盘、Activity stop 和息屏不会结束会话。

## 查看诊断日志

Logcat：

```powershell
$adb = Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe'
& $adb -s WATCH_SERIAL logcat -s HR_PROBE
```

应用私有滚动日志：

```powershell
& $adb -s WATCH_SERIAL shell run-as best.nagikokoro.watch6heartrateprobe cat files/logs/hr_probe.log
```

日志上限约 1 MiB，最多保留 `hr_probe.log`、`hr_probe.log.1`、`hr_probe.log.2`。设备生成的原始报告和本机环境报告默认不纳入版本控制。

后台交付及时性与 10/20 分钟续航测试的完整步骤、报告字段和 ADB 导出命令见 [BACKGROUND_TEST_GUIDE.md](BACKGROUND_TEST_GUIDE.md)。每次正式测试会在应用私有目录 `files/tests/` 生成独立的 `.json`、`.txt` 和原始 `.events.jsonl` 文件。

## 三端联通测试

1. 在电脑双击 `pc-bridge\Start Heart Rate Bridge.cmd`，默认监听 UDP `9123`，OSC 目标为 `127.0.0.1:9000`。
2. 在手机安装并打开 `mobile-debug.apk`，填写电脑界面显示的局域网 IPv4 和端口 `9123`。
3. 手机点击“发送测试包”，确认手机和电脑的回执计数增加。
4. 手表点击 `Send phone / PC link test`。测试包必须经过三端并返回回执，但不会进入 VRChat。
5. 真正测量时启动手表的 Exercise 模式；手机会自动转发每个有效样本。

电脑程序输出 `/avatar/parameters/HeartRate`（Int）、`HeartRateNormalized`（Float）和 `HeartRateValid`（Bool）。超过 10 秒没有真实数据时，`HeartRateValid` 自动变为 false。
