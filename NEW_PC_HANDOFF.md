# 换机继续开发交接文档

本文件是给“换到另一台 Windows 电脑后继续开发”使用的完整 handoff，不是面向普通用户的简版说明。请先通读第 1、2、7、8、11 节，再开始改代码或接设备。

本次整理时间：2026-09-04
最后一次已验证源码提交：`53dbeb8 Add production watch app and Python bridge`
当前开发分支：`codex/low-power-wearos`

## 1. 项目目标与当前真实链路

项目用于把 Galaxy Watch6 的真实心率送到 VRChat Avatar。

当前已经跑通并正在使用的链路：

```text
Galaxy Watch6
  → ExerciseClient + health ForegroundService
  → Wear OS Data Layer（手表到手机，通常走蓝牙）
  → Android 手机 UDP 转发
  → Windows Python 接收器
  → VRChat OSC
```

最初规划的 `Watch BLE GATT 外设 → Windows 直连` 尚未实现。当前版本仍需要 Android 手机中转，不能把它描述成手表直连电脑 BLE。

Unity、Avatar 模型、Animator 和数字显示由用户自己维护；本仓库负责到 OSC 参数输出为止。

## 2. GitHub 位置与继续开发分支

- 仓库：<https://github.com/RICHARDwuxiaofei/Vrc_realtimeheartbeat>
- 继续开发分支：`codex/low-power-wearos`
- `main` 当前仍是较早的交接检查点。换机后不要直接从旧 `main` 重新做一遍。

新电脑获取代码：

```powershell
git clone https://github.com/RICHARDwuxiaofei/Vrc_realtimeheartbeat.git
cd Vrc_realtimeheartbeat
git switch codex/low-power-wearos
git pull --ff-only
```

GitHub 自动构建文件在仓库的 **Actions → Build distributables → 对应运行 → Artifacts**：

- `android-debug-apks`：手表测试版、手表正式版、手机 APK
- `windows-python-heart-rate-bridge`：首选 Python EXE 与 ZIP
- `windows-csharp-heart-rate-bridge`：旧 C# 回退 EXE 与 ZIP

工作流文件为 `.github/workflows/build.yml`。当前只在 `main` push、Pull Request 或手动运行时触发；仅推送开发分支后如需云端产物，可在 Actions 手动选择该分支运行，或创建 Pull Request。

## 3. 三端分别是什么

### 手表端 `app/`

同一份源码产生两个 APK：

- `diagnosticDebug`：测试版，保留 MeasureClient 探针、息屏/续航测试、原始事件、统计报告和链路诊断。
- `productionDebug`：日常正式版，固定使用 ExerciseClient，只显示 BPM、后台状态、手机、样本年龄、电量以及开始/停止。

两版为了和手机 Wear Data Layer 通信，必须使用同一个 applicationId 和签名。因此不能在同一块手表上同时安装；互相覆盖就是升级或回退。当前“正式版”是功能正式版，仍为 debug 签名，不是商店发布签名。

正式版已做低功耗隔离：不含 `WAKE_LOCK` 权限，编译期也禁止进入高耗电实时交付诊断路径。

### 手机端 `mobile/`

接收手表 Data Layer 消息，再用 UDP 发给电脑。默认电脑端口 `9123`，发送间隔可选 `1/2/5/10/30 秒`，建议日常使用 `5 秒`。手机可以暂停/恢复向电脑发送，但不停止手表采样。

### Windows 端

- `pc-python/`：当前首选版本，Python + Tkinter，带 pytest，可打包为单文件 EXE。
- `pc-bridge/`：原 C# WinForms 版本，完整保留用于回退，不要删除。

两版协议相同，但不能同时监听 UDP `9123`。

## 4. 日常使用方法

