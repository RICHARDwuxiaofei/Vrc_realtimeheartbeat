package best.nagikokoro.watch6heartrateprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.ambient.AmbientLifecycleObserver
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val viewModel: HeartRateViewModel by viewModels()
    private lateinit var permissionManager: PermissionManager
    private lateinit var ambientObserver: AmbientLifecycleObserver
    private var pendingStartAfterPermission = false
    private var restoreChecked = false

    private val heartRatePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val permanentlyDenied = !granted &&
                permissionManager.wasRequested() &&
                !shouldShowRequestPermissionRationale(permissionManager.requiredPermission)
            viewModel.onPermissionRequestResult(granted, permanentlyDenied)
            if (granted && pendingStartAfterPermission) continuePendingStart()
        }

    private val backgroundHealthPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.onBackgroundPermissionResult(granted)
            if (granted && pendingStartAfterPermission) {
                pendingStartAfterPermission = false
                viewModel.startSelectedMode()
            }
        }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            viewModel.onScreenEvent(intent.action ?: "UNKNOWN")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionManager = PermissionManager(this)
        ambientObserver = AmbientLifecycleObserver(
            this,
            object : AmbientLifecycleObserver.AmbientLifecycleCallback {
                override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
                    viewModel.onAmbientChanged(
                        true,
                        mapOf(
                            "deviceHasLowBitAmbient" to ambientDetails.deviceHasLowBitAmbient,
                            "burnInProtectionRequired" to ambientDetails.burnInProtectionRequired,
                        ),
                    )
                }

                override fun onExitAmbient() {
                    viewModel.onAmbientChanged(false)
                }

                override fun onUpdateAmbient() {
                    viewModel.onAmbientUpdate()
                }
            },
        )
        lifecycle.addObserver(ambientObserver)
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        viewModel.onActivityLifecycle(
            "CREATE",
            mapOf("savedInstanceStatePresent" to (savedInstanceState != null)),
        )
        viewModel.onPermissionCheck(permissionManager.currentState(this))
        keepActivityScreenOn(true)

        setContent {
            MaterialTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val relayStatus by RelayStatusStore.state.collectAsStateWithLifecycle()
                val relayTester = remember { WearHeartRateRelay(this@MainActivity, DiagnosticLogger.get(this@MainActivity)) }
                HeartRateProbeScreen(
                    state = state,
                    relayStatus = relayStatus,
                    onSendRelayTest = relayTester::sendDiagnosticTest,
                    onSelectMode = viewModel::selectMode,
                    onStart = ::requestPermissionsAndStart,
                    onStop = viewModel::stopSelectedMode,
                    onRequestPermission = { requestHeartRatePermission(startAfterGrant = false) },
                    onClearLog = viewModel::clearVisibleLog,
                    onSelectWearScenario = viewModel::selectWearScenario,
                    onStartBackgroundTest = { type ->
                        // A formal background test must be allowed to enter real screen-off state.
                        keepActivityScreenOn(false)
                        if (!viewModel.startBackgroundTest(type)) keepActivityScreenOn(true)
                    },
                    onStopBackgroundTest = viewModel::stopBackgroundTest,
                    onViewBackgroundTestResult = viewModel::viewBackgroundTestResult,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onActivityLifecycle("START")
    }

    override fun onResume() {
        super.onResume()
        if (!viewModel.uiState.value.backgroundTest.isActive) keepActivityScreenOn(true)
        viewModel.onActivityLifecycle("RESUME")
        if (::permissionManager.isInitialized) {
            viewModel.onPermissionCheck(permissionManager.currentState(this))
        }
        if (!restoreChecked) {
            restoreChecked = true
            viewModel.ensureExerciseSessionRestored()
        }
    }

    override fun onPause() {
        viewModel.onActivityLifecycle("PAUSE", mapOf("measureCallbackAction" to "KEEP_REGISTERED"))
        super.onPause()
    }

    override fun onStop() {
        viewModel.onHostStopped()
        super.onStop()
    }

    override fun onDestroy() {
        viewModel.onActivityLifecycle(
            "DESTROY",
            mapOf(
                "isFinishing" to isFinishing,
                "isChangingConfigurations" to isChangingConfigurations,
                "activityCallbackAction" to "NONE",
            ),
        )
        lifecycle.removeObserver(ambientObserver)
        runCatching { unregisterReceiver(screenReceiver) }
        super.onDestroy()
    }

    private fun requestPermissionsAndStart() {
        pendingStartAfterPermission = true
        continuePendingStart()
    }

    private fun keepActivityScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun continuePendingStart() {
        if (!viewModel.isPermissionGranted()) {
            requestHeartRatePermission(startAfterGrant = true)
            return
        }
        if (viewModel.uiState.value.selectedMode == ProbeMode.EXERCISE &&
            !viewModel.isBackgroundHealthPermissionGranted()
        ) {
            val permission = viewModel.requiredBackgroundHealthPermission()
            if (permission != null) {
                viewModel.onPermissionRequestStarted(permission)
                backgroundHealthPermissionLauncher.launch(permission)
                return
            }
        }
        pendingStartAfterPermission = false
        viewModel.startSelectedMode()
    }

    private fun requestHeartRatePermission(startAfterGrant: Boolean) {
        pendingStartAfterPermission = startAfterGrant
        val state = permissionManager.currentState(this)
        if (state == PermissionState.GRANTED) {
            viewModel.onPermissionCheck(PermissionState.GRANTED)
            if (startAfterGrant) continuePendingStart()
            return
        }
        if (state == PermissionState.PERMANENTLY_DENIED) {
            pendingStartAfterPermission = false
            viewModel.onSettingsOpenedForPermission()
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    "package:$packageName".toUri(),
                ),
            )
            return
        }
        viewModel.onPermissionRequestStarted()
        heartRatePermissionLauncher.launch(permissionManager.requiredPermission)
    }
}

