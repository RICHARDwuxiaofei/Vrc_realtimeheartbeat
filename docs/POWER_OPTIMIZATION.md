# Wear OS 心率链路功耗审计

最后更新：2026-07-21

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

## 已编译并完成 20 分钟真机验证的低功耗改动

普通“后台连续”模式改为：

1. 使用 `ExerciseClient`；若设备 capability 支持，则启用 `HEART_RATE_5_SECONDS`。
2. 普通模式不注册直接心率 `SensorManager` listener。
3. 普通模式不获取或续租手动 `PARTIAL_WAKE_LOCK`。
4. Exercise 回调里保留整批样本的原始时间戳统计，但只把最新有效 BPM 发给手机。
5. Watch -> Phone 额外限制为最快 5 秒一次。
6. 一批样本只更新一次 SharedPreferences、只写一条摘要日志。
7. 电池读取和状态持久化从每秒降为每分钟。

旧的直接传感器 + WakeLock 路径只保留在明确标注为“10 分钟实时交付实验（较耗电）”的诊断入口，便于做 A/B 对照，不再影响日常模式。

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
4. 普通模式的 `batterystats` 中不得出现 `CONTINUOUS_REALTIME_RELAY` 长 WakeLock。

即使优化成功，持续 PPG 和 5 秒实时交付仍不可能达到系统“全天心率、几十分钟级同步”的功耗水平。合理目标是显著低于旧的 1 Hz + 常驻 WakeLock + 每秒传输方案，同时保持 VRChat 可接受的约 5 秒更新。
