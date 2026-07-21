# CODEX 交接文档

最后核对时间：2026-07-21（Asia/Hong_Kong）

仓库：`D:\CODE\heartbeats`

核对基线：`main` / `96f9ef2 Add Codex handoff checkpoint (#2)` / tag `v0.1.0`

Android applicationId（手表与手机共用）：`best.nagikokoro.watch6heartrateprobe`

> 本文只把当前源码、Manifest、构建结果、现有测试报告和可读取日志支持的内容写成事实。计划、代码存在但未真机验证的功能，以及历史报告中已被后续实现取代的结论均明确标注。
>
> 特别注意：`EXECUTION_REPORT.md`、`SECOND_STAGE_TEST_REPORT.md` 和 `test-results/` 当前被 `.gitignore` 忽略，不属于 Git 提交；新克隆只能从本文看到其结论摘要，不能直接取得原始文件。

## 0. 最终目标与当前边界

最终目标是从 Samsung Galaxy Watch 获取真实心率，在正常佩戴、手表息屏、Activity 后台的情况下持续传输，由手表作为 BLE GATT 外设直接发送到 Windows，再通过 VRChat OSC 驱动 Avatar 参数。手机 Data Layer 中继是当前已实现的诊断/备用链路，不是最终 BLE 直连方案。Unity 模型、Animator 与 Avatar 显示由项目使用者维护，本仓库负责到 OSC 参数接入为止。

当前已经实现了各层代码，并完成新的 Health Services 低功耗路径 20 分钟正常佩戴测试，但尚未完成 60 分钟正式续航和 VRChat 实收验证。2026-07-21 已确认 Galaxy Watch6 支持 `HEART_RATE_5_SECONDS`；普通模式仅使用 `ExerciseClient`，不注册直接心率传感器、不持有手动 WakeLock，息屏时仍保持约 1 Hz 真实采样和约 5 秒批量交付。旧的直接传感器 + 有界 WakeLock 仅保留为明确标注的高功耗诊断实验，不属于日常路径。

### 2026-07-21 最新检查点

- 2026-07-21 后续功耗审计确认旧普通模式在约 5 分 43 秒前台服务窗口内持有 `CONTINUOUS_REALTIME_RELAY` WakeLock 约 4 分 13 秒，并注册心率传感器约 6 分 25 秒。当前普通模式已改为 Health Services `HEART_RATE_5_SECONDS`、无手动 WakeLock/无直接传感器，并已编译安装完成 20 分钟真机验证。
- 最新会话 `20260721_161305_972_BATTERY_20_MIN_ON_WRIST_REAL_USE`：1194 个窗口内真实样本，平均/最大采样间隔 1004.3/2005 ms；息屏交付 P95 4056 ms、最大 4413 ms，最长无 callback 6016 ms；0 历史缓存批次、0 重启、0 错误、0 崩溃、0 WakeLock。电量 97% -> 94%，粗略外推 9%/小时，仍需至少 60 分钟确认。
- 该会话发现严格 `>=5000 ms` 节流把 4990–4999 ms 批次误跳过，造成手机约 42% 更新间隔接近 10 秒。Watch/Phone 已改为最多 10%、上限 500 ms 的容差策略并新增边界测试；旧数据回放 P95 约 5359 ms，仅 3/235 个跨屏幕转换间隔超过 8 秒。
- Watch/Phone 原先都同时使用 Application 运行时 listener 和 `WearableListenerService`。现仅保留可后台唤醒的 service listener；安装后固定 72 BPM 诊断包真机验证：手机接收 1 次、duplicate 0 次、ACK 1 次，手表 ACK 记录 1 次。
- 手表自动旋转实测曾为 `accelerometer_rotation=1`，已通过 ADB 恢复为 `0`，`user_rotation=0`；仓库没有修改旋转设置的代码或脚本。

- Watch6 处于 `mWakefulness=Dozing`、`screenInteractive=false`、Activity `STOP` 时，直接心率回调仍连续运行。
- 未佩戴诊断窗口取得 87 次息屏传感器回调，平均间隔 999.6 ms、最大 1022 ms，证明回调本身不再等待亮屏批量补发。
- 随后正常佩戴取得 73–79 BPM 真实变化；相邻 `sampleEpochMillis` 约 1003–1004 ms，传感器事件到服务接收延迟 0–15 ms。
- 手机逐序号接收；VPN 已由用户关闭。手机 APK 已修复双 listener 并发去重竞态，并把 PC 离线时的转发队列改为“当前在途 + 最新待发”，不再积压数百条过期心率。
- Windows `VrcRealtimeHeartbeat.exe` 已在 `192.168.100.188:9123` 实际接收手机 `192.168.100.150` 的真实心率，并逐包返回匹配 ACK；窗口显示实时 BPM 和约 1 秒级端到端数据年龄。
- 2026-07-21 三端 UI 已统一为 PulseLink 深色界面并加入应用图标；Windows EXE 升至界面版本 v0.3。手机新增 `1/2/5/10/30 秒`转发间隔（默认 5 秒）和暂停/恢复。实测手机测试包在运行状态到达 PC，暂停后 PC 计数不变，恢复后计数继续增加。
- 当前尚未完成 VRChat 实际接收、20 分钟新实时模式测试、60 分钟正式续航、断线重连及最终 BLE 直连 PC。

## 1. 当前系统架构

当前源码实际实现的数据链路如下：

```text
Galaxy Watch6 PPG / Health Services + SensorManager
  -> Wear OS app (:app)
     - MeasureClient（前台对照探针）
     - ExerciseClient + health ForegroundService（保持健康会话与连续性记录）
     - 唤醒型 TYPE_HEART_RATE + maxReportLatency=0（当前息屏实时交付路径）
  -> Google Play services Wearable Data Layer / MessageClient
     - 只选择 capability=heart_rate_phone_relay 且 isNearby 的手机节点
  -> Android phone app (:mobile)
  -> UDP JSON over LAN，默认目标 PC_PORT=9123
  -> Windows 原生 EXE bridge（C# WinForms；旧 PowerShell 版仅作回退）
  -> OSC UDP，固定目标 IP 127.0.0.1，默认端口 9000
  -> VRChat Avatar Parameters
  -> Unity Animator / Expression Parameters / Avatar 显示组件（尚未实现）
```

