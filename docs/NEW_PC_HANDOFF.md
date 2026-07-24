# 换机继续开发摘要

更新时间：2026-07-25

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
- 当前候选分支：`codex/v1.1.0-diagnostics-ready`
- 正式 Release `v1.0.0` 仍是上一版；`v1.1.0` 当前只作为候选源码和构建产物，未创建正式 Release。
- `main` 仍早于当前候选功能。换机后先取得上面的候选分支，不要从旧 `main` 重做。

新电脑获取代码：

```powershell
git clone https://github.com/RICHARDwuxiaofei/Vrc_realtimeheartbeat.git
cd Vrc_realtimeheartbeat
git switch codex/v1.1.0-diagnostics-ready
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
- `productionDebug`：日常正式版，固定使用 ExerciseClient，并提供“1 秒实时 / 5 秒省电 / 10 秒超省电”三个启动前可选档位。

两版为了和手机 Wear Data Layer 通信，必须使用同一个 applicationId 和签名。因此不能在同一块手表上同时安装；互相覆盖就是升级或回退。当前“正式版”是功能正式版，仍为 debug 签名，不是商店发布签名。

正式版默认使用 5 秒省电档；10 秒超省电档进一步减少 Data Layer 通信。只有用户明确选择 1 秒实时档时才注册约 1 Hz 直接心率传感器并使用有界滚动 `PARTIAL_WAKE_LOCK`。停止、异常、服务销毁和 Exercise 外部结束都会释放该锁。

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
4. 需要排查时先在电脑开启“诊断模式”，再由手机点击“手机 → 电脑一键诊断”，确认电脑收到 `phone_diagnostic` 且手机得到匹配 ACK。
5. 手表打开“心率传输”，向下滑动并点击“开始传输”，首次运行授予心率和后台健康权限。
6. 可以直接返回表盘并息屏；ForegroundService 和 Exercise 会话应继续运行。
7. VRChat Action Menu 中开启 OSC。电脑默认向 `127.0.0.1:9000` 发送。
8. 默认档电脑在 BPM 变化时约每 5 秒收到一份最新值，稳定值约每 10 秒保活；10 秒档对应约 10/20 秒。这不代表手表内部按这个频率采样，Health Services 回调可以包含一批按 `sampleEpochMillis` 排列的真实样本。

停止时优先在手表正式版中点击“停止传输”。不要通过强制停止服务代替正常停止 Exercise 会话。

## 5. VRChat OSC 参数

主要参数：

```text
/avatar/parameters/HR_Value   Int
/avatar/parameters/HR_Hundreds Int
/avatar/parameters/HR_Tens    Int
/avatar/parameters/HR_Ones    Int
/avatar/parameters/HRValid    Bool
/avatar/parameters/HRPulse    Bool
```

- `HR_Value` 是钳制到 `0..999` 的完整 BPM；后三项分别是百位、十位和个位。
- 四个显示参数按 `HR_Value → HR_Hundreds → HR_Tens → HR_Ones` 顺序发送，类型均为 OSC Int32。
- `HRPulse` 由电脑按 BPM 本地生成，不依赖每个手表样本触发。
- 数据超时后 `HRValid=false`，Avatar 可显示 `--` 或 `NO SIGNAL`。
- 为兼容早期版本，同时发送 `HeartRate`、`HeartRateNormalized`、`HeartRateValid`。
- `phone_test` 和 `relay_test` 只用于 ACK/链路诊断，不会冒充真实心率写入 Avatar 参数。

## 6. 跨端诊断模式原理

电脑是诊断模式的主控制端，状态传播如下：

```text
PC 勾选诊断模式
  → PC 在每个 pc_ack 中返回 diagnosticMode=true
  → Phone 收到变化后更新诊断 UI
  → Phone 只在状态变化时发送 /hr/control/v1
  → Watch 开始附加扩展字段