@Composable
private fun HeartRateProbeScreen(
    state: ProbeUiState,
    relayStatus: RelayStatus,
    onSendRelayTest: () -> Unit,
    onSelectMode: (ProbeMode) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRequestPermission: () -> Unit,
    onClearLog: () -> Unit,
    onSelectWearScenario: (WearScenario) -> Unit,
    onStartBackgroundTest: (BackgroundTestType) -> Unit,
    onStopBackgroundTest: () -> Unit,
    onViewBackgroundTestResult: () -> Unit,
) {
    val pageScroll = rememberScrollState()
    val logScroll = rememberScrollState()
    val recentLogs = state.visibleLogs.takeLast(20)
    val exerciseMode = state.selectedMode == ProbeMode.EXERCISE
    val currentBpm = if (exerciseMode) state.exercise.bpm else state.displayedBpm
    val sampleCount = if (exerciseMode) state.exercise.sampleCount else state.validSampleCount
    val lastUpdate = if (exerciseMode) state.exercise.lastSampleMillis else state.lastValidUpdateMillis
    val dataAge = lastUpdate?.let { ((state.nowMillis - it) / 1_000).coerceAtLeast(0) }
    val staleCount = if (exerciseMode) state.exercise.staleCount else state.staleCount
    val maxGap = if (exerciseMode) state.exercise.maxGapMillis else state.maxGapMillis
    val startBattery = if (exerciseMode) state.exercise.startBatteryPercent else state.startBatteryPercent
    val currentBattery = if (exerciseMode) state.exercise.currentBatteryPercent else state.currentBatteryPercent
    val sessionStart = if (exerciseMode) state.exercise.sessionStartMillis else state.sessionStartMillis
    val sessionEnd = if (exerciseMode) state.exercise.sessionEndMillis else state.sessionEndMillis
    val duration = sessionStart?.let { (((sessionEnd ?: state.nowMillis) - it) / 1_000).coerceAtLeast(0) }

    LaunchedEffect(recentLogs.size, recentLogs.lastOrNull()?.timestampMillis) {
        delay(50)
        logScroll.animateScrollTo(logScroll.maxValue)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(pageScroll)
            .padding(horizontal = 26.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Watch6 HR Probe", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        ProbeButton(
            "${if (state.selectedMode == ProbeMode.MEASURE) "▶ " else ""}MeasureClient Probe",
            !state.callbackRegistered && !state.exercise.serviceRunning,
        ) {
            onSelectMode(ProbeMode.MEASURE)
        }
        ProbeButton(
            "${if (state.selectedMode == ProbeMode.EXERCISE) "▶ " else ""}ExerciseClient Screen-off Test",
            !state.callbackRegistered && !state.exercise.serviceRunning,
        ) {
            onSelectMode(ProbeMode.EXERCISE)
        }
        if (state.selectedMode == ProbeMode.EXERCISE && state.exercise.serviceRunning) {
            ProbeButton("Stop active Exercise session", state.exercise.sessionState != ExerciseSessionState.ENDING, onStop)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            currentBpm?.let { "$it BPM" } ?: "-- BPM",
            color = if (dataAge != null && dataAge >= 10) Color(0xFFFFB74D) else Color(0xFFFF5252),
            fontSize = 40.sp,
            fontWeight = FontWeight.Black,
        )
        Spacer(Modifier.height(6.dp))

        StatusBlock(
            state = state,
            relayStatus = relayStatus,
            sampleCount = sampleCount,
            lastUpdate = lastUpdate,
            dataAge = dataAge,
            staleCount = staleCount,
            maxGap = maxGap,
            startBattery = startBattery,
            currentBattery = currentBattery,
            duration = duration,
        )
        Spacer(Modifier.height(10.dp))

        ProbeButton("Start selected test", state.canStart, onStart)
        ProbeButton("Stop selected test", state.canStop, onStop)
        ProbeButton("Request heart-rate permission", true, onRequestPermission)
        ProbeButton("Clear visible log", state.visibleLogs.isNotEmpty(), onClearLog)
        ProbeButton("Send phone / PC link test", true, onSendRelayTest)

        if (exerciseMode) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Background Delivery & Battery Test",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            ProbeButton(
                "OFF_WRIST_BASELINE",
                !state.backgroundTest.isActive && state.selectedWearScenario != WearScenario.OFF_WRIST_BASELINE,
            ) { onSelectWearScenario(WearScenario.OFF_WRIST_BASELINE) }
            ProbeButton(
                "ON_WRIST_REAL_USE",
                !state.backgroundTest.isActive && state.selectedWearScenario != WearScenario.ON_WRIST_REAL_USE,
            ) { onSelectWearScenario(WearScenario.ON_WRIST_REAL_USE) }
            StatusLine("测试场景", state.selectedWearScenario.name)
            val testReady = state.exercise.serviceRunning &&
                state.exercise.sessionState == ExerciseSessionState.ACTIVE &&
                state.exercise.callbackRegistered &&
                state.exercise.sampleCount >= 5 &&
                !state.backgroundTest.isActive
            ProbeButton("Start 10-minute screen-off test", testReady) {
                onStartBackgroundTest(BackgroundTestType.SCREEN_OFF_10_MIN)
            }
            ProbeButton("Start 20-minute battery test", testReady) {
                onStartBackgroundTest(BackgroundTestType.BATTERY_20_MIN)
            }
            ProbeButton("Stop test", state.backgroundTest.isActive, onStopBackgroundTest)
            ProbeButton(
                "Export/View result",
                state.backgroundTest.latestReportTextPath != "--",
                onViewBackgroundTestResult,
            )
            BackgroundTestStatus(state.backgroundTest, state.nowMillis)
        }

        Spacer(Modifier.height(12.dp))
        Text("最近 HR_PROBE 日志 (${recentLogs.size}/20)", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp)
                .background(Color(0xFF111111))
                .verticalScroll(logScroll)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (recentLogs.isEmpty()) {
                Text("(visible log is empty)", color = Color.Gray, fontSize = 10.sp)
            } else {
                recentLogs.forEach { entry -> DiagnosticLogLine(entry) }
            }
        }
        Spacer(Modifier.height(36.dp))
    }
}