实现与验证必须分开理解：

- Watch `ExerciseClient` 采集：已实现，已在 SM-R960 真机验证。
- Watch -> Phone Data Layer：已实现；一个 `relay_test` 诊断包已验证能到手机。
- Phone -> PC UDP：已实现；同一诊断包取得 `pcAck=true`，证明单包到达 PC 并回执。
- PC -> VRChat OSC：代码已实现；EXE 的 UDP 输入、PC ACK 和 OSC 127.0.0.1 回环集成测试已通过，但没有仓库证据证明 VRChat 实际收到。
- Avatar 参数与可视化：仓库中没有 Unity/Avatar 文件，尚未开始。

当前没有实现 Watch 直接连接 PC，也没有自定义 BLE/GATT 链路。Data Layer 的底层承载由 Google Play services 和配对设备管理，代码不能证明或强制“始终只走蓝牙”；不要把当前方案描述成自研 BLE 协议。

2026-07-20 后续审计已补充 60 分钟正式测试入口、已佩戴/未佩戴场景预检、真正的偶数样本中位数、息屏专用交付延迟分位数，以及缓存/亮屏补发证据字段。手机和手表的 Data Layer manifest listener 已改用 `MESSAGE_RECEIVED`；两端构建、单元测试和 Lint 已通过。这些代码改动尚需新的佩戴真机测试结果，不能替代实测。

同日进一步把后台测试的 SharedPreferences 同步提交限制在测试开始和结束边界；1 Hz 回调期间改为异步提交，原始 JSONL 仍逐事件落盘，以降低测试记录器自身阻塞后续回调的风险。电脑端新增 `pc-bridge/WatchTestReport.ps1`，可在测试结束后不经过手机直接从手表导出报告，并从原始 JSONL 独立复算采样间隔、息屏交付延迟和最长无心率回调。该工具不得在正式息屏窗口内运行。

## 2. 各端代码状态

| 部分 | 状态 | 当前事实 |
|---|---|---|
| 手表端应用 | 短时实时链路已验证 / 待长测 | 两种 Health Services 模式、直接唤醒型心率传感器、权限、日志、后台测试记录、健康前台服务和 Data Layer 发送均已实现。MeasureClient 仅保留为对照；ExerciseClient 单独回调会息屏批处理，直接传感器路径已在 Dozing + Activity STOP 下约 1 Hz 取得并发送真实 BPM。 |
| Android 手机端 | 短时实时链路已验证 / 待长测 | Material 3 UI、Data Layer listener、PC IP/端口持久化、UDP 转发、1 秒 ACK、ACK 回手表、VPN 提示、原子去重和最新包合并均已实现。手机持续接收约 1 Hz 样本，向 PC 默认每 5 秒发送最新值，可选 1/2/5/10/30 秒并可暂停/恢复。已安装在 SM-S928B。 |
| PC 中转程序 | 真实心率接收已验证 / 待 VRChat | C# WinForms 单 EXE 已实现 UDP 9123、ACK、PulseLink 中文 BPM UI、内嵌程序图标、按手机发送间隔自适应的真实数据超时和 OSC；已实际接收息屏真实 BPM并回 ACK。OSC 编码/回环已测，VRChat 实收未验证。 |
| VRChat OSC 输出模块 | 部分完成 / 待验证 | EXE 能按顺序发送三位数 Int32 参数 `HR_Value`、`HR_Hundreds`、`HR_Tens`、`HR_Ones`，并发送 `HRValid`、本地节拍 `HRPulse` 及旧参数兼容包；已验证 OSC UDP 包实际抵达本机监听器，未验证 VRChat 或 Avatar 实际响应。 |
| Unity / Avatar 配置 | 尚未开始 | 仓库无 Unity 工程、Animator、Expression Parameters、Expressions Menu、材质或显示组件。当前不能在 Avatar 上显示 BPM。 |
| 旧 `onStop` 注销方案 | 已废弃 | 早期 Activity `onStop` 主动注销 `MeasureCallback` 的路径已确认并移除。现有 `onPause`、`onStop`、Ambient、screen-off 只记录日志。 |
| MeasureClient 作为最终后台方案 | 已废弃（仍保留诊断模式） | 修正生命周期后仍会在 Watch6 息屏/后台停止供数；保留用于 A/B 对照，不应作为最终后台采集方案。 |

版本注意：Gradle 中手表和手机的 `versionName` 仍是 `1.0.0`、`versionCode` 是 `1`，GitHub 试玩预发布标签是 `v0.1.0`；二者当前不一致。

## 3. 心率采集实现

### 3.1 API 与模式

- 依赖：`androidx.health:health-services-client:1.1.0-rc02`。
- 数据类型：`DataType.HEART_RATE_BPM`。
- Measure 模式：`HealthServices.getClient(...).measureClient` + `MeasureCallback`。
- Exercise 模式：`HealthServices.getClient(...).exerciseClient` + `ExerciseUpdateCallback`。
- Exercise 类型按设备 capability 动态选择，优先顺序为 `WORKOUT`、`WALKING`、`EXERCISE_CLASS`、`STRENGTH_TRAINING`，再遍历其他支持类型；SM-R960 真机实际选择 `WORKOUT`。
- Exercise 只请求 `HEART_RATE_BPM`，GPS=false，auto pause/resume=false。
- 有效诊断范围为 20–300 BPM；非法、NaN、Infinity 数据被丢弃，不生成替代值。

### 3.2 采样方式与频率

- 代码没有请求或强制采样频率，频率由 Health Services 和设备决定。
- MeasureClient 前台实测能产生真实变化的 BPM，但间隔不稳定；后期对照测试在仍有数据时最大相邻间隔为 12.028 秒，随后完全停止供数。
- ExerciseClient 严格息屏 146.158 秒窗口内有 146 个真实采样时间戳，主要间隔约 0.98–1.02 秒，最大 1.343 秒，近似 1 Hz。
- “采样时间”与“回调收到时间”不同。息屏时 Health Services 会缓存/批量回传；曾有 12:52:21.776 采样的数据到 12:54:09.242 才回调，等待约 107 秒。

