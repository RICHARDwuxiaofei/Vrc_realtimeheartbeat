# 更新日志

本文件记录每个公开版本和当前候选版本的主要变化。日期使用仓库维护时区。

## [Unreleased]

- 暂无；后续尚未归入具体版本的改动记录在这里。

## [1.1.0] - 2026-07-27

### 小米手环与输入来源

- 手机端新增“切换至小米手环 / 切回 Galaxy Watch”，使用手环固件“共享心率”和 Bluetooth SIG `0x180D/0x2A37`。
- 手机小米模式支持扫描、设备选择、设备记忆、8/16 位 BPM、断线重连，并且只在选择该模式时运行 `connectedDevice` 前台服务。
- Python Windows 端新增默认关闭的“电脑直连小米手环（实验）”，无需手机即可扫描、订阅心率、保存设备并在意外断线 5 秒后重连。
- 手机 UDP 与电脑 BLE 输入互斥；切换来源会停止旧引擎，防止两路真实 BPM 同时驱动 OSC。
- Phone → PC JSON 新增向后兼容的 `source` 字段；诊断模式可记录 BLE 设备、Profile 和来源，普通模式不附加设备身份。
- 新增 `docs/XIAOMI_BAND.md`，说明公开 API 边界、为什么不制作无心率读取能力的 Vela RPK、两条链路操作和真机验收项。

### 诊断与桌面功能

- Python Windows 端新增 Avatar 参数测试、GitHub 正式版检查和手机配对二维码。
- 新增默认关闭的跨端诊断模式：电脑通过 UDP ACK 控制手机，手机仅在状态变化时同步给 Galaxy Watch。
- 诊断曲线默认显示最近 1 分钟，可用滑轨选择 1–10 分钟，并计算最低、最高和平均 BPM。
- 诊断样本追加写入内部 CSV；曲线用独立句柄从文件尾部读取所选窗口，支持边写边读且不会随长时会话全量扫描。
- CSV 只在用户手动点击“导出”后生成用户文件，不会自动导出。
- 手机端新增二维码配对、手机 → 电脑一键诊断和完整诊断信息面板。
- 手表诊断版新增可手动启停的 60–80 BPM 平缓波动模拟器；模拟值按真实 `heart_rate` 链路进入手机、电脑和 OSC，用于充电座、未佩戴等无法取得传感器心率时的全链路验证。
- 模拟包始终携带 `simulated=true` 和 `source=watch_diagnostic_simulator`；手机与电脑用黄色警告明确显示“非传感器”，诊断 CSV 也保留该标记。
- 模拟器、按钮和生成器只编译进 `diagnostic` 源集，正式版不包含相关类或入口。

### 手表功耗优化

- 正式手表版提供 `1 秒实时 / 5 秒省电 / 10 秒超省电` 三档；5/10 秒档只使用 Health Services 批量交付，不注册直接传感器、不持有手动 WakeLock。
- 正式版热路径改为内存状态更新，减少逐批 SharedPreferences、Logcat 和文件 flush。
- 缓存附近手机节点，为发现和发送失败增加退避；正式版手表 ACK 请求降低到约每分钟一次。
- BPM 不变时使用双倍发送间隔保活，减少重复 Data Layer 通信。
- 普通模式不保留电脑曲线历史、不写诊断 CSV，也不采集跨端扩展诊断字段。

### 稳定性与安全修正

- 手机为用户触发的一键诊断保留独立待发槽，连续心率不会再覆盖尚未发送的诊断请求。
- 手机 UDP socket 连接到目标电脑后再等待 ACK，避免其他主机用相同序号伪造回执。
- “暂停发送到电脑”同时取消待发和在途诊断状态。
- 普通模式移除 `sessionId`；手机禁用云备份和设备迁移，避免电脑地址等本机配置离开设备。
- 正式手表版对相同警告做 60 秒限频，诊断版继续保留完整事件。

### Windows 实现收敛

