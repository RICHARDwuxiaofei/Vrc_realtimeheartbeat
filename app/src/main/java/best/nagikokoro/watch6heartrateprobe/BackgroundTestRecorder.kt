package best.nagikokoro.watch6heartrateprobe

import android.content.Context
import android.os.Process
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

enum class BackgroundTestType(
    val displayName: String,
    val durationMillis: Long,
    val requiresDeliveryWakeLock: Boolean = false,
) {
    SCREEN_OFF_10_MIN("10 分钟息屏交付测试", 10 * 60_000L),
    REALTIME_DELIVERY_10_MIN("10 分钟实时交付实验", 10 * 60_000L, requiresDeliveryWakeLock = true),
    BATTERY_20_MIN("20 分钟快速续航测试", 20 * 60_000L),
    FORMAL_60_MIN("60 分钟正式续航测试", 60 * 60_000L),
}

enum class WearScenario(val displayName: String) {
    OFF_WRIST_BASELINE("未佩戴基线"),
    ON_WRIST_REAL_USE("正常佩戴"),
}

enum class BackgroundTestState {
    IDLE,
    ACTIVE,
    COMPLETED,
    STOPPED,
    ERROR,
    ABNORMAL_TERMINATION,
}

enum class BackgroundTestTickResult {
    COMPLETE,
    DRAIN_TIMEOUT,
}

data class BackgroundTestSnapshot(
    val state: BackgroundTestState = BackgroundTestState.IDLE,
    val sessionId: String = "--",
    val testType: String = "--",
    val scenario: WearScenario = WearScenario.ON_WRIST_REAL_USE,
    val startMillis: Long? = null,
    val targetEndMillis: Long? = null,
    val targetReachedMillis: Long? = null,
    val endMillis: Long? = null,
    val startBatteryPercent: Int? = null,
    val currentBatteryPercent: Int? = null,
    val targetEndBatteryPercent: Int? = null,
    val startCharging: Boolean? = null,
    val targetEndCharging: Boolean? = null,
    val startWorn: Boolean? = null,
    val totalSamples: Long = 0,
    val callbackBatchCount: Long = 0,
    val samplesDeliveredWhileScreenOff: Long = 0,
    val callbacksDeliveredWhileScreenOff: Long = 0,
    val staleEventCount: Long = 0,
    val availabilityChangeCount: Long = 0,
    val serviceRestartCount: Long = 0,
    val processRestartCount: Long = 0,
    val screenOnCount: Long = 0,
    val screenOffCount: Long = 0,
    val lastScreenOnMillis: Long? = null,
    val errorCount: Long = 0,
    val crashCount: Long = 0,
    val lastCallbackReceiveMillis: Long? = null,
    val latestSampleEpochMillis: Long? = null,
    val longestNoCallbackDurationMs: Long = 0,
    val latestReportJsonPath: String = "--",
    val latestReportTextPath: String = "--",
    val latestReportText: String = "--",
    val warning: String = "--",
) {
    val isActive: Boolean get() = state == BackgroundTestState.ACTIVE
    val isDraining: Boolean get() = isActive && targetReachedMillis != null
}

data class RecordedHeartRatePoint(
    val sampleEpochMillis: Long,
    val bpm: Double,
    val accuracy: String,
)

