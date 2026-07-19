# CODEX 交接文档

最后核对时间：2026-07-20（Asia/Hong_Kong）

仓库：`D:\CODE\heartbeats`

核对基线：`main` / `42f9f0f Add GitHub cloud builds (#1)` / tag `v0.1.0`

Android applicationId（手表与手机共用）：`best.nagikokoro.watch6heartrateprobe`

> 本文只把当前源码、Manifest、构建结果、现有测试报告和可读取日志支持的内容写成事实。计划、代码存在但未真机验证的功能，以及历史报告中已被后续实现取代的结论均明确标注。
>
> 特别注意：`EXECUTION_REPORT.md`、`SECOND_STAGE_TEST_REPORT.md` 和 `test-results/` 当前被 `.gitignore` 忽略，不属于 Git 提交；新克隆只能从本文看到其结论摘要，不能直接取得原始文件。

## 0. 最终目标与当前边界

最终目标是从 Samsung Galaxy Watch 获取真实心率，在正常佩戴、手表息屏、Activity 后台的情况下持续传输，经手机和 Windows 中转后，通过 VRChat OSC 驱动 Avatar 参数，最终在发卡或其他显示组件上显示 BPM。

当前已经实现了各层代码，但没有完成“佩戴 + 息屏 + 长时间 + 真实心率 + 手机 + PC + VRChat + Avatar 显示”的端到端验证。最强的已验证结论是：Watch6 的 `ExerciseClient` 会在息屏时继续产生约 1 Hz 的采样时间戳，但 Health Services 可能在息屏期间缓存并批量回传，曾出现约 107 秒的回传等待；这会直接阻止下游真正实时更新。

## 1. 当前系统架构

当前源码实际实现的数据链路如下：

```text
Galaxy Watch6 PPG / Health Services
  -> Wear OS app (:app)
     - MeasureClient（前台对照探针）
     - ExerciseClient + health ForegroundService（后台候选方案）
  -> Google Play services Wearable Data Layer / MessageClient
     - 只选择 capability=heart_rate_phone_relay 且 isNearby 的手机节点
  -> Android phone app (:mobile)
  -> UDP JSON over LAN，默认目标 PC_PORT=9123
  -> Windows PowerShell bridge
  -> OSC UDP，固定目标 IP 127.0.0.1，默认端口 9000
  -> VRChat Avatar Parameters
  -> Unity Animator / Expression Parameters / Avatar 显示组件（尚未实现）
```

实现与验证必须分开理解：

- Watch `ExerciseClient` 采集：已实现，已在 SM-R960 真机验证。
- Watch -> Phone Data Layer：已实现；一个 `relay_test` 诊断包已验证能到手机。
- Phone -> PC UDP：已实现；同一诊断包取得 `pcAck=true`，证明单包到达 PC 并回执。
- PC -> VRChat OSC：代码已实现，OSC 编码自检通过；没有仓库证据证明 VRChat 实际收到。
- Avatar 参数与可视化：仓库中没有 Unity/Avatar 文件，尚未开始。

当前没有实现 Watch 直接连接 PC，也没有自定义 BLE/GATT 链路。Data Layer 的底层承载由 Google Play services 和配对设备管理，代码不能证明或强制“始终只走蓝牙”；不要把当前方案描述成自研 BLE 协议。

## 2. 各端代码状态

| 部分 | 状态 | 当前事实 |
|---|---|---|
| 手表端应用 | 部分完成 / 待验证 | 两种 Health Services 模式、权限、日志、后台测试记录、健康前台服务和 Data Layer 发送均已实现。MeasureClient 仅保留为对照探针；ExerciseClient 是后台候选方案。真实心率采集和 146.158 秒息屏采样已验证；息屏端到端实时传输未验证。 |
| Android 手机端 | 部分完成 / 待验证 | Material 3 UI、Data Layer listener、PC IP/端口持久化、UDP 转发、1 秒 ACK 等待、ACK 回手表均已实现。已安装在 SM-S928B，诊断包单次三端回执成功；真实心率长时间后台转发没有日志证据。 |
| PC 中转程序 | 部分完成 / 待验证 | WinForms PowerShell UDP 接收器、ACK、BPM UI、OSC 编码和 stale 逻辑已实现。`-SelfTest` 通过，诊断包曾获得 PC ACK；持续真实心率接收与 VRChat 实收未验证。 |
| VRChat OSC 输出模块 | 部分完成 / 待验证 | PC 端能构造并发送 Int/Float/Bool OSC 数据包。只验证了编码自检，未验证 VRChat 或 Avatar 实际响应。 |
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
- `ExerciseForegroundService` 为 `START_STICKY`，能查询并恢复本应用持有的 exercise；SharedPreferences + StateFlow 持久化会话/统计显示。
- 正常结束路径调用 `endExercise()`、清 callback、停止前台服务；原子变量阻止重复 start/end。
- `MeasureCallback` 只在用户 Stop 或 ViewModel 真正 `onCleared` 时清理；`onPause`、`onStop`、Ambient、screen-off 不清理。