- 删除旧 C# WinForms、旧 PowerShell Windows 接收器及其 GitHub Actions 构建任务，桌面端统一维护 `pc-python`。
- Python 打包图标迁移到 `pc-python/assets`。
- 独立的手表报告工具保留并迁移到 `tools/WatchTestReport.ps1`，不随 C# 接收器删除。

### 验证

- Python 71 项 pytest、源码自检、手机中转 → 电脑直连 Tk UI 切换冒烟通过。
- Python 单文件 EXE 构建、自检和打包后的 WinRT/Bleak `0x180D` 真实扫描通过；2026-07-27 重建产物 SHA-256 为 `cedcec7fe4b9e97e8aeab6748ca0d6c2bace2e4ebd31b3f601136325a1ab7716`。
- 记录 PyInstaller onefile 在受限沙箱内因无法创建 `TEMP/TMP` 解包子目录而表现为 `--self-test` 卡住；正常 Windows 进程环境中打包 EXE 约 2 秒退出且退出码为 0，构建脚本保留 15 秒有界超时并提供权限诊断提示。
- Android 手表 diagnostic/production 与手机共执行 58 项单元测试，三个变体 Lint 均为 0 error，两个手表 APK 和手机 APK 构建成功。
- Galaxy Watch6 SM-R960、Galaxy S24 Ultra 和 Windows 在酒店 Wi-Fi 完成模拟心率真机闭环：电脑 15 秒收到 10 个带永久模拟标记的 75–79 BPM 包，端到端延迟约 409–862 ms，PC ACK 经手机返回手表；模拟器停止后不再发送。
- 手表报告工具迁移后自检通过。
- 测试环境附近没有开启共享心率的小米手环，因此 Windows 扫描通过不等于真机通知、重连和续航已经验证。

## [1.0.0] - 2026-07-24

### 首个稳定版

- Wear OS 正式版提供 `1 秒实时`和`5 秒省电`两种启动前模式，并保留独立诊断 APK。
- Android 手机接收 Galaxy Watch Data Layer 心率，以可选 `1/2/5/10/30 秒`间隔通过 UDP 转发到电脑。
- Python Windows 接收器成为首选桌面端，负责 UDP ACK、VRChat OSC、三位数心率拆分和本地 `HRPulse`。
- 支持 `HR_Value`、`HR_Hundreds`、`HR_Tens`、`HR_Ones`、`HRValid`、`HRPulse`，并保留旧 `HeartRate*` 参数。
- 当时保留 C# Windows 接收器作为兼容回退；该回退实现后来在 `v1.1.0` 候选版删除。
- GitHub Release 从同一次 Actions 构建提供手表正式/诊断 APK、手机 APK、Windows 包和 SHA-256 校验。

### 已知边界

- Android 附件使用 GitHub Actions Debug 签名，不是应用商店发布签名。
- `1 秒实时`使用直接心率传感器和有界 WakeLock，耗电明显高于默认省电模式。
- 本版没有小米手环来源、二维码配对、可调诊断曲线或 CSV 导出。

## [0.1.0] - 2026-07-20

### 首个公开试玩版

- 建立 Galaxy Watch / Wear OS 心率采集、Android 手机中转和 Windows → VRChat OSC 的基础三端链路。
- 手表端包含 MeasureClient 探针、ExerciseClient 息屏测试、健康前台服务、权限处理和本地诊断日志。
- 手机端接收 Wear OS Data Layer 消息，通过局域网 UDP 发给电脑并处理 ACK。
- Windows 初版接收器监听 UDP `9123`，将真实心率转换为 VRChat OSC；链路测试包不会冒充真实 BPM。
- 加入 GitHub Actions 自动构建、下载 Artifact 和 SHA-256 校验文件。

### 定位

- 该版本标记为 Pre-release，以链路试玩和继续开发为主。
- 手机与手表均为 Debug APK，需要使用同一次构建的匹配签名产物。
- 尚未提供正式/诊断分版、系统化功耗优化、Python 桌面端或长时稳定性结论。