### 3.3 连续性与生命周期

- MeasureClient：前台可获取；修正后息屏不会主动注销 callback，但 Watch6 息屏/后台后仍停止新数据并进入 stale。
- ExerciseClient：已证明息屏期间采样时间戳连续；Activity pause/stop 不结束会话，stop 后仍有样本回调。
- ExerciseClient 的“息屏近实时交付”未证明，已有证据反而显示可能长时间批量缓存。
- 当前产品实时路径不再等待 ExerciseClient 回调：服务同时注册 Watch6 的唤醒型 `Sensor.TYPE_HEART_RATE`，请求约 1 Hz 且 `maxReportLatencyUs=0`；注册成功时由直接传感器样本负责 Data Layer 发送，避免与随后补发的 Exercise 样本重复。
- `ExerciseForegroundService` 为 `START_STICKY`，能查询并恢复本应用持有的 exercise；SharedPreferences + StateFlow 持久化会话/统计显示。
- 正常结束路径调用 `endExercise()`、清 callback、停止前台服务；原子变量阻止重复 start/end。
- `MeasureCallback` 只在用户 Stop 或 ViewModel 真正 `onCleared` 时清理；`onPause`、`onStop`、Ambient、screen-off 不清理。

### 3.4 前台服务、屏幕与 WakeLock

- Exercise 使用 `foregroundServiceType="health"` 的前台服务和常驻低优先级通知。
- 当前实时路径使用有界滚动 `PARTIAL_WAKE_LOCK`：单次最长 15 分钟，运行 10 分钟后刷新；停止、异常、服务销毁和 Exercise 结束均显式释放。它不是无限期单次持锁，但会增加耗电，必须用 20/60 分钟测试量化。
- `MainActivity.onResume()` 在没有正式后台测试时设置 `FLAG_KEEP_SCREEN_ON`，因此普通交互会阻止自动息屏。
- 点击 10/20 分钟后台测试时会清除 `FLAG_KEEP_SCREEN_ON`，允许自然息屏。
- 不应为了“实时”增加无限期 WakeLock；当前采集仍受 Health Services、Wear OS 省电和厂商调度限制。

### 3.5 权限与 Manifest

手表 Manifest 当前声明：

