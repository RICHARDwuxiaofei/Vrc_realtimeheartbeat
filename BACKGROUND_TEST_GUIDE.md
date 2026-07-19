# Background Delivery & Battery Test 操作指南

设备：Samsung Galaxy Watch6 SM-R960
包名：`best.nagikokoro.watch6heartrateprobe`

## 本轮 OFF_WRIST_BASELINE 20 分钟测试

1. 先佩戴手表并打开 Watch6 HR Probe。
2. 选择 `ExerciseClient Screen-off Test`。
3. 点击 `Start selected test`，等到：
   - Exercise 会话为 `ACTIVE`；
   - Service 运行为 `true`；
   - Exercise Callback 为 `true`；
   - 样本数至少增加 5 个；
   - 没有 Exercise 错误。
4. 选择 `OFF_WRIST_BASELINE`。
5. 从充电器取下手表。确认系统不再显示充电；20 分钟按钮会再次在代码中检查该状态。
6. 点击 `Start 20-minute battery test`。成功后应看到后台测试状态为 `ACTIVE` 和新的 sessionId。
7. 现在可以摘下手表去洗澡，让屏幕自然关闭。不要在测试期间运行 ADB 查询、持续 Logcat、模拟点击或为了 ADB 而唤醒手表。
8. 到达 20 分钟后，应用会自动生成报告并正常结束 ExerciseClient 和前台服务。锁屏密码不影响后台执行。
9. 回来后手动解锁，打开应用，选择 Exercise 模式并点击 `Export/View result`。

如果要提前结束，解锁后点击 `Stop test`。提前停止的报告会明确警告“未达到请求时长”，不能作为正式 20 分钟结果。

## 后续 ON_WRIST_REAL_USE 测试

佩戴状态下选择 `ON_WRIST_REAL_USE`，其余步骤相同。OFF_WRIST_BASELINE 和 ON_WRIST_REAL_USE 会写入不同 sessionId 与报告，不应混合比较。正式续航建议至少运行 60 分钟。

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

## 报告判定

报告分别给出：

- `continuousSampling`：依据 sampleEpochMillis 连续性和真实采样间隔。
- `nearRealtimeDelivery`：要求 p95 delivery latency ≤ 5 秒且 longestNoCallbackDuration ≤ 10 秒。
- `classification`：近实时交付、缓存补发、停止采样、服务/进程终止或数据不足。
- 电量百分比变化和简单每小时外推。

20 分钟测试只是一轮快速估算。若开始和结束电量相同，只能解释为“在系统 1% 显示粒度下未观察到变化”，不能声称零耗电。
