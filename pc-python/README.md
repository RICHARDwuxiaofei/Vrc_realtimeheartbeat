# VRChat 心率桥（Python 版）

这是项目唯一维护的 Windows 接收器。它同时支持手机 UDP 中转和小米手环 Windows BLE 直连，可打包为单文件 EXE。

## 直接运行源码

```powershell
python .\pc-python\run_app.py
```

程序默认使用“手机中转”并监听 UDP `9123`，向 VRChat `127.0.0.1:9000` 发送 OSC，并自动返回手机要求的 `pc_ack`。也可以切换为“电脑直连小米手环（实验）”，直接用 Windows BLE 订阅标准心率通知，不经过手机。源码运行需要安装 `qrcode[pil]` 和 `bleak`，发布的单文件 EXE 已包含这两个组件。

只有 `type=heart_rate` 的真实数据会进入 Avatar 参数；`phone_test` 和 `relay_test` 只验证链路和回执。

界面支持简体中文、English 和日本語。默认跟随 Windows 显示语言；系统不是中文
或日语时使用英语。页面最下方的“偏好设置”可以手动选择语言，选择会保存并在界面重载后生效。
设备名称、IP、端口、BLE UUID 和 VRChat OSC 参数保持原文。

## 桌面工具

- 一键向 Avatar 发送 `123 BPM`、三位数字、有效状态与脉冲测试。
- 默认使用普通轻量模式，不在内存中保留历史心率；开启诊断模式后才请求手表/手机扩展字段。
- 圆角深色卡片界面与手机端保持一致；曲线卡始终可见，点击曲线卡右上角“开始曲线记录”后启用诊断曲线。默认显示最近 1 分钟，可用滑轨选择 1–10 分钟，并显示所选范围的最低、最高、平均 BPM。
- 诊断模式把样本追加写入内部 CSV，曲线用独立读句柄从文件尾部读取同一个文件，可边写边读；读到所选时间窗边界就停止，长时会话不会每秒全量扫描。用户文件仍必须点击“导出 CSV”才会生成。
- 启动时自动检查 GitHub 最新正式版。
- 运行事件和 Tk 回调异常堆栈按日写入 `%LOCALAPPDATA%\VrcRealtimeHeartbeat\logs`，自动清理 14 天以前的日志；界面底部可直接打开日志文件夹。
- 显示手机扫码配对二维码，并提供电脑端一键诊断。
- 心率来源可切换为手机 UDP 中转或 Windows 直连小米手环；两路互斥，切换时会停止旧输入引擎。
- 直连模式只扫描标准 Heart Rate Service `0x180D`，订阅 Measurement `0x2A37`，记住设备并在意外断线 5 秒后重连。

电脑通过每个 UDP 回执中的 `diagnosticMode` 标记控制手机，手机只在开关变化时把模式同步给手表。普通模式的手表包不包含原始 BPM、精度、电量、屏幕状态和发送模式；诊断模式才附加这些字段以及手机网络、VPN、接收时间等信息。

直连模式没有手机 ACK，也不会启动 UDP 监听；BLE BPM 会直接进入同一个 OSC/超时/曲线/CSV 状态机。小米手环只需打开系统的“共享心率”，不需要另装 RPK。Windows 直连当前要求 Windows 11；目标电脑还必须有可用的 Bluetooth LE 适配器。

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
