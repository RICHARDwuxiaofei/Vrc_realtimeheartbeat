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
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
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
    // Do not present the final value from a stopped session as if it were live.
    val currentBpm = when {
        exerciseMode && state.exercise.serviceRunning -> state.exercise.bpm
        !exerciseMode && state.callbackRegistered -> state.displayedBpm
        else -> null
    }
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
            .background(Color(0xFF08101F))
            .verticalScroll(pageScroll)
            .padding(horizontal = 26.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Image(
                painter = painterResource(R.drawable.ic_launcher),
                contentDescription = null,
                modifier = Modifier.size(38.dp),
            )
            Column {
                Text("PulseLink Watch", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("息屏实时心率", color = Color(0xFF20B8FF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(8.dp))

        val canChangeMode = !state.callbackRegistered && !state.exercise.serviceRunning
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Button(
                onClick = { onSelectMode(ProbeMode.MEASURE) },
                enabled = canChangeMode,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    if (state.selectedMode == ProbeMode.MEASURE) "✓ 前台对照" else "前台对照",
                    textAlign = TextAlign.Center,
                    fontSize = 10.sp,
                )
            }
            Button(
                onClick = { onSelectMode(ProbeMode.EXERCISE) },
                enabled = canChangeMode,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    if (state.selectedMode == ProbeMode.EXERCISE) "✓ 后台连续" else "后台连续",
                    textAlign = TextAlign.Center,
                    fontSize = 10.sp,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            currentBpm?.let { "$it BPM" } ?: "-- BPM",
            color = if (dataAge != null && dataAge >= 10) Color(0xFFFFC56D) else Color(0xFFFF5B62),
            fontSize = 40.sp,
            fontWeight = FontWeight.Black,
        )
        Spacer(Modifier.height(6.dp))

        if (state.canStop) {
            ProbeButton(
                if (exerciseMode) "停止息屏实时心率" else "停止前台对照测量",
                true,
                onStop,
            )
        } else {
            ProbeButton(
                if (exerciseMode) "开始后台节能心率（约 5 秒）" else "开始前台对照测量",
                state.canStart,
                onStart,
            )
        }
        if (state.permissionState != PermissionState.GRANTED) {
            ProbeButton("授予心率权限", true, onRequestPermission)
        }

        if (exerciseMode) {
            Spacer(Modifier.height(12.dp))
            Text(
                "息屏交付与续航测试",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Button(
                    onClick = { onSelectWearScenario(WearScenario.ON_WRIST_REAL_USE) },
                    enabled = !state.backgroundTest.isActive,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        if (state.selectedWearScenario == WearScenario.ON_WRIST_REAL_USE) "✓ 正常佩戴" else "正常佩戴",
                        textAlign = TextAlign.Center,
                        fontSize = 9.sp,
                    )
                }
                Button(
                    onClick = { onSelectWearScenario(WearScenario.OFF_WRIST_BASELINE) },
                    enabled = !state.backgroundTest.isActive,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        if (state.selectedWearScenario == WearScenario.OFF_WRIST_BASELINE) "✓ 未佩戴" else "未佩戴",
                        textAlign = TextAlign.Center,
                        fontSize = 9.sp,
                    )
                }
            }
            val testReady = state.exercise.serviceRunning &&
                state.exercise.sessionState == ExerciseSessionState.ACTIVE &&
                state.exercise.callbackRegistered &&
                state.exercise.sampleCount >= 5 &&
                !state.backgroundTest.isActive
            val readiness = when {
                state.backgroundTest.isDraining -> "计时已结束：先保持息屏5分钟，再亮屏等待尾部缓存"
                state.backgroundTest.isActive -> "测试正在进行"
                !state.exercise.serviceRunning -> "请先点击“开始后台节能心率（约 5 秒）”"
                state.exercise.sessionState != ExerciseSessionState.ACTIVE -> "正在启动后台会话"
                !state.exercise.callbackRegistered -> "正在注册心率回调"
                state.exercise.sampleCount < 5 -> "等待至少 5 个真实样本"
                else -> "已经就绪，可以选择测试时长"
            }
            StatusLine("准备状态", readiness)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Button(
                    onClick = { onStartBackgroundTest(BackgroundTestType.SCREEN_OFF_10_MIN) },
                    enabled = testReady,
                    modifier = Modifier.weight(1f),
                ) { Text("息屏测试\n10分钟", textAlign = TextAlign.Center, fontSize = 9.sp) }
                Button(
                    onClick = { onStartBackgroundTest(BackgroundTestType.BATTERY_20_MIN) },
                    enabled = testReady,
                    modifier = Modifier.weight(1f),
                ) { Text("快速续航\n20分钟", textAlign = TextAlign.Center, fontSize = 9.sp) }
            }
            ProbeButton(
                "正式续航测试（60分钟）",
                testReady && state.selectedWearScenario == WearScenario.ON_WRIST_REAL_USE,
            ) { onStartBackgroundTest(BackgroundTestType.FORMAL_60_MIN) }
            ProbeButton(
                "实时交付实验（10分钟，较耗电）",
                testReady && state.selectedWearScenario == WearScenario.ON_WRIST_REAL_USE,
            ) { onStartBackgroundTest(BackgroundTestType.REALTIME_DELIVERY_10_MIN) }
            if (state.backgroundTest.isActive) {
                ProbeButton("提前结束当前测试", true, onStopBackgroundTest)
            }
            if (state.backgroundTest.latestReportTextPath != "--") {
                ProbeButton("刷新上次测试结果", true, onViewBackgroundTestResult)
            }
            BackgroundTestStatus(state.backgroundTest, state.nowMillis)
        }

        Spacer(Modifier.height(12.dp))
        Text("详细状态", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
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
        ProbeButton("手机/电脑中继测试（可选）", true, onSendRelayTest)
        if (state.visibleLogs.isNotEmpty()) ProbeButton("清除屏幕日志", true, onClearLog)
        Text("技术日志（${recentLogs.size}/20）", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp)
                .background(Color(0xFF111B2E))
                .verticalScroll(logScroll)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (recentLogs.isEmpty()) {
                Text("暂无日志", color = Color.Gray, fontSize = 10.sp)
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
        StatusLine("后台测试状态", backgroundTestStateText(test.state))
        StatusLine("测试场景", test.scenario.displayName)
        StatusLine("测试类型", backgroundTestTypeText(test.testType))
        StatusLine("测试耗时", elapsed?.let { formatDurationSeconds(it / 1_000) } ?: "--")
        if (test.isDraining) StatusLine("收尾状态", "亮屏后请保持应用开启至少15秒，请勿停止测量")
        StatusLine("测试样本", test.totalSamples.toString())
        StatusLine("回调批次", test.callbackBatchCount.toString())
        StatusLine("息屏交付样本", test.samplesDeliveredWhileScreenOff.toString())
        StatusLine("息屏交付回调", test.callbacksDeliveredWhileScreenOff.toString())
        StatusLine("最长无回调", formatDurationMillis(test.longestNoCallbackDurationMs))
        StatusLine("服务 / 进程重启", "${test.serviceRestartCount} / ${test.processRestartCount}")
        StatusLine("屏幕开 / 关", "${test.screenOnCount} / ${test.screenOffCount}")
        StatusLine("测试电量", "${test.startBatteryPercent ?: "--"}% / ${test.currentBatteryPercent ?: "--"}%")
        if (test.latestReportTextPath != "--") StatusLine("本地报告", "已生成，可在电脑导出")
        if (test.warning != "--") StatusLine("测试警告", localizedWarning(test.warning))
        if (test.sessionId != "--") StatusLine("测试编号", test.sessionId)
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
        StatusLine("后台服务", booleanText(state.exercise.serviceRunning))
        StatusLine("后台会话", exerciseSessionStateText(state.exercise.sessionState))
        StatusLine("运动类型", exerciseTypeText(state.exercise.exerciseType))
        StatusLine("前台测量状态", state.status.userMessage)
        StatusLine("前台回调", booleanText(state.callbackRegistered))
        StatusLine("后台回调", booleanText(state.exercise.callbackRegistered))
        StatusLine("屏幕可交互", booleanText(state.screenInteractive))
        StatusLine("环境显示模式", booleanText(state.ambient))
        StatusLine("页面状态", activityPhaseText(state.activityPhase))
        StatusLine("进程 PID", state.pid.toString())
        StatusLine("前台心率权限", state.permissionState.displayText)
        StatusLine("后台健康权限", booleanText(state.backgroundHealthPermissionGranted))
        StatusLine("Health Services", state.healthServicesAvailable.displayText)
        StatusLine("手机蓝牙中转", if (relayStatus.phoneNearby) "已连接 ${relayStatus.phoneName}" else "等待手机")
        StatusLine("已发 / 失败", "${relayStatus.sentCount} / ${relayStatus.failedCount}")
        StatusLine("电脑回执", if (relayStatus.lastPcAck) "已确认" else "等待")
        StatusLine("样本数", sampleCount.toString())
        StatusLine("最后更新时间", lastUpdate?.let(::formatTimestamp) ?: "--")
        StatusLine("数据年龄", dataAge?.let { "$it 秒" } ?: "--")
        StatusLine("数据超时次数", staleCount.toString())
        StatusLine("最大断档", formatDurationMillis(maxGap))
        StatusLine("开始 / 当前电量", "${startBattery?.let { "$it%" } ?: "--"} / ${currentBattery?.let { "$it%" } ?: "--"}")
        StatusLine("会话持续时间", duration?.let(::formatDurationSeconds) ?: "--")
        StatusLine(
            "数据可用性",
            availabilityText(if (state.selectedMode == ProbeMode.EXERCISE) state.exercise.availability else state.measureAvailability),
        )
        if (state.exercise.lastError != "--") StatusLine("后台测量错误", state.exercise.lastError)
        if (state.exercise.endReason != "--") StatusLine("结束原因", endReasonText(state.exercise.endReason))
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

private fun booleanText(value: Boolean): String = if (value) "是" else "否"

private fun exerciseSessionStateText(state: ExerciseSessionState): String = when (state) {
    ExerciseSessionState.IDLE -> "未启动"
    ExerciseSessionState.RESTORING -> "正在恢复"
    ExerciseSessionState.STARTING -> "正在启动"
    ExerciseSessionState.ACTIVE -> "运行中"
    ExerciseSessionState.PAUSED -> "已暂停"
    ExerciseSessionState.ENDING -> "正在结束"
    ExerciseSessionState.ENDED -> "已结束"
    ExerciseSessionState.ERROR -> "错误"
}

private fun backgroundTestStateText(state: BackgroundTestState): String = when (state) {
    BackgroundTestState.IDLE -> "未开始"
    BackgroundTestState.ACTIVE -> "进行中"
    BackgroundTestState.COMPLETED -> "已完成"
    BackgroundTestState.STOPPED -> "已提前停止"
    BackgroundTestState.ERROR -> "错误"
    BackgroundTestState.ABNORMAL_TERMINATION -> "异常终止"
}

private fun backgroundTestTypeText(value: String): String = when (value) {
    BackgroundTestType.SCREEN_OFF_10_MIN.name -> BackgroundTestType.SCREEN_OFF_10_MIN.displayName
    BackgroundTestType.REALTIME_DELIVERY_10_MIN.name -> BackgroundTestType.REALTIME_DELIVERY_10_MIN.displayName
    BackgroundTestType.BATTERY_20_MIN.name -> BackgroundTestType.BATTERY_20_MIN.displayName
    BackgroundTestType.FORMAL_60_MIN.name -> BackgroundTestType.FORMAL_60_MIN.displayName
    "--" -> "--"
    else -> value
}

private fun exerciseTypeText(value: String): String = when (value) {
    "WORKOUT" -> "综合训练"
    "WALKING" -> "步行"
    "EXERCISE_CLASS" -> "课程训练"
    "STRENGTH_TRAINING" -> "力量训练"
    "--" -> "--"
    else -> value
}

private fun availabilityText(value: String): String = when {
    value == "AVAILABLE" -> "可用（已佩戴）"
    value.contains("OFF_BODY") -> "不可用（未佩戴）"
    value == "ACQUIRING" -> "正在获取心率"
    value.contains("UNAVAILABLE") -> "暂时不可用"
    value == "UNKNOWN" -> "未知"
    else -> value
}

private fun activityPhaseText(value: String): String = when (value) {
    "CREATE" -> "已创建"
    "START" -> "已显示"
    "RESUME" -> "正在前台"
    "PAUSE" -> "已暂停"
    "STOP" -> "已进入后台"
    "DESTROY" -> "已销毁"
    "PROCESS_START" -> "进程已启动"
    else -> value
}

private fun endReasonText(value: String): String = when (value) {
    "USER" -> "用户停止"
    "TEST_DURATION_REACHED" -> "测试时间到"
    "BACKGROUND_TEST_USER_STOP" -> "用户提前结束测试"
    else -> value
}

private fun localizedWarning(value: String): String {
    val translated = mutableListOf<String>()
    if (value.contains("20-minute battery test", ignoreCase = true)) translated += "20 分钟结果只适合快速估算，正式续航至少测试 60 分钟"
    if (value.contains("No 1% change", ignoreCase = true)) translated += "系统电量显示没有下降 1%，不能据此认定零耗电"
    if (value.contains("OFF_WRIST_BASELINE", ignoreCase = true)) translated += "未佩戴基线不能与正常佩戴结果混合比较"
    if (value.contains("PPG illumination", ignoreCase = true)) translated += "软件只能依据样本和佩戴状态判断，不能直接证明传感器灯是否点亮"
    if (value.contains("started while charging", ignoreCase = true)) translated += "测试开始时未确认已断开充电"
    return translated.distinct().ifEmpty { listOf(value) }.joinToString("；")
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
