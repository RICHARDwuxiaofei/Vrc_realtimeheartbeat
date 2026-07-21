package best.nagikokoro.watch6heartrateprobe.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import best.nagikokoro.watch6heartrateprobe.R
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
    val state by PhoneRelayRepository.state.collectAsStateWithLifecycle()
    var ip by remember(state.targetIp) { mutableStateOf(state.targetIp) }
    var port by remember(state.targetPort) { mutableStateOf(state.targetPort.toString()) }
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

            SectionTitle("链路状态")
            StatusCard("01", "Galaxy Watch6", if (watchAlive) "蓝牙中转正常 · 序号 ${state.lastSequence ?: "--"}" else "等待手表心率", watchAlive)
            StatusCard("02", "这台手机", "${state.networkType} · ${state.localIp}" + if (state.vpnActive) " · VPN 已开启" else "", state.localIp != "--")
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
            )
            if (watchIntervalSeconds != null &&
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
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { saveTarget(ip, port) },
                            modifier = Modifier.weight(1f),
                        ) { Text("保存设置") }
                        OutlinedButton(
                            onClick = {
                                saveTarget(ip, port)
                                PhoneRelayRepository.sendTestPacket()
                            },
                            enabled = state.forwardingEnabled,
                            modifier = Modifier.weight(1f),
                        ) { Text("测试电脑") }
                    }
                }
            }

            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = CardBackground)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("运行统计", fontWeight = FontWeight.Bold)
                    Metric("手表样本", state.receivedCount.toString())
                    Metric("未转发样本", state.throttledCount.toString())
                    Metric("已发往电脑", state.forwardedCount.toString())
                    Metric("电脑确认", state.pcAckCount.toString())
                    Metric("最近手表数据", state.lastPhoneReceiveMillis?.let(::formatTime) ?: "--")
                    Metric("最近电脑确认", state.lastPcAckMillis?.let(::formatTime) ?: "--")
                    Metric("当前目标", state.targetIp.ifBlank { "未设置" } + ":${state.targetPort}")
                }
            }

            if (state.lastError != "--") AlertCard(state.lastError, AccentCoral.copy(alpha = 0.16f), AccentCoral)
            if (state.vpnActive) {
                AlertCard("检测到 VPN。电脑回执失败时，请允许局域网访问或暂时关闭 VPN。", Color(0x33FFB74D), Color(0xFFFFC56D))
            }
            Text(
                "手表决定新心率多久到达手机；这里的发送间隔只控制手机到电脑。暂停后手机仍继续接收手表数据。",
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(20.dp))
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
                    state.lastSampleMillis?.let { "采样于 ${formatTime(it)} · ${ageText(now, it)}" } ?: "等待 Galaxy Watch6 数据",
                    color = Muted,
                    fontSize = 13.sp,
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
                Text("手表 → 手机", color = Muted, fontSize = 12.sp)
                Text(watchIntervalSeconds?.let { "约 ${it} 秒" } ?: "等待手表上报", fontSize = 12.sp)
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
