package best.nagikokoro.watch6heartrateprobe.mobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import best.nagikokoro.watch6heartrateprobe.R
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val AppBackground = Color(0xFF0B0B0F)
private val CardBackground = Color(0xFF121216)
private val CardElevated = Color(0xFF1B1B20)
private val AccentBlue = Color(0xFFD0BCFF)
private val AccentCoral = Color(0xFFFFB4AB)
private val Success = Color(0xFF8BD5A3)
private val Muted = Color(0xFFCAC4D0)
private val Outline = Color(0xFF49454F)

class MobileMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PhoneRelayRepository.initialize(this)
        setContent { RelayApp() }
    }

    override fun onResume() {
        super.onResume()
        PhoneRelayRepository.refreshNetwork()
        if (PhoneRelayRepository.isXiaomiMode() && hasXiaomiBlePermissions(this)) {
            startXiaomiService(this, XiaomiHeartRateService.ACTION_START)
        }
    }
}

@Composable
private fun RelayApp() {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = AccentBlue,
            secondary = AccentCoral,
            background = AppBackground,
            surface = CardBackground,
            surfaceVariant = CardElevated,
            error = AccentCoral,
            outline = Outline,
            onPrimary = Color(0xFF381E72),
            onBackground = Color(0xFFE6E1E5),
            onSurface = Color(0xFFE6E1E5),
            onSurfaceVariant = Muted,
        ),
    ) { RelayScreen() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RelayScreen() {
    val context = LocalContext.current
    val state by PhoneRelayRepository.state.collectAsStateWithLifecycle()
    var ip by remember(state.targetIp) { mutableStateOf(state.targetIp) }
    var port by remember(state.targetPort) { mutableStateOf(state.targetPort.toString()) }
    var pairingMessage by remember { mutableStateOf<String?>(null) }
    val xiaomiPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (hasXiaomiBlePermissions(context)) {
            PhoneRelayRepository.setHeartRateSource(HeartRateSource.XIAOMI_BAND_BLE)
            startXiaomiService(context, XiaomiHeartRateService.ACTION_START)
        } else {
            PhoneRelayRepository.reportXiaomiError("附近设备权限被拒绝，无法读取小米手环心率")
        }
    }
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents != null) {
            runCatching { PairingUri.parse(contents) }
                .onSuccess { target ->
                    ip = target.host
                    port = target.port.toString()
                    PhoneRelayRepository.saveTarget(target.host, target.port)
                    pairingMessage = "配对成功：${target.host}:${target.port}"
                }
                .onFailure { pairingMessage = it.message ?: "配对码无法识别" }
        }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val watchAlive = state.lastPhoneReceiveMillis?.let { now - it < 15_000 } == true
    val pcAlive = state.lastPcAckMillis?.let { now - it < maxOf(15_000L, state.forwardIntervalSeconds * 2_500L) } == true
    val watchIntervalSeconds = state.watchRelayIntervalSeconds

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground),
                navigationIcon = {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher),
                        contentDescription = null,
                        modifier = Modifier.padding(start = 16.dp, end = 10.dp).size(42.dp).clip(RoundedCornerShape(12.dp)),
                    )
                },
                title = {
                    Column {
                        Text("心率中转站", fontWeight = FontWeight.Bold)
                        Text("WATCH  ·  PHONE  ·  PC", fontSize = 11.sp, color = Muted, letterSpacing = 1.sp)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            HeartRateHero(state, now, watchAlive)
            HeartRateSourceCard(
                state = state,
                onSwitchToXiaomi = {
                    if (hasXiaomiBlePermissions(context)) {
                        PhoneRelayRepository.setHeartRateSource(HeartRateSource.XIAOMI_BAND_BLE)
                        startXiaomiService(context, XiaomiHeartRateService.ACTION_START)
                    } else {
                        xiaomiPermissionLauncher.launch(xiaomiRuntimePermissions())
                    }
                },
                onSwitchToGalaxy = {
                    context.stopService(Intent(context, XiaomiHeartRateService::class.java))
                    PhoneRelayRepository.setHeartRateSource(HeartRateSource.GALAXY_WATCH)
                },
                onScan = {
                    startXiaomiService(context, XiaomiHeartRateService.ACTION_SCAN)
                },
                onConnect = { candidate ->
                    startXiaomiService(
                        context,
                        XiaomiHeartRateService.ACTION_CONNECT,
                        candidate,
                    )
                },
            )
            DiagnosticModeCard(state.diagnosticMode, state.heartRateSource)

            SectionTitle("链路状态")
            StatusCard(
                "01",
                if (state.heartRateSource == HeartRateSource.XIAOMI_BAND_BLE) {
                    state.xiaomiDeviceName ?: "小米手环"
                } else {
                    "Galaxy Watch6"
                },
                if (watchAlive) {
                    if (state.diagnosticMode) {
                        "${sourceLabel(state.heartRateSource)}正常 · 序号 ${state.lastSequence ?: "--"}"
                    } else {
                        "${sourceLabel(state.heartRateSource)}正常"
                    }
                } else {
                    if (state.heartRateSource == HeartRateSource.XIAOMI_BAND_BLE) {
                        state.xiaomiStatus
                    } else {
                        "等待手表心率"
                    }
                },
                watchAlive,
            )
            StatusCard(
                "02",
                "这台手机",
                "${state.networkType} · ${state.localIp}" +
                    if (state.diagnosticMode && state.vpnActive) " · VPN 已开启" else "",
                state.localIp != "--",
            )
            StatusCard(
                "03",
                "Windows 接收器",
                when {
                    !state.forwardingEnabled -> "已暂停发送到电脑"
                    pcAlive -> "电脑已确认 · 每 ${state.forwardIntervalSeconds} 秒发送"
                    else -> "等待电脑回执 · 每 ${state.forwardIntervalSeconds} 秒发送"
                },
                pcAlive && state.forwardingEnabled,
            )

            TransferControlCard(
                enabled = state.forwardingEnabled,
                intervalSeconds = state.forwardIntervalSeconds,
                watchIntervalSeconds = state.watchRelayIntervalSeconds,
                source = state.heartRateSource,
            )
            if (watchIntervalSeconds != null &&
                state.heartRateSource == HeartRateSource.GALAXY_WATCH &&
                state.forwardIntervalSeconds < watchIntervalSeconds
            ) {
                AlertCard(
                    "手机已选 ${state.forwardIntervalSeconds} 秒，但手表当前约 $watchIntervalSeconds 秒才产生一份新数据。请在手表停止传输后切到“1 秒实时”。",
                    AccentBlue.copy(alpha = 0.10f),
                    AccentBlue,
                )
            }

            SectionTitle("电脑地址")
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = CardBackground)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            scanLauncher.launch(
                                ScanOptions()
                                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                    .setPrompt("扫描电脑端显示的配对二维码")
                                    .setBeepEnabled(false)
                                    .setOrientationLocked(false),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("扫码配对电脑") }
                    OutlinedTextField(
                        value = ip,
                        onValueChange = { ip = it.trim() },
                        label = { Text("电脑 IPv4") },
                        placeholder = { Text("例如 192.168.100.188") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter(Char::isDigit).take(5) },
                        label = { Text("UDP 端口") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    )
                    Button(
                        onClick = { saveTarget(ip, port) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("保存设置") }
                    if (state.diagnosticMode) {
                        OutlinedButton(
                            onClick = {
                                saveTarget(ip, port)
                                PhoneRelayRepository.runDiagnostics()
                            },
                            enabled = state.forwardingEnabled && !state.diagnosticRunning,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (state.diagnosticRunning) "诊断中…" else "手机 → 电脑一键诊断") }
                    }
                    pairingMessage?.let { message ->
                        Text(message, color = if (message.startsWith("配对成功")) Success else AccentCoral, fontSize = 12.sp)
                    }
                    if (state.diagnosticMode) {
                        Text(
                            state.diagnosticStatus,
                            color = when {
                                state.diagnosticRunning -> AccentBlue
                                state.diagnosticStatus.startsWith("通过") -> Success
                                state.diagnosticStatus.startsWith("失败") -> AccentCoral
                                else -> Muted
                            },
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            if (state.diagnosticMode) {
                Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = CardBackground)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("完整诊断数据", fontWeight = FontWeight.Bold)
                        Metric("手表样本", state.receivedCount.toString())
                        Metric("未转发样本", state.throttledCount.toString())
                        Metric("已发往电脑", state.forwardedCount.toString())
                        Metric("电脑确认", state.pcAckCount.toString())
                        Metric("原始 BPM", state.watchRawBpm?.toString() ?: "--")
                        Metric("传感器精度", state.watchAccuracy ?: "--")
                        Metric("手表电量", state.watchBatteryPercent?.let { "$it%" } ?: "--")
                        Metric(
                            "手表屏幕",
                            state.watchScreenInteractive?.let { if (it) "亮屏" else "息屏" } ?: "--",
                        )
                        Metric("手表发送模式", state.watchRelayMode ?: "--")
                        Metric("心率来源", sourceLabel(state.heartRateSource))
                        if (state.heartRateSource == HeartRateSource.XIAOMI_BAND_BLE) {
                            Metric("BLE 设备", state.xiaomiDeviceName ?: "--")
                            Metric("BLE 地址", state.xiaomiDeviceAddress ?: "--")
                        }
                        Metric("手表发送间隔", state.watchRelayIntervalSeconds?.let { "${it}s" } ?: "--")
                        Metric("手机网络", state.networkType + if (state.vpnActive) " · VPN" else "")
                        Metric("手机局域网 IP", state.localIp)
                        Metric("最近手表数据", state.lastPhoneReceiveMillis?.let(::formatTime) ?: "--")
                        Metric("最近电脑确认", state.lastPcAckMillis?.let(::formatTime) ?: "--")
                        Metric("当前目标", state.targetIp.ifBlank { "未设置" } + ":${state.targetPort}")
                    }
                }
            }

            if (state.lastError != "--") AlertCard(state.lastError, AccentCoral.copy(alpha = 0.16f), AccentCoral)
            if (state.diagnosticMode && state.vpnActive) {
                AlertCard("检测到 VPN。电脑回执失败时，请允许局域网访问或暂时关闭 VPN。", Color(0x33FFB74D), Color(0xFFFFC56D))
            }
            Text(
                if (state.heartRateSource == HeartRateSource.XIAOMI_BAND_BLE) {
                    "小米模式只在启用时运行 BLE 前台服务；这里的发送间隔只控制手机到电脑。切回 Galaxy Watch 后会立即停止 BLE 扫描和连接。"
                } else {
                    "手表决定新心率多久到达手机；这里的发送间隔只控制手机到电脑。暂停后手机仍继续接收手表数据。"
                },
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun DiagnosticModeCard(enabled: Boolean, source: HeartRateSource) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = CardElevated)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("诊断模式", fontWeight = FontWeight.Bold)
                Text(
                    if (enabled) {
                        if (source == HeartRateSource.XIAOMI_BAND_BLE) {
                            "采集 BLE 设备与完整链路字段；电脑开关会同步到手机"
                        } else {
                            "采集手表扩展字段并显示完整链路数据；电脑开关会同步"
                        }
                    } else {
                        "普通模式：只保留心率中转所需数据；电脑端为主开关"
                    },
                    color = Muted,
                    fontSize = 12.sp,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = PhoneRelayRepository::setDiagnosticMode,
            )
        }
    }
}