### 3.4 前台服务、屏幕与 WakeLock

- Exercise 使用 `foregroundServiceType="health"` 的前台服务和常驻低优先级通知。
- 没有使用 `PowerManager.WakeLock`，全仓库不存在 `newWakeLock/acquire/release`。
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
- 转发使用单线程 executor。若 PC 不回 ACK，每个样本都可能占用约 1 秒并形成积压风险。
- 仅按“上一个 sequence”去重；无重发、无离线缓存、无批量确认、无心跳。
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
| `/avatar/parameters/HeartRate` | OSC Int，直接发送整数 BPM |
| `/avatar/parameters/HeartRateNormalized` | OSC Float，`clamp((BPM - 40) / 160, 0, 1)`，即 40–200 BPM 映射到 0–1 |
| `/avatar/parameters/HeartRateValid` | OSC Bool；收到首个真实包后 true，10 秒无任何新 UDP 包后 false |

补充边界：

- 只有 `type == "heart_rate"` 才发送 BPM/Normalized/Valid；`relay_test` 和 `phone_test` 只显示链路测试并 ACK，不进入 OSC。
- 当前没有拆分百位、十位、个位参数。
- 没有平滑、滞回、限速或 BPM 跳动抑制。
- `HeartRateBridge.ps1 -SelfTest` 只检查 OSC Int 地址编码和 4 字节对齐，不向 UDP 9000 发包，也不证明 VRChat 接收。
- 仓库没有 Unity Animator、Expression Parameters、Expressions Menu 或 Avatar 显示组件。
- 当前没有证据表明 VRChat 已实际收到参数，更没有证据表明 Avatar 已显示 BPM。

## 6. 已完成测试

下表按证据记录；“无记录”不等于失败，只表示不能下结论。

| 测试项 | 条件与时长 | 真实结果 | 可否确定 | 仍需补测 |
|---|---|---|---|---|
| 亮屏前台 MeasureClient | SM-R960、前台贴肤；报告未记录单一连续时长 | 历史执行报告累计 166 个有效样本，56–98 BPM、35 个不同值、accuracy HIGH；0 invalid、0 ERROR | 能确定前台可读真实心率；不能确定长期稳定性 | 记录明确起止时间、间隔分位数和耗电 |
| MeasureClient 息屏/后台 | 修正生命周期后；至少复查至最后样本后 187.1 秒 | screen-off/Activity stop 时 callback 仍注册；最后样本停住，进入 stale；开放断档至少 187.1 秒 | 能确定不是应用主动注销；能确定该次 Watch6 上停止供数 | 可作为对照复测，不应继续当最终方案 |
| ExerciseClient 息屏 | 连续息屏 146.158 秒、真实 BPM | 146 个采样时间戳，最大采样间隔 1.343 秒；但约 107 秒批量回传 | 能确定持续采样；也能确定该次不是近实时回调 | 佩戴状态下运行 10/20/60 分钟并同时观察手机/PC接收时间 |
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

### 7.1 息屏采样连续，但回调/下游不是近实时

- 已确认事实：146.158 秒息屏内采样时间戳连续，最大间隔 1.343 秒；部分样本约 107 秒后才批量回调。
- 当前推测：Health Services/Wear OS 为省电在息屏时对 Exercise 更新进行 batching；不是 Activity 注销 callback。
- 已尝试：从 MeasureClient 切换到 ExerciseClient + health FGS；修正按 `sampleEpochMillis` 统计，避免把批量等待误判为采样中断。
- 尚未验证：佩戴 10/20/60 分钟时 batching 规律；是否有受支持的 Exercise 配置或平台机制能获得更及时更新；传到手机/PC后的真实延迟。

### 7.2 正式三端链路只有单包诊断，没有真实心率续航

- 已确认事实：`relay_test` 单包完整回执成功；没有真实 `heart_rate` 长时传输报告。
- 当前推测：代码可以转发 Exercise 样本，但屏幕关闭时的批量回调决定了下游更新上限；手机后台限制和 UDP ACK 等待还可能增加丢包/积压。
- 已尝试：Data Layer nearby capability、UDP ACK、Watch ACK 状态、PC stale 标志。
- 尚未验证：屏幕关闭、手机锁屏、网络切换、PC 暂停/恢复时的丢包、重连、缓存与延迟。