1. 电脑启动 `VrcRealtimeHeartbeat-Python.exe`，确认显示“监听 9123”。
2. Windows 防火墙首次询问时允许专用网络访问。
3. 手机打开“心率中转站”，填写电脑局域网 IPv4 和 UDP `9123`，发送间隔选择 `5 秒`。
4. 手机点击“发送测试包”，确认电脑显示“链路测试通过”，手机收到电脑 ACK。
5. 手表打开“心率传输”，向下滑动并点击“开始传输”，首次运行授予心率和后台健康权限。
6. 可以直接返回表盘并息屏；ForegroundService 和 Exercise 会话应继续运行。
7. VRChat Action Menu 中开启 OSC。电脑默认向 `127.0.0.1:9000` 发送。
8. 正常时电脑约每 5 秒收到一份最新 BPM；这不代表手表内部只有 0.2 Hz 采样，Health Services 回调可以包含一批按 `sampleEpochMillis` 排列的真实样本。

停止时优先在手表正式版中点击“停止传输”。不要通过强制停止服务代替正常停止 Exercise 会话。

## 5. VRChat OSC 参数

主要参数：

```text
/avatar/parameters/HR_Tens    Int
/avatar/parameters/HR_Ones    Int
/avatar/parameters/HRValid    Bool
/avatar/parameters/HRPulse    Bool
```

- 两位数版本超过 99 时钳制为 99。
- `HRPulse` 由电脑按 BPM 本地生成，不依赖每个手表样本触发。
- 数据超时后 `HRValid=false`，Avatar 可显示 `--` 或 `NO SIGNAL`。
- 为兼容早期版本，同时发送 `HeartRate`、`HeartRateNormalized`、`HeartRateValid`。
- `phone_test` 和 `relay_test` 只用于 ACK/链路诊断，不会冒充真实心率写入 Avatar 参数。

## 6. 新电脑需要的环境

必需：

- Windows 10/11 x64
- Git
- Android Studio，包含 Android SDK、Platform Tools 和系统自带 JBR
- Android SDK Platform `36.1`
- Android SDK Build Tools `36.0.0`
- JDK 21 用于 Gradle；项目 Kotlin/JVM 目标为 Java 17
- Python 3.13 推荐；源码最低要求 Python 3.11
- Samsung Galaxy Watch6 与 Android 手机上的无线调试，仅在安装和导出报告时使用

Python 开发依赖：

```text
pytest >= 8, < 10
PyInstaller >= 6, < 7
```

可选：

- Visual Studio 或 Visual Studio Build Tools：仅在需要重新编译旧 C# 回退版时使用。
- VRChat：做 OSC 实收测试时需要。
- Unity 不属于本仓库工作范围。

## 7. 首次环境配置

在 Android Studio SDK Manager 安装：

- Android SDK Platform 36.1
- Android SDK Build-Tools 36.0.0
- Android SDK Platform-Tools
- Android SDK Command-line Tools

仓库根目录创建不提交 Git 的 `local.properties`：

```properties
sdk.dir=C:\\Users\\你的用户名\\AppData\\Local\\Android\\Sdk
```

PowerShell 当前会话设置 Java/SDK：

```powershell
$env:JAVA_HOME = '你的 Android Studio\jbr'
$env:ANDROID_SDK_ROOT = "$env:LOCALAPPDATA\Android\Sdk"
```

Python 环境：

```powershell
python -m venv .\pc-python\.venv
.\pc-python\.venv\Scripts\python.exe -m pip install --upgrade pip
.\pc-python\.venv\Scripts\python.exe -m pip install -r .\pc-python\requirements-dev.txt
```

电脑和手机应在可以互相访问的局域网中。VPN 可能改变路由或阻断手机到电脑 UDP；之前出现“息屏不发送”的一次现象实际是 VPN 导致，关闭 VPN 后恢复。电脑换网后必须在手机中更新电脑 IPv4。

## 8. 本地测试与构建命令

Android 全量回归：

```powershell
.\gradlew.bat `
  :app:testDiagnosticDebugUnitTest `
  :app:testProductionDebugUnitTest `
  :mobile:testDebugUnitTest `
  :app:assembleDiagnosticDebug `
  :app:assembleProductionDebug `
  :mobile:assembleDebug `
  :app:lintDiagnosticDebug `
  :app:lintProductionDebug `
  :mobile:lintDebug `
  --no-daemon
```