@Composable
private fun BackgroundTestStatus(test: BackgroundTestSnapshot, nowMillis: Long) {
    val elapsed = test.startMillis?.let { ((test.endMillis ?: nowMillis) - it).coerceAtLeast(0L) }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        StatusLine("后台测试状态", test.state.name)
        StatusLine("sessionId", test.sessionId)
        StatusLine("测试类型", test.testType)
        StatusLine("测试耗时", elapsed?.let { formatDurationSeconds(it / 1_000) } ?: "--")
        StatusLine("测试样本", test.totalSamples.toString())
        StatusLine("回调批次", test.callbackBatchCount.toString())
        StatusLine("息屏交付样本", test.samplesDeliveredWhileScreenOff.toString())
        StatusLine("息屏交付回调", test.callbacksDeliveredWhileScreenOff.toString())
        StatusLine("最长无回调", formatDurationMillis(test.longestNoCallbackDurationMs))
        StatusLine("服务 / 进程重启", "${test.serviceRestartCount} / ${test.processRestartCount}")
        StatusLine("屏幕开 / 关", "${test.screenOnCount} / ${test.screenOffCount}")
        StatusLine("测试电量", "${test.startBatteryPercent ?: "--"}% / ${test.currentBatteryPercent ?: "--"}%")
        if (test.warning != "--") StatusLine("测试警告", test.warning)
        if (test.latestReportText != "--") {
            Text(
                test.latestReportText.takeLast(2_500),
                color = Color(0xFFB2FF59),
                fontSize = 8.sp,
                lineHeight = 10.sp,
                modifier = Modifier.fillMaxWidth().background(Color(0xFF111111)).padding(8.dp),
            )
        }
    }
}