### 7.3 重连、重试与缓存不足

- 已确认事实：Watch 节点缓存 30 秒，失败后仅清节点；无样本重发。手机 UDP 超时 1 秒且无重试/缓存，单线程转发。PC 只做 10 秒 stale。
- 当前推测：PC 不在线时手机 executor 可能接近每包阻塞 1 秒并积压；网络恢复后不能补齐历史数据。
- 已尝试：下一样本重新发现节点、UDP ACK、last sequence 去重。
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
- 没有 WakeLock，这是有意的安全边界。
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

- `pc-bridge/HeartRateBridge.ps1`：WinForms、UDP 9123、ACK、OSC 127.0.0.1:9000、stale。
- `pc-bridge/Start Heart Rate Bridge.cmd`：双击入口。
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
.\gradlew.bat :app:assembleDebug :mobile:assembleDebug --no-daemon

& powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File '.\pc-bridge\HeartRateBridge.ps1' -SelfTest
```

交接时复跑结果：Android `BUILD SUCCESSFUL in 31s`，76 tasks up-to-date；PC 输出 `HeartRateBridge protocol self-test: PASS`。本地 Gradle 有 SDK XML v4/旧解析器 warning，但不阻断构建。

APK：

```text
app\build\outputs\apk\debug\app-debug.apk
mobile\build\outputs\apk\debug\mobile-debug.apk
```

### 9.2 ADB、安装与启动

交接时当前设备：手机 `R5CX81QGFAV`（SM-S928B）；手表当前 IP 标识 `192.168.100.132:39247`（SM-R960）。手表同时存在一个 mDNS 标识，执行命令时只选一个，避免 ambiguous device。

```powershell
$adb = 'C:\Users\wrq18\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb devices -l
& $adb mdns services

$watch = '192.168.100.132:39247' # 临时端口，必须按当时 devices/mdns 更新
$phone = 'R5CX81QGFAV'

& $adb -s $watch install -r 'D:\CODE\heartbeats\app\build\outputs\apk\debug\app-debug.apk'
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
& '.\pc-bridge\Start Heart Rate Bridge.cmd'
```

PC 默认自动监听 UDP 9123。手机填写 PC UI 显示的局域网 IPv4 与端口 9123。先用手机 `发送测试包` 验证 Phone->PC ACK。

Watch->Phone->PC 的诊断入口（不读取传感器，不进入 OSC）：

```powershell
& $adb -s $watch shell am start -n best.nagikokoro.watch6heartrateprobe/.RelayTestActivity
```

VRChat 当前只能这样做真实验证：在 VRChat Action Menu 开启 OSC，保持 PC bridge 的 `Forward to VRChat OSC` 勾选，启动手表 Exercise 真实采集，然后在 PC 日志确认 `type=heart_rate`，再观察 VRChat OSC/Avatar 参数。仓库没有自动化 VRChat 接收验证，也没有 Avatar 配置。不得用 `relay_test` 或手工 BPM=72 冒充真实心率测试。

## 10. 下一步建议（按优先级）

1. **单独证明手表息屏后是否持续采集心率。** 执行佩戴状态 `ON_WRIST_REAL_USE` 10/20 分钟，先只分析 `sampleEpochMillis`；不要同时改传输代码。
2. **单独证明息屏后是否持续、近实时传输。** 同一轮同时记录 callback receive time、Watch Data Layer queued/ACK、Phone receive/forward/ACK、PC receive；区分采样连续和批量交付。
3. **区分 ADB 调试与正式链路。** 正式窗口不依赖 ADB；ADB 只在前后读取状态/导出文件。正式路径必须是 Data Layer -> Phone UDP -> PC。
4. **测试正常佩戴实际耗电。** 至少 60 分钟，记录屏幕、网络、充电、采样/回调/传输计数；不要把 off-wrist 20 分钟结果当正式耗电。
5. **完善断线重连和后台保活。** 在证据明确后逐层处理：Watch->Phone 重试/有界缓存；Phone UDP 有界队列、ACK/重试；手机后台策略；每次只改一层并复测。
6. **稳定输出 VRChat OSC。** 先用真实传感器数据验证 `HeartRate`、`Normalized`、`Valid`，记录 VRChat 实收；再决定限速、平滑和异常值策略。
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

当前项目已经有可构建的 Watch/Phone/PC 三端雏形，并证明 Watch6 ExerciseClient 在约 2.4 分钟息屏窗口内持续产生约 1 Hz 真实采样；但样本可能被系统缓存约 107 秒后才回调，真实心率在息屏时能否近实时穿过 Phone/PC 到达 VRChat 仍是最关键、尚未解决的问题。
