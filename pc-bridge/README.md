# VRChat 实时心率桥（Windows）

这是原有的 C# WinForms 回退版本，源码和构建流程继续保留。日常优先使用 `pc-python/` 里的 Python 版；两者兼容同一份手机 UDP 与 VRChat OSC 协议，不能同时监听同一个 UDP 端口。

## 直接运行 EXE

1. 双击 `VrcRealtimeHeartbeat.exe`。程序会自动开始监听 UDP `9123`。
2. 在手机端填写程序界面显示的电脑局域网 IPv4，端口填写 `9123`。
3. 手机点击“发送测试包”，确认电脑界面显示“链路测试”并且手机收到电脑回执。
4. 在 VRChat Action Menu 中启用 OSC。程序默认发送到本机 `127.0.0.1:9000`。
5. 在手表启动“后台连续”真实心率测量；只有 `type=heart_rate` 的真实心率包会进入 OSC。手机默认每 5 秒转发最新 BPM，可选 `1/2/5/10/30 秒`，暂停发送时手表与手机仍继续采样和显示。

电脑程序会根据手机包中的 `phoneForwardIntervalSeconds` 自动调整超时阈值（至少 10 秒；默认 5 秒档约 12.5 秒），超时后把有效状态切换为 false。`phone_test` 和 `relay_test` 只测试链路、发送回执，不会冒充真实心率进入 VRChat。

## OSC 参数

- `/avatar/parameters/HR_Value`：Int，钳制到 `0..999` 的完整 BPM。
- `/avatar/parameters/HR_Hundreds`：Int，心率百位。
- `/avatar/parameters/HR_Tens`：Int，心率十位。
- `/avatar/parameters/HR_Ones`：Int，心率个位。
- `/avatar/parameters/HRValid`：Bool，真实心率有效时为 true，超时后为 false。
- `/avatar/parameters/HRPulse`：Bool，由电脑按照最近 BPM 周期性生成约 120 ms 的脉冲。

每次收到真实心率时，程序都严格按照 `HR_Value → HR_Hundreds → HR_Tens → HR_Ones` 的顺序发送四条三位数显示消息。链路测试包不会发送这些参数。

为了兼容早期测试 Avatar，当前版本还会发送：

- `/avatar/parameters/HeartRate`：Int，完整 BPM。
- `/avatar/parameters/HeartRateNormalized`：Float，把 40–200 BPM 映射到 0–1。
- `/avatar/parameters/HeartRateValid`：Bool，旧版有效状态参数。

## 本地编译

在 Windows PowerShell 中从仓库根目录运行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\pc-bridge\Build-Exe.ps1
```

程序生成在：

```text
dist\windows\VrcRealtimeHeartbeat.exe
```

构建脚本会自动运行 EXE 内置的 OSC 协议自测，并生成 `SHA256SUMS.txt`。本地编译需要 Visual Studio Build Tools 或 Visual Studio；生成的程序面向 Windows 10/11，并使用系统自带的 .NET Framework。

`HeartRateBridge.ps1` 与 `Start Heart Rate Bridge.cmd` 是早期 PowerShell 版本，暂时保留作为回退，不再是首选入口。

## 手表连续性报告（不需要手机）

息屏测试完成后，可独立导出并复算手表持久化数据：

```powershell
.\WatchTestReport.ps1 -Watch 'WATCH_IP:ADB_PORT'
```

不要在正式息屏测试窗口内运行该工具。测试期间的记录完全保存在手表本地，测试结束后再使用 ADB 导出。