Python 测试与 EXE：

```powershell
.\pc-python\.venv\Scripts\python.exe -m pytest .\pc-python\tests -q -p no:cacheprovider
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\pc-python\Build-Exe.ps1 `
  -Python .\pc-python\.venv\Scripts\python.exe
```

旧 C# 回退版：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\pc-bridge\HeartRateBridge.ps1 -SelfTest
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\pc-bridge\WatchTestReport.ps1 -SelfTest
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\pc-bridge\Build-Exe.ps1
```

本轮已验证：Android 39 项单元测试 0 失败；Python 35 项 pytest 全部通过；三套 Android Lint 通过；两个 Watch APK、Phone APK、Python EXE 和旧 C# EXE 均构建成功。

## 9. 构建产物位置

```text
app/build/outputs/apk/diagnostic/debug/app-diagnostic-debug.apk
app/build/outputs/apk/production/debug/app-production-debug.apk
mobile/build/outputs/apk/debug/mobile-debug.apk
dist/windows-python/VrcRealtimeHeartbeat-Python.exe
dist/windows/VrcRealtimeHeartbeat.exe
```

`dist/`、APK、测试原始输出和设备报告被 `.gitignore` 排除，不会随源码分支上传。换机时应从 GitHub Actions Artifacts 下载，或在新电脑重新构建。

## 10. ADB 安装与测试注意事项

Wear OS 无线调试端口会变化，不能复用旧端口：

```powershell
$adb = "$env:ANDROID_SDK_ROOT\platform-tools\adb.exe"
& $adb devices -l
& $adb mdns services
& $adb connect WATCH_IP:CURRENT_PORT
```

安装正式版与手机端：

```powershell
& $adb -s WATCH_SERIAL install -r .\app\build\outputs\apk\production\debug\app-production-debug.apk
& $adb -s PHONE_SERIAL install -r .\mobile\build\outputs\apk\debug\mobile-debug.apk
```

正式息屏或续航测试期间不要持续运行 Logcat、轮询 dumpsys 或发送输入事件。测试结果应由手表本地持久化，测试结束后再用 ADB 导出。不要修改手表自动旋转；当前已恢复并验证 `accelerometer_rotation=0`、`user_rotation=0`。

## 11. 当前验证状态与下一步

已经完成：

- ExerciseClient + health ForegroundService 真机后台采样。
- 返回表盘、Activity stop、Ambient、息屏后 Exercise 会话保持。
- 20 分钟佩戴低功耗测试：1194 个真实样本，最大采样间隔 2005 ms，息屏交付 P95 4056 ms，最长无 callback 6016 ms，无 WakeLock、服务重启、错误或崩溃。
- 修复 4990–4999 ms 批次被严格 5000 ms 节流跳过的问题。
- 移除 Watch/Phone 重复 Data Layer runtime listener。
- 正式版 Watch APK 已在 SM-R960 安装并真机检查入口、界面、按钮和无 WakeLock 权限。
- Python Windows GUI、UDP ACK、OSC、超时、数字拆分和 HRPulse。

换机后按顺序继续：

1. 记录当前正式版 5–10 分钟息屏冒烟测试结果：电脑是否持续更新、返回表盘是否继续、是否出现断线。
2. 做正常佩戴至少 60 分钟正式续航测试，记录开始/结束电量、发送间隔、手机/电脑断档。
3. 在 VRChat 中实收 `HR_Tens`、`HR_Ones`、`HRValid`、`HRPulse`。
4. 决定是否仍要继续最初的 Watch BLE GATT 直连电脑阶段；这部分尚未开始。
5. 正式发布前配置稳定的 Android release signing；当前 APK 是 debug 签名。

## 12. 不要重复或误改