- `BODY_SENSORS`（maxSdk 35）
- `BODY_SENSORS_BACKGROUND`（maxSdk 35）
- `android.permission.health.READ_HEART_RATE`
- `android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND`
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_HEALTH`

运行时逻辑：API 36+ 请求 `READ_HEART_RATE` 与 `READ_HEALTH_DATA_IN_BACKGROUND`；API 35 及以下按版本使用 `BODY_SENSORS`/`BODY_SENSORS_BACKGROUND`。

已知真机：Samsung Galaxy Watch6 SM-R960 / `wise6blue`，Android/Wear OS API 36，Health Services capability 支持 `HEART_RATE_BPM`。当前结论不能自动推广到其他 Wear OS 型号或系统版本。

## 4. 数据传输实现

### 4.1 Watch -> Phone

- API：Google Play services Wearable Data Layer `MessageClient` + `CapabilityClient`。
- capability：`heart_rate_phone_relay`，定义在手机 `res/values/wear.xml`。
- 路径：样本 `/hr/sample/v1`，ACK `/hr/ack/v1`。
- 只选 `FILTER_REACHABLE` 中第一个 `isNearby` 节点。
- 节点缓存 30 秒；发送失败时清缓存，后续样本会再次发现节点。
- 当前失败样本不会重发；节点查询正在进行时到达的新样本会直接返回，不进入队列。
- 没有显式心跳、持久缓存、序列窗口、退避策略或完整断线重连状态机。
- 手机端同时有 Application 运行时 listener 和 `WearableListenerService`；Activity 不持有 Data Layer 生命周期。

### 4.2 Phone -> PC

- 协议：UTF-8 JSON over UDP，手机 UI 默认 PC 端口 `9123`，PC 监听 `0.0.0.0:9123`。
- 手机只配置 PC IP/端口；PC 从 UDP 源地址自动识别手机。
- 每个包新建一个 `DatagramSocket`，发送后最多等待 1,000 ms 的 `pc_ack`。
- 转发使用单线程 executor。PC 不回 ACK 时只保留当前在途包和最新待发包；新的心率会覆盖尚未发送的旧包，避免离线期间形成无界积压。
- 心率采样和 Watch -> Phone 接收仍约为 1 Hz；Phone -> PC 只发送所选间隔到点时的最新样本，默认 5 秒，可选 1/2/5/10/30 秒。暂停只停止 Phone -> PC，不停止手表采集和 Phone 端接收。
- 手机 UDP JSON 增加 `phoneForwardIntervalSeconds`；PC 用它自适应 stale 超时，避免低频档被错误判为断线。
- `handleWatchSample` 已同步执行 sequence 判重，解决 Application listener 与 `WearableListenerService` 同时收到同一消息时的竞态；无重发、无离线缓存、无批量确认、无心跳。
- UDP 无连接，所以不存在 TCP 式连接保持；网络切换后的下一包会重新解析目标 IP，但目标 PC IP 不会自动发现或更新。
- 手机没有前台服务；Data Layer listener 能在 Activity 不可见时被系统调度，但长时间后台、锁屏和省电下的持续真实心率转发尚未验证。

### 4.3 数据包格式

Watch 发送 JSON 字段：

```json
{
  "version": 1,
  "type": "heart_rate",
  "sessionId": "<exercise start millis>",
  "sequence": 123,
  "sampleEpochMillis": 1784489431611,
  "watchReceivedEpochMillis": 1784489431701,
  "bpm": 70,
  "rawBpm": 70.0,
  "accuracy": "HrAccuracy(sensorStatus=ACCURACY_HIGH)",
  "watchBatteryPercent": 93,
  "watchScreenInteractive": false
}
```

手机追加 `phoneReceivedEpochMillis` 与 `phoneLocalIp` 后原样 UDP 转发。PC ACK 格式为：

```json
{"type":"pc_ack","sequence":123,"pcEpochMillis":1784489431800}
```

手机再经 Data Layer 回手表：`type=phone_ack`、`sequence`、`pcAck`、`phoneEpochMillis`、`error`。

### 4.4 已测连接与性能

- 2026-07-20 05:06:39–05:06:40，Watch 私有日志记录 `RelayTestActivity` 发送 `relay_test`，发现附近手机 `Richard 的 S24 Ultra`，消息 queued，随后收到同 sequence 的 `pcAck=true`。这只证明 Watch -> Phone -> PC -> Phone -> Watch 的单个诊断包回路。
- 诊断包明确不读传感器；PC 对非 `heart_rate` 包不发送 OSC。
- 没有持久 PC 日志或测试报告给出该包的端到端延迟。
- 没有证据证明息屏期间 Data Layer/UDP 持续保持、断网自动恢复或长期无丢包。
- Exercise 采样约 1 Hz，但正式三端传输的实际更新频率与延迟未测。

## 5. VRChat OSC 实现

PC 端当前配置和参数：

| 项目 | 当前实现 |
|---|---|
| OSC 目标 IP | 固定 `127.0.0.1`（代码没有 IP 输入框） |
| OSC 默认端口 | `9000`，WinForms UI 可修改 |
| `/avatar/parameters/HR_Value` | OSC Int32；钳制到 `0..999` 的完整 BPM，四条显示消息中最先发送 |
| `/avatar/parameters/HR_Hundreds` | OSC Int32；三位数 BPM 的百位 |
| `/avatar/parameters/HR_Tens` | OSC Int32；三位数 BPM 的十位 |
| `/avatar/parameters/HR_Ones` | OSC Int32；三位数 BPM 的个位 |
| `/avatar/parameters/HRValid` | OSC Bool；收到真实心率后 true，10 秒没有新的真实心率包后 false |
| `/avatar/parameters/HRPulse` | OSC Bool；PC 根据最近 BPM 本地生成，true 脉冲约 120 ms |
| `/avatar/parameters/HeartRate` | OSC Int，直接发送整数 BPM |
| `/avatar/parameters/HeartRateNormalized` | OSC Float，`clamp((BPM - 40) / 160, 0, 1)`，即 40–200 BPM 映射到 0–1 |
| `/avatar/parameters/HeartRateValid` | OSC Bool；旧版兼容有效状态参数 |

补充边界：

- 只有 `type == "heart_rate"` 才发送 BPM、数位、Valid 和 Pulse；`relay_test` 和 `phone_test` 只显示链路测试并 ACK，不进入 OSC，也不会延长真实心率有效期。
- 当前三位数显示把完整 BPM 钳制到 `0..999`，并按 `HR_Value → HR_Hundreds → HR_Tens → HR_Ones` 顺序发送；旧版 `HeartRate` 参数仍保留兼容。
- 没有平滑、滞回、限速或 BPM 跳动抑制。
- `VrcRealtimeHeartbeat.exe --self-test` 检查全部目标 OSC 地址和 4 字节对齐。本地集成测试另已证明 EXE 能对真实格式 UDP 包回 ACK，并向 UDP 9000 发出全部目标地址；这仍不等于 VRChat 实收。
- 仓库没有 Unity Animator、Expression Parameters、Expressions Menu 或 Avatar 显示组件。
- 当前没有证据表明 VRChat 已实际收到参数，更没有证据表明 Avatar 已显示 BPM。

## 6. 已完成测试

下表按证据记录；“无记录”不等于失败，只表示不能下结论。

| 测试项 | 条件与时长 | 真实结果 | 可否确定 | 仍需补测 |
|---|---|---|---|---|
| 亮屏前台 MeasureClient | SM-R960、前台贴肤；报告未记录单一连续时长 | 历史执行报告累计 166 个有效样本，56–98 BPM、35 个不同值、accuracy HIGH；0 invalid、0 ERROR | 能确定前台可读真实心率；不能确定长期稳定性 | 记录明确起止时间、间隔分位数和耗电 |
| MeasureClient 息屏/后台 | 修正生命周期后；至少复查至最后样本后 187.1 秒 | screen-off/Activity stop 时 callback 仍注册；最后样本停住，进入 stale；开放断档至少 187.1 秒 | 能确定不是应用主动注销；能确定该次 Watch6 上停止供数 | 可作为对照复测，不应继续当最终方案 |
| ExerciseClient 息屏 | 连续息屏 146.158 秒、真实 BPM | 146 个采样时间戳，最大采样间隔 1.343 秒；但约 107 秒批量回传 | 能确定持续采样；也能确定该次不是近实时回调 | 佩戴状态下运行 10/20/60 分钟并同时观察手机/PC接收时间 |
| 直接传感器息屏实时链路 | SM-R960 + SM-S928B + Windows EXE；Dozing、Activity STOP、正常佩戴；短时诊断 | 73–79 BPM 真实变化约 1 Hz；样本间隔约 1003–1004 ms、服务接收延迟 0–15 ms；手机逐包接收，PC 逐包匹配 ACK | 能确定短时 Watch→Phone→PC 息屏实时链路成立；不能推断长时耗电和稳定性 | 新模式正常佩戴 20 分钟，再正式 60 分钟 |
| Activity 后台/返回表盘 | Exercise 会话 ACTIVE；Activity stop 后观察数秒 | stop 时 service/callback ACTIVE；1–2 秒后仍收到真实样本 | 能确定短时间 Activity stop 不结束会话 | 长时间后台、锁屏、进程回收后恢复 |
| 正常佩戴 | 严格息屏窗口有 78–81 BPM、accuracy HIGH；另有前台 56–98 BPM | 能取得变化的真实样本 | 能确定传感器读数真实；不能形成正式耗电/长时间交付结论 | 单独执行 `ON_WRIST_REAL_USE` 至少 20 分钟，正式续航至少 60 分钟 |
| 从充电器取下且摘下手表 | `OFF_WRIST_BASELINE`，20分01.138秒，开始未充电且 `startWorn=false` | availability 已为 OFF_BODY，窗口内 0 样本、0 callback；服务/进程无重启、0 error/crash | 能确定未佩戴时没有有效心率；不能判断佩戴息屏表现 | 与 ON_WRIST 测试分开比较 |
| 10 分钟连续测试 | 无完成报告 | 无结果 | 否 | 佩戴、自然息屏、关闭 ADB 干扰，完整执行 |
| 20 分钟连续测试 | 仅 OFF_WRIST_BASELINE | 完整到时自动结束；但 0 样本 | 只对未佩戴场景有效 | 完整 ON_WRIST_REAL_USE 20 分钟 |
| 电池消耗 | OFF_WRIST 20分01秒；93% -> 91% | 粗略线性外推 5.99%/h | 不能作为正式耗电结论；百分比粒度和刚离充电器会干扰 | ON_WRIST ≥60 分钟，记录 ADB/屏幕/网络条件 |
| Wi-Fi ADB | 当前 `adb devices -l` 可见 SM-R960；历史曾成功 pair/connect | 当前连接正常，但手表同时出现 IP 与 mDNS 两个标识；无线端口会变化 | 不能得出长期不断线结论 | 数据测试期间不要把 ADB 当数据链路；另做连接稳定性观察 |
| 手机/PC 持续接收 | 单个 `relay_test` 于 05:06:39–40 获得 `pcAck=true` | 三端单包回路成功 | 只能证明单包联通 | 真实 `heart_rate` 至少 20 分钟，统计 Watch sent、Phone received/forwarded、PC packets、ACK、丢包与延迟 |
| VRChat OSC 持续接收 | 无真机/VRChat日志 | PC OSC 编码自检 PASS；没有 VRChat 接收证据 | 否 | VRChat 开启 OSC，以真实心率包观察参数日志和 Avatar 参数 |
| Avatar 显示 | 无 Unity 文件、无测试 | 未实现 | 是，能确定尚未开始 | 最后阶段再做 Animator/参数/显示效果 |

本地未提交证据：

- `SECOND_STAGE_TEST_REPORT.md`：Measure/Exercise 对照测试，最后修改 2026-07-18 13:01。
- `EXECUTION_REPORT.md`：早期 MeasureClient 与环境报告，最后修改 2026-07-18 05:24；其中“无手机/OSC、onStop 注销”等是当时事实，已被后续源码/报告取代，不能当当前状态。
- `test-results/20260720_033407_750_BATTERY_20_MIN_OFF_WRIST_BASELINE/`：20 分钟 off-wrist 的 JSON/TXT/events/分析。
- Watch 私有文件 `files/logs/hr_probe.log`：交接时约 937 KB；没有复制进 Git。

## 7. 当前主要问题

### 7.1 Exercise 回调会批处理；直接传感器实时路径仍需长测

- 已确认事实：146.158 秒息屏内采样时间戳连续，最大间隔 1.343 秒；部分样本约 107 秒后才批量回调。
- 当前推测：Health Services/Wear OS 为省电在息屏时对 Exercise 更新进行 batching；不是 Activity 注销 callback。
- 已尝试：从 MeasureClient 切换到 ExerciseClient + health FGS；修正按 `sampleEpochMillis` 统计，避免把批量等待误判为采样中断。
- 已解决的短时路径：直接唤醒型心率传感器 + 零报告延迟 + 有界滚动 WakeLock，在 Dozing/Activity STOP 时约 1 Hz 取得真实 BPM，并送达手机和 PC。
- 尚未验证：该路径佩戴 20/60 分钟的最大断档、耗电、系统回收、离腕再佩戴和网络切换表现。

### 7.2 三端真实心率短时成功，但没有正式续航报告

- 已确认事实：真实 `heart_rate` 在手表 Dozing 状态下逐秒到达手机和 Windows，Windows 逐包 ACK；仍没有 20/60 分钟长时传输报告。
- 当前推测：直接传感器已绕开 Exercise callback batching；剩余限制主要是耗电、手机后台策略、网络切换和系统回收。
- 已实现：Data Layer nearby capability、UDP ACK、Watch ACK 状态、PC stale 标志、手机 sequence 原子去重和离线最新包合并。
- 尚未验证：屏幕关闭、手机锁屏、网络切换、PC 暂停/恢复时的丢包、重连、缓存与延迟。

### 7.3 重连、重试与缓存不足

- 已确认事实：Watch 节点缓存 30 秒，失败后仅清节点；无样本重发。手机 UDP 超时 1 秒且无重试/历史缓存，单线程只保留最新待发包。PC 只做 10 秒 stale。
- 已解决：PC 不在线时不再为每个 1 Hz 样本排队；实测旧实现曾落后约 540 个序号，新实现保持在当前序号附近。
- 已尝试：下一样本重新发现节点、UDP ACK、同步 sequence 去重、最新待发包覆盖。
- 尚未验证：长断网恢复、队列上限、重复/乱序、多节点和包丢失行为。

### 7.4 测试数据与正式数据需要持续隔离

- 已确认事实：`relay_test`/`phone_test` 使用 BPM=72，但 PC 只对 `type=heart_rate` 发 OSC；诊断日志明确标为非传感器数据。
- 当前推测：未来若修改类型判断或测试入口，可能误把模拟值传入 Avatar。
- 已尝试：类型字段和 PC 端严格 `heart_rate` 判断。
- 尚未验证：自动化测试覆盖所有测试包绝不进入 OSC。

### 7.5 Avatar 层完全缺失

- 已确认事实：没有 Unity/Avatar 文件；没有百十个位参数；没有 VRChat 实收日志。
- 当前推测：直接用一个 Int 是否适合目标 Avatar 取决于 VRChat 参数预算和显示实现。
- 已尝试：PC 已准备 Int、Normalized Float、Valid Bool。
- 尚未验证：参数类型兼容、Expression Parameters、Animator、菜单、数字显示与 BPM 跳动处理。

### 7.6 其他限制

- 正常 UI 会 `FLAG_KEEP_SCREEN_ON`；测试自然息屏必须使用后台测试按钮或明确清除此 flag。
- 当前实时模式使用有界滚动 WakeLock，并在所有停止/异常路径释放；它是明确的耗电风险，不能跳过 20/60 分钟量化。
- ADB 是调试/取日志手段，不是正式传输链路。
- GitHub `v0.1.0` 是 Debug 预发布，未配置稳定 release signing；不同 Debug 签名来源不适合作长期覆盖升级。

## 8. 重要文件说明

### 手表端

- `app/src/main/java/best/nagikokoro/watch6heartrateprobe/MainActivity.kt`：入口 Activity、权限启动流程、Ambient/screen lifecycle、Compose UI、普通模式 keep-screen-on、后台测试清 flag。
- `HeartRateViewModel.kt`：模式状态机、Measure start/stop、生命周期日志、stale 检测、后台测试控制、Exercise 恢复命令。
- `HeartRateMeasureManager.kt`：MeasureClient capability、callback 注册/注销、数据点验证。
- `ExerciseForegroundService.kt`：ExerciseClient、health FGS、会话恢复/结束、样本处理、Data Layer 发送。
- `ExerciseSessionStore.kt`：SharedPreferences + StateFlow 持久化 Exercise 状态和统计。
- `BackgroundTestRecorder.kt`：10/20 分钟测试、原始事件、JSON/TXT 报告、采样/交付/电量统计。
- `WearHeartRateRelay.kt`：Capability 节点发现、MessageClient 样本发送、诊断包。
- `RelayProtocol.kt`：capability、路径、协议版本。
- `RelayStatusStore.kt`：手表 UI 的中转计数/ACK 状态（只在内存中）。
- `WatchProbeApplication.kt`、`PhoneRelayAckService.kt`：ACK listener。
- `RelayTestActivity.kt`：ADB 可触发的传输诊断入口；不读取传感器。
- `PermissionManager.kt`：API 分级权限。
- `DiagnosticLogger.kt`：`HR_PROBE` Logcat + 1 MiB × 3 私有滚动日志。
- `RuntimeDiagnostics.kt`：屏幕、电池、PID、服务状态字段。
- `ProbeModels.kt`：UI 状态、状态码、模式枚举。
- `app/src/main/AndroidManifest.xml`：权限、Activity、listener service、health FGS。

### 手机端

- `mobile/.../MobileMainActivity.kt`：PC IP/端口配置、状态与测试包 UI。
- `PhoneRelayRepository.kt`：Data Layer 样本解析、UDP 转发、ACK、目标配置。
- `PhoneRelayApplication.kt`：进程内 MessageClient listener。
- `PhoneRelayListenerService.kt`：后台 Data Layer listener。
- `RelayProtocol.kt`：样本/ACK 路径和默认端口。
- `mobile/src/main/res/values/wear.xml`：`heart_rate_phone_relay` capability。
- `mobile/src/main/AndroidManifest.xml`：INTERNET、ACCESS_NETWORK_STATE、手机 Activity/listener。

### PC / OSC

- `pc-bridge/src/HeartRateBridge/Program.cs`：当前 C# WinForms EXE 源码，UDP 9123、ACK、OSC 127.0.0.1:9000、数位拆分、HRPulse 和 stale。
- `pc-bridge/Build-Exe.ps1`：本地/GitHub 共用的单 EXE 构建及协议自测入口。
- `pc-bridge/HeartRateBridge.ps1`、`Start Heart Rate Bridge.cmd`：早期 PowerShell 版回退入口。
- `pc-bridge/README.md`：PC 使用与 OSC 参数摘要。

### 构建、配置、日志和说明

- `settings.gradle.kts`：`:app` 与 `:mobile` 两模块。
- `build.gradle.kts`：AGP 8.13.2、Kotlin 2.2.21。
- `app/build.gradle.kts`、`mobile/build.gradle.kts`：SDK、依赖、版本。
- `gradle/wrapper/gradle-wrapper.properties`：Gradle 8.13 + SHA-256。
- `.github/workflows/build.yml`：GitHub 构建两 APK、PC self-test、Artifacts。
- `.gitignore`：排除 build、测试结果、报告、日志、签名材料与 dist。
- `README.md`：总体使用说明；以源码和本文为准。
- `BACKGROUND_TEST_GUIDE.md`：后台测试和报告导出步骤。
- `EXECUTION_REPORT.md`、`SECOND_STAGE_TEST_REPORT.md`、`test-results/`：本机忽略文件，见第 6 节。
- Unity 相关文件：不存在。

## 9. 当前真实可用的运行方式

以下命令在本机路径下核对。新机器应替换 JDK/SDK 路径；无线手表端口会变化，先运行 `adb devices -l` 或 `adb mdns services`。

### 9.1 构建与 PC 自检

```powershell
$env:JAVA_HOME = 'D:\ANDORID\jbr'
$env:ANDROID_SDK_ROOT = 'C:\Users\wrq18\AppData\Local\Android\Sdk'
.\gradlew.bat :app:assembleDiagnosticDebug :app:assembleProductionDebug :mobile:assembleDebug --no-daemon

& powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File '.\pc-bridge\HeartRateBridge.ps1' -SelfTest

& powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File '.\pc-bridge\Build-Exe.ps1'
```

交接时复跑结果：Android `BUILD SUCCESSFUL in 31s`，76 tasks up-to-date；旧 PC 脚本输出 `HeartRateBridge protocol self-test: PASS`。2026-07-21 新 EXE 构建及内置协议自测通过，并以 BPM=72 的真实格式 UDP 包完成 ACK 和全部 OSC 地址回环验证。本地 Gradle 有 SDK XML v4/旧解析器 warning，但不阻断构建。

APK：

```text
app\build\outputs\apk\diagnostic\debug\app-diagnostic-debug.apk
app\build\outputs\apk\production\debug\app-production-debug.apk
mobile\build\outputs\apk\debug\mobile-debug.apk
```

### 9.2 ADB、安装与启动

交接时当前设备：手机 `192.168.100.150:34253` / `R5CX81QGFAV`（SM-S928B）；手表当前临时 ADB 标识 `192.168.100.162:41631`（SM-R960，2026-07-21 已复核）。无线 ADB 端口会变化；执行命令前重新运行 `adb devices -l`，并只选一个标识，避免 ambiguous device。

```powershell
$adb = 'C:\Users\wrq18\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb devices -l
& $adb mdns services

