# Vrc_realtimeheartbeat

[![Build distributables](https://github.com/RICHARDwuxiaofei/Vrc_realtimeheartbeat/actions/workflows/build.yml/badge.svg)](https://github.com/RICHARDwuxiaofei/Vrc_realtimeheartbeat/actions/workflows/build.yml)

换电脑继续开发时，先阅读 [docs/NEW_PC_HANDOFF.md](docs/NEW_PC_HANDOFF.md)。它包含当前链路、三端用法、环境安装、构建测试、GitHub 产物位置和后续任务。

版本变化与本轮复核修正见 [CHANGELOG.md](CHANGELOG.md)。

Galaxy Watch6 / 小米手环心率采集与 VRChat OSC 中转项目。包含三个可独立构建的组件：

- `app`：Wear OS 应用。`diagnostic` 是保留完整测试与报告功能的测试版，`production` 是只保留日常启停和状态显示的正式版；两版都由健康类型 `ForegroundService` 持有 `ExerciseClient`。
- `mobile`：Android 手机伴侣应用，使用 Material 3；可在 Galaxy Watch Data Layer 与小米手环标准 BLE 心率广播之间切换，再通过局域网 UDP 转发到电脑。
- `pc-python`：首选 Windows 接收器，使用 Python 编写，可打包为不依赖 Python 环境的单个 EXE，并带 pytest 测试。

当前仓库保留三条链路，其中 Windows Python 端会让“手机中转”和“电脑 BLE 直连”互斥，避免同一时间两路真实心率争用 OSC：

- `Galaxy Watch (Wear OS Data Layer) → Phone → Windows → VRChat OSC`
- `Xiaomi Band (BLE Heart Rate Service) → Phone → Windows → VRChat OSC`
- `Xiaomi Band (BLE Heart Rate Service) → Windows → VRChat OSC`（实验性，无需手机）

Galaxy 手表和手机使用相同包名及签名，数据层只选择 `isNearby` 手机节点。小米模式使用手环系统自带“共享心率”，只在该模式开启按需 BLE 前台服务。链路测试包不会作为真实心率发送给 VRChat。小米实现原理、公开 API 边界、操作和真机验收项见 [docs/XIAOMI_BAND.md](docs/XIAOMI_BAND.md)。

小米手环电脑直连已由 Python Windows 端实现：Windows 使用系统蓝牙扫描标准心率服务 `0x180D`，订阅 `0x2A37`，并复用原有 OSC、曲线、CSV、统计和超时状态机。原手机中转链路继续保留且仍为默认；旧 C# Windows 接收器已在 `v1.1.0` 开发周期移除，避免维护两套桌面实现。Unity 模型、Animator 与 Avatar 数字显示由项目使用者自行维护，不属于本仓库当前实现范围。

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

1. 在电脑双击 `dist\windows-python\VrcRealtimeHeartbeat-Python.exe`，默认监听 UDP `9123`，OSC 目标为 `127.0.0.1:9000`。
2. 在电脑点击“显示配对二维码”，手机点击“扫码配对电脑”；也可以手动填写电脑局域网 IPv4 和端口 `9123`。
3. 手机点击“一键诊断”，确认手机显示电脑已回执，电脑运行记录出现 `phone_diagnostic`。
4. 手表点击 `Send phone / PC link test`。测试包必须经过三端并返回回执，但不会进入 VRChat。
5. 手表无法取得真实心率时，可安装 `diagnosticDebug`，滑到“模拟链路（非传感器）”并启动 60–80 BPM 模拟心率。它会刻意走完整 `heart_rate → OSC` 路径，因此手机和电脑都以黄色警告标出模拟数据；测试结束必须在手表点击停止。正式版 APK 不包含这个入口。
6. 真正测量前先在手表正式版选择发送频率，再启动“后台连续”：`5 秒省电`（默认）与 `10 秒超省电`都使用 Health Services 批量交付，不持有 WakeLock、不注册直接传感器；`1 秒实时`使用约 1 Hz 的直接心率传感器和有界滚动 WakeLock，息屏延迟更低但明显更耗电。省电档在 BPM 不变时自动把重复保活放宽到双倍间隔。手机到电脑可独立选择 `1/2/5/10/30 秒`并暂停/恢复。要得到真正约 1 秒一份的新 BPM，手表和手机两端都要选择 1 秒；手机档位快于手表档位时只能等待下一份手表数据。

小米手环模式不安装新的 RPK：先在手环进入 `设置 → 共享心率 → 开启`。需要手机中转时，在手机点击“切换至小米手环”；需要无手机直连时，在 Python 电脑端的“心率来源”选择“电脑直连小米手环（实验）”，启动后扫描并连接手环。两种接收方式不要同时连接；切回“手机中转”后电脑会停止 BLE。代码、Windows BLE 扫描冒烟和自动化测试已通过，但尚缺 Xiaomi Smart Band 10 真机通知/重连/续航验收，不能把它视为已完成的正式设备认证。

功耗根因、官方依据和下一轮 A/B 测试指标见 [docs/POWER_OPTIMIZATION.md](docs/POWER_OPTIMIZATION.md)。2026-07-21 的 5 秒省电档 20 分钟正常佩戴测试取得 1194 个真实样本，最大采样间隔 2005 ms、息屏交付 P95 4056 ms、最长无 callback 6016 ms，且无 WakeLock、服务重启、错误或崩溃。2026-07-23 的第二轮代码优化又移除了正式版逐批日志 flush、逐批 SharedPreferences 写入、每 30 秒节点重查和逐包 ACK，并为手机离线发现增加退避；这些改动仍需 60 分钟真机 A/B 验证。

电脑程序会把 BPM 钳制到 `0..999`，并按顺序输出 `/avatar/parameters/HR_Value`、`HR_Hundreds`、`HR_Tens`、`HR_Ones`（全部为 OSC Int32），用于三位数 Avatar 显示；同时保留 `HRValid`、由 BPM 本地生成的 `HRPulse`，以及旧版 `HeartRate`、`HeartRateNormalized`、`HeartRateValid` 兼容参数。真实数据超时阈值会根据手机上报的发送间隔自动放宽（默认 5 秒档约 12.5 秒），超时后有效状态自动变为 false。

Python 电脑端 v1.1.0 还提供 Avatar 参数测试、启动时 GitHub 正式版检查、配对二维码和按需诊断模式。普通模式不保留历史心率，也不要求手表/手机附加扩展诊断字段；开启诊断模式后，曲线默认显示最近 1 分钟，可用滑轨选择 1–10 分钟，并计算所选范围的最低/最高/平均 BPM。诊断样本追加写入内部 CSV，曲线通过独立读句柄从文件尾部读取所选时间窗，可边写边读且不会随整次会话长度全量扫描；只有手动点击“导出 CSV”才会生成用户选择的导出文件。模拟心率即使在普通模式也保留 `simulated/source` 两个安全标记，开启诊断 CSV 后会额外写入 `simulated` 列，防止导出后误当真实传感器记录。

运行 Python 电脑端测试并构建单文件 EXE：

```powershell
.\pc-python\.venv\Scripts\python.exe -m pytest .\pc-python\tests -q -p no:cacheprovider
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\pc-python\Build-Exe.ps1 -Python .\pc-python\.venv\Scripts\python.exe
```

## GitHub 云端构建

仓库中的 `Build distributables` GitHub Actions 工作流会在 Pull Request、`main` 更新和手动触发时运行：

- 用 Gradle Wrapper 测试并构建 Wear OS 测试版与正式版两个 APK；
- 用同一次任务构建 Android `phone-debug.apk`，确保两端 Debug 签名匹配；
- 在 Windows Runner 上测试和打包 Python 单文件 EXE；
- 为下载文件生成 `SHA256SUMS.txt`；
- 将 Android 和 Windows 输出保存为14天的 Workflow Artifacts。

在 GitHub 仓库打开 **Actions → Build distributables → 对应运行 → Artifacts** 即可下载。

### Artifact 与 Release 的区别

- **Artifact** 属于某一次 Actions 运行，主要用于测试和验证，当前设置保留14天。
- **Release** 绑定一个 Git 标签（例如 `v1.0.0`），是面向使用者的长期版本页面；Release 本身不负责编译，通常发布 Actions 已验证的文件。
- 当前 Android 云端产物是 Debug APK。同一次运行的手机与手表 APK 可以互通，但不同运行的临时 Debug 签名不适合作为长期覆盖升级方案。
- 正式 Release APK 应使用一把稳定、离线备份且通过 GitHub Secrets 提供的发布签名密钥。Windows ZIP 不需要代码签名即可运行，但正式分发仍可另加 Authenticode 签名。
