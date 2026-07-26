# 小米手环心率接入

更新时间：2026-07-26

## 结论

当前实现采用小米手环自带的 **Bluetooth HR broadcast / 共享心率**。保留两条可选链路：

```text
Xiaomi Smart Band 10
  → 手环“共享心率”
  → BLE Heart Rate Service 0x180D
  → Heart Rate Measurement 0x2A37
  ├→ Android 手机按需前台服务 → UDP JSON / PC ACK → Windows
  └→ Windows 11 BLE（实验）─────────────────────────→ Windows
  → 同一套 VRChat OSC / 超时 / 曲线 / CSV
```

两条链路均不读取小米账号、Mi Fitness 数据库或历史健康记录，也不依赖私有协议。Windows Python 端一次只启用一种输入源，避免两路 BPM 竞争。

## 为什么没有制作 RPK 心率采集应用

参考项目 [Vincent-hechuan/codex-quota-band](https://github.com/Vincent-hechuan/codex-quota-band) 的通信方向是：

```text
Windows HTTP → AstroBox 插件 → Vela RPK
```

它证明 RPK、AstroBox 插件和局域网程序之间可以交换应用消息，但不证明 RPK 能读取手环心率。

截至本次核对：

- 小米 Vela 公布的 [`system.sensor`](https://iot.mi.com/vela/quickapp/zh/features/system/sensor.html) 只公开压力、加速度和罗盘，没有心率读取方法。
- 官方 [`system.interconnect`](https://iot.mi.com/vela/quickapp/zh/features/network/interconnect.html) 用于快应用与 Android 应用收发消息；直接集成要求包名和签名匹配。
- 官方《小米穿戴第三方 APP 能力开放接口文档 v1.4》公开连接、电量、充电、佩戴、睡眠状态、应用间消息和通知，没有提供实时心率数据查询/订阅接口。
- 小米手环 10 官方 FAQ 明确提供 **Settings → Share HR → Turn on**，并把实时心率通过 BLE 交给兼容接收设备。

因此，给项目额外做一个 RPK 只能成为控制/展示壳，无法通过公开接口取得真实 BPM。当前没有引入这种无效依赖；若小米以后公开 Vela 心率 API，可在不改 Phone → PC 协议的前提下增加 RPK 来源适配器。

## 用户操作

### 手机中转

1. 在小米手环打开 `应用列表 → 设置 → 共享心率 → 开启`。
2. 打开手机“心率中转站”，点击“切换至小米手环”。
3. Android 首次会请求“附近设备”权限；允许后开始 20 秒扫描。
4. 在扫描结果中点选手环。以后会记住 BLE 地址并自动重连。
5. 手机显示实时 BPM 后，电脑端仍使用原来的二维码/IP 和 UDP 端口。
6. 要恢复 Galaxy Watch，点击“切回 Galaxy Watch”；BLE 扫描、连接和前台服务随即停止。

### 电脑直连（实验）

1. 确认电脑是 Windows 11，并有可用 Bluetooth LE 适配器。
2. 在手环打开 `设置 → 共享心率 → 开启`。
3. 打开 Python Windows 接收器，在“心率来源”选择“电脑直连小米手环（实验）”。
4. 程序自动扫描；也可点击“扫描”，在结果中选择手环后点击“连接”。
5. 连接后 BPM 直接进入 VRChat OSC；开启诊断模式后，曲线、统计和 CSV 与手机链路相同。
6. 要恢复手机中转，在“心率来源”切回“手机中转（Galaxy / 小米）”。程序会先停止电脑 BLE，再恢复 UDP。

手环端不额外制作“直连/手机中转”RPK 开关。公开 Vela API 无法控制系统“共享心率”，而且另跑 RPK 只会增加不必要的手环进程开销。手环负责开启共享心率；接收方由手机或电脑端选择。标准心率 GATT 通常只应由一个接收方占用，因此不要让手机和电脑同时连接。

Windows 实现遵循微软的 [GATT Client](https://learn.microsoft.com/en-us/windows/apps/develop/devices-sensors/gatt-client) 流程（发现设备、枚举服务/特征、写入通知配置并处理 ValueChanged），底层使用 [Bleak Windows 后端](https://bleak.readthedocs.io/en/latest/backends/windows.html)。

若找不到设备：

- 确认手环的“共享心率”仍为开启状态，并让手环靠近手机。
- 关闭再开启“共享心率”，然后在手机点“重新扫描”。
- 确认 Android 的附近设备权限与蓝牙已开启。
- 其他健身设备正在占用共享心率连接时，先断开该设备再重试。

## 实现原则

- 两种来源互斥：小米模式忽略 Wear Data Layer 心率；Galaxy 模式不运行 Xiaomi BLE 服务。
- 电脑输入互斥：选择 Windows BLE 时不绑定 UDP；切回手机模式会终止 BLE 线程和连接。
- 扫描有边界：只扫描标准 0x180D 服务，前台低延迟扫描最多 20 秒。
- Windows 扫描同样只筛选 0x180D，单次 8 秒；不遍历或读取无关服务。
- 日常省资源：只有用户选择小米模式时才启动 `connectedDevice` 前台服务。
- 电脑直连不运行手机服务，也不要求手环 RPK；手环侧只有固件自带的共享心率功能在工作。
- 不积压数据：BLE 通知进入现有“在途一包 + 最新待发心率”模型，电脑离线不会形成无界队列。
- 协议兼容：核心字段不变，只新增 `source=galaxy_watch|xiaomi_band_ble`；旧电脑端会忽略它。
- 诊断按需：设备名、BLE 地址和 Profile 信息只在诊断模式进入扩展数据/CSV。
- 断线恢复：记住最后选择的 BLE 地址，断线后 5 秒重连；手动重新扫描可换设备。
- 同一状态机：电脑直连 BPM 被包装为 `source=xiaomi_band_pc_ble`，再进入现有去重、OSC、超时、诊断 CSV 和曲线逻辑。

## 验证边界

已完成：

- 8/16 位 Heart Rate Measurement 解析及异常输入单元测试。
- Android 手机单元测试、Debug APK 编译。
- Python 协议、输入来源、运行时与 CSV 回归测试。
- Windows 11 WinRT/Bleak 后端和专用事件线程启停测试通过；源码与打包 EXE 均完成按 0x180D 筛选的真实 BLE 扫描冒烟。测试现场未发现开启共享心率的设备，因此没有伪造连接成功。

仍需 Xiaomi Smart Band 10 真机验证：

- 扫描结果中的实际设备名称与 GATT 特征。
- Windows 直连订阅 0x2A37、真实 BPM、手动断开和 5 秒自动重连。
- Mi Fitness 同时连接时能否稳定共享心率。
- 手机锁屏、进程回收、断开再进入范围后的恢复。
- 分别完成“手机中转”和“电脑直连”至少 60 分钟的手环耗电、最长断档与延迟对比。

没有真机证据前，不应把该模式标记为正式 Release 已验证功能。