- 不要回到 MeasureClient 作为最终方案；它息屏后停止供数。
- 不要重新证明 ExerciseClient 能否息屏采样；这已经真机验证。
- 不要在正式版恢复长期 WakeLock 或直接传感器注册。
- 不要删除测试版、报告记录器、Python 版或旧 C# 回退版。
- 不要用 callback 接收时间代替 `sampleEpochMillis` 判断真实采样连续性。
- 不要把链路测试包或固定 72 BPM 当成真实心率。
- 不要假设 ADB 是运行链路的一部分；ADB 断线不应影响正式采集和传输。
- 不要把当前手机中转链路称为 BLE 直连 Windows。

更完整的历史、真实测试数据和问题演变见 `docs/CODEX_HANDOFF.md`；功耗分析见 `docs/POWER_OPTIMIZATION.md`；测试流程见 `BACKGROUND_TEST_GUIDE.md`。

## 13. 源码地图：改东西前先找对位置

### Watch / Wear OS：`app/`

| 路径 | 作用 | 是否属于正式版 |
|---|---|---|
| `app/src/main/.../MainActivity.kt` | 测试版入口、权限、MeasureClient/ExerciseClient 测试 UI、息屏/续航测试入口 | 否，diagnostic 专用 |
| `app/src/production/.../ProductionMainActivity.kt` | 正式版入口和精简 UI，固定选择 ExerciseClient | 是 |
| `app/src/main/.../ExerciseForegroundService.kt` | ExerciseClient、health FGS、callback、批次解析、Data Layer 转发、服务恢复 | 是，核心链路 |
| `app/src/main/.../BackgroundTestRecorder.kt` | 本地持久化测试事件和报告，含 sample/交付/电量统计 | 否，测试能力 |
| `app/src/main/.../HeartRateMeasureManager.kt` | MeasureClient 探针注册与取消 | 否，历史探针 |
| `app/src/main/.../HeartRateViewModel.kt` | 测试版状态机和测试操作 | 否，正式版不直接使用测试 UI |
| `app/src/main/.../WearHeartRateRelay.kt` | 构造并发送 Watch→Phone Data Layer 消息 | 是 |
| `app/src/main/.../RelayProtocol.kt` | Data Layer capability、path、协议常量 | 是 |
| `app/src/main/.../RelayIntervalPolicy.kt` | 防止 4990–4999ms 样本被严格 5000ms 门槛误跳过 | 是 |
| `app/src/production/AndroidManifest.xml` | 正式版删除 `RelayTestActivity`，移除 `WAKE_LOCK` | 是 |
| `app/build.gradle.kts` | `diagnostic`/`production` flavor 与 launcher 配置 | 是 |

正式版不要把测试 UI 复制进去。若修改 `ExerciseForegroundService.kt`，必须同时考虑正常正式传输、测试版息屏测试、服务销毁/恢复、异常结束和 WakeLock 释放。

### Phone / Android：`mobile/`

| 路径 | 作用 |
|---|---|
| `MobileMainActivity.kt` | 手机 UI：电脑 IP、UDP 端口、发送间隔、暂停/恢复、链路状态 |
| `PhoneRelayRepository.kt` | 接收手表 Data Layer、去重、最新样本合并、UDP 转发、ACK、重试 |
| `PhoneRelayApplication.kt` | 进程内 Data Layer listener 初始化 |
| `PhoneRelayListenerService.kt` | 后台 Data Layer listener；不要再额外注册重复 runtime listener |
| `RelayIntervalPolicy.kt` | 手机转发节流的边界容差 |
| `src/main/res/` | 手机图标和 UI 资源 |

手机端实时心率不能积累无界重试队列；电脑离线时应保留最新包并替换旧等待包。暂停“发往电脑”不等于停止手表采样。

### Windows：`pc-python/` 和 `pc-bridge/`

Python 首选端：

- `vrc_heartbeat/protocol.py`：JSON 严格解析、BPM 范围、序号、ACK、延迟、超时和两位数拆分。
- `vrc_heartbeat/engine.py`：纯状态机；真实 `heart_rate` 才进入 OSC，电脑本地产生 `HRPulse`。
- `vrc_heartbeat/osc.py`：OSC int/float/bool 编码。
- `vrc_heartbeat/runtime.py`：UDP 接收线程、ACK、OSC 发送、异常隔离、动态开关。
- `vrc_heartbeat/app.py`：Tkinter Windows UI、DPI 感知、设置保存、运行日志。
- `tests/`：pytest 测试，不要删掉用于回归的 UDP 回环测试。
- `Build-Exe.ps1`：pytest + 源码自检 + PyInstaller 单文件打包。