class BackgroundTestRecorder private constructor(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val testsDir = File(context.filesDir, "tests").apply { mkdirs() }
    private val _state = MutableStateFlow(load())
    val state: StateFlow<BackgroundTestSnapshot> = _state.asStateFlow()

    @Synchronized
    fun start(
        type: BackgroundTestType,
        scenario: WearScenario,
        exercise: ExerciseSessionSnapshot,
        charging: Boolean?,
    ): Result<BackgroundTestSnapshot> = runCatching {
        check(!_state.value.isActive) { "已有后台测试正在运行" }
        check(exercise.serviceRunning) { "后台心率服务尚未运行" }
        check(exercise.sessionState == ExerciseSessionState.ACTIVE) { "后台心率会话尚未进入运行状态" }
        check(exercise.callbackRegistered) { "后台心率回调尚未注册" }
        check(exercise.sampleCount >= 5) { "开始测试前至少需要 5 个有效心率样本" }
        check(exercise.lastError == "--") { "后台心率测量发生错误：${exercise.lastError}" }
        val worn = wornFromAvailability(exercise.availability)
        when (scenario) {
            WearScenario.OFF_WRIST_BASELINE -> check(worn == false) {
                "未佩戴基线要求手表明确识别为未佩戴"
            }
            WearScenario.ON_WRIST_REAL_USE -> check(worn == true) {
                "正常佩戴测试要求心率数据可用，请先正确佩戴手表"
            }
        }
        if (type != BackgroundTestType.SCREEN_OFF_10_MIN) {
            check(charging == false) { "续航测试要求先断开充电器" }
        }
        if (type == BackgroundTestType.FORMAL_60_MIN) {
            check(scenario == WearScenario.ON_WRIST_REAL_USE) {
                "60 分钟正式续航测试只允许正常佩戴场景"
            }
        }

        val now = System.currentTimeMillis()
        val sessionId = SESSION_ID_FORMATTER.format(Instant.ofEpochMilli(now)) +
            "_${type.name}_${scenario.name}"
        val battery = context.batteryPercent()
        val next = BackgroundTestSnapshot(
            state = BackgroundTestState.ACTIVE,
            sessionId = sessionId,
            testType = type.name,
            scenario = scenario,
            startMillis = now,
            targetEndMillis = now + type.durationMillis,
            startBatteryPercent = battery,
            currentBatteryPercent = battery,
            startCharging = charging,
            startWorn = worn,
        )
        eventsFile(sessionId).writeText("")
        writeEvent(
            sessionId,
            JSONObject()
                .put("event", "TEST_START")
                .put("epochMillis", now)
                .put("sessionId", sessionId)
                .put("testType", type.name)
                .put("scenario", scenario.name)
                .put("targetDurationMs", type.durationMillis)
                .putNullable("startBatteryPercent", battery)
                .putNullable("startCharging", charging)
                .putNullable("startWorn", worn)
                .put("exerciseState", exercise.sessionState.name)
                .put("foregroundServiceRunning", exercise.serviceRunning)
                .putNullable("currentBpm", exercise.bpm)
                .put("pid", Process.myPid())
                .put("screenInteractive", context.isScreenInteractive())
                .put("availability", exercise.availability),
        )
        // Test boundaries are rare and must survive an immediate process loss.
        update(next, persistSynchronously = true)
        next
    }

    @Synchronized
    fun recordCallbackBatch(receiveMillis: Long, points: List<RecordedHeartRatePoint>) {
        val current = _state.value
        if (!current.isActive) return
        val interactive = context.isScreenInteractive()
        val validPoints = points
            .filter { it.bpm.isFinite() && it.bpm in 20.0..300.0 }
            .sortedBy { it.sampleEpochMillis }
        val ages = validPoints.map { (receiveMillis - it.sampleEpochMillis).coerceAtLeast(0L) }
        val historical = ages.any { it > HISTORICAL_SAMPLE_THRESHOLD_MS }
        val pointArray = JSONArray().apply {
            validPoints.forEach { point ->
                put(
                    JSONObject()
                        .put("sampleEpochMillis", point.sampleEpochMillis)
                        .put("bpm", point.bpm)
                        .put("accuracy", point.accuracy)
                        .put("deliveryLatencyMs", (receiveMillis - point.sampleEpochMillis).coerceAtLeast(0L)),
                )
            }
        }
        writeEvent(
            current.sessionId,
            JSONObject()
                .put("event", "CALLBACK_BATCH")
                .put("callbackReceiveEpochMillis", receiveMillis)
                .put("screenInteractive", interactive)
                .put("batchSampleCount", validPoints.size)
                .putNullable("oldestSampleAgeMs", ages.maxOrNull())
                .putNullable("newestSampleAgeMs", ages.minOrNull())
                .put("containsHistoricalSamples", historical)
                .put("samples", pointArray),
        )
        // ExerciseUpdate callbacks without valid heart-rate points are not
        // heart-rate delivery and must not reset the no-delivery timer.
        if (validPoints.isEmpty()) return
        val noCallback = current.lastCallbackReceiveMillis
            ?.let { (receiveMillis - it).coerceAtLeast(0L) }
            ?: current.startMillis?.let { (receiveMillis - it).coerceAtLeast(0L) }
            ?: 0L
        update(
            current.copy(
                totalSamples = current.totalSamples + validPoints.size,
                callbackBatchCount = current.callbackBatchCount + 1,
                samplesDeliveredWhileScreenOff = current.samplesDeliveredWhileScreenOff +
                    if (interactive) 0 else validPoints.size,
                callbacksDeliveredWhileScreenOff = current.callbacksDeliveredWhileScreenOff +
                    if (interactive) 0 else 1,
                lastCallbackReceiveMillis = receiveMillis,
                latestSampleEpochMillis = maxOf(
                    current.latestSampleEpochMillis ?: Long.MIN_VALUE,
                    validPoints.maxOf { it.sampleEpochMillis },
                ),
                longestNoCallbackDurationMs = maxOf(current.longestNoCallbackDurationMs, noCallback),
            ),
        )
    }

    @Synchronized
    fun recordScreen(interactive: Boolean, epochMillis: Long = System.currentTimeMillis()) {
        val current = _state.value
        if (!current.isActive) return
        writeEvent(
            current.sessionId,
            JSONObject()
                .put("event", if (interactive) "SCREEN_ON" else "SCREEN_OFF")
                .put("epochMillis", epochMillis),
        )
        update(
            current.copy(
                screenOnCount = current.screenOnCount + if (interactive) 1 else 0,
                screenOffCount = current.screenOffCount + if (interactive) 0 else 1,
                lastScreenOnMillis = if (interactive) epochMillis else current.lastScreenOnMillis,
            ),
        )
    }

    @Synchronized
    fun recordAvailability(availability: String, epochMillis: Long = System.currentTimeMillis()) {
        val current = _state.value
        if (!current.isActive) return
        writeEvent(
            current.sessionId,
            JSONObject()
                .put("event", "AVAILABILITY")
                .put("epochMillis", epochMillis)
                .put("availability", availability)
                .putNullable("worn", wornFromAvailability(availability)),
        )
        update(current.copy(availabilityChangeCount = current.availabilityChangeCount + 1))
    }

    @Synchronized
    fun recordStale(ageMillis: Long) {
        val current = _state.value
        if (!current.isActive) return
        writeEvent(
            current.sessionId,
            JSONObject()
                .put("event", "STALE")
                .put("epochMillis", System.currentTimeMillis())
                .put("dataAgeMillis", ageMillis),
        )
        update(current.copy(staleEventCount = current.staleEventCount + 1))
    }

    @Synchronized
    fun recordWakeLock(acquired: Boolean, reason: String, timeoutMillis: Long? = null) {
        val current = _state.value
        if (!current.isActive) return
        writeEvent(
            current.sessionId,
            JSONObject()
                .put("event", if (acquired) "WAKE_LOCK_ACQUIRED" else "WAKE_LOCK_RELEASED")
                .put("epochMillis", System.currentTimeMillis())
                .put("reason", reason)
                .putNullable("timeoutMillis", timeoutMillis),
        )
    }

    @Synchronized
    fun recordError(code: String, throwable: Throwable? = null) {
        val current = _state.value
        if (!current.isActive) return
        writeEvent(
            current.sessionId,
            JSONObject()
                .put("event", "ERROR")
                .put("epochMillis", System.currentTimeMillis())
                .put("code", code)
                .putNullable("exception", throwable?.stackTraceToString()),
        )
        update(current.copy(errorCount = current.errorCount + 1))
    }

    @Synchronized
    fun onServiceCreated() {
        val current = _state.value
        if (!current.isActive) return
        val storedPid = preferences.getInt(KEY_ACTIVE_PID, -1)
        val processRestarted = storedPid != -1 && storedPid != Process.myPid()
        writeEvent(
            current.sessionId,
            JSONObject()
                .put("event", "SERVICE_RESTART")
                .put("epochMillis", System.currentTimeMillis())
                .put("pid", Process.myPid())
                .put("processRestart", processRestarted),
        )
        update(
            current.copy(
                serviceRestartCount = current.serviceRestartCount + 1,
                processRestartCount = current.processRestartCount + if (processRestarted) 1 else 0,
                warning = if (processRestarted) "测试期间应用进程发生重启" else current.warning,
            ),
        )
    }

    /**
     * A target window is not complete until Health Services has delivered a
     * sample at or beyond its end. This preserves the cached tail while the
     * screen is off instead of stopping ExerciseClient at the timer boundary.
     */
    @Synchronized
    fun tick(now: Long = System.currentTimeMillis()): BackgroundTestTickResult? {
        var current = _state.value
        if (!current.isActive) return null
        if (now - preferences.getLong(KEY_LAST_BATTERY_PERSIST, 0L) >= 60_000L) {
            preferences.edit { putLong(KEY_LAST_BATTERY_PERSIST, now) }
            update(current.copy(currentBatteryPercent = context.batteryPercent()))
            current = _state.value
        }
        val targetEnd = current.targetEndMillis ?: return null
        if (now < targetEnd) return null
        if (current.targetReachedMillis == null) {
            val battery = context.batteryPercent()
            val charging = context.isBatteryCharging()
            writeEvent(
                current.sessionId,
                JSONObject()
                    .put("event", "TEST_WINDOW_END")
                    .put("epochMillis", targetEnd)
                    .put("detectedEpochMillis", now)
                    .putNullable("endBatteryPercent", battery)
                    .putNullable("endCharging", charging),
            )
            current = current.copy(
                targetReachedMillis = now,
                targetEndBatteryPercent = battery,
                targetEndCharging = charging,
                currentBatteryPercent = battery,
            )
            update(current, persistSynchronously = true)
        }
        if ((current.latestSampleEpochMillis ?: Long.MIN_VALUE) >= targetEnd) {
            return BackgroundTestTickResult.COMPLETE
        }
        return if (shouldTailDrainTimeout(now, targetEnd, current.lastScreenOnMillis)) {
            BackgroundTestTickResult.DRAIN_TIMEOUT
        } else {
            null
        }
    }

    @Synchronized
    fun finish(reason: String, abnormal: Boolean = false): BackgroundTestSnapshot? {
        val current = _state.value
        if (!current.isActive) return null
        val finalizedMillis = System.currentTimeMillis()
        val targetCompletion = reason.startsWith("TARGET_DURATION")
        val testWindowEndMillis = if (targetCompletion) {
            current.targetEndMillis ?: finalizedMillis
        } else {
            finalizedMillis
        }
        val endBattery = if (targetCompletion) {
            current.targetEndBatteryPercent ?: context.batteryPercent()
        } else {
            context.batteryPercent()
        }
        val endCharging = if (targetCompletion) {
            current.targetEndCharging ?: context.isBatteryCharging()
        } else {
            context.isBatteryCharging()
        }
        val endingNoCallback = current.lastCallbackReceiveMillis
            ?.let { (finalizedMillis - it).coerceAtLeast(0L) }
            ?: current.startMillis?.let { (finalizedMillis - it).coerceAtLeast(0L) }
            ?: 0L
        writeEvent(
            current.sessionId,
            JSONObject()
                .put("event", "TEST_END")
                .put("epochMillis", finalizedMillis)
                .put("testWindowEndEpochMillis", testWindowEndMillis)
                .put("reason", reason)
                .put("abnormal", abnormal)
                .putNullable("endBatteryPercent", endBattery)
                .putNullable("endCharging", endCharging),
        )
        val report = buildReport(
            initial = current,
            endMillis = testWindowEndMillis,
            reportFinalizedMillis = finalizedMillis,
            endBattery = endBattery,
            endCharging = endCharging,
            reason = reason,
            abnormal = abnormal,
            endingNoCallback = endingNoCallback,
        )
        val jsonFile = File(testsDir, "${current.sessionId}.json")
        val textFile = File(testsDir, "${current.sessionId}.txt")
        jsonFile.writeText(report.first.toString(2))
        textFile.writeText(report.second)
        val finalState = current.copy(
            state = when {
                abnormal -> BackgroundTestState.ABNORMAL_TERMINATION
                targetCompletion -> BackgroundTestState.COMPLETED
                else -> BackgroundTestState.STOPPED
            },
            endMillis = testWindowEndMillis,
            currentBatteryPercent = endBattery,
            longestNoCallbackDurationMs = maxOf(current.longestNoCallbackDurationMs, endingNoCallback),
            latestReportJsonPath = jsonFile.absolutePath,
            latestReportTextPath = textFile.absolutePath,
            latestReportText = report.second,
            warning = report.first.optJSONArray("warnings")?.let { warnings ->
                (0 until warnings.length()).joinToString("; ") { warnings.optString(it) }
            }?.ifBlank { "--" } ?: "--",
        )
        // The final report paths and state must be durable before returning.
        update(finalState, persistSynchronously = true)
        return finalState
    }

    fun refreshLatestReport(): BackgroundTestSnapshot {
        val current = _state.value
        val reportFile = current.latestReportTextPath.takeIf { it != "--" }?.let(::File)
        val text = reportFile?.takeIf { it.isFile }?.readText() ?: current.latestReportText
        val next = current.copy(latestReportText = text)
        update(next)
        return next
    }

    private fun buildReport(
        initial: BackgroundTestSnapshot,
        endMillis: Long,
        reportFinalizedMillis: Long,
        endBattery: Int?,
        endCharging: Boolean?,
        reason: String,
        abnormal: Boolean,
        endingNoCallback: Long,
    ): Pair<JSONObject, String> {
        val events = eventsFile(initial.sessionId).takeIf { it.isFile }?.readLines().orEmpty()
            .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
        val samplesByTimestamp = sortedMapOf<Long, SampleForReport>()
        val callbackTimes = mutableListOf<Long>()
        val screenEvents = mutableListOf<Pair<Long, Boolean>>()
        var callbacksOff = 0L
        var availabilityChanges = 0L
        var unavailableOffBodyCount = 0L
        var staleEvents = 0L
        var serviceRestarts = 0L
        var processRestarts = 0L
        var errors = 0L
        var historicalCallbackBatches = 0L
        var maxCallbackBatchSize = 0
        var wakeLockAcquireCount = 0L
        var wakeLockReleaseCount = 0L
        var initialInteractive = true
        events.forEach { event ->
            when (event.optString("event")) {
                "TEST_START" -> initialInteractive = event.optBoolean("screenInteractive", true)
                "SCREEN_ON" -> screenEvents += event.optLong("epochMillis") to true
                "SCREEN_OFF" -> screenEvents += event.optLong("epochMillis") to false
                "AVAILABILITY" -> {
                    availabilityChanges++
                    if (event.optString("availability").contains("OFF_BODY")) unavailableOffBodyCount++
                }
                "STALE" -> staleEvents++
                "SERVICE_RESTART" -> {
                    serviceRestarts++
                    if (event.optBoolean("processRestart")) processRestarts++
                }
                "ERROR" -> errors++
                "WAKE_LOCK_ACQUIRED" -> wakeLockAcquireCount++
                "WAKE_LOCK_RELEASED" -> wakeLockReleaseCount++
                "CALLBACK_BATCH" -> {
                    val receive = event.optLong("callbackReceiveEpochMillis")
                    val array = event.optJSONArray("samples") ?: JSONArray()
                    if (array.length() == 0) return@forEach
                    callbackTimes += receive
                    maxCallbackBatchSize = maxOf(maxCallbackBatchSize, array.length())
                    if (event.optBoolean("containsHistoricalSamples")) historicalCallbackBatches++
                    val callbackOff = !event.optBoolean("screenInteractive", true)
                    if (callbackOff) callbacksOff++
                    for (index in 0 until array.length()) {
                        val sample = array.getJSONObject(index)
                        val timestamp = sample.getLong("sampleEpochMillis")
                        if (timestamp in (initial.startMillis ?: Long.MIN_VALUE)..endMillis) {
                            samplesByTimestamp[timestamp] = SampleForReport(
                                timestamp,
                                receive,
                                sample.optDouble("bpm"),
                                sample.optString("accuracy"),
                                deliveredWhileScreenOff = callbackOff,
                            )
                        }
                    }
                }
            }
        }
        val samples = samplesByTimestamp.values.toList()
        val intervals = samples.zipWithNext { first, second -> second.sampleMillis - first.sampleMillis }
            .filter { it >= 0 }
        val latencies = samples.map { (it.receiveMillis - it.sampleMillis).coerceAtLeast(0L) }.sorted()
        val sortedScreens = screenEvents.sortedBy { it.first }
        fun interactiveAt(timestamp: Long): Boolean {
            var interactive = initialInteractive
            sortedScreens.forEach { (time, value) ->
                if (time <= timestamp) interactive = value else return@forEach
            }
            return interactive
        }
        val sampledWhileOff = samples.count { !interactiveAt(it.sampleMillis) }.toLong()
        val screenOffSamples = samples.filter { !interactiveAt(it.sampleMillis) }
        val samplesDeliveredOff = samples.count { it.deliveredWhileScreenOff }.toLong()
        val screenOffSamplesDeliveredAfterWake = screenOffSamples.count { !it.deliveredWhileScreenOff }.toLong()
        val screenOffLatencies = screenOffSamples
            .map { (it.receiveMillis - it.sampleMillis).coerceAtLeast(0L) }
            .sorted()
        val callbackSequence = (
            listOfNotNull(initial.startMillis) + callbackTimes.sorted() + reportFinalizedMillis
        ).sorted()
        val noCallbackDurations = callbackSequence.zipWithNext { first, second -> second - first }
        val longestNoCallback = maxOf(noCallbackDurations.maxOrNull() ?: 0L, endingNoCallback)
        val avgSampleInterval = intervals.averageOrNull()
        val maxSampleInterval = intervals.maxOrNull()
        val avgLatency = latencies.averageOrNull()
        val medianLatency = median(latencies)
        val p95Latency = percentile(latencies, 0.95)
        val maxLatency = latencies.maxOrNull()
        val screenOffAvgLatency = screenOffLatencies.averageOrNull()
        val screenOffMedianLatency = median(screenOffLatencies)
        val screenOffP95Latency = percentile(screenOffLatencies, 0.95)
        val screenOffMaxLatency = screenOffLatencies.maxOrNull()
        val duration = (endMillis - (initial.startMillis ?: endMillis)).coerceAtLeast(0L)
        val firstSampleDelay = samples.firstOrNull()?.let {
            (it.sampleMillis - (initial.startMillis ?: it.sampleMillis)).coerceAtLeast(0L)
        }
        val lastSampleToWindowEnd = samples.lastOrNull()?.let {
            (endMillis - it.sampleMillis).coerceAtLeast(0L)
        }
        val targetWindowCovered = isTargetWindowCovered(
            firstSampleDelay,
            lastSampleToWindowEnd,
            COVERAGE_GAP_LIMIT_MILLIS,
        )
        val batteryDelta = if (initial.startBatteryPercent != null && endBattery != null) {
            initial.startBatteryPercent - endBattery
        } else null
        val hourlyDrain = batteryDelta?.let { if (duration > 0) it * 3_600_000.0 / duration else null }
        val offWindowExists = screenEvents.any { !it.second } || !initialInteractive
        val continuouslySampled = targetWindowCovered &&
            sampledWhileOff > 0 &&
            (maxSampleInterval ?: Long.MAX_VALUE) <= COVERAGE_GAP_LIMIT_MILLIS
        val nearRealtime = screenOffP95Latency != null &&
            screenOffP95Latency <= 5_000L &&
            longestNoCallback <= 10_000L
        val batchedDeliveryDetected = historicalCallbackBatches > 0 || screenOffSamplesDeliveredAfterWake > 0
        val classification = when {
            abnormal || serviceRestarts > 0 || processRestarts > 0 -> "4. 会话或服务被终止/重启"
            !offWindowExists -> "5. 数据不足：没有息屏窗口"
            sampledWhileOff == 0L -> "3. 息屏后停止采样，或没有息屏样本"
            continuouslySampled && nearRealtime -> "1. 持续采样，并在息屏时近实时交付"
            continuouslySampled -> "2. 持续采样，但息屏数据被缓存或批量补发"
            else -> "5. 数据不足，无法证明持续采样"
        }
        val warnings = JSONArray().apply {
            if (duration < BackgroundTestType.valueOf(initial.testType).durationMillis) {
                put("测试提前结束，未达到要求时长")
            }
            if (!targetWindowCovered) {
                put("目标测试窗口的开头或结尾缺少样本，不能据此证明完整时段持续采样")
            }
            if (reason == "TARGET_DURATION_DRAIN_TIMEOUT") {
                put("目标时长结束至少 5 分钟且亮屏等待 15 秒后，仍未收到覆盖窗口末端的样本")
            }
            if (initial.testType == BackgroundTestType.BATTERY_20_MIN.name) {
                put("20 分钟续航测试只适合快速估算，正式结果至少测试 60 分钟")
                if (batteryDelta == 0) put("系统电量没有下降 1%，不能据此认定零耗电")
            }
            if (initial.testType == BackgroundTestType.REALTIME_DELIVERY_10_MIN.name) {
                put("本轮使用有超时上限的 PARTIAL_WAKE_LOCK，仅用于验证实时交付及额外耗电")
                if (wakeLockAcquireCount == 0L) put("实时交付实验未记录到 WakeLock 获取事件，结果无效")
            }
            if (initial.testType == BackgroundTestType.FORMAL_60_MIN.name && initial.scenario != WearScenario.ON_WRIST_REAL_USE) {
                put("60 分钟正式结果只对正常佩戴场景有效")
            }
            if (initial.startCharging != false && initial.testType != BackgroundTestType.SCREEN_OFF_10_MIN.name) {
                put("测试开始时未确认已断开充电")
            }
            if (initial.scenario == WearScenario.OFF_WRIST_BASELINE) {
                put("未佩戴基线不能与正常佩戴结果混合比较")
            }
            put("软件只能依据样本和佩戴状态判断，不能直接证明 PPG 灯是否点亮")
        }
        val statistics = JSONObject()
            .put("actualDurationMs", duration)
            .put("totalUniqueSamples", samples.size)
            .put("samplesSampledWhileScreenOff", sampledWhileOff)
            .put("samplesDeliveredWhileScreenOff", samplesDeliveredOff)
            .put("callbackBatchCount", callbackTimes.size)
            .put("callbacksDeliveredWhileScreenOff", callbacksOff)
            .putNullable("averageSampleIntervalMs", avgSampleInterval)
            .putNullable("maxSampleIntervalMs", maxSampleInterval)
            .putNullable("firstSampleDelayMs", firstSampleDelay)
            .putNullable("lastSampleToWindowEndMs", lastSampleToWindowEnd)
            .put("targetWindowCovered", targetWindowCovered)
            .putNullable("averageDeliveryLatencyMs", avgLatency)
            .putNullable("medianDeliveryLatencyMs", medianLatency)
            .putNullable("p95DeliveryLatencyMs", p95Latency)
            .putNullable("maxDeliveryLatencyMs", maxLatency)
            .putNullable("screenOffAverageDeliveryLatencyMs", screenOffAvgLatency)
            .putNullable("screenOffMedianDeliveryLatencyMs", screenOffMedianLatency)
            .putNullable("screenOffP95DeliveryLatencyMs", screenOffP95Latency)
            .putNullable("screenOffMaxDeliveryLatencyMs", screenOffMaxLatency)
            .put("longestNoCallbackDurationMs", longestNoCallback)
            .put("historicalCallbackBatchCount", historicalCallbackBatches)
            .put("maxCallbackBatchSampleCount", maxCallbackBatchSize)
            .put("screenOffSamplesDeliveredAfterWake", screenOffSamplesDeliveredAfterWake)
            .put("batchedDeliveryDetected", batchedDeliveryDetected)
            .put("staleEventCount", staleEvents)
            .put("availabilityChangeCount", availabilityChanges)
            .put("offBodyAvailabilityCount", unavailableOffBodyCount)
            .put("serviceRestartCount", serviceRestarts)
            .put("processRestartCount", processRestarts)
            .put("wakeLockAcquireCount", wakeLockAcquireCount)
            .put("wakeLockReleaseCount", wakeLockReleaseCount)
            .put("screenOnCount", screenEvents.count { it.second })
            .put("screenOffCount", screenEvents.count { !it.second })
            .put("errorCount", errors)
            .put("crashCount", if (abnormal) 1 else 0)
        val battery = JSONObject()
            .putNullable("startPercent", initial.startBatteryPercent)
            .putNullable("endPercent", endBattery)
            .putNullable("changePercentagePoints", batteryDelta)
            .putNullable("simpleHourlyDrainPercent", hourlyDrain)
            .putNullable("startCharging", initial.startCharging)
            .putNullable("endCharging", endCharging)
        val report = JSONObject()
            .put("sessionId", initial.sessionId)
            .put("testType", initial.testType)
            .put("scenario", initial.scenario.name)
            .put("startEpochMillis", initial.startMillis)
            .put("endEpochMillis", endMillis)
            .put("reportFinalizedEpochMillis", reportFinalizedMillis)
            .put("tailDrainDurationMs", (reportFinalizedMillis - endMillis).coerceAtLeast(0L))
            .put("endReason", reason)
            .putNullable("startWorn", initial.startWorn)
            .put("statistics", statistics)
            .put("battery", battery)
            .put("continuousSampling", continuouslySampled)
            .put("nearRealtimeDelivery", nearRealtime)
            .put("classification", classification)
            .put("warnings", warnings)
            .put("rawEventsFile", eventsFile(initial.sessionId).absolutePath)
        val text = buildString {
            appendLine("后台交付与续航测试报告")
            appendLine("测试编号: ${initial.sessionId}")
            appendLine("测试类型: ${BackgroundTestType.valueOf(initial.testType).displayName}")
            appendLine("测试场景: ${initial.scenario.displayName}")
            appendLine("开始时间: ${formatTime(initial.startMillis)}")
            appendLine("结束时间: ${formatTime(endMillis)}")
            appendLine("报告生成时间: ${formatTime(reportFinalizedMillis)}")
            appendLine("尾部缓存等待: ${formatDuration((reportFinalizedMillis - endMillis).coerceAtLeast(0L))}")
            appendLine("实际时长: ${formatDuration(duration)}")
            appendLine("结束原因: $reason")
            appendLine()
            appendLine("A. 是否持续采样: ${if (continuouslySampled) "是" else "否"}")
            appendLine("B. 息屏时是否近实时交付: ${if (nearRealtime) "是" else "否"}")
            appendLine("判定: $classification")
            appendLine()
            appendLine("totalUniqueSamples: ${samples.size}")
            appendLine("samplesSampledWhileScreenOff: $sampledWhileOff")
            appendLine("samplesDeliveredWhileScreenOff: $samplesDeliveredOff")
            appendLine("callbackBatchCount: ${callbackTimes.size}")
            appendLine("callbacksDeliveredWhileScreenOff: $callbacksOff")
            appendLine("averageSampleIntervalMs: ${formatNumber(avgSampleInterval)}")
            appendLine("maxSampleIntervalMs: ${maxSampleInterval ?: "--"}")
            appendLine("firstSampleDelayMs: ${firstSampleDelay ?: "--"}")
            appendLine("lastSampleToWindowEndMs: ${lastSampleToWindowEnd ?: "--"}")
            appendLine("targetWindowCovered: $targetWindowCovered")
            appendLine("averageDeliveryLatencyMs: ${formatNumber(avgLatency)}")
            appendLine("medianDeliveryLatencyMs: ${formatNumber(medianLatency)}")
            appendLine("p95DeliveryLatencyMs: ${p95Latency ?: "--"}")
            appendLine("maxDeliveryLatencyMs: ${maxLatency ?: "--"}")
            appendLine("screenOffAverageDeliveryLatencyMs: ${formatNumber(screenOffAvgLatency)}")
            appendLine("screenOffMedianDeliveryLatencyMs: ${formatNumber(screenOffMedianLatency)}")
            appendLine("screenOffP95DeliveryLatencyMs: ${screenOffP95Latency ?: "--"}")
            appendLine("screenOffMaxDeliveryLatencyMs: ${screenOffMaxLatency ?: "--"}")
            appendLine("longestNoCallbackDurationMs: $longestNoCallback")
            appendLine("historicalCallbackBatchCount: $historicalCallbackBatches")
            appendLine("maxCallbackBatchSampleCount: $maxCallbackBatchSize")
            appendLine("screenOffSamplesDeliveredAfterWake: $screenOffSamplesDeliveredAfterWake")
            appendLine("batchedDeliveryDetected: $batchedDeliveryDetected")
            appendLine("staleEventCount: $staleEvents")
            appendLine("availabilityChangeCount: $availabilityChanges")
            appendLine("offBodyAvailabilityCount: $unavailableOffBodyCount")
            appendLine("serviceRestartCount: $serviceRestarts")
            appendLine("processRestartCount: $processRestarts")
            appendLine("wakeLockAcquireCount: $wakeLockAcquireCount")
            appendLine("wakeLockReleaseCount: $wakeLockReleaseCount")
            appendLine("errorCount: $errors")
            appendLine("crashCount: ${if (abnormal) 1 else 0}")
            appendLine()
            appendLine("batteryStart: ${initial.startBatteryPercent ?: "--"}%")
            appendLine("batteryEnd: ${endBattery ?: "--"}%")
            appendLine("batteryChange: ${batteryDelta ?: "--"} percentage points")
            appendLine("simpleHourlyDrain: ${formatNumber(hourlyDrain)}%/h")
            appendLine("startCharging: ${initial.startCharging ?: "--"}")
            appendLine("startWorn: ${initial.startWorn ?: "UNKNOWN"}")
            appendLine()
            appendLine("警告:")
            for (index in 0 until warnings.length()) appendLine("- ${warnings.getString(index)}")
        }
        return report to text
    }

    private fun load(): BackgroundTestSnapshot = BackgroundTestSnapshot(
        state = runCatching {
            BackgroundTestState.valueOf(preferences.getString("state", "IDLE") ?: "IDLE")
        }.getOrDefault(BackgroundTestState.IDLE),
        sessionId = preferences.getString("sessionId", "--") ?: "--",
        testType = preferences.getString("testType", "--") ?: "--",
        scenario = runCatching {
            WearScenario.valueOf(preferences.getString("scenario", WearScenario.ON_WRIST_REAL_USE.name)!!)
        }.getOrDefault(WearScenario.ON_WRIST_REAL_USE),
        startMillis = preferences.longOrNull("startMillis"),
        targetEndMillis = preferences.longOrNull("targetEndMillis"),
        targetReachedMillis = preferences.longOrNull("targetReachedMillis"),
        endMillis = preferences.longOrNull("endMillis"),
        startBatteryPercent = preferences.intOrNull("startBatteryPercent"),
        currentBatteryPercent = preferences.intOrNull("currentBatteryPercent"),
        targetEndBatteryPercent = preferences.intOrNull("targetEndBatteryPercent"),
        startCharging = preferences.booleanOrNull("startCharging"),
        targetEndCharging = preferences.booleanOrNull("targetEndCharging"),
        startWorn = preferences.booleanOrNull("startWorn"),
        totalSamples = preferences.getLong("totalSamples", 0),
        callbackBatchCount = preferences.getLong("callbackBatchCount", 0),
        samplesDeliveredWhileScreenOff = preferences.getLong("samplesDeliveredWhileScreenOff", 0),
        callbacksDeliveredWhileScreenOff = preferences.getLong("callbacksDeliveredWhileScreenOff", 0),
        staleEventCount = preferences.getLong("staleEventCount", 0),
        availabilityChangeCount = preferences.getLong("availabilityChangeCount", 0),
        serviceRestartCount = preferences.getLong("serviceRestartCount", 0),
        processRestartCount = preferences.getLong("processRestartCount", 0),
        screenOnCount = preferences.getLong("screenOnCount", 0),
        screenOffCount = preferences.getLong("screenOffCount", 0),
        lastScreenOnMillis = preferences.longOrNull("lastScreenOnMillis"),
        errorCount = preferences.getLong("errorCount", 0),
        crashCount = preferences.getLong("crashCount", 0),
        lastCallbackReceiveMillis = preferences.longOrNull("lastCallbackReceiveMillis"),
        latestSampleEpochMillis = preferences.longOrNull("latestSampleEpochMillis"),
        longestNoCallbackDurationMs = preferences.getLong("longestNoCallbackDurationMs", 0),
        latestReportJsonPath = preferences.getString("latestReportJsonPath", "--") ?: "--",
        latestReportTextPath = preferences.getString("latestReportTextPath", "--") ?: "--",
        latestReportText = preferences.getString("latestReportText", "--") ?: "--",
        warning = preferences.getString("warning", "--") ?: "--",
    )

    @Synchronized
    private fun update(
        value: BackgroundTestSnapshot,
        persistSynchronously: Boolean = false,
    ) {
        _state.value = value
        preferences.edit(commit = persistSynchronously) {
            putString("state", value.state.name)
            putString("sessionId", value.sessionId)
            putString("testType", value.testType)
            putString("scenario", value.scenario.name)
            putNullableLong("startMillis", value.startMillis)
            putNullableLong("targetEndMillis", value.targetEndMillis)
            putNullableLong("targetReachedMillis", value.targetReachedMillis)
            putNullableLong("endMillis", value.endMillis)
            putNullableInt("startBatteryPercent", value.startBatteryPercent)
            putNullableInt("currentBatteryPercent", value.currentBatteryPercent)
            putNullableInt("targetEndBatteryPercent", value.targetEndBatteryPercent)
            putNullableBoolean("startCharging", value.startCharging)
            putNullableBoolean("targetEndCharging", value.targetEndCharging)
            putNullableBoolean("startWorn", value.startWorn)
            putLong("totalSamples", value.totalSamples)
            putLong("callbackBatchCount", value.callbackBatchCount)
            putLong("samplesDeliveredWhileScreenOff", value.samplesDeliveredWhileScreenOff)
            putLong("callbacksDeliveredWhileScreenOff", value.callbacksDeliveredWhileScreenOff)
            putLong("staleEventCount", value.staleEventCount)
            putLong("availabilityChangeCount", value.availabilityChangeCount)
            putLong("serviceRestartCount", value.serviceRestartCount)
            putLong("processRestartCount", value.processRestartCount)
            putLong("screenOnCount", value.screenOnCount)
            putLong("screenOffCount", value.screenOffCount)
            putNullableLong("lastScreenOnMillis", value.lastScreenOnMillis)
            putLong("errorCount", value.errorCount)
            putLong("crashCount", value.crashCount)
            putNullableLong("lastCallbackReceiveMillis", value.lastCallbackReceiveMillis)
            putNullableLong("latestSampleEpochMillis", value.latestSampleEpochMillis)
            putLong("longestNoCallbackDurationMs", value.longestNoCallbackDurationMs)
            putString("latestReportJsonPath", value.latestReportJsonPath)
            putString("latestReportTextPath", value.latestReportTextPath)
            putString("latestReportText", value.latestReportText.take(12_000))
            putString("warning", value.warning)
            if (value.isActive) putInt(KEY_ACTIVE_PID, Process.myPid()) else remove(KEY_ACTIVE_PID)
        }
    }

    private fun writeEvent(sessionId: String, event: JSONObject) {
        eventsFile(sessionId).appendText(event.toString() + "\n")
    }

    private fun eventsFile(sessionId: String) = File(testsDir, "$sessionId.events.jsonl")

    private data class SampleForReport(
        val sampleMillis: Long,
        val receiveMillis: Long,
        val bpm: Double,
        val accuracy: String,
        val deliveredWhileScreenOff: Boolean,
    )

    companion object {
        private const val PREFS = "background_test_state"
        private const val KEY_ACTIVE_PID = "activePid"
        private const val KEY_LAST_BATTERY_PERSIST = "lastBatteryPersist"
        private const val HISTORICAL_SAMPLE_THRESHOLD_MS = 5_000L
        private const val COVERAGE_GAP_LIMIT_MILLIS = 10_000L
        private val SESSION_ID_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")
            .withZone(ZoneId.systemDefault())

        @Volatile
        private var instance: BackgroundTestRecorder? = null

        fun get(context: Context): BackgroundTestRecorder = instance ?: synchronized(this) {
            instance ?: BackgroundTestRecorder(context.applicationContext).also { instance = it }
        }

        fun wornFromAvailability(availability: String): Boolean? = when {
            availability.contains("OFF_BODY") -> false
            availability == "AVAILABLE" -> true
            else -> null
        }
    }
}

