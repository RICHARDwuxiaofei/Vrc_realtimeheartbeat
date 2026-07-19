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

enum class BackgroundTestType(val displayName: String, val durationMillis: Long) {
    SCREEN_OFF_10_MIN("10-minute screen-off delivery test", 10 * 60_000L),
    BATTERY_20_MIN("20-minute battery test", 20 * 60_000L),
}

enum class WearScenario {
    OFF_WRIST_BASELINE,
    ON_WRIST_REAL_USE,
}

enum class BackgroundTestState {
    IDLE,
    ACTIVE,
    COMPLETED,
    STOPPED,
    ERROR,
    ABNORMAL_TERMINATION,
}

data class BackgroundTestSnapshot(
    val state: BackgroundTestState = BackgroundTestState.IDLE,
    val sessionId: String = "--",
    val testType: String = "--",
    val scenario: WearScenario = WearScenario.OFF_WRIST_BASELINE,
    val startMillis: Long? = null,
    val targetEndMillis: Long? = null,
    val endMillis: Long? = null,
    val startBatteryPercent: Int? = null,
    val currentBatteryPercent: Int? = null,
    val startCharging: Boolean? = null,
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
    val errorCount: Long = 0,
    val crashCount: Long = 0,
    val lastCallbackReceiveMillis: Long? = null,
    val longestNoCallbackDurationMs: Long = 0,
    val latestReportJsonPath: String = "--",
    val latestReportTextPath: String = "--",
    val latestReportText: String = "--",
    val warning: String = "--",
) {
    val isActive: Boolean get() = state == BackgroundTestState.ACTIVE
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
        check(!_state.value.isActive) { "A background test is already active" }
        check(exercise.serviceRunning) { "ForegroundService is not running" }
        check(exercise.sessionState == ExerciseSessionState.ACTIVE) { "ExerciseClient is not ACTIVE" }
        check(exercise.callbackRegistered) { "Exercise callback is not registered" }
        check(exercise.sampleCount >= 5) { "At least 5 valid samples are required before starting" }
        check(exercise.lastError == "--") { "ExerciseClient has an error: ${exercise.lastError}" }
        if (type == BackgroundTestType.BATTERY_20_MIN) {
            check(charging == false) { "Battery test requires the watch to be disconnected from the charger" }
        }

        val now = System.currentTimeMillis()
        val sessionId = SESSION_ID_FORMATTER.format(Instant.ofEpochMilli(now)) +
            "_${type.name}_${scenario.name}"
        val battery = context.batteryPercent()
        val worn = wornFromAvailability(exercise.availability)
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
        update(next)
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
                warning = if (processRestarted) "Process restarted during active test" else current.warning,
            ),
        )
    }

    /** Returns true once an active test reaches its target duration. */
    @Synchronized
    fun tick(now: Long = System.currentTimeMillis()): Boolean {
        val current = _state.value
        if (!current.isActive) return false
        if (now - preferences.getLong(KEY_LAST_BATTERY_PERSIST, 0L) >= 60_000L) {
            preferences.edit { putLong(KEY_LAST_BATTERY_PERSIST, now) }
            update(current.copy(currentBatteryPercent = context.batteryPercent()))
        }
        return current.targetEndMillis?.let { now >= it } == true
    }

    @Synchronized
    fun finish(reason: String, abnormal: Boolean = false): BackgroundTestSnapshot? {
        val current = _state.value
        if (!current.isActive) return null
        val now = System.currentTimeMillis()
        val endingNoCallback = current.lastCallbackReceiveMillis
            ?.let { (now - it).coerceAtLeast(0L) }
            ?: current.startMillis?.let { (now - it).coerceAtLeast(0L) }
            ?: 0L
        writeEvent(
            current.sessionId,
            JSONObject()
                .put("event", "TEST_END")
                .put("epochMillis", now)
                .put("reason", reason)
                .put("abnormal", abnormal)
                .putNullable("endBatteryPercent", context.batteryPercent())
                .putNullable("endCharging", context.isBatteryCharging()),
        )
        val report = buildReport(current, now, reason, abnormal, endingNoCallback)
        val jsonFile = File(testsDir, "${current.sessionId}.json")
        val textFile = File(testsDir, "${current.sessionId}.txt")
        jsonFile.writeText(report.first.toString(2))
        textFile.writeText(report.second)
        val finalState = current.copy(
            state = when {
                abnormal -> BackgroundTestState.ABNORMAL_TERMINATION
                reason == "TARGET_DURATION_REACHED" -> BackgroundTestState.COMPLETED
                else -> BackgroundTestState.STOPPED
            },
            endMillis = now,
            currentBatteryPercent = context.batteryPercent(),
            longestNoCallbackDurationMs = maxOf(current.longestNoCallbackDurationMs, endingNoCallback),
            latestReportJsonPath = jsonFile.absolutePath,
            latestReportTextPath = textFile.absolutePath,
            latestReportText = report.second,
            warning = report.first.optJSONArray("warnings")?.let { warnings ->
                (0 until warnings.length()).joinToString("; ") { warnings.optString(it) }
            }?.ifBlank { "--" } ?: "--",
        )
        update(finalState)
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
                "CALLBACK_BATCH" -> {
                    val receive = event.optLong("callbackReceiveEpochMillis")
                    callbackTimes += receive
                    val callbackOff = !event.optBoolean("screenInteractive", true)
                    if (callbackOff) callbacksOff++
                    val array = event.optJSONArray("samples") ?: JSONArray()
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
        val samplesDeliveredOff = samples.count { it.deliveredWhileScreenOff }.toLong()
        val callbackSequence = (listOfNotNull(initial.startMillis) + callbackTimes.sorted() + endMillis).sorted()
        val noCallbackDurations = callbackSequence.zipWithNext { first, second -> second - first }
        val longestNoCallback = maxOf(noCallbackDurations.maxOrNull() ?: 0L, endingNoCallback)
        val avgSampleInterval = intervals.averageOrNull()
        val maxSampleInterval = intervals.maxOrNull()
        val avgLatency = latencies.averageOrNull()
        val medianLatency = percentile(latencies, 0.50)
        val p95Latency = percentile(latencies, 0.95)
        val maxLatency = latencies.maxOrNull()
        val duration = (endMillis - (initial.startMillis ?: endMillis)).coerceAtLeast(0L)
        val endBattery = context.batteryPercent()
        val batteryDelta = if (initial.startBatteryPercent != null && endBattery != null) {
            initial.startBatteryPercent - endBattery
        } else null
        val hourlyDrain = batteryDelta?.let { if (duration > 0) it * 3_600_000.0 / duration else null }
        val offWindowExists = screenEvents.any { !it.second } || !initialInteractive
        val continuouslySampled = sampledWhileOff > 0 && (maxSampleInterval ?: Long.MAX_VALUE) <= 10_000L
        val nearRealtime = p95Latency != null && p95Latency <= 5_000L && longestNoCallback <= 10_000L
        val classification = when {
            abnormal || serviceRestarts > 0 || processRestarts > 0 -> "4. Session or service was terminated/restarted"
            !offWindowExists -> "5. Insufficient data: no screen-off window"
            sampledWhileOff == 0L -> "3. Sampling stopped after screen-off or no off-screen samples"
            continuouslySampled && nearRealtime -> "1. Continuous sampling with near-real-time screen-off delivery"
            continuouslySampled -> "2. Continuous sampling, but screen-off data was cached/batched"
            else -> "5. Insufficient data to prove continuous sampling"
        }
        val warnings = JSONArray().apply {
            if (duration < BackgroundTestType.valueOf(initial.testType).durationMillis) {
                put("Test ended before its requested duration")
            }
            if (initial.testType == BackgroundTestType.BATTERY_20_MIN.name) {
                put("A 20-minute battery test is only a coarse estimate; run at least 60 minutes for formal results")
                if (batteryDelta == 0) put("No 1% change was observed at the system battery percentage granularity; this is not zero drain")
            }
            if (initial.startCharging != false && initial.testType == BackgroundTestType.BATTERY_20_MIN.name) {
                put("Battery test started while charging state was not confirmed as false")
            }
            if (initial.scenario == WearScenario.OFF_WRIST_BASELINE) {
                put("OFF_WRIST_BASELINE must not be mixed with ON_WRIST_REAL_USE results")
            }
            put("PPG illumination cannot be proven directly by this API; infer only from samples and availability")
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
            .putNullable("averageDeliveryLatencyMs", avgLatency)
            .putNullable("medianDeliveryLatencyMs", medianLatency)
            .putNullable("p95DeliveryLatencyMs", p95Latency)
            .putNullable("maxDeliveryLatencyMs", maxLatency)
            .put("longestNoCallbackDurationMs", longestNoCallback)
            .put("staleEventCount", staleEvents)
            .put("availabilityChangeCount", availabilityChanges)
            .put("offBodyAvailabilityCount", unavailableOffBodyCount)
            .put("serviceRestartCount", serviceRestarts)
            .put("processRestartCount", processRestarts)
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
            .putNullable("endCharging", context.isBatteryCharging())
        val report = JSONObject()
            .put("sessionId", initial.sessionId)
            .put("testType", initial.testType)
            .put("scenario", initial.scenario.name)
            .put("startEpochMillis", initial.startMillis)
            .put("endEpochMillis", endMillis)
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
            appendLine("Background Delivery & Battery Test")
            appendLine("sessionId: ${initial.sessionId}")
            appendLine("testType: ${initial.testType}")
            appendLine("scenario: ${initial.scenario.name}")
            appendLine("start: ${formatTime(initial.startMillis)}")
            appendLine("end: ${formatTime(endMillis)}")
            appendLine("actualDuration: ${formatDuration(duration)}")
            appendLine("endReason: $reason")
            appendLine()
            appendLine("A. Continuous sampling: $continuouslySampled")
            appendLine("B. Near-real-time ForegroundService delivery: $nearRealtime")
            appendLine("classification: $classification")
            appendLine()
            appendLine("totalUniqueSamples: ${samples.size}")
            appendLine("samplesSampledWhileScreenOff: $sampledWhileOff")
            appendLine("samplesDeliveredWhileScreenOff: $samplesDeliveredOff")
            appendLine("callbackBatchCount: ${callbackTimes.size}")
            appendLine("callbacksDeliveredWhileScreenOff: $callbacksOff")
            appendLine("averageSampleIntervalMs: ${formatNumber(avgSampleInterval)}")
            appendLine("maxSampleIntervalMs: ${maxSampleInterval ?: "--"}")
            appendLine("averageDeliveryLatencyMs: ${formatNumber(avgLatency)}")
            appendLine("medianDeliveryLatencyMs: ${medianLatency ?: "--"}")
            appendLine("p95DeliveryLatencyMs: ${p95Latency ?: "--"}")
            appendLine("maxDeliveryLatencyMs: ${maxLatency ?: "--"}")
            appendLine("longestNoCallbackDurationMs: $longestNoCallback")
            appendLine("staleEventCount: $staleEvents")
            appendLine("availabilityChangeCount: $availabilityChanges")
            appendLine("offBodyAvailabilityCount: $unavailableOffBodyCount")
            appendLine("serviceRestartCount: $serviceRestarts")
            appendLine("processRestartCount: $processRestarts")
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
            appendLine("Warnings:")
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
            WearScenario.valueOf(preferences.getString("scenario", WearScenario.OFF_WRIST_BASELINE.name)!!)
        }.getOrDefault(WearScenario.OFF_WRIST_BASELINE),
        startMillis = preferences.longOrNull("startMillis"),
        targetEndMillis = preferences.longOrNull("targetEndMillis"),
        endMillis = preferences.longOrNull("endMillis"),
        startBatteryPercent = preferences.intOrNull("startBatteryPercent"),
        currentBatteryPercent = preferences.intOrNull("currentBatteryPercent"),
        startCharging = preferences.booleanOrNull("startCharging"),
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
        errorCount = preferences.getLong("errorCount", 0),
        crashCount = preferences.getLong("crashCount", 0),
        lastCallbackReceiveMillis = preferences.longOrNull("lastCallbackReceiveMillis"),
        longestNoCallbackDurationMs = preferences.getLong("longestNoCallbackDurationMs", 0),
        latestReportJsonPath = preferences.getString("latestReportJsonPath", "--") ?: "--",
        latestReportTextPath = preferences.getString("latestReportTextPath", "--") ?: "--",
        latestReportText = preferences.getString("latestReportText", "--") ?: "--",
        warning = preferences.getString("warning", "--") ?: "--",
    )

    @Synchronized
    private fun update(value: BackgroundTestSnapshot) {
        _state.value = value
        preferences.edit(commit = true) {
            putString("state", value.state.name)
            putString("sessionId", value.sessionId)
            putString("testType", value.testType)
            putString("scenario", value.scenario.name)
            putNullableLong("startMillis", value.startMillis)
            putNullableLong("targetEndMillis", value.targetEndMillis)
            putNullableLong("endMillis", value.endMillis)
            putNullableInt("startBatteryPercent", value.startBatteryPercent)
            putNullableInt("currentBatteryPercent", value.currentBatteryPercent)
            putNullableBoolean("startCharging", value.startCharging)
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
            putLong("errorCount", value.errorCount)
            putLong("crashCount", value.crashCount)
            putNullableLong("lastCallbackReceiveMillis", value.lastCallbackReceiveMillis)
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

private fun percentile(sortedValues: List<Long>, percentile: Double): Long? {
    if (sortedValues.isEmpty()) return null
    val index = (ceil(percentile * sortedValues.size).toInt() - 1).coerceIn(sortedValues.indices)
    return sortedValues[index]
}

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