旧 C# 端：

- `pc-bridge/src/HeartRateBridge/Program.cs`：原有 WinForms 逻辑。
- `pc-bridge/Build-Exe.ps1`：寻找 Roslyn `csc.exe` 或系统 .NET Framework csc 编译。
- `pc-bridge/HeartRateBridge.ps1`：早期 PowerShell 版本，继续保留作诊断/回退。
- `pc-bridge/WatchTestReport.ps1`：从手表导出并复算本地测试报告。

## 14. 三段时间戳和数据含义

不要只看电脑收到包的时间判断手表采样是否连续。当前数据至少有这些时间：

```text
sampleEpochMillis
    手表实际样本时间。用于判断采样间隔，不能被 callback 到达时间替代。

callbackReceiveEpochMillis
    手表 Exercise callback 收到这一批样本的时间。手表报告用
    callbackReceiveEpochMillis - sampleEpochMillis 统计交付延迟。

phoneReceivedEpochMillis
    手机收到 Watch Data Layer 消息的时间。手机转发前写入。

pcEpochMillis
    Windows 生成 pc_ack 的时间。PC 端可用当前接收时间 - sampleEpochMillis
    做端到端年龄/延迟估计。
```

典型真实心率 UDP JSON（字段可能比示例更多）：

```json
{
  "version": 1,
  "type": "heart_rate",
  "sequence": 12345,
  "sampleEpochMillis": 1780000000123,
  "phoneReceivedEpochMillis": 1780000000456,
  "phoneForwardIntervalSeconds": 5,
  "bpm": 72,
  "rawBpm": 72.0,
  "accuracy": "HIGH",
  "watchBatteryPercent": 94,
  "watchScreenInteractive": false
}
```

PC 回执格式：

```json
{"type":"pc_ack","sequence":12345,"pcEpochMillis":1780000000789}
```

诊断包的 `type` 是 `phone_test` 或 `relay_test`，即使里面写了 `bpm:72` 也绝不能进入 Avatar。Python/C# 两端都只把 `type=heart_rate` 当作真实心率。

当前默认超时公式是 `max(10 秒, phoneForwardIntervalSeconds × 2.5)`：5 秒转发档约 12.5 秒，30 秒档约 75 秒。超时后发送 `HRValid=false` 和兼容参数的无效状态，并停止心跳脉冲。

息屏时可能出现“采样连续但交付不实时”：手表仍然有连续的 `sampleEpochMillis`，但系统把 callback 批量缓存到亮屏后才交付。必须用手表报告中的批次数、息屏样本在亮屏后交付数和 delivery latency 判断，不能只看 PC 是否有包。

## 15. 换机后的最短可用路径

如果目标只是尽快在主力电脑上继续开发，按下面顺序，不必先改代码：

### A. 获取源码

```powershell
git clone https://github.com/RICHARDwuxiaofei/Vrc_realtimeheartbeat.git
Set-Location .\Vrc_realtimeheartbeat
git fetch origin
git switch --track origin/codex/low-power-wearos
git status
```

如果已经 clone 过：

```powershell
git fetch origin
git switch codex/low-power-wearos
git pull --ff-only
```

此时 `git status` 可以有你自己新的修改，但不要在未确认前使用 `reset --hard` 或 `checkout --`。

### B. 先只验证 Python 端

```powershell
Set-Location .\pc-python
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install --upgrade pip
.\.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
.\.venv\Scripts\python.exe -m pytest .\tests -q -p no:cacheprovider
Set-Location ..
```

期望结果是所有测试通过（当前基线为 `35 passed`，未来测试数增加属于正常情况，但不能有失败）。然后运行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\pc-python\Build-Exe.ps1 `
  -Python .\pc-python\.venv\Scripts\python.exe
```

