# VRChat 实时心率桥

[![Build distributables](https://github.com/RICHARDwuxiaofei/Vrc_realtimeheartbeat/actions/workflows/build.yml/badge.svg)](https://github.com/RICHARDwuxiaofei/Vrc_realtimeheartbeat/actions/workflows/build.yml)

把 Galaxy Watch 或小米手环的实时心率发送到 Windows，再通过 OSC 提供给 VRChat Avatar。

支持三种链路：

- Galaxy Watch → Android 手机 → Windows → VRChat
- 小米手环 → Android 手机 → Windows → VRChat
- 小米手环 → Windows → VRChat（实验功能）

手表、手机和 Windows 程序均支持简体中文、English 和日本語。首次启动会跟随
系统语言；系统不是中文或日语时默认使用英语，也可以在各端界面中单独手动选择。
Galaxy Watch、Xiaomi Smart Band、设备名称、IP 地址、端口、BLE UUID 和
VRChat OSC 参数等技术标识不会被翻译。

## 下载

请从 [GitHub Releases](https://github.com/RICHARDwuxiaofei/Vrc_realtimeheartbeat/releases/latest) 下载最新版。

> 下表是 v1.2.0 的文件命名。当前公开的 v1.1.0 仍使用单个 `phone-debug.apk`，且两只手表 APK 不能共存；待 v1.2.0 验证发布后才使用下面的双包组合。

| 文件 | 安装位置 | 用途 |
| --- | --- | --- |
| `VrcRealtimeHeartbeat-Python.exe` | Windows | 接收心率并发送 VRChat OSC |
| `Vrc_realtimeheartbeat-phone-production.apk` | Android 手机 | 日常使用，在正式手表版与电脑之间中转 |
| `Vrc_realtimeheartbeat-watch-production.apk` | Galaxy Watch | 日常使用的正式功能版，推荐安装 |
| `Vrc_realtimeheartbeat-watch-diagnostic.apk` | Galaxy Watch | 排错与完整链路测试专用 |
| `Vrc_realtimeheartbeat-phone-diagnostic.apk` | Android 手机 | 与手表诊断版配套的独立中转应用 |
| `SHA256SUMS.txt` | 任意 | 校验下载文件是否完整 |

Windows ZIP 包包含 EXE、使用说明和校验文件；只想直接运行时下载单独的 EXE 即可。

## 手表正式版和诊断版

从 v1.2.0 起，手表端有两个可同时安装的版本：

| 版本 | 适合谁 | 包含内容 |
| --- | --- | --- |
| 正式功能版 `production` | 日常传输真实心率，推荐 | 简洁界面、真实传感器、发送频率选择、开始/停止和必要状态 |
| 诊断版 `diagnostic` | 排错、续航测试、开发验证 | 正式版能力，以及探针、息屏/续航测试、详细报告、日志和 60–80 BPM 模拟心率 |

两版使用不同包名，可以同时安装。Wear Data Layer 要求手表和手机包名匹配，因此：

- 日常链路：`watch-production.apk` + `phone-production.apk`
- 诊断链路：`watch-diagnostic.apk` + `phone-diagnostic.apk`

两条链路可以共存，但不要同时启动两只手表应用向同一个电脑端口发送心率。

诊断版的模拟心率不是传感器数据，只用于验证“手表 → 手机 → 电脑 → OSC”整条链路，界面会明确标记为模拟数据。

> 这里的“正式功能版”表示面向日常使用的功能和界面版本。本次三端 APK 使用同一份项目现有 debug 签名，尚未配置商店级 Release 密钥。

## 快速开始

1. 在 Galaxy Watch 安装 `watch-production.apk`，在 Android 手机安装 `phone-production.apk`。
2. 在 Windows 运行 `VrcRealtimeHeartbeat-Python.exe`。默认监听 UDP `9123`，OSC 发送到 `127.0.0.1:9000`。
3. 在电脑点击“显示配对二维码”，用手机扫描；也可以在手机手动填写电脑的局域网 IPv4 地址和端口 `9123`。
4. 在手表点击“开始传输”。手机显示电脑已回执、电脑显示 BPM 后，链路即已接通。
5. 在 VRChat 中开启 OSC。

手表与手机通过 Wear OS Data Layer 通信；手机与电脑需要位于可互相访问的局域网。酒店 Wi-Fi 可能禁止设备间通信，即使能上网也不代表 UDP 可以互通。

Windows 端使用与手机一致的圆角深色卡片界面；按钮、输入框、下拉框和开关也使用圆角控件。心率曲线始终显示，点击曲线卡右上角“开始曲线记录”后启用区间统计和 CSV。语言与版本检查等低频设置放在页面最下方，窗口内容可用鼠标滚轮上下滚动。

Windows 程序会把运行事件和异常堆栈写入 `%LOCALAPPDATA%\VrcRealtimeHeartbeat\logs` 下的按日日志，默认保留 14 天。界面底部显示当前文件路径，并提供“打开日志文件夹”按钮。

Galaxy Watch Classic 可以使用旋转表圈滚动正式版和诊断版页面，也可以直接触摸滑动。

## 小米手环

手机和 Windows 端均可读取标准 BLE Heart Rate Service（`0x180D/0x2A37`）。

- 手机中转：先在手环开启“共享心率”，再在手机切换到小米手环模式。
- Windows 直连：在电脑端将“心率来源”改为“电脑直连小米手环（实验）”。
- 不要让手机和电脑同时连接同一只手环。

Windows 直连已通过自动化和扫描冒烟测试，但仍缺少 Xiaomi Smart Band 10 的长期真机通知、重连和续航验收，因此标记为实验功能。技术细节见 [小米手环说明](docs/XIAOMI_BAND.md)。

## 支持的三星手表

- 正式支持目标：Galaxy Watch4 系列及更新的 Wear OS Galaxy Watch。
- 最低系统要求：Wear OS 3 / Android 11（API 30）。
- Galaxy Watch4 当前仍在三星官方更新范围内；不同地区获得更新的时间可能不同。
- Galaxy Watch、Watch Active、Watch3 等使用 Tizen 的旧型号不支持本应用。

项目使用标准 Wear OS Health Services，并保留 API 30–35 的 `BODY_SENSORS` 权限路径和 API 36+ 的健康权限路径。其他厂商 Wear OS 手表理论上可以安装，但心率后台行为仍需要逐机验证。

## VRChat OSC 参数

电脑端会发送以下参数：

- `/avatar/parameters/HR_Value`：完整 BPM，OSC Int32
- `/avatar/parameters/HR_Hundreds`、`HR_Tens`、`HR_Ones`：百位、十位、个位
- `/avatar/parameters/HRValid`：当前数据是否有效
- `/avatar/parameters/HRPulse`：根据 BPM 生成的节拍
- `HeartRate`、`HeartRateNormalized`、`HeartRateValid`：兼容旧版参数

## 常见问题

### 手表装完后界面不对

请先看应用名称：“心率传输”是正式版，“心率诊断”是诊断版。v1.2.0 起两者可以共存；诊断版必须搭配手机上的“心率中转站（诊断）”。

### 手机找不到电脑

- 确认电脑防火墙允许程序访问专用网络。
- 确认手机和电脑可以互相访问，而不只是连接到同一个 Wi-Fi 名称。
- 酒店、访客和企业 Wi-Fi 常开启客户端隔离；这时可以改用手机热点或允许局域网互访的路由器。
- 检查手机填写的是电脑局域网 IPv4，而不是 `127.0.0.1`。

### Windows 第一次启动较慢

单文件 EXE 首次运行需要解压运行环境，安全软件也可能进行扫描。程序支持包含中文或空格的路径，并会在默认临时目录不可用时使用备用目录。

### 无法无线 ADB 连接手表

Wear OS 的无线调试端口会在重新启用或重启后变化。请以手表“无线调试”页面当前显示的 IP 和端口为准；这不影响应用正常传输心率。

## 隐私与安全

- 心率数据默认只在手表、手机和你的局域网电脑之间传输，不上传项目服务器。
- 普通模式不保留连续心率历史；只有主动开启电脑诊断记录并手动导出时才会生成 CSV。
- Windows 会在本机应用数据目录保留 14 天运行日志，用于排错；诊断模式日志可能包含当时的 BPM、来源、延迟和包序号，不会自动上传。
- 本项目不是医疗设备，不应用于诊断、治疗或紧急健康判断。

## 开源许可状态

仓库当前尚未放置根目录 `LICENSE`，因此在维护者确定许可证和版权署名之前，
不能把当前代码视为已经授予开源再分发许可。候选方案与正式发布前仍需完成的
签名、第三方许可、隐私和 GitHub 元数据工作见
[正式发布准备清单](docs/RELEASE_READINESS.md)。

## 版本与开发文档

- [更新日志](CHANGELOG.md)
- [功耗优化与实测](docs/POWER_OPTIMIZATION.md)
- [后台与续航测试指南](BACKGROUND_TEST_GUIDE.md)
- [新电脑开发交接](docs/NEW_PC_HANDOFF.md)

开发者可用以下命令执行完整 Android 构建与测试：

```powershell
.\gradlew.bat test lint :app:assembleDiagnosticDebug :app:assembleProductionDebug :mobile:assembleDiagnosticDebug :mobile:assembleProductionDebug
```

Windows 测试与打包：

```powershell
.\pc-python\.venv\Scripts\python.exe -m pytest .\pc-python\tests -q -p no:cacheprovider
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\pc-python\Build-Exe.ps1 -Python .\pc-python\.venv\Scripts\python.exe
```
