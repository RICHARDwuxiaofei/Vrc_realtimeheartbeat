# 换机继续开发摘要

更新时间：2026-07-21

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
- `productionDebug`：日常正式版，固定使用 ExerciseClient，并提供“1 秒实时 / 5 秒省电”两个启动前可选档位。

两版为了和手机 Wear Data Layer 通信，必须使用同一个 applicationId 和签名。因此不能在同一块手表上同时安装；互相覆盖就是升级或回退。当前“正式版”是功能正式版，仍为 debug 签名，不是商店发布签名。

正式版默认使用 5 秒省电档；只有用户明确选择 1 秒实时档时才注册约 1 Hz 直接心率传感器并使用有界滚动 `PARTIAL_WAKE_LOCK`。停止、异常、服务销毁和 Exercise 外部结束都会释放该锁。

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
- 旧正式版 Watch APK 已在 SM-R960 真机验证 5 秒省电链路；新增的 1 秒/5 秒选择尚未安装到设备验证。
- Python Windows GUI、UDP ACK、OSC、超时、三位数拆分和 HRPulse。

换机后按顺序继续：

1. 分别对 1 秒实时档和 5 秒省电档做 5–10 分钟息屏冒烟测试，确认手表、手机、电脑显示的频率一致。
2. 两个档位分别做正常佩戴至少 60 分钟测试，记录开始/结束电量、发送间隔、手机/电脑断档。
3. 在 VRChat 中实收 `HR_Value`、`HR_Hundreds`、`HR_Tens`、`HR_Ones`、`HRValid`、`HRPulse`。
4. 决定是否仍要继续最初的 Watch BLE GATT 直连电脑阶段；这部分尚未开始。
5. 正式发布前配置稳定的 Android release signing；当前 APK 是 debug 签名。

## 12. 不要重复或误改

- 不要回到 MeasureClient 作为最终方案；它息屏后停止供数。
- 不要重新证明 ExerciseClient 能否息屏采样；这已经真机验证。
- 不要把 1 秒实时档改成无界 WakeLock；它必须保持用户显式选择、有界续租并覆盖所有释放路径。
- 不要删除测试版、报告记录器、Python 版或旧 C# 回退版。
- 不要用 callback 接收时间代替 `sampleEpochMillis` 判断真实采样连续性。
- 不要把链路测试包或固定 72 BPM 当成真实心率。
- 不要假设 ADB 是运行链路的一部分；ADB 断线不应影响正式采集和传输。
- 不要把当前手机中转链路称为 BLE 直连 Windows。

更完整的历史、真实测试数据和问题演变见 `docs/CODEX_HANDOFF.md`；功耗分析见 `docs/POWER_OPTIMIZATION.md`；测试流程见 `BACKGROUND_TEST_GUIDE.md`。
