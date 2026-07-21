package best.nagikokoro.watch6heartrateprobe.mobile

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.Executors

data class PhoneRelayState(
    val targetIp: String = "",
    val targetPort: Int = RelayProtocol.DEFAULT_PC_PORT,
    val localIp: String = "--",
    val networkType: String = "未连接",
    val vpnActive: Boolean = false,
    val watchNodeId: String = "--",
    val watchConnected: Boolean = false,
    val currentBpm: Int? = null,
    val receivedCount: Long = 0,
    val forwardedCount: Long = 0,
    val pcAckCount: Long = 0,
    val lastSequence: Long? = null,
    val lastSampleMillis: Long? = null,
    val lastPhoneReceiveMillis: Long? = null,
    val lastPcAckMillis: Long? = null,
    val lastError: String = "--",
    val forwarding: Boolean = false,
    val forwardingEnabled: Boolean = true,
    val forwardIntervalSeconds: Int = 5,
    val throttledCount: Long = 0,
)

object PhoneRelayRepository {
    private const val PREFS = "phone_relay_settings"
    private val executor = Executors.newSingleThreadExecutor()
    private val pendingForwardLock = Any()
    private var pendingForward: PendingForward? = null
    private var forwardWorkerRunning = false
    private var lastHeartRateForwardElapsed = 0L
    private val mutableState = MutableStateFlow(PhoneRelayState())
    val state = mutableState.asStateFlow()
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        update {
            it.copy(
                targetIp = prefs.getString("targetIp", "") ?: "",
                targetPort = prefs.getInt("targetPort", RelayProtocol.DEFAULT_PC_PORT),
                localIp = findLocalIpv4(),
                networkType = networkType(context),
                vpnActive = isVpnActive(context),
                forwardingEnabled = prefs.getBoolean("forwardingEnabled", true),
                forwardIntervalSeconds = prefs.getInt("forwardIntervalSeconds", 5).coerceIn(1, 30),
            )
        }
    }

    fun saveTarget(ip: String, port: Int) {
        val context = appContext ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("targetIp", ip.trim())
            .putInt("targetPort", port)
            .apply()
        update { it.copy(targetIp = ip.trim(), targetPort = port, lastError = "--") }
    }

    fun refreshNetwork() {
        val context = appContext ?: return
        update {
            it.copy(
                localIp = findLocalIpv4(),
                networkType = networkType(context),
                vpnActive = isVpnActive(context),
            )
        }
    }

    fun setForwardIntervalSeconds(seconds: Int) {
        val context = appContext ?: return
        val value = seconds.coerceIn(1, 30)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("forwardIntervalSeconds", value)
            .apply()
        lastHeartRateForwardElapsed = 0L
        update { it.copy(forwardIntervalSeconds = value, lastError = "--") }
    }

    fun setForwardingEnabled(enabled: Boolean) {
        val context = appContext ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("forwardingEnabled", enabled)
            .apply()
        if (!enabled) {
            synchronized(pendingForwardLock) { pendingForward = null }
        } else {
            lastHeartRateForwardElapsed = 0L
        }
        update {
            it.copy(
                forwardingEnabled = enabled,
                forwarding = if (enabled) it.forwarding else false,
                lastError = "--",
            )
        }
    }

    @Synchronized
    fun handleWatchSample(sourceNodeId: String, bytes: ByteArray) {
        val context = appContext ?: return
        val phoneReceiveMillis = System.currentTimeMillis()
        val json = try {
            JSONObject(bytes.toString(Charsets.UTF_8))
        } catch (failure: Throwable) {
            update { it.copy(lastError = "手表数据格式错误：${failure.message}") }
            return
        }
        val sequence = json.optLong("sequence", -1L)
        if (sequence >= 0 && mutableState.value.lastSequence == sequence) {
            Log.i(TAG, "Duplicate Data Layer sequence=$sequence ignored")
            return
        }
        Log.i(TAG, "Watch sample accepted type=${json.optString("type")} sequence=$sequence source=$sourceNodeId")
        val bpm = json.optInt("bpm", -1).takeIf { it > 0 }
        val sampleMillis = json.optLong("sampleEpochMillis", 0L).takeIf { it > 0 }
        json.put("phoneReceivedEpochMillis", phoneReceiveMillis)
        json.put("phoneLocalIp", findLocalIpv4())
        json.put("phoneForwardIntervalSeconds", mutableState.value.forwardIntervalSeconds)
        update {
            it.copy(
                watchNodeId = sourceNodeId,
                watchConnected = true,
                currentBpm = bpm,
                receivedCount = it.receivedCount + 1,
                lastSequence = sequence.takeIf { value -> value >= 0 },
                lastSampleMillis = sampleMillis,
                lastPhoneReceiveMillis = phoneReceiveMillis,
                lastError = "--",
            )
        }
        val isRealHeartRate = json.optString("type") == "heart_rate"
        if (isRealHeartRate && !shouldForwardHeartRate()) {
            update { it.copy(throttledCount = it.throttledCount + 1) }
            return
        }
        forward(context, sourceNodeId, json, isRealHeartRate)
    }

    fun sendTestPacket() {
        val context = appContext ?: return
        val now = System.currentTimeMillis()
        val json = JSONObject()
            .put("version", 1)
            .put("type", "phone_test")
            .put("sessionId", "phone-test")
            .put("sequence", now)
            .put("sampleEpochMillis", now)
            .put("phoneReceivedEpochMillis", now)
            .put("bpm", 72)
            .put("rawBpm", 72.0)
            .put("accuracy", "TEST")
            .put("watchBatteryPercent", -1)
            .put("watchScreenInteractive", true)
            .put("phoneLocalIp", findLocalIpv4())
        forward(context, null, json, isHeartRate = false)
    }

    @Synchronized
    private fun shouldForwardHeartRate(): Boolean {
        val state = mutableState.value
        if (!state.forwardingEnabled) return false
        val now = SystemClock.elapsedRealtime()
        val intervalMillis = state.forwardIntervalSeconds * 1_000L
        if (!RelayIntervalPolicy.isDue(lastHeartRateForwardElapsed, now, intervalMillis)) {
            return false
        }
        lastHeartRateForwardElapsed = now
        return true
    }

    private fun forward(context: Context, watchNodeId: String?, json: JSONObject, isHeartRate: Boolean) {
        val target = mutableState.value
        if (!target.forwardingEnabled) {
            update { it.copy(lastError = "已暂停发送到电脑") }
            return
        }
        if (target.targetIp.isBlank()) {
            update { it.copy(lastError = "请先填写电脑 IP") }
            if (watchNodeId != null) sendWatchAck(context, watchNodeId, json.optLong("sequence"), false, "电脑 IP 未设置")
            return
        }
        var shouldStartWorker = false
        synchronized(pendingForwardLock) {
            // Real-time heart rate must never build an unbounded retry queue while the PC is offline.
            // Keep the packet currently in flight and replace any waiting packet with the newest sample.
            pendingForward = PendingForward(
                context,
                watchNodeId,
                JSONObject(json.toString()),
                target.targetIp,
                target.targetPort,
                isHeartRate,
            )
            if (!forwardWorkerRunning) {
                forwardWorkerRunning = true
                shouldStartWorker = true
            }
        }
        update { it.copy(forwarding = true) }
        if (shouldStartWorker) {
            executor.execute(::drainLatestForwards)
        }
    }

    private fun drainLatestForwards() {
        while (true) {
            val request = synchronized(pendingForwardLock) {
                pendingForward.also { pendingForward = null }
            }
            if (request == null) {
                val reallyFinished = synchronized(pendingForwardLock) {
                    if (pendingForward == null) {
                        forwardWorkerRunning = false
                        true
                    } else {
                        false
                    }
                }
                if (reallyFinished) {
                    update { it.copy(forwarding = false) }
                    return
                }
                continue
            }
            performForward(request)
        }
    }

    private fun performForward(request: PendingForward) {
        val sequence = request.json.optLong("sequence")
        var pcAck = false
        var error = ""
        try {
            if (request.isHeartRate && !mutableState.value.forwardingEnabled) {
                Log.i(TAG, "Forward skipped after pause sequence=$sequence")
                return
            }
            Log.i(TAG, "Forwarding sequence=$sequence to ${request.targetIp}:${request.targetPort}")
            DatagramSocket().use { socket ->
                socket.soTimeout = 1_000
                val bytes = request.json.toString().toByteArray(Charsets.UTF_8)
                val address = InetAddress.getByName(request.targetIp)
                socket.send(DatagramPacket(bytes, bytes.size, address, request.targetPort))
                update { it.copy(forwardedCount = it.forwardedCount + 1) }
                val ackBuffer = ByteArray(1_024)
                val ackPacket = DatagramPacket(ackBuffer, ackBuffer.size)
                socket.receive(ackPacket)
                val ack = JSONObject(String(ackPacket.data, 0, ackPacket.length, Charsets.UTF_8))
                pcAck = ack.optString("type") == "pc_ack" &&
                    ack.optLong("sequence", Long.MIN_VALUE) == sequence
                if (!pcAck) error = "电脑回执内容不匹配"
                Log.i(TAG, "PC acknowledgement sequence=$sequence matched=$pcAck")
            }
        } catch (failure: Throwable) {
            val detail = failure.message ?: failure.javaClass.simpleName
            error = if (detail.contains("EPERM", ignoreCase = true) || detail.contains("Operation not permitted", ignoreCase = true)) {
                if (isVpnActive(request.context)) {
                    "手机 VPN 阻止了局域网 UDP（EPERM）。请在 VPN 中开启“绕过局域网/允许局域网”，或测试时关闭 VPN"
                } else {
                    "系统拒绝局域网 UDP（EPERM）。请检查“附近设备/局域网”权限和系统网络限制"
                }
            } else {
                "电脑未回执：$detail"
            }
            Log.e(TAG, "UDP forwarding failed sequence=$sequence", failure)
        }
        val ackMillis = if (pcAck) System.currentTimeMillis() else null
        update {
            it.copy(
                pcAckCount = it.pcAckCount + if (pcAck) 1 else 0,
                lastPcAckMillis = ackMillis ?: it.lastPcAckMillis,
                lastError = error.ifBlank { "--" },
            )
        }
        if (request.watchNodeId != null) {
            sendWatchAck(request.context, request.watchNodeId, sequence, pcAck, error)
        }
    }

    private fun sendWatchAck(context: Context, nodeId: String, sequence: Long, pcAck: Boolean, error: String) {
        val payload = JSONObject()
            .put("version", 1)
            .put("type", "phone_ack")
            .put("sequence", sequence)
            .put("pcAck", pcAck)
            .put("phoneEpochMillis", System.currentTimeMillis())
            .put("error", error)
            .toString()
            .toByteArray(Charsets.UTF_8)
        Wearable.getMessageClient(context).sendMessage(nodeId, RelayProtocol.ACK_PATH, payload)
            .addOnSuccessListener {
                Log.i(TAG, "Phone acknowledgement queued to watch node=$nodeId sequence=$sequence pcAck=$pcAck")
            }
            .addOnFailureListener { failure ->
                Log.e(TAG, "Phone acknowledgement failed node=$nodeId sequence=$sequence", failure)
                update { it.copy(lastError = "回传手表失败：${failure.message ?: failure.javaClass.simpleName}") }
            }
    }

    @Synchronized
    private fun update(transform: (PhoneRelayState) -> PhoneRelayState) {
        mutableState.value = transform(mutableState.value)
    }

    private fun findLocalIpv4(): String = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { Collections.list(it.inetAddresses).asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress ?: "--"
    }.getOrDefault("--")

    private fun networkType(context: Context): String {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return "未连接"
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动网络"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
            else -> "其他网络"
        }
    }

    private fun isVpnActive(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return manager.getNetworkCapabilities(manager.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }

    private data class PendingForward(
        val context: Context,
        val watchNodeId: String?,
        val json: JSONObject,
        val targetIp: String,
        val targetPort: Int,
        val isHeartRate: Boolean,
    )

    private const val TAG = "HR_RELAY"
}