$watch = '192.168.100.162:41631' # 临时端口，必须按当时 devices/mdns 更新
$phone = '192.168.100.150:34253' # 也可用 USB 序列号 R5CX81QGFAV

& $adb -s $watch install -r 'D:\CODE\heartbeats\app\build\outputs\apk\production\debug\app-production-debug.apk'
& $adb -s $phone install -r 'D:\CODE\heartbeats\mobile\build\outputs\apk\debug\mobile-debug.apk'

& $adb -s $watch shell am start -n best.nagikokoro.watch6heartrateprobe/.MainActivity
& $adb -s $phone shell am start -n best.nagikokoro.watch6heartrateprobe/.mobile.MobileMainActivity
```

若需重新无线配对，配对端口与连接端口不是同一个：

```powershell
& $adb pair WATCH_IP:PAIR_PORT
& $adb connect WATCH_IP:ADB_PORT
```

### 9.3 启动采集

1. 手表打开应用。
2. 选择 `ExerciseClient Screen-off Test`。
3. 点击 `Start selected test`，授予前台心率和后台健康权限。
4. 等待 `Service=true`、Exercise `ACTIVE`、Callback=true 和真实 BPM。
5. 正式自然息屏测试应使用 `Start 10-minute screen-off test` 或 `Start 20-minute battery test`，它们会清除 keep-screen-on。
6. 只有用户 Stop/测试到时才正常结束 Exercise 会话。

### 9.4 日志与测试报告

```powershell
& $adb -s $watch logcat -s HR_PROBE
& $adb -s $phone logcat -s HR_RELAY