internal fun percentile(sortedValues: List<Long>, percentile: Double): Long? {
    if (sortedValues.isEmpty()) return null
    val index = (ceil(percentile * sortedValues.size).toInt() - 1).coerceIn(sortedValues.indices)
    return sortedValues[index]
}

internal fun median(sortedValues: List<Long>): Double? = when {
    sortedValues.isEmpty() -> null
    sortedValues.size % 2 == 1 -> sortedValues[sortedValues.size / 2].toDouble()
    else -> {
        val upper = sortedValues.size / 2
        (sortedValues[upper - 1].toDouble() + sortedValues[upper].toDouble()) / 2.0
    }
}

internal fun isTargetWindowCovered(
    firstSampleDelayMs: Long?,
    lastSampleToWindowEndMs: Long?,
    gapLimitMs: Long = 10_000L,
): Boolean = firstSampleDelayMs != null &&
    lastSampleToWindowEndMs != null &&
    firstSampleDelayMs <= gapLimitMs &&
    lastSampleToWindowEndMs <= gapLimitMs

internal fun shouldTailDrainTimeout(
    nowMillis: Long,
    targetEndMillis: Long,
    lastScreenOnMillis: Long?,
    maxDrainWaitMillis: Long = 5 * 60_000L,
    postWakeGraceMillis: Long = 15_000L,
): Boolean = nowMillis - targetEndMillis >= maxDrainWaitMillis &&
    lastScreenOnMillis != null &&
    lastScreenOnMillis >= targetEndMillis &&
    nowMillis - lastScreenOnMillis >= postWakeGraceMillis