@Composable
private fun HeartRateHero(state: PhoneRelayState, now: Long, watchAlive: Boolean) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = CardElevated),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 22.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(if (watchAlive) Success else Muted))
                    Text(if (watchAlive) "LIVE SENSOR" else "WAITING", color = if (watchAlive) Success else Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Text(state.currentBpm?.toString() ?: "--", fontSize = 66.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("BPM", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AccentCoral)
                Text(
                    state.lastSampleMillis?.let { "采样于 ${formatTime(it)} · ${ageText(now, it)}" }
                        ?: "等待${sourceLabel(state.heartRateSource)}数据",
                    color = Muted,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun HeartRateSourceCard(
    state: PhoneRelayState,
    onSwitchToXiaomi: () -> Unit,
    onSwitchToGalaxy: () -> Unit,
    onScan: () -> Unit,
    onConnect: (XiaomiBandCandidate) -> Unit,
) {
    val xiaomiMode = state.heartRateSource == HeartRateSource.XIAOMI_BAND_BLE
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = CardElevated)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("心率来源", fontWeight = FontWeight.Bold)
                    Text(sourceLabel(state.heartRateSource), color = Muted, fontSize = 12.sp)
                }
                Box(
                    Modifier.size(9.dp).clip(CircleShape)
                        .background(if (state.watchConnected) Success else Muted),
                )
            }
            if (!xiaomiMode) {
                Button(onClick = onSwitchToXiaomi, modifier = Modifier.fillMaxWidth()) {
                    Text("切换至小米手环", fontWeight = FontWeight.Bold)
                }
                Text(
                    "支持小米手环 10 等提供标准 BLE 心率广播的设备。切换前请在手环打开：设置 → 共享心率 → 开启。",
                    color = Muted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            } else {
                Text(state.xiaomiStatus, color = if (state.xiaomiConnected) Success else Muted, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onScan,
                        enabled = !state.xiaomiScanning,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (state.xiaomiScanning) "扫描中…" else "重新扫描")
                    }
                    OutlinedButton(onClick = onSwitchToGalaxy, modifier = Modifier.weight(1f)) {
                        Text("切回 Galaxy Watch")
                    }
                }
                state.xiaomiCandidates.forEach { candidate ->
                    OutlinedButton(
                        onClick = { onConnect(candidate) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(candidate.name, fontWeight = FontWeight.Bold)
                            Text(
                                "${candidate.address} · ${candidate.signalStrength} dBm",
                                color = Muted,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
                Text(
                    "只扫描标准心率服务，不读取小米账号或历史健康数据。首次找到设备后点一下设备；以后会记住并自动重连。",
                    color = Muted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }
        }
    }
}

@Composable
private fun TransferControlCard(
    enabled: Boolean,
    intervalSeconds: Int,
    watchIntervalSeconds: Int?,
    source: HeartRateSource,
) {
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = CardElevated)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("发送控制", fontWeight = FontWeight.Bold)
                    Text(
                        if (enabled) "手机 → 电脑" else "已暂停，手机仍继续接收",
                        color = Muted,
                        fontSize = 12.sp,
                    )
                }
                Text(if (enabled) "运行中" else "已暂停", color = if (enabled) Success else AccentCoral, fontWeight = FontWeight.Bold)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${sourceLabel(source)} → 手机", color = Muted, fontSize = 12.sp)
                Text(
                    if (source == HeartRateSource.XIAOMI_BAND_BLE) {
                        "由 BLE 广播决定"
                    } else {
                        watchIntervalSeconds?.let { "约 ${it} 秒" } ?: "等待手表上报"
                    },
                    fontSize = 12.sp,
                )
            }
            Text("手机 → 电脑发送间隔", color = Muted, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf(1, 2, 5, 10, 30).forEach { seconds ->
                    FilterChip(
                        selected = intervalSeconds == seconds,
                        onClick = { PhoneRelayRepository.setForwardIntervalSeconds(seconds) },
                        label = { Text("${seconds}s") },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Button(
                onClick = { PhoneRelayRepository.setForwardingEnabled(!enabled) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (enabled) Color(0xFF2B2022) else AccentBlue,
                    contentColor = if (enabled) AccentCoral else Color(0xFF381E72),
                ),
            ) { Text(if (enabled) "暂停发送到电脑" else "恢复发送到电脑", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
}

@Composable
private fun StatusCard(step: String, title: String, subtitle: String, connected: Boolean) {
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = CardBackground)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(if (connected) AccentBlue.copy(alpha = 0.16f) else Muted.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) { Text(step, color = if (connected) AccentBlue else Muted, fontWeight = FontWeight.Bold) }
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(subtitle, color = Muted, fontSize = 13.sp)
            }
            Box(Modifier.size(9.dp).clip(CircleShape).background(if (connected) Success else Muted))
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 13.sp)
        Text(value, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

@Composable
private fun AlertCard(text: String, background: Color, foreground: Color) {
    Card(colors = CardDefaults.cardColors(containerColor = background), shape = RoundedCornerShape(16.dp)) {
        Text(text, Modifier.padding(15.dp), color = foreground, fontSize = 13.sp)
    }
}

private fun saveTarget(ip: String, port: String) {
    val parsed = port.toIntOrNull()
    if (parsed != null && parsed in 1..65_535) PhoneRelayRepository.saveTarget(ip, parsed)
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))

private fun ageText(now: Long, millis: Long): String = "${((now - millis).coerceAtLeast(0) / 1_000)} 秒前"

private fun sourceLabel(source: HeartRateSource): String = when (source) {
    HeartRateSource.GALAXY_WATCH -> "Galaxy Watch"
    HeartRateSource.XIAOMI_BAND_BLE -> "小米手环 BLE"
}

private fun xiaomiBlePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

private fun xiaomiRuntimePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33) {
        xiaomiBlePermissions() + Manifest.permission.POST_NOTIFICATIONS
    } else {
        xiaomiBlePermissions()
    }

private fun hasXiaomiBlePermissions(context: Context): Boolean =
    xiaomiBlePermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

private fun startXiaomiService(
    context: Context,
    action: String,
    candidate: XiaomiBandCandidate? = null,
) {
    val intent = Intent(context, XiaomiHeartRateService::class.java)
        .setAction(action)
    candidate?.let {
        intent.putExtra(XiaomiHeartRateService.EXTRA_ADDRESS, it.address)
        intent.putExtra(XiaomiHeartRateService.EXTRA_NAME, it.name)
    }
    ContextCompat.startForegroundService(context, intent)
}
