# Vrc_realtimeheartbeat

[![Build distributables](https://github.com/RICHARDwuxiaofei/Vrc_realtimeheartbeat/actions/workflows/build.yml/badge.svg)](https://github.com/RICHARDwuxiaofei/Vrc_realtimeheartbeat/actions/workflows/build.yml)

换电脑继续开发时，先阅读 [docs/NEW_PC_HANDOFF.md](docs/NEW_PC_HANDOFF.md)。它包含当前链路、三端用法、环境安装、构建测试、GitHub 产物位置和后续任务。

Samsung Galaxy Watch6 心率采集与 VRChat OSC 中转项目。包含四个可独立构建的组件：

- `app`：Wear OS 应用。`diagnostic` 是保留完整测试与报告功能的测试版，`production` 是只保留日常启停和状态显示的正式版；两版都由健康类型 `ForegroundService` 持有 `ExerciseClient`。
- `mobile`：Android 手机伴侣应用，使用 Material 3，接收手表消息并通过局域网 UDP 转发到电脑。
- `pc-python`：首选 Windows 接收器，使用 Python 编写，可打包为不依赖 Python 环境的单个 EXE，并带 pytest 测试。
- `pc-bridge`：原有 C# WinForms 接收器，完整保留为回退版本。

当前仓库已实现的中继链路是 `Watch (Wear OS Data Layer) → Phone (UDP / LAN) → Windows → VRChat OSC`。手表和手机使用相同包名及签名，数据层只选择 `isNearby` 手机节点。链路测试包不会作为真实心率发送给 VRChat。

最终目标链路是 `Watch (BLE Heart Rate Service) → Windows → VRChat OSC`；Watch 作为 BLE GATT 外设、Windows BLE 接收和目标 OSC 参数尚未实现。Unity 模型、Animator 与 Avatar 数字显示由项目使用者自行维护，不属于本仓库当前实现范围。

## 在 Android Studio 中打开

1. 用 Android Studio 打开克隆后的项目根目录。
2. Gradle JDK 选择 Android Studio 默认 JBR。
3. 等待 Gradle Sync 完成。手表日常使用选择 `app` 的 `productionDebug`，诊断和续航测试选择 `diagnosticDebug`；手机运行配置选择 `mobile`。

项目固定使用 Gradle Wrapper 8.13；Wrapper 下载包带 SHA-256 校验。当前构建参数为 compileSdk 36.1、targetSdk 36、minSdk 30。

## 命令行构建

在 PowerShell 中：

```powershell
$env:JAVA_HOME = 'PATH_TO_JDK'
$env:ANDROID_SDK_ROOT = 'PATH_TO_ANDROID_SDK'
.\gradlew.bat :app:assembleDiagnosticDebug :app:assembleProductionDebug :mobile:assembleDebug
```

生成的 APK：

```text
app\build\outputs\apk\diagnostic\debug\app-diagnostic-debug.apk
app\build\outputs\apk\production\debug\app-production-debug.apk
mobile\build\outputs\apk\debug\mobile-debug.apk
```

## 安装和启动

```powershell
$adb = Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe'
& $adb devices -l
& $adb -s WATCH_SERIAL install -r 'app\build\outputs\apk\production\debug\app-production-debug.apk'
& $adb -s PHONE_SERIAL install -r 'mobile\build\outputs\apk\debug\mobile-debug.apk'
```

正式版只有“开始传输 / 停止传输”和必要状态，固定使用 ExerciseClient；测试版保留 `MeasureClient Probe`、息屏测试、续航测试、原始报告和链路诊断。这里的“正式版”指功能和界面分版，当前本地文件仍是 debug 签名 APK。两版使用相同包名与签名以维持 Wear Data Layer 兼容，因此不能同时安装；安装另一版会覆盖当前版本，APK 文件本身可随时用于回退。API 35 及以下使用身体传感器权限；API 36 及以上使用 `READ_HEART_RATE`，Exercise 模式还会请求 `READ_HEALTH_DATA_IN_BACKGROUND`。Exercise 会话只会在用户点击停止后正常结束，返回表盘、Activity stop 和息屏不会结束会话。

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

后台交付及时性与 10/20/60 分钟续航测试的完整步骤、报告字段和 ADB 导出命令见 [BACKGROUND_TEST_GUIDE.md](BACKGROUND_TEST_GUIDE.md)。每次正式测试会在应用私有目录 `files/tests/` 生成独立的 `.json`、`.txt` 和原始 `.events.jsonl` 文件。

## 三端联通测试

1. 在电脑双击首选产物 `dist\windows-python\VrcRealtimeHeartbeat-Python.exe`，默认监听 UDP `9123`，OSC 目标为 `127.0.0.1:9000`。旧 C# 版仍位于 `dist\windows\VrcRealtimeHeartbeat.exe`。
2. 在手机安装并打开 `mobile-debug.apk`，填写电脑界面显示的局域网 IPv4 和端口 `9123`。
3. 手机点击“发送测试包”，确认手机和电脑的回执计数增加。
4. 手表点击 `Send phone / PC link test`。测试包必须经过三端并返回回执，但不会进入 VRChat。
5. 真正测量时启动手表的“后台连续”模式。当前已真机验证的低功耗实现使用 Health Services `HEART_RATE_5_SECONDS`，普通模式不再持有 WakeLock 或注册直接传感器，并约每 5 秒向手机发送一份最新 BPM。手机到电脑仍可在 `1/2/5/10/30 秒`之间切换，也可随时暂停/恢复；上游 5 秒模式下，1/2 秒档不会产生额外的新心率。

功耗根因、官方依据和下一轮 A/B 测试指标见 [docs/POWER_OPTIMIZATION.md](docs/POWER_OPTIMIZATION.md)。2026-07-21 的 20 分钟正常佩戴测试取得 1194 个真实样本，最大采样间隔 2005 ms、息屏交付 P95 4056 ms、最长无 callback 6016 ms，且无 WakeLock、服务重启、错误或崩溃。测试后已修复 4990–4999 ms 批次被严格 5000 ms 节流误跳过的问题，并移除 Watch/Phone 两端重复的运行时 Data Layer listener；仍需进行至少 60 分钟正式续航测试。

电脑程序输出 `/avatar/parameters/HR_Tens`（Int）、`HR_Ones`（Int）、`HRValid`（Bool）和由 BPM 本地生成的 `HRPulse`（Bool），同时保留旧版 `HeartRate`、`HeartRateNormalized`、`HeartRateValid` 参数。真实数据超时阈值会根据手机上报的发送间隔自动放宽（默认 5 秒档约 12.5 秒），超时后有效状态自动变为 false。

运行 Python 电脑端测试并构建单文件 EXE：

```powershell
.\pc-python\.venv\Scripts\python.exe -m pytest .\pc-python\tests -q -p no:cacheprovider
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\pc-python\Build-Exe.ps1 -Python .\pc-python\.venv\Scripts\python.exe
```

旧 C# 回退版仍可使用 `pc-bridge\Build-Exe.ps1` 单独构建。

## GitHub 云端构建

仓库中的 `Build distributables` GitHub Actions 工作流会在 Pull Request、`main` 更新和手动触发时运行：

- 用 Gradle Wrapper 测试并构建 Wear OS 测试版与正式版两个 APK；
- 用同一次任务构建 Android `phone-debug.apk`，确保两端 Debug 签名匹配；
- 在 Windows Runner 上分别测试和打包首选 Python EXE 与旧 C# 回退 EXE；
- 为下载文件生成 `SHA256SUMS.txt`；
- 将 Android 和 Windows 输出保存为14天的 Workflow Artifacts。

在 GitHub 仓库打开 **Actions → Build distributables → 对应运行 → Artifacts** 即可下载。

### Artifact 与 Release 的区别

- **Artifact** 属于某一次 Actions 运行，主要用于测试和验证，当前设置保留14天。
- **Release** 绑定一个 Git 标签（例如 `v1.0.0`），是面向使用者的长期版本页面；Release 本身不负责编译，通常发布 Actions 已验证的文件。
- 当前 Android 云端产物是 Debug APK。同一次运行的手机与手表 APK 可以互通，但不同运行的临时 Debug 签名不适合作为长期覆盖升级方案。
- 正式 Release APK 应使用一把稳定、离线备份且通过 GitHub Secrets 提供的发布签名密钥。Windows ZIP 不需要代码签名即可运行，但正式分发仍可另加 Authenticode 签名。