private fun List<Long>.averageOrNull(): Double? = if (isEmpty()) null else average()

private fun JSONObject.putNullable(key: String, value: Any?): JSONObject =
    put(key, value ?: JSONObject.NULL)

private fun android.content.SharedPreferences.longOrNull(key: String): Long? =
    if (contains(key)) getLong(key, 0) else null

private fun android.content.SharedPreferences.intOrNull(key: String): Int? =
    if (contains(key)) getInt(key, 0) else null

private fun android.content.SharedPreferences.booleanOrNull(key: String): Boolean? =
    if (contains(key)) getBoolean(key, false) else null

private fun android.content.SharedPreferences.Editor.putNullableLong(key: String, value: Long?) {
    if (value == null) remove(key) else putLong(key, value)
}

private fun android.content.SharedPreferences.Editor.putNullableInt(key: String, value: Int?) {
    if (value == null) remove(key) else putInt(key, value)
}

private fun android.content.SharedPreferences.Editor.putNullableBoolean(key: String, value: Boolean?) {
    if (value == null) remove(key) else putBoolean(key, value)
}

private fun formatTime(value: Long?): String = value?.let {
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(it))
} ?: "--"

private fun formatDuration(millis: Long): String = String.format(
    Locale.US,
    "%02d:%02d:%02d",
    millis / 3_600_000,
    millis / 60_000 % 60,
    millis / 1_000 % 60,
)

private fun formatNumber(value: Double?): String =
    value?.let { String.format(Locale.US, "%.1f", it) } ?: "--"
