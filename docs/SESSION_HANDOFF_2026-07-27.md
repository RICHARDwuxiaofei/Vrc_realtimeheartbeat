# 2026-07-27 模拟心率与酒店网络联调交接

> 最新未提交开发状态请先阅读 [`SESSION_HANDOFF_2026-07-28.md`](SESSION_HANDOFF_2026-07-28.md)。本文件保留 `v1.1.0` 发布与 7 月 27 日联调历史。

> Windows EXE 的 `--self-test` 卡住问题已定位并完成复核。全部本地与 GitHub Actions 门禁均已通过，PR #5 已合并，`v1.1.0` 正式 Release 已发布。

## 1. 用户本轮目标

1. 测试酒店 Wi-Fi 是否能连接 Galaxy Watch 的无线 ADB。
2. 手机通过 USB ADB 连接后，把手机也纳入联调。
3. 在手表诊断版增加无需传感器的 60–80 BPM 波动心率。
4. 真实跑一遍 Watch → Phone → PC → Phone → Watch，并执行全部本地测试。

## 2. 仓库与 Git 状态

- 仓库：`D:\CODE\Vrc_realtimeheartbeat`
- 开发分支：`codex/v1.1.0-diagnostics-ready`
- 分支最终提交：`d473642eebe54e9b8bf2561c196faa62c355ac89`
- PR [#5](https://github.com/RICHARDwuxiaofei/Vrc_realtimeheartbeat/pull/5) 已合并到 `main`。
- `main` 合并提交：`ed7ca9d5d9d6d83b2423527828ae468c6d9db7b0`
- 正式 Release：[`v1.1.0`](https://github.com/RICHARDwuxiaofei/Vrc_realtimeheartbeat/releases/tag/v1.1.0)
- 本轮源码、用户版 README、APK、EXE、ZIP 与校验表均已上传 GitHub。

开始接手时先运行：

```powershell
Set-Location D:\CODE\Vrc_realtimeheartbeat
git status --short
git diff --check
git diff --stat
```

## 3. ADB 与设备状态

Android SDK ADB：

```text
C:\Users\wrq18\AppData\Local\Android\Sdk\platform-tools\adb.exe
```

本轮已确认：

```text
R5CX81QGFAV          SM-S928B 手机，USB ADB
10.25.24.253:43019   SM-R960 Galaxy Watch6，酒店 Wi-Fi ADB
```

酒店网络结论：

- `10.25.24.253:43019` TCP 可达。
- `adb connect 10.25.24.253:43019` 成功。
- 酒店 AP 没有阻止电脑到手表的 ADB，也没有阻止手机到电脑的 UDP。
- 手表还会通过 mDNS 出现第二个序列，所有自动化必须固定使用用户给出的 `10.25.24.253:43019`，避免 `more than one device`。

已安装：

- 手表：本轮 `app-diagnostic-debug.apk`，版本 `1.1.0 (2)`，覆盖了此前的 `1.0.0`。
- 手机：本轮 `mobile-debug.apk`，版本 `1.1.0 (2)`。
- 手机电脑目标已从旧地址 `192.168.100.139:9123` 改为酒店电脑 `10.25.25.175:9123`，发送间隔仍为 1 秒。
- 手表模拟器已通过 UI 停止，停止日志为 `SIMULATED_HEART_RATE_STOPPED sentCount=135`，停止后没有新发送。

## 4. 已实现功能

### 4.1 仅诊断版存在的手表模拟器

新增：

```text
app/src/diagnostic/java/.../DiagnosticHeartRateSimulator.kt
app/src/diagnostic/java/.../SimulatedHeartRateGenerator.kt
app/src/testDiagnostic/java/.../SimulatedHeartRateGeneratorTest.kt
```

并把诊断入口：

```text
app/src/main/java/.../MainActivity.kt
```

移动到：

```text
app/src/diagnostic/java/.../MainActivity.kt
```

因此 production 源集不会编译 `MainActivity`、模拟器或生成器。已检查 Kotlin 编译目录：

- production：没有 `MainActivity*`、`DiagnosticHeartRateSimulator*`、`SimulatedHeartRateGenerator*`。
- diagnostic：包含上述模拟器类。

模拟算法：

- 初值在 68–72 BPM。
- 每秒随机变化 -2 到 +2。
- 在 60 和 80 边界反弹，不会越界。
- 每 5 个样本请求一次 Watch ACK。
- 手表 UI 明确显示“模拟链路（非传感器）”，可手动开始/停止，并显示当前 BPM 和累计发送数。
- 启动真实测量前会先停止模拟器。

### 4.2 永久模拟标记

模拟数据刻意使用 `type=heart_rate`，所以会走真实的手机转发、电脑处理和 OSC 路径。为避免被误认成传感器数据，包始终携带：

```json
{
  "type": "heart_rate",
  "simulated": true,
  "source": "watch_diagnostic_simulator"
}
```

这两个标记不是可裁剪的扩展诊断字段；手机即使关闭诊断模式也必须保留。

三端行为：

- Watch：只在 diagnostic flavor 提供模拟入口。
- Phone：状态增加 `simulated`，主卡片、链路状态和诊断面板显示“模拟（非传感器）”。
- PC：`HeartRatePacket.is_simulated` 严格只接受 JSON 布尔 `true`；界面用黄色显示“模拟心率（非传感器）”。
- CSV：新增 `simulated` 列。
- OSC：模拟数据仍进入 OSC，这是该测试模式验证完整 Avatar 链路的目的。

## 5. 真机联调证据

### 第一轮：Watch → Phone

- 手表 UI 在约 15 秒内从 60–80 BPM 生成并发送 15 条。
- 手表发现附近节点 `Richard 的 S24 Ultra`。
- 每条均记录 `PHONE_RELAY_MESSAGE_QUEUED`。
- 手机收到后尝试向旧电脑地址发 UDP，因电脑未监听而超时；这证明 Watch → Phone 已成立。

### 第二轮：Watch → Phone → PC → Phone → Watch

执行前将手机目标改为当前电脑：

```text
10.25.25.175:9123
```

电脑使用仓库 `BridgeRuntime` 启动 15 秒无 OSC 的 UDP 接收验证：

- 收到 10 个包。
- 10/10 均为 `simulated=true`。
- 10/10 均为 `source=watch_diagnostic_simulator`。
- BPM：`[79, 79, 77, 77, 75, 76, 76, 76, 78, 79]`。
- 端到端延迟约 409–862 ms。
- 手机日志出现 `PC acknowledgement ... matched=true`。
- 手机向手表返回 ACK。
- 手表出现 `PHONE_RELAY_ACK_RECEIVED ... pcAck=true`。

由此可以确认本轮模拟链路闭环成立。它不等于真实传感器、VRChat Avatar 实际显示或长时功耗已经验收。

## 6. 已通过的自动化与构建

### Android

以下命令成功：

```powershell
$env:JAVA_HOME='D:\ANDORID\jbr'
$env:ANDROID_HOME='C:\Users\wrq18\AppData\Local\Android\Sdk'
.\gradlew.bat `
  :app:testDiagnosticDebugUnitTest `
  :app:testProductionDebugUnitTest `
  :app:assembleDiagnosticDebug `
  :app:assembleProductionDebug `
  :mobile:testDebugUnitTest `
  :mobile:assembleDebug `
  --no-daemon
```

结果：

- Watch diagnostic：23 tests，0 failed。
- Watch production：21 tests，0 failed。
- Phone：14 tests，0 failed。
- 合计 58 tests。
- 两个 Watch APK 和 Phone APK 均构建成功。

Lint：

```powershell
.\gradlew.bat :app:lintDiagnosticDebug :app:lintProductionDebug --no-daemon
.\gradlew.bat :mobile:lintDebug --no-daemon
```

三个变体均 `BUILD SUCCESSFUL`。

### Python

```powershell
Set-Location D:\CODE\Vrc_realtimeheartbeat\pc-python
.\.venv\Scripts\python.exe -m pytest -q
```

结果：

```text
71 passed
```

## 7. Windows EXE 自检问题：已定位并通过

运行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\pc-python\Build-Exe.ps1 `
  -Python .\pc-python\.venv\Scripts\python.exe
```

诊断结论：

1. 没有残留 `VrcRealtimeHeartbeat-Python` 进程。
2. 源码入口自检约 303 ms 退出，退出码为 0。
3. 临时 `onedir + console` 版约 455 ms 退出，退出码为 0，证明导入、二维码、Bleak 检查和业务自检没有死锁。
4. 临时 `onefile + console` 版在 Codex 受限沙箱内直接报告：

   ```text
   Failed to create parent directory structure.
   ```

   PyInstaller bootloader 只创建了空的 `_MEI...` 根目录，无法继续创建解包子目录。正式 `--windowed` 版隐藏了这条 bootloader 错误，所以表现为进程不退出。
5. 同一个 onefile 诊断 EXE 脱离受限沙箱后约 2.1 秒退出，退出码为 0；交接时留下的正式 EXE 也在正常 Windows 进程环境中约 1.9 秒退出，退出码为 0。由此排除业务线程死锁和 Defender/SmartScreen 猜测。
6. `Build-Exe.ps1` 已恢复 15 秒有界等待；超时时会明确提示检查 PyInstaller onefile 对 `TEMP/TMP` 嵌套目录的写权限。
7. 在正常 Windows 进程环境中完整重跑 `Build-Exe.ps1` 成功：71 项 pytest、源码入口自检、PyInstaller 构建和打包 EXE 自检全部通过。
8. 对新产物额外执行 `--ble-scan-self-test`，WinRT/Bleak `0x180D` 扫描约 7.2 秒退出，退出码为 0。

故障复核阶段产物（正式发布前又重新打包，故文件哈希不同）：

```text
dist/windows-python/VrcRealtimeHeartbeat-Python.exe
SHA-256: cedcec7fe4b9e97e8aeab6748ca0d6c2bace2e4ebd31b3f601136325a1ab7716
```

`v1.1.0` Release 最终 EXE 的 SHA-256 为 `f663f50b4647570bec6f00f7e9e170e7ec7bb2ec430f7756310e7f8a7a90b5e5`。

受限自动化环境运行 onefile EXE 时，必须允许它在 `TEMP/TMP` 下创建嵌套解包目录；不能把该权限错误误判为应用自检卡死。

## 8. 本轮涉及文件

```text
CHANGELOG.md
README.md
docs/NEW_PC_HANDOFF.md
docs/SESSION_HANDOFF_2026-07-27.md

app/src/main/java/.../MainActivity.kt                 # 移出 main
app/src/diagnostic/java/.../MainActivity.kt           # 诊断入口
app/src/diagnostic/java/.../DiagnosticHeartRateSimulator.kt
app/src/diagnostic/java/.../SimulatedHeartRateGenerator.kt
app/src/testDiagnostic/java/.../SimulatedHeartRateGeneratorTest.kt
app/src/main/java/.../RelayProtocol.kt
app/src/main/java/.../RelaySamplePayload.kt
app/src/main/java/.../WearHeartRateRelay.kt

mobile/src/main/java/.../MobileMainActivity.kt
mobile/src/main/java/.../PhoneRelayRepository.kt
mobile/src/main/java/.../RelayProtocol.kt

pc-python/Build-Exe.ps1
pc-python/vrc_heartbeat/protocol.py
pc-python/vrc_heartbeat/app.py
pc-python/vrc_heartbeat/diagnostic_csv.py
pc-python/tests/test_protocol.py
pc-python/tests/test_diagnostic_csv.py
```

## 9. 提交前最终门禁：已通过

2026-07-27 已完整执行：

```powershell
git diff --check
git status --short

$env:JAVA_HOME='D:\ANDORID\jbr'
$env:ANDROID_HOME='C:\Users\wrq18\AppData\Local\Android\Sdk'
.\gradlew.bat `
  :app:testDiagnosticDebugUnitTest `
  :app:testProductionDebugUnitTest `
  :mobile:testDebugUnitTest `
  :app:lintDiagnosticDebug `
  :app:lintProductionDebug `
  :mobile:lintDebug `
  :app:assembleDiagnosticDebug `
  :app:assembleProductionDebug `
  :mobile:assembleDebug `
  --no-daemon

powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\pc-python\Build-Exe.ps1 `
  -Python .\pc-python\.venv\Scripts\python.exe
```

然后复核：

- Android：58 项单元测试全部通过；三个变体 Lint、两个 Watch APK 和 Phone APK 构建均 `BUILD SUCCESSFUL`。
- Python：71 项 pytest、源码入口自检、单文件 EXE 构建、打包 EXE 自检和打包后 WinRT/Bleak `0x180D` 扫描自检全部通过。
- 正式 Watch 编译目录无诊断版 `MainActivity`、`DiagnosticHeartRateSimulator` 或 `SimulatedHeartRateGenerator`；诊断编译目录包含这些类。
- APK 版本均为 `1.1.0 (2)`。
- EXE `--self-test` 在正常 Windows 进程环境中退出码为 0。
- Release EXE SHA-256 已写入 `docs/NEW_PC_HANDOFF.md`。
- PR 与 `main` 的 `Build distributables` 均成功；`main` run ID 为 `30213246653`。
- `v1.1.0` Release 含手表正式功能版、手表诊断版、手机 APK、Windows EXE、Windows ZIP 与统一 `SHA256SUMS.txt`。
