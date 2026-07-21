# Background Delivery & Battery Test 操作指南

设备：Samsung Galaxy Watch6 SM-R960
包名：`best.nagikokoro.watch6heartrateprobe`

## 本轮 OFF_WRIST_BASELINE 20 分钟测试

1. 先佩戴手表并打开 Watch6 HR Probe。
2. 选择“后台连续”。
3. 点击“开始后台心率测量”，等到：
   - Exercise 会话为 `ACTIVE`；
   - Service 运行为 `true`；
   - Exercise Callback 为 `true`；
   - 样本数至少增加 5 个；
   - 没有 Exercise 错误。
4. 选择 `OFF_WRIST_BASELINE`。
5. 从充电器取下手表并摘下，等待 Availability 明确变为 `UNAVAILABLE_DEVICE_OFF_BODY`。测试入口会核对未佩戴状态，避免误标场景。
6. 点击“快速续航 20分钟”。成功后应看到后台测试状态为“进行中”和新的测试编号。
7. 让屏幕自然关闭。不要在测试期间运行 ADB 查询、持续 Logcat、模拟点击或为了 ADB 而唤醒手表。
8. 到达目标时长后继续保持息屏至少 5 分钟。随后亮屏并让应用保持开启至少 15 秒，让 Health Services 有机会交付尾部缓存；在亮屏前程序不会仅因绝对超时而抢先停止 Exercise。
9. 尾部样本覆盖目标窗口后应用自动生成报告并结束 ExerciseClient；若亮屏 15 秒后仍未覆盖才生成超时报告。续航电量仍取目标窗口边界快照，不把排空等待算入耗电窗口。

如果要提前结束，解锁后点击 `Stop test`。提前停止的报告会明确警告“未达到请求时长”，不能作为正式 20 分钟结果。

## 后续 ON_WRIST_REAL_USE 测试

佩戴状态下选择“正常佩戴”，保持 Availability 为 `AVAILABLE`，从充电器取下后运行“快速续航 20分钟”。入口会核对已佩戴和未充电状态。OFF_WRIST_BASELINE 和 ON_WRIST_REAL_USE 会写入不同测试编号与报告，不应混合比较。

先完成一轮至少 20 分钟的佩戴测试，用于确认息屏回调交付行为。通过后再点击 `Start 60-minute formal test` 做正式续航；这个入口只允许 `ON_WRIST_REAL_USE`，并要求未充电。测试开始后让手表自然息屏，期间不依赖 ADB。

若普通息屏测试确认采样连续但回调被深睡缓存，可使用“实时交付实验（10分钟，较耗电）”做受控对照。该入口要求正常佩戴且未充电，只在这一轮持有最长 12 分钟的 `PARTIAL_WAKE_LOCK`，并在完成、停止或服务销毁时显式释放。普通息屏、20 分钟与 60 分钟测试不持有该锁。此实验用于判断保持 CPU 活跃能否把 callback 延迟压回实时范围，同时量化额外耗电，不应直接当作最终续航结果。

## ADB 重新连接与导出

配对端口只用于 `adb pair`。实际连接端口会变化，可先查看 mDNS：

```powershell
$adb = Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe'
$watch = 'WATCH_IP:ADB_PORT'
& $adb mdns services
& $adb connect $watch
& $adb devices -l
```

每次应以 `adb mdns services` 的 `_adb-tls-connect._tcp` 结果填写 `$watch`；无线调试连接端口可能变化。

列出测试文件：

```powershell
& $adb -s $watch shell run-as best.nagikokoro.watch6heartrateprobe ls -la files/tests
```

读取文本报告、JSON 报告和原始批次事件：

```powershell
& $adb -s $watch exec-out run-as best.nagikokoro.watch6heartrateprobe cat files/tests/SESSION_ID.txt
& $adb -s $watch exec-out run-as best.nagikokoro.watch6heartrateprobe cat files/tests/SESSION_ID.json
& $adb -s $watch exec-out run-as best.nagikokoro.watch6heartrateprobe cat files/tests/SESSION_ID.events.jsonl
```

导出到电脑当前目录：

```powershell
& $adb -s $watch exec-out run-as best.nagikokoro.watch6heartrateprobe cat files/tests/SESSION_ID.txt > SESSION_ID.txt
& $adb -s $watch exec-out run-as best.nagikokoro.watch6heartrateprobe cat files/tests/SESSION_ID.json > SESSION_ID.json
& $adb -s $watch exec-out run-as best.nagikokoro.watch6heartrateprobe cat files/tests/SESSION_ID.events.jsonl > SESSION_ID.events.jsonl
```

测试窗口结束前不要执行这些命令。

也可以在测试结束后由电脑自动选择最新报告、导出三个原始文件并独立复算连续性：

```powershell
.\pc-bridge\WatchTestReport.ps1 -Watch 'WATCH_IP:ADB_PORT'
```

该工具不经过手机，也不参与实时传输；它只在测试结束后使用 ADB，并额外生成 `.pc-analysis.json` 和 `.pc-analysis.txt` 供交叉核对。

## 报告判定

报告分别给出：

- `continuousSampling`：依据 sampleEpochMillis 连续性和真实采样间隔。
- `targetWindowCovered`、`firstSampleDelayMs`、`lastSampleToWindowEndMs`：同时检查测试窗口首尾是否有样本覆盖；仅检查已收到样本之间的最大间隔不再算作完整连续。
- `nearRealtimeDelivery`：使用息屏期间的 p95 delivery latency，要求不超过 5 秒，且最长无有效心率回调不超过 10 秒。没有心率点的普通 ExerciseUpdate 不会重置该计时。
- `classification`：近实时交付、缓存补发、停止采样、服务/进程终止或数据不足。
- `screenOffAverage/Median/P95/MaxDeliveryLatencyMs`：只统计采样发生在息屏窗口内的交付延迟。
- `historicalCallbackBatchCount`、`maxCallbackBatchSampleCount`、`screenOffSamplesDeliveredAfterWake` 与 `batchedDeliveryDetected`：直接判断息屏缓存和亮屏后补发。
- 电量百分比变化和简单每小时外推。

20 分钟测试只是一轮快速估算。若开始和结束电量相同，只能解释为“在系统 1% 显示粒度下未观察到变化”，不能声称零耗电。
