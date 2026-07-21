package best.nagikokoro.watch6heartrateprobe

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Text

class ProductionMainActivity : ComponentActivity() {
    private val viewModel: HeartRateViewModel by viewModels()
    private lateinit var permissionManager: PermissionManager
    private lateinit var relaySettings: WatchRelaySettings
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionManager = PermissionManager(this)
        relaySettings = WatchRelaySettings.get(this)
        viewModel.selectMode(ProbeMode.EXERCISE)
        viewModel.onActivityLifecycle(
            "CREATE_PRODUCTION",
            mapOf("savedInstanceStatePresent" to (savedInstanceState != null)),
        )
        viewModel.onPermissionCheck(permissionManager.currentState(this))

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val relayStatus by RelayStatusStore.state.collectAsStateWithLifecycle()
            val selectedRelayMode by relaySettings.mode.collectAsStateWithLifecycle()
            ProductionScreen(
                state = state,
                relayStatus = relayStatus,
                selectedRelayMode = selectedRelayMode,
                onRelayModeChange = relaySettings::setMode,
                onStart = ::requestPermissionsAndStart,
                onStop = viewModel::stopSelectedMode,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onActivityLifecycle("START_PRODUCTION")
    }

    override fun onResume() {
        super.onResume()
        viewModel.onActivityLifecycle("RESUME_PRODUCTION")
        viewModel.onPermissionCheck(permissionManager.currentState(this))
        viewModel.selectMode(ProbeMode.EXERCISE)
        if (!restoreChecked) {
            restoreChecked = true
            viewModel.ensureExerciseSessionRestored()
        }
    }

    override fun onPause() {
        viewModel.onActivityLifecycle("PAUSE_PRODUCTION")
        super.onPause()
    }

    override fun onStop() {
        viewModel.onHostStopped()
        super.onStop()
    }

    private fun requestPermissionsAndStart() {
        viewModel.selectMode(ProbeMode.EXERCISE)
        pendingStartAfterPermission = true
        continuePendingStart()
    }

    private fun continuePendingStart() {
        if (!viewModel.isPermissionGranted()) {
            requestHeartRatePermission()
            return
        }
        if (!viewModel.isBackgroundHealthPermissionGranted()) {
            val permission = viewModel.requiredBackgroundHealthPermission()
            if (permission != null) {
                viewModel.onPermissionRequestStarted(permission)
                backgroundHealthPermissionLauncher.launch(permission)
                return
            }
        }
        pendingStartAfterPermission = false
        viewModel.selectMode(ProbeMode.EXERCISE)
        viewModel.startSelectedMode()
    }

    private fun requestHeartRatePermission() {
        when (permissionManager.currentState(this)) {
            PermissionState.GRANTED -> continuePendingStart()
            PermissionState.PERMANENTLY_DENIED -> {
                pendingStartAfterPermission = false
                viewModel.onSettingsOpenedForPermission()
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        "package:$packageName".toUri(),
                    ),
                )
            }
            else -> {
                viewModel.onPermissionRequestStarted()
                heartRatePermissionLauncher.launch(permissionManager.requiredPermission)
            }
        }
    }
}

@Composable
private fun ProductionScreen(
    state: ProbeUiState,
    relayStatus: RelayStatus,
    selectedRelayMode: WatchRelayMode,
    onRelayModeChange: (WatchRelayMode) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val exercise = state.exercise
    val running = exercise.serviceRunning && exercise.sessionState in setOf(
        ExerciseSessionState.RESTORING,
        ExerciseSessionState.STARTING,
        ExerciseSessionState.ACTIVE,
        ExerciseSessionState.PAUSED,
    )
    val bpm = exercise.bpm.takeIf { running }
    val ageSeconds = if (running) {
        exercise.lastSampleMillis?.let {
            ((state.nowMillis - it) / 1_000L).coerceAtLeast(0L)
        }
    } else {
        null
    }
    val signalFresh = running && bpm != null && ageSeconds != null && ageSeconds < 10L
    val displayedRelayMode = if (running) exercise.relayMode else selectedRelayMode
    val statusText = when {
        !running -> "尚未启动"
        bpm == null -> "正在等待心率"
        !signalFresh -> "心率信号暂时中断"
        else -> "后台传输中"
    }
    val statusColor = when {
        signalFresh -> Color(0xFF68B684)
        running -> Color(0xFFE7A84B)
        else -> Color(0xFF8B8B8B)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF050505))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 30.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher),
                contentDescription = null,
                modifier = Modifier.size(34.dp),
            )
            Column {
                Text("心率传输", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("Galaxy Watch → 手机", color = Color(0xFF9A9A9A), fontSize = 10.sp)
            }
        }

        Spacer(Modifier.height(14.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF151515))
                .padding(vertical = 18.dp, horizontal = 16.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = bpm?.toString() ?: "--",
                    color = if (bpm == null) Color(0xFF777777) else Color(0xFFF1F1F1),
                    fontSize = 50.sp,
                    lineHeight = 52.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text("BPM", color = Color(0xFF9A9A9A), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor))
                    Text(statusText, color = statusColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        RelayModeSelector(
            selectedMode = displayedRelayMode,
            enabled = !running,
            onModeChange = onRelayModeChange,
        )

        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF101010))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ProductionStatusRow(
                    "手机",
                    if (relayStatus.phoneNearby) relayStatus.phoneName else "等待连接",
                )
                ProductionStatusRow(
                    "数据",
                    ageSeconds?.let { "$it 秒前" } ?: "--",
                )
                ProductionStatusRow("频率", displayedRelayMode.displayName)
                ProductionStatusRow(
                    "电量",
                    exercise.currentBatteryPercent?.let { "$it%" } ?: "--",
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        val buttonColor = if (running) Color(0xFF343434) else Color(0xFFD84A57)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(buttonColor)
                .clickable { if (running) onStop() else onStart() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (running) "停止传输" else "开始传输",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "返回表盘或息屏后仍会继续运行",
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFF777777),
            fontSize = 9.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RelayModeSelector(
    selectedMode: WatchRelayMode,
    enabled: Boolean,
    onModeChange: (WatchRelayMode) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF101010))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("发送频率", color = Color(0xFFE6E1E5), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WatchRelayMode.entries.forEach { mode ->
                    val selected = selectedMode == mode
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clip(RoundedCornerShape(19.dp))
                            .background(if (selected) Color(0xFFD84A57) else Color(0xFF242424))
                            .clickable(enabled = enabled) { onModeChange(mode) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (mode == WatchRelayMode.REALTIME_1_SECOND) "1 秒" else "5 秒",
                            color = if (selected) Color.White else Color(0xFFBDBDBD),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            Text(
                if (enabled) selectedMode.description else "运行中不可切换，请先停止传输",
                color = if (selectedMode == WatchRelayMode.REALTIME_1_SECOND) Color(0xFFE7A84B) else Color(0xFF858585),
                fontSize = 8.sp,
                lineHeight = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ProductionStatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color(0xFF828282), fontSize = 10.sp)
        Text(value, color = Color(0xFFD2D2D2), fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}
