# Wear OS 心率链路功耗审计

最后更新：2026-07-22

## 当前结论

旧的普通后台模式为了得到约 1 Hz 的息屏实时交付，同时启用了：

- `ExerciseClient` 心率 Exercise；
- 唤醒型 `Sensor.TYPE_HEART_RATE`，1 秒采样请求、零报告延迟；
- 可持续续租的 `PARTIAL_WAKE_LOCK`；
- 每个有效样本一次 Data Layer `MessageClient.sendMessage()`；
- 每个样本读取一次电量、更新并持久化状态、打开并刷新一次诊断日志文件。

这个组合优先保证低延迟，不是低功耗方案。

2026-07-21 在 Galaxy Watch6 上读取到的 `batterystats` 证据：

- 应用 UID：`10000`；
- 前台服务累计：5 分 43 秒；
- `CONTINUOUS_REALTIME_RELAY` 部分唤醒锁归因时间：4 分 13 秒；
- 同一应用实际部分唤醒时间：4 分 51 秒；
- 心率传感器注册：6 分 25 秒；
- 该统计窗口对应用估算约 1.97 mAh，但窗口太短且 Health Services/传感器成本可能归因给系统进程，不能直接当作完整小时耗电率。

## 官方建议对应关系

- Wear OS 原则明确建议长时间健身会话不要自行持有 WakeLock，让 Health Services 在传感器回调时唤醒处理器。
- Health Services `BatchingMode.HEART_RATE_5_SECONDS` 就是为“把心率连续显示到手机或电视”等场景设计的：息屏时目标约 5 秒交付一次，但官方也说明它仍会比默认批处理耗电。
- Wear OS 功耗指南说明每一次 Data Layer 传输都会耗电，应该只发送真正会更新远端 UI 的状态。
- `SensorManager` 的零延迟唤醒型传感器适合最低延迟，不适合全天低功耗；能接受延迟时应使用批处理。

官方资料：

- <https://developer.android.com/training/wearables/principles>
- <https://developer.android.com/training/wearables/apps/power>
- <https://developer.android.com/health-and-fitness/health-services/active-data>
- <https://developer.android.com/reference/androidx/health/services/client/data/BatchingMode>
- <https://developer.android.com/training/wearables/data/client-types>

## 第一轮已完成 20 分钟真机验证的低功耗改动

普通“后台连续”模式改为：

1. 使用 `ExerciseClient`；若设备 capability 支持，则启用 `HEART_RATE_5_SECONDS`。
2. 普通模式不注册直接心率 `SensorManager` listener。
3. 普通模式不获取或续租手动 `PARTIAL_WAKE_LOCK`。
4. Exercise 回调里保留整批样本的原始时间戳统计，但只把最新有效 BPM 发给手机。
5. Watch -> Phone 额外限制为最快 5 秒一次。
6. 一批样本只更新一次 SharedPreferences、只写一条摘要日志。
7. 电池读取和状态持久化从每秒降为每分钟。

正式版默认仍走以上 5 秒省电路径。2026-07-22 起，正式版同时提供用户显式选择的“1 秒实时”档：复用已短时真机验证的唤醒型直接传感器、约 1 Hz 请求、零报告延迟和有界滚动 WakeLock。该档位用于低延迟场景，不应与默认省电档混淆；停止、异常、服务销毁和 Exercise 外部结束必须释放 WakeLock。

## 2026-07-23 第二轮外围功耗优化

持续 PPG 仍是主要耗电来源；本轮针对不会改善心率精度的外围开销：

1. 正式版样本状态只在内存中更新；开始、恢复、停止和异常等会话边界仍持久化，避免每 5 秒写 SharedPreferences。
2. 正式版热路径不再生成逐批诊断记录、写 Logcat 或打开并 flush 日志文件；测试版保持完整记录能力。
3. 手机节点成功解析后缓存到发送失败为止；手机不可达时发现重试退避 60 秒，发送失败退避 15 秒。
4. 正式版成功链路 ACK 约每 60 秒请求一次，诊断包和测试版仍逐包请求；Phone -> PC 的 UDP ACK 不变。
5. 5/10 秒省电档在 BPM 变化时按所选频率发送，BPM 不变时使用双间隔保活。
6. 新增 `10 秒超省电`档；手机上报给 PC 的有效间隔取手表与手机两端的较慢值，避免 PC 提前判定超时。
7. 后台服务检查从每秒降至每 5 秒，正式版息屏 UI ticker 从每秒降至每 15 秒；电池仍最多每分钟读取一次。

这些优化主要减少应用 CPU、闪存与 Data Layer/蓝牙活动，不能关闭 ExerciseClient 的连续 PPG。要判断实际收益，必须在同一手表、相近电量和佩戴条件下分别对旧 5 秒档、新 5 秒档和 10 秒档做至少 60 分钟 A/B 测试。

2026-07-21 正常佩戴 20 分钟验证结果：

- 1194 个测试窗口内唯一真实样本，平均采样间隔 1004.3 ms，最大 2005 ms；
- 息屏交付延迟平均 2058 ms、中位 2045 ms、P95 4056 ms、最大 4413 ms；
- 最长无有效 callback 6016 ms，历史缓存批次 0，最大单批 5 个样本；
- 无服务/进程重启、错误、崩溃或手动 WakeLock；
- 电量 97% -> 94%，20 分钟粗略外推约 9%/小时，只能作为整数电量精度下的快速估算。

同一轮数据暴露出严格 `>= 5000 ms` 节流边界：大量 Health Services 批次实际相距 4990–4999 ms，因而被跳过并形成约 10 秒更新。Watch 和 Phone 两端现统一允许最多 10%、且不超过 500 ms 的提前容差；使用旧数据回放后，P95 预计更新间隔从约 10.1 秒降至约 5.36 秒。两端同时移除了与 `WearableListenerService` 重复的 Application 运行时 listener，固定诊断包真机验证为接收 1 次、重复 0 次、ACK 1 次。

## 仍需验证的指标

20 分钟快速测试已通过连续采样和近实时交付判定；正式结论仍至少需要：

1. 修复版正常佩戴短测，确认手机到达间隔 P95 不再约为 10 秒且没有重复 Data Layer 事件。
2. 至少 60 分钟正常佩戴、返回表盘并息屏测试；测试窗口内不持续连接 Logcat。
3. 对至少 60 分钟结果统计采样间隔、callback 交付延迟、三端 ACK、断线恢复和实际耗电。
4. 5/10 秒省电档的 `batterystats` 中不得出现 `CONTINUOUS_REALTIME_RELAY`；1 秒实时档则需核对 WakeLock 只在会话内存在且停止后释放。

即使是 5/10 秒省电档，持续 PPG 也不可能达到系统“全天心率、几十分钟级同步”的功耗水平。1 秒实时档会进一步增加耗电，只适合确实需要低延迟时使用；日常优先尝试 10 秒档，若 VRChat 数值响应偏慢再切回默认 5 秒档。