& $adb -s $watch shell run-as best.nagikokoro.watch6heartrateprobe ls -l files/logs files/tests
& $adb -s $watch shell run-as best.nagikokoro.watch6heartrateprobe cat files/logs/hr_probe.log
& $adb -s $watch exec-out run-as best.nagikokoro.watch6heartrateprobe cat files/tests/SESSION_ID.txt
& $adb -s $watch exec-out run-as best.nagikokoro.watch6heartrateprobe cat files/tests/SESSION_ID.json
& $adb -s $watch exec-out run-as best.nagikokoro.watch6heartrateprobe cat files/tests/SESSION_ID.events.jsonl
```

不要在正式息屏窗口持续执行 ADB 查询、Logcat 或输入事件；测试结束后再导出。

### 9.5 PC、链路诊断与 VRChat

```powershell
& '.\dist\windows-python\VrcRealtimeHeartbeat-Python.exe'
```

PC 默认自动监听 UDP 9123。手机填写 PC UI 显示的局域网 IPv4 与端口 9123。先用手机 `测试电脑` 验证 Phone->PC ACK。手机转发默认 5 秒，可选 1/2/5/10/30 秒；`暂停发送到电脑`不会停止手表持续采样。

Watch->Phone->PC 的诊断入口（不读取传感器，不进入 OSC）：

```powershell
& $adb -s $watch shell am start -n best.nagikokoro.watch6heartrateprobe/.RelayTestActivity
```

VRChat 当前只能这样做真实验证：在 VRChat Action Menu 开启 OSC，保持 PC bridge 的 `Forward to VRChat OSC` 勾选，启动手表 Exercise 真实采集，然后在 PC 日志确认 `type=heart_rate`，再观察 VRChat OSC/Avatar 参数。仓库没有自动化 VRChat 接收验证，也没有 Avatar 配置。不得用 `relay_test` 或手工 BPM=72 冒充真实心率测试。

## 10. 下一步建议（按优先级）

1. **正式验证新的息屏实时路径。** 先执行佩戴 20 分钟，再执行至少 60 分钟；同时统计直接传感器 `sampleEpochMillis`、服务接收时间、Phone receive、PC ACK、最大断档与耗电。
2. **验证返回表盘、锁屏和系统回收边界。** 已短时证明 Activity STOP/Dozing 可用，下一轮加入长时间后台、离腕再佩戴、手机锁屏和进程恢复。
3. **区分 ADB 调试与正式链路。** 正式窗口不依赖 ADB；ADB 只在前后读取状态/导出文件。正式路径必须是 Data Layer -> Phone UDP -> PC。
4. **测试正常佩戴实际耗电。** 至少 60 分钟，记录屏幕、网络、充电、采样/回调/传输计数；不要把 off-wrist 20 分钟结果当正式耗电。
5. **完善断线重连和后台保活。** 手机离线队列已改为最新包合并；下一步逐层处理 Watch->Phone 重试/有界缓存、ACK/重试和手机后台策略，每次只改一层并复测。
6. **稳定输出 VRChat OSC。** 用真实传感器数据验证 `HR_Value`、`HR_Hundreds`、`HR_Tens`、`HR_Ones`、`HRValid`、`HRPulse` 并记录 VRChat 实收；默认 5 秒数值更新和 PC 本地节拍已经分离，不再重复提高采样率。
7. **最后完善 Avatar 显示。** 再建立 Expression Parameters、Animator 和发卡/显示组件；根据参数预算决定 Int 直接驱动还是拆分数字。

当前建议第一实验的核心指标：`sample gap`、`callback delivery latency`、`Watch->Phone ACK latency`、`Phone->PC ACK latency`、每层 sequence 丢失率、最长无真实更新时长。不要用界面计数代替时间戳日志。

## 11. 禁止事项

新对话接手后不得：

- 在没有验证现有实现前重写整个项目。
- 随意更换通信架构；当前实际方案是 Data Layer -> Phone UDP -> PC OSC。
- 把 ADB 当作最终数据传输方案。
- 把 `relay_test`、`phone_test` 或任何模拟 BPM 当作真实传感器结果。
- 为追求持续运行而无限期持有 WakeLock。
- 未经说明就提高心率采样频率；当前代码没有控制采样频率。
- 同时修改采集、传输和 OSC 三层，导致无法定位问题。
- 删除 `DiagnosticLogger`、`BackgroundTestRecorder`、Relay 测试入口或已有测试报告。
- 把源码存在但未真机验证的功能写成“已完成”。
- 用 callback 收到时间代替 `sampleEpochMillis` 判断采样连续性。
- 把 off-wrist 电量/0样本结论推广到正常佩戴。
- 把 `EXECUTION_REPORT.md` 的早期架构结论当成当前源码状态；它是历史报告。

## 12. 接手时的一句话结论

当前项目已经有可构建且已短时真机贯通的 Watch/Phone/PC 三端：Watch6 在 Dozing、Activity STOP 时通过直接唤醒型心率传感器约 1 Hz 采样并送达手机，手机默认每 5 秒向 Windows 转发最新 BPM（可调、可暂停），Windows EXE 实际接收并 ACK；下一关键任务是完成佩戴状态下 20/60 分钟稳定性与续航测试，以及 VRChat OSC 实收验证。

### 2026-07-20 正常佩戴 20 分钟结果

- 测试编号：`20260720_203613_749_BATTERY_20_MIN_ON_WRIST_REAL_USE`。
- 1127 个已交付真实样本，已观察区间平均采样间隔 1001 ms、最大 1788 ms。
- 息屏交付延迟平均 81.286 秒、中位 74.259 秒、P95 195.698 秒、最大 243.698 秒；最长无有效心率 callback 245.126 秒。
- 明确存在缓存/批量交付：最大单批 243 个样本，816 个息屏采样在之后交付。
- 电量 100% -> 97%，20 分钟粗略外推约 9%/小时；只能作为快速估算。
- 旧版测试器在目标到时后立即停止 Exercise，最后已交付样本距 20 分钟窗口末端尚有 72.003 秒，因此只能证明已观察到的约 18 分 47 秒连续，不能严谨证明完整 20 分钟。电脑复算器已改为 `targetWindowCovered=false`、`continuousSampling=false`。
- 新版 APK 已修复尾部排空：目标到时后最多等待 5 分钟，直到收到覆盖窗口末端的样本才完成；耗电仍按目标时刻快照计算。

### 2026-07-20 严格息屏 10 分钟复测

- 测试编号：`20260720_223137_254_SCREEN_OFF_10_MIN_ON_WRIST_REAL_USE`。
- 已交付 202 个样本，已观察区间平均间隔 1007 ms、最大 1930 ms；但尾部仍缺 397.083 秒，不能证明完整窗口。
- 息屏 P95 交付延迟 178.946 秒，最长无有效 callback 845.041 秒，最大单批 155 个样本；再次证明无 CPU 唤醒条件时无法实时交付。
- 结束原因 `TARGET_DURATION_DRAIN_TIMEOUT`。根因是息屏时目标计时协程也被挂起，恢复时旧逻辑先按绝对时间判超时并停止 Exercise，尾部 callback 尚未来得及执行。
- 已修复竞态：息屏时不再自动超时停止；目标结束至少 5 分钟后，必须观察到用户亮屏并再等待 15 秒，仍无窗口末端样本才允许超时。

### 2026-07-20 23:58 严格息屏成功结果

- 测试编号：`20260720_235846_876_SCREEN_OFF_10_MIN_ON_WRIST_REAL_USE`。
- 完整 10 分钟窗口覆盖成功：596 个唯一样本，首样本延迟 406 ms、尾部缺口 781 ms、平均间隔 1006 ms、最大间隔 4685 ms；`targetWindowCovered=true`、`continuousSampling=true`。
- 无服务/进程重启、错误、崩溃或离腕事件；电脑与手表统计差异 0。
- 实时交付明确失败：息屏 P95 延迟 565.079 秒，最长无有效 callback 597.648 秒；亮屏后一次补发 591 个样本，563 个息屏样本全部在亮屏后交付。
- 尾部排空耗时 31.072 秒。电量 98% -> 98%，因系统只显示整数百分比，不能解释为零耗电。
- 下一实验已加入“实时交付实验（10分钟，较耗电）”：仅该类型使用最长 12 分钟且显式释放的 `PARTIAL_WAKE_LOCK`，用于验证 CPU 活跃是否恢复实时 callback 并测量代价。普通测试不持锁。

### 2026-07-21 正式版 APK 与 Python Windows 端

- Watch 模块新增 `diagnostic` 和 `production` 两个 product flavor。测试版保留探针、原始数据、续航测试和链路诊断；正式版固定使用 ExerciseClient，只显示 BPM、后台传输状态、手机状态、样本年龄、电量和启停按钮。
- 两个 Watch APK 必须保持同一 applicationId 和签名，才能继续与手机 Wear Data Layer 通信。因此它们是可相互覆盖、可回退的两个安装包，不能在同一块表上并存。
- 正式版入口为 `ProductionMainActivity`；production manifest 不导出 `RelayTestActivity`，并移除 `WAKE_LOCK` 权限。服务还有 `BuildConfig.PRODUCTION_EDITION` 编译期防线，覆盖安装保留旧测试状态时也不会进入高耗电诊断路径。前台服务通知通过包管理器查找当前 flavor 的 launcher，避免写死测试入口。
- Windows 首选实现新增到 `pc-python/`。运行时只依赖 Python 标准库，开发测试与打包使用 pytest、PyInstaller；旧 C# WinForms 版完整保留在 `pc-bridge/`。
- Python 版实现 UDP 回执、严格数据验证、动态超时、三位数 Int32 参数、兼容旧参数和 PC 本地 `HRPulse`。诊断包只显示链路成功并返回 ACK，不写入 Avatar 参数。
- Python 本地测试覆盖协议、非法输入、OSC 编码、三位数拆分与顺序、超时、心跳脉冲、运行时开关和真实 UDP 回环。
- GitHub Actions 已拆分为 Android、Python Windows、C# Windows 三个任务，同时产出 Watch 测试 APK、Watch 正式 APK、手机 APK、首选 Python EXE/ZIP 和旧 C# EXE/ZIP。