双击 `dist\windows-python\VrcRealtimeHeartbeat-Python.exe`。若端口 9123 已被旧 C# 端占用，先关闭另一个程序，不能同时开两个接收器。

### C. 再配置 Android

1. Android Studio 打开仓库根目录。
2. SDK Manager 安装 Platform 36.1、Build Tools 36.0.0、Platform-Tools、Command-line Tools。
3. 检查 Android Studio 的 Gradle JDK 使用 bundled JBR；命令行 `java -version` 必须可用。
4. 设置 `local.properties` 的 `sdk.dir`，该文件已被 Git 忽略。
5. 运行第 8 节的 Android 全量命令。

完成后再安装 APK。换电脑时手机和手表上的旧 APK 可以继续运行，不需要为了换 PC 先卸载它们。

### D. 最后接入设备

1. 电脑、手机、手表连到可以互相访问的局域网。
2. 关闭 VPN，或确认 VPN 允许局域网访问。
3. 手机里填写新电脑的局域网 IPv4，端口填 9123。
4. 先发手机测试包确认 PC ACK，再启动手表正式版。
5. 首次正式版启动时只处理权限；不需要通过 ADB 保持程序前台。

## 16. 验收测试顺序和合格标准

### 16.1 源码层

- Python pytest 全部通过。
- Android diagnostic/production/mobile 单元测试、Lint、assemble 全部通过。
- C# `HeartRateBridge.ps1 -SelfTest` 和 `WatchTestReport.ps1 -SelfTest` 通过。
- `git diff --check` 无空白错误。

### 16.2 5–10 分钟冒烟

先不做长续航，确认基本链路：

- 手机测试包在 Python UI 显示“链路测试通过”，且手机 ACK 计数增加。
- 手表正式版点击开始后显示 BPM，手机显示已连接，电脑显示真实数据。
- 返回表盘、Ambient、息屏后不手动点亮手表，观察电脑端是否继续收到数据。
- 亮屏后检查是持续更新还是批量跳变；如果是批量补发要记录，不要直接判为 PC bug。
- 点击手表“停止传输”，确认 Exercise 正常结束。

### 16.3 正式 60 分钟续航

只有佩戴且未充电的结果才能作为正式续航参考：

- 开始前记录手表电量、手机电量、是否佩戴、是否充电、电脑 IP、转发间隔。
- 目标 60 分钟内不运行持续 Logcat、dumpsys、截图或模拟输入。
- 结束后等尾部数据排空，再导出手表 JSON/TXT/events JSONL。
- 同时查看 `sampleEpochMillis` 连续性、callback 延迟、手机转发间隔、PC ACK/丢包和电量变化。
- 电量按 1% 显示时，`100%→100%` 只能写“未观察到 1% 级变化”，不能写“零耗电”。

### 16.4 VRChat 实收

1. VRChat Action Menu 开启 OSC。
2. PC Python UI 保持“发送到 VRChat OSC”勾选。
3. 确认 Avatar 端参数名和类型完全一致：Tens/Ones 是 Int，Valid/Pulse 是 Bool。
4. 先用真实静息心率观察两位数，再暂停手机转发或断开网络，确认一段时间后 `HRValid=false`。
5. 不要把 `phone_test` 当成 Avatar 心率验收；它只验证网络和 ACK。

## 17. 常见故障排查