@Composable
private fun StatusBlock(
    state: ProbeUiState,
    relayStatus: RelayStatus,
    sampleCount: Long,
    lastUpdate: Long?,
    dataAge: Long?,
    staleCount: Long,
    maxGap: Long,
    startBattery: Int?,
    currentBattery: Int?,
    duration: Long?,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        StatusLine("当前模式", state.selectedMode.displayName)
        StatusLine("Service 运行", state.exercise.serviceRunning.toString())
        StatusLine("Exercise 会话", state.exercise.sessionState.name)
        StatusLine("Exercise 类型", state.exercise.exerciseType)
        StatusLine("Measure 状态", state.status.name)
        StatusLine("Measure Callback", state.callbackRegistered.toString())
        StatusLine("Exercise Callback", state.exercise.callbackRegistered.toString())
        StatusLine("屏幕交互", state.screenInteractive.toString())
        StatusLine("Ambient", state.ambient.toString())
        StatusLine("Activity", state.activityPhase)
        StatusLine("进程 PID", state.pid.toString())
        StatusLine("前台心率权限", state.permissionState.displayText)
        StatusLine("后台健康权限", state.backgroundHealthPermissionGranted.toString())
        StatusLine("Health Services", state.healthServicesAvailable.displayText)
        StatusLine("手机蓝牙中转", if (relayStatus.phoneNearby) "已连接 ${relayStatus.phoneName}" else "等待手机")
        StatusLine("已发 / 失败", "${relayStatus.sentCount} / ${relayStatus.failedCount}")
        StatusLine("电脑回执", if (relayStatus.lastPcAck) "已确认" else "等待")
        StatusLine("样本数", sampleCount.toString())
        StatusLine("最后更新时间", lastUpdate?.let(::formatTimestamp) ?: "--")
        StatusLine("数据年龄", dataAge?.let { "$it 秒" } ?: "--")
        StatusLine("Stale 次数", staleCount.toString())
        StatusLine("最大断档", formatDurationMillis(maxGap))
        StatusLine("开始 / 当前电量", "${startBattery?.let { "$it%" } ?: "--"} / ${currentBattery?.let { "$it%" } ?: "--"}")
        StatusLine("会话持续时间", duration?.let(::formatDurationSeconds) ?: "--")
        StatusLine("数据可用性", if (state.selectedMode == ProbeMode.EXERCISE) state.exercise.availability else state.measureAvailability)
        if (state.exercise.lastError != "--") StatusLine("Exercise 错误", state.exercise.lastError)
        if (state.exercise.endReason != "--") StatusLine("结束原因", state.exercise.endReason)
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text("$label: ", color = Color(0xFFBDBDBD), fontSize = 10.sp, modifier = Modifier.weight(0.42f))
        Text(value, color = Color.White, fontSize = 10.sp, modifier = Modifier.weight(0.58f))
    }
}

@Composable
private fun ProbeButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Text(label, textAlign = TextAlign.Center, fontSize = 11.sp)
    }
}

@Composable
private fun DiagnosticLogLine(entry: DiagnosticEntry) {
    val time = LOG_TIME_FORMATTER.format(Instant.ofEpochMilli(entry.timestampMillis))
    val params = entry.parameters.entries.joinToString(",") { "${it.key}=${it.value}" }
    Text(
        "$time ${entry.eventCode}${if (params.isEmpty()) "" else " $params"}",
        color = when (entry.level) {
            LogLevel.ERROR -> Color(0xFFFF5252)
            LogLevel.WARN -> Color(0xFFFFB74D)
            LogLevel.INFO -> Color(0xFFB2FF59)
            LogLevel.DEBUG -> Color(0xFF90CAF9)
        },
        fontSize = 8.sp,
        lineHeight = 10.sp,
    )
}

private fun formatTimestamp(timestampMillis: Long): String =
    DATE_TIME_FORMATTER.format(Instant.ofEpochMilli(timestampMillis))

private fun formatDurationMillis(millis: Long): String =
    String.format(Locale.US, "%.1f 秒", millis / 1_000.0)

private fun formatDurationSeconds(seconds: Long): String =
    "%02d:%02d:%02d".format(seconds / 3_600, (seconds % 3_600) / 60, seconds % 60)

private val LOG_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

private val DATE_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
