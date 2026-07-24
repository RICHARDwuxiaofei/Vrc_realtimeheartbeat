# VRChat 心率桥（Python 版）

这是 Windows 接收器的 Python 实现。原有 C# WinForms 版本仍保留在 `pc-bridge/`，两者使用同一份 UDP/OSC 协议，可以随时回退。

## 直接运行源码

```powershell
python .\pc-python\run_app.py
```

程序默认监听 UDP `9123`，向 VRChat `127.0.0.1:9000` 发送 OSC，并自动返回手机要求的 `pc_ack`。源码运行需要安装 `qrcode[pil]`，发布的单文件 EXE 已经包含该组件。

只有 `type=heart_rate` 的真实数据会进入 Avatar 参数；`phone_test` 和 `relay_test` 只验证链路和回执。

## 桌面工具

- 一键向 Avatar 发送 `123 BPM`、三位数字、有效状态与脉冲测试。
- 默认使用普通轻量模式，不在内存中保留历史心率；开启诊断模式后才请求手表/手机扩展字段。
- 诊断曲线默认显示最近 1 分钟，可用滑轨选择 1–10 分钟，并显示所选范围的最低、最高、平均 BPM。
- 诊断模式把样本追加写入内部 CSV，曲线用独立读句柄从文件尾部读取同一个文件，可边写边读；读到所选时间窗边界就停止，长时会话不会每秒全量扫描。用户文件仍必须点击“导出 CSV”才会生成。
- 启动时自动检查 GitHub 最新正式版。
- 显示手机扫码配对二维码，并提供电脑端一键诊断。

电脑通过每个 UDP 回执中的 `diagnosticMode` 标记控制手机，手机只在开关变化时把模式同步给手表。普通模式的手表包不包含原始 BPM、精度、电量、屏幕状态和发送模式；诊断模式才附加这些字段以及手机网络、VPN、接收时间等信息。

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
