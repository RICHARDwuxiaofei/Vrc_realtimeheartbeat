package best.nagikokoro.watch6heartrateprobe.mobile

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val context = LocalContext.current
    val colors = if (Build.VERSION.SDK_INT >= 31) dynamicLightColorScheme(context) else lightColorScheme(
        primary = Color(0xFF6750A4),
        secondary = Color(0xFF625B71),
    )
    MaterialTheme(colorScheme = colors) { RelayScreen() }
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
    val pcAlive = state.lastPcAckMillis?.let { now - it < 15_000 } == true

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("心率中转站", fontWeight = FontWeight.SemiBold)
                        Text("Watch → Phone → PC", style = MaterialTheme.typography.labelMedium)
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
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(Modifier.padding(24.dp)) {
                    Text("当前心率", style = MaterialTheme.typography.labelLarge)
                    Text(
                        state.currentBpm?.let { "$it BPM" } ?: "-- BPM",
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        state.lastSampleMillis?.let { "采样于 ${formatTime(it)} · ${ageText(now, it)}" } ?: "等待手表数据",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Text("连接状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            StatusCard("1", "Galaxy Watch6", if (watchAlive) "正在接收 · 序号 ${state.lastSequence ?: "--"}" else "等待心率样本", watchAlive)
            StatusCard("2", "这台手机", "${state.networkType} · ${state.localIp}", state.localIp != "--")
            StatusCard("3", "Windows 接收器", if (pcAlive) "已收到电脑回执" else "等待电脑回执", pcAlive)

            Text("电脑地址", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = ip,
                onValueChange = { ip = it.trim() },
                label = { Text("电脑 IPv4，例如 192.168.100.107") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit).take(5) },
                label = { Text("UDP 端口") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        val parsed = port.toIntOrNull()
                        if (parsed != null && parsed in 1..65_535) PhoneRelayRepository.saveTarget(ip, parsed)
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("保存") }
                OutlinedButton(
                    onClick = {
                        val parsed = port.toIntOrNull()
                        if (parsed != null && parsed in 1..65_535) {
                            PhoneRelayRepository.saveTarget(ip, parsed)
                            PhoneRelayRepository.sendTestPacket()
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("发送测试包") }
            }

            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Metric("手表接收", state.receivedCount.toString())
                    Metric("已发往电脑", state.forwardedCount.toString())
                    Metric("电脑确认", state.pcAckCount.toString())
                    Metric("最近接收", state.lastPhoneReceiveMillis?.let(::formatTime) ?: "--")
                    Metric("最近电脑确认", state.lastPcAckMillis?.let(::formatTime) ?: "--")
                    Metric("目标", state.targetIp.ifBlank { "未设置" } + ":${state.targetPort}")
                    if (state.forwarding) Metric("状态", "正在等待电脑回执…")
                }
            }

            if (state.lastError != "--") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Text(state.lastError, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            Text(
                "实时数据会自动转发。电脑无需填写手机 IP；收到数据后会自动识别本机。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun StatusCard(step: String, title: String, subtitle: String, connected: Boolean) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                step,
                color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
            Column {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))

private fun ageText(now: Long, millis: Long): String = "${((now - millis).coerceAtLeast(0) / 1_000)} 秒前"