```

关闭时走同一条反向流程。Watch 或 Phone 进程重启后默认回到普通模式；下一次有效 PC ACK 会重新同步。Phone UI 允许临时切换以便排查，但只要 PC 继续回 ACK，最终以 PC 开关为准。

普通 `heart_rate` 包只保留转发和超时判断必需字段：

| 字段 | 用途 |
|---|---|
| `version/type/sequence` | 协议版本、包类型、去重和 ACK 匹配 |
| `sampleEpochMillis/bpm` | 真实采样时间与心率 |
| `watchRelayIntervalSeconds` | 让手机与电脑采用不会误判超时的有效间隔 |
| `watchAckRequested` | 正式版只按低频策略请求手表 ACK |
| `phoneForwardIntervalSeconds` | 电脑计算动态超时 |

诊断模式才增加 `sessionId`、原始 BPM、精度、手表电量/屏幕/发送模式、两端接收时间、手机局域网 IP、网络类型和 VPN 状态。手机在普通模式会再次主动删除这组字段，即使收到旧手表版本发来的扩展字段也不会继续传给电脑。

手机到电脑使用单工作线程和两个有界待发槽：

- 连续心率槽始终只保留最新样本，电脑离线时不会形成无界积压。
- 手动诊断槽独立保留并优先发送，不会被下一份心率覆盖。
- UDP socket 连接到配置的目标 IP/端口后才等待匹配序号 ACK，其他来源的数据报不能完成该请求。

## 7. CSV、曲线和内存原理

- 普通模式不创建历史队列、不写 CSV，曲线和统计面板不运行。
- 第一次开启诊断模式时创建 `%LOCALAPPDATA%\VrcRealtimeHeartbeat\diagnostic-current.csv`；同一次程序运行内可暂停后继续追加。
- 写句柄每行 `flush`，读取使用独立句柄，因此 Windows 上可以边写边读。
- 曲线默认 1 分钟，滑轨限制为 1–10 分钟；每次刷新从文件尾部反向读取，读到窗口边界立即停止，不随整次诊断时长线性变慢。
- “导出 CSV”只是把当前内部文件复制到用户选择的位置。程序不会自动导出；正常退出时若有未导出行会提示。
- 下一次程序启动并首次开启诊断时会重建内部临时 CSV。需要保留的数据必须在退出前手动导出。

## 8. 新电脑需要的环境

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

## 9. 首次环境配置

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

## 10. 本地测试与构建命令

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

发布分支的最低门禁：

1. Python pytest、模块自检、打包 EXE 自检全部通过。
2. 手表 diagnostic/production 与手机单元测试全部通过。
3. 三个 Android 变体 Lint、assemble 全部通过。
4. C# 回退桥和报告脚本自检通过。
5. `git diff --check` 无空白错误，工作区只包含本次预期改动。

2026-07-25 候选分支复核结果：

- Python：52 项 pytest 全部通过；源码入口自检、Tk 诊断模式/滑轨冒烟、PyInstaller 单文件 EXE 构建与打包后 `--self-test` 通过。
- Android：手表 diagnostic/production 与手机共执行 51 项单元测试，0 失败、0 error、0 skip。
- Android Lint：三个变体均为 0 error；剩余 warning 只有“依赖存在更新版本”的提示，候选分支没有为了追新而变更运行时依赖。
- 构建：两个 Watch APK、Phone APK、Python EXE、C# 回退 EXE 均成功；三套 APK 元数据均为 `versionName=1.1.0`、`versionCode=2`。
- 回退工具：`HeartRateBridge.ps1 -SelfTest` 与 `WatchTestReport.ps1 -SelfTest` 通过。

自动化通过不等于 Galaxy Watch、手机、VRChat 的真机实收已经完成。命令行出现的 SDK XML 3/4 版本提示来自本机 Android Studio 与 command-line tools 版本差异，本轮未影响测试、Lint 或构建；换机时应让两者保持同一 Android Studio 发布周期。

## 11. 构建产物位置

```text
app/build/outputs/apk/diagnostic/debug/app-diagnostic-debug.apk
app/build/outputs/apk/production/debug/app-production-debug.apk
mobile/build/outputs/apk/debug/mobile-debug.apk
dist/windows-python/VrcRealtimeHeartbeat-Python.exe
dist/windows/VrcRealtimeHeartbeat.exe
```

`dist/`、APK、测试原始输出和设备报告被 `.gitignore` 排除，不会随源码分支上传。换机时应从 GitHub Actions Artifacts 下载，或在新电脑重新构建。

## 12. ADB 安装与测试注意事项

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

## 13. 当前验证状态与下一步

已经完成：

- ExerciseClient + health ForegroundService 真机后台采样。
- 返回表盘、Activity stop、Ambient、息屏后 Exercise 会话保持。
- 20 分钟佩戴低功耗测试：1194 个真实样本，最大采样间隔 2005 ms，息屏交付 P95 4056 ms，最长无 callback 6016 ms，无 WakeLock、服务重启、错误或崩溃。
- 修复 4990–4999 ms 批次被严格 5000 ms 节流跳过的问题。
- 移除 Watch/Phone 重复 Data Layer runtime listener。
- 旧正式版 Watch APK 已在 SM-R960 真机验证 5 秒省电链路；新增的 1 秒/5 秒选择尚未安装到设备验证。
- Python Windows GUI、UDP ACK、OSC、超时、三位数拆分和 HRPulse。
- v1.1.0 Python GUI 已加入默认关闭的按需诊断模式、1–10 分钟可调曲线（默认 1 分钟）、最低/最高/平均 BPM、Avatar 参数测试、边写边读的内部诊断 CSV、仅手动用户导出、GitHub 更新检查、配对二维码和电脑诊断；手机端已加入扫码配对和完整诊断模式。电脑通过 UDP ACK 控制手机，手机只在模式变化时通知手表，普通包不携带原始 BPM、精度、电量、屏幕状态等扩展字段。
- 2026-07-25 代码复核修正了长时 CSV 全量扫描、诊断请求可能被心率覆盖、普通模式遗留 `sessionId`、UDP ACK 来源未绑定和正式版重复警告写日志的问题，并增加对应回归测试。

换机后按顺序继续：

1. 分别对 1 秒实时档和 5 秒省电档做 5–10 分钟息屏冒烟测试，确认手表、手机、电脑显示的频率一致。
2. 两个档位分别做正常佩戴至少 60 分钟测试，记录开始/结束电量、发送间隔、手机/电脑断档。
3. 在 VRChat 中实收 `HR_Value`、`HR_Hundreds`、`HR_Tens`、`HR_Ones`、`HRValid`、`HRPulse`。
4. 决定是否仍要继续最初的 Watch BLE GATT 直连电脑阶段；这部分尚未开始。
5. 正式发布前配置稳定的 Android release signing；当前 APK 是 debug 签名。

## 14. 功耗设计原则

- 主要耗电来自连续 PPG/Exercise 会话，应用侧优化只能减少 CPU、闪存和无线通信，不能让持续心率达到系统低频全天监测的水平。
- `1 秒实时`明确属于高功耗模式：直接心率传感器、零报告延迟和有界 WakeLock 只在用户选择该档时启用。
- `5/10 秒`档必须保持 Health Services 批量交付、无直接传感器、无手动 WakeLock。
- 正式版热路径不写逐样本日志；同一 warning 最多每分钟落一次。诊断版保留完整记录用于复现。
- 不要为了 UI 数据重新引入普通模式历史缓存；历史、原始字段和 CSV 都必须继续受诊断开关控制。
- 发送失败时保持“在途 + 最新心率 + 最新手动诊断”的有界结构，不能恢复无界重试队列。

## 15. 不要重复或误改

- 不要回到 MeasureClient 作为最终方案；它息屏后停止供数。
- 不要重新证明 ExerciseClient 能否息屏采样；这已经真机验证。
- 不要把 1 秒实时档改成无界 WakeLock；它必须保持用户显式选择、有界续租并覆盖所有释放路径。
- 不要删除测试版、报告记录器、Python 版或旧 C# 回退版。
- 不要用 callback 接收时间代替 `sampleEpochMillis` 判断真实采样连续性。
- 不要把链路测试包或固定 72 BPM 当成真实心率。
- 不要假设 ADB 是运行链路的一部分；ADB 断线不应影响正式采集和传输。
- 不要把当前手机中转链路称为 BLE 直连 Windows。

更完整的历史、真实测试数据和问题演变见 `docs/CODEX_HANDOFF.md`；功耗分析见 `docs/POWER_OPTIMIZATION.md`；测试流程见 `BACKGROUND_TEST_GUIDE.md`。