| 现象 | 优先检查 | 处理 |
|---|---|---|
| ADB 以前能连，现在不能连 | 手表无线调试端口变化 | `adb mdns services` 找 `_adb-tls-connect._tcp`，再 `adb connect IP:端口` |
| 手机显示电脑地址但无 ACK | PC 是否监听 9123、Windows 防火墙、VPN、电脑 IP 是否变 | 先本机发测试包；允许专用网络；手机更新新 IPv4 |
| Python 启动失败，提示端口占用 | 旧 C# 版或另一个 Python 进程占用 9123 | 关闭另一个桥接器；不要两个程序同时监听 |
| 手表有 BPM，手机没样本 | Watch/Phone Data Layer capability、手机蓝牙/附近设备、手机应用是否被系统限制 | 先打开手机伴侣，再看“手表已连接”；不要先怀疑 UDP |
| 手机收到但 PC 不更新 | 手机目标 IP/端口、VPN、局域网隔离 | 确认 PC 和手机同网段；手机端口保持 9123 |
| 息屏期间电脑长时间不更新 | 可能是手表 callback 缓存，不一定是采样停止 | 亮屏后看是否批量补发；用 `sampleEpochMillis` 和本地报告判定 |
| BPM 后变成 `--`/无效 | PC 真实包超时 | 检查手机发送间隔和 `phoneForwardIntervalSeconds`，确认没有暂停 |
| Avatar 数字不变 | OSC 开关、VRChat OSC、参数拼写和类型 | 先确认 PC 日志有真实 `heart_rate`，再检查 Avatar 参数 |
| 手表屏幕方向被改变 | 不要用 ADB 改旋转设置 | 只读检查 `accelerometer_rotation`/`user_rotation`，目标保持 `0/0` |
| 正式版出现测试按钮 | 安装了 diagnostic APK 或 launcher 未覆盖 | 再安装 production APK；两个 flavor 不能并存 |

## 18. Git 和本地文件规则

### 应该保留并提交的

- `app/`、`mobile/`、`pc-python/`、`pc-bridge/` 源码
- `docs/`、README、测试脚本、GitHub Actions
- `pc-bridge/assets/` 和源码使用的图标

### 默认不提交的

- `local.properties`
- `*.jks`、`*.keystore`、签名配置、`.env*`
- `dist/` 中的 APK/EXE/ZIP
- `app/build/`、`mobile/build/`、`pc-python/build/`、`.venv/`
- `outputs/`、`artifacts/`、测试报告和临时截图

查看当前状态：

```powershell
git status --short
git log -5 --oneline --decorate
git branch -vv
```

本次手动整理只修改本文件，按要求不提交、不上传。若之后要同步到 GitHub，先检查 `git diff` 和 `git diff --check`，确认没有设备报告或密钥，再单独提交文档/代码。

不要为了“清理仓库”删除别人留下的未跟踪目录；先确认它们是否是用户要保留的测试资料。不要用 `git reset --hard`、`git clean -fd` 作为普通排查手段。

## 19. 当前没有完成的事情

这些是后续任务，不要在交接时写成已经完成：

1. 至少 60 分钟正常佩戴正式续航的可重复结果。
2. 正式版在真实佩戴、返回表盘、息屏状态下的长时间稳定交付统计。
3. VRChat 中四个新参数的实收验收和 Avatar 端断线显示联调。
4. 最初规划的手表标准 BLE GATT 外设：Service `0x180D`、Characteristic `0x2A37`、固定 72 BPM 广播、Windows 扫描/订阅/重连和息屏发送。
5. BLE 直连稳定后再替换当前“手表 Data Layer → 手机 UDP”链路；不要现在提前删除手机中转实现。
6. Android 稳定 release signing；当前构建产物是 debug 签名，正式功能版不等于商店签名版。
7. Python 接收器后续可继续优化安装包、日志导出和 VRChat 断线提示，但先不要牺牲已经通过的协议测试。

## 20. 换机完成判定

新电脑满足以下条件，就可以认为“开发环境接上了”：

- 能切到 `codex/low-power-wearos`，且能读到本 handoff。
- Python 测试全部通过，能启动 Python EXE 并监听 9123。
- Android 两个 Watch flavor 和 Phone debug 能成功 assemble。
- 手机上测试包能收到 PC ACK。
- 手表正式版能启动，自动旋转没有被修改，开始/停止按钮存在。
- 至少完成一次 5–10 分钟息屏冒烟，再决定是否进行 60 分钟续航。

做到这里以后，下一次开发可以直接进入“真实佩戴 60 分钟数据分析”或“BLE GATT 阶段”，无需重新搭建三端基础链路。
