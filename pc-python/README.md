# VRChat 心率桥（Python 版）

这是 Windows 接收器的 Python 实现。原有 C# WinForms 版本仍保留在 `pc-bridge/`，两者使用同一份 UDP/OSC 协议，可以随时回退。

## 直接运行源码

```powershell
python .\pc-python\run_app.py
```

运行时只使用 Python 标准库。程序默认监听 UDP `9123`，向 VRChat `127.0.0.1:9000` 发送 OSC，并自动返回手机要求的 `pc_ack`。

只有 `type=heart_rate` 的真实数据会进入 Avatar 参数；`phone_test` 和 `relay_test` 只验证链路和回执。

## OSC 参数

- `/avatar/parameters/HR_Value`：钳制到 `0..999` 的完整 BPM，OSC Int32。
- `/avatar/parameters/HR_Hundreds`：百位，OSC Int32。
- `/avatar/parameters/HR_Tens`：十位，OSC Int32。
- `/avatar/parameters/HR_Ones`：个位，OSC Int32。
- `/avatar/parameters/HRValid`：真实心率有效状态。
- `/avatar/parameters/HRPulse`：电脑按照 BPM 本地生成的 120 ms 心跳脉冲。
- 兼容旧参数：`HeartRate`、`HeartRateNormalized`、`HeartRateValid`。

每次收到真实心率时，程序都严格按照 `HR_Value → HR_Hundreds → HR_Tens → HR_Ones` 的顺序发送四条三位数显示消息。链路测试包不会发送这些参数。

## 测试

```powershell
python -m pytest .\pc-python\tests -q -p no:cacheprovider
```

测试包括 JSON 协议校验、非法数据、回执、OSC Int32 编码、三位数拆分和发送顺序、超时失效、本地心跳节拍和真实 UDP 回环。

## 构建单文件 EXE

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\pc-python\Build-Exe.ps1
```

产物：`dist\windows-python\VrcRealtimeHeartbeat-Python.exe`。构建需要开发环境安装 pytest 和 PyInstaller；最终 EXE 不要求目标电脑安装 Python。
