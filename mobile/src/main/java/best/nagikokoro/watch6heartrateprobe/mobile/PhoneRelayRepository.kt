package best.nagikokoro.watch6heartrateprobe.mobile

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
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
import java.util.concurrent.atomic.AtomicLong

data class PhoneRelayState(
    val heartRateSource: HeartRateSource = HeartRateSource.GALAXY_WATCH,
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
    val watchRelayIntervalSeconds: Int? = null,
    val watchRelayMode: String? = null,
    val lastPcAckMillis: Long? = null,
    val lastError: String = "--",
    val forwarding: Boolean = false,
    val forwardingEnabled: Boolean = true,
    val forwardIntervalSeconds: Int = 5,
    val throttledCount: Long = 0,
    val diagnosticRunning: Boolean = false,
    val diagnosticStatus: String = "尚未诊断",
    val diagnosticMode: Boolean = false,
    val watchRawBpm: Double? = null,
    val watchAccuracy: String? = null,
    val watchBatteryPercent: Int? = null,
    val watchScreenInteractive: Boolean? = null,
    val xiaomiStatus: String = "尚未启用",
    val xiaomiScanning: Boolean = false,
    val xiaomiConnected: Boolean = false,
    val xiaomiDeviceAddress: String? = null,
    val xiaomiDeviceName: String? = null,
    val xiaomiCandidates: List<XiaomiBandCandidate> = emptyList(),
)

object PhoneRelayRepository {
    private const val PREFS = "phone_relay_settings"
    private val executor = Executors.newSingleThreadExecutor()
    private val pendingForwardLock = Any()
    private val pendingForwards = LatestForwardBuffer<PendingForward>()
    private var forwardWorkerRunning = false
    private var lastHeartRateForwardElapsed = 0L
    private var lastWatchDiagnosticNodeId: String? = null
    private var lastWatchDiagnosticMode: Boolean? = null
    private val xiaomiSequence = AtomicLong(System.currentTimeMillis())
    private val mutableState = MutableStateFlow(PhoneRelayState())
    val state = mutableState.asStateFlow()
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        update {
            it.copy(
                heartRateSource = HeartRateSource.fromPreference(prefs.getString("heartRateSource", null)),
                targetIp = prefs.getString("targetIp", "") ?: "",
                targetPort = prefs.getInt("targetPort", RelayProtocol.DEFAULT_PC_PORT),
                localIp = findLocalIpv4(),
                networkType = networkType(context),
                vpnActive = isVpnActive(context),
                forwardingEnabled = prefs.getBoolean("forwardingEnabled", true),
                forwardIntervalSeconds = prefs.getInt("forwardIntervalSeconds", 5).coerceIn(1, 30),
                xiaomiDeviceAddress = prefs.getString("xiaomiDeviceAddress", null),
                xiaomiDeviceName = prefs.getString("xiaomiDeviceName", null),
            )
        }
    }

    fun setHeartRateSource(source: HeartRateSource) {
        val context = appContext ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString("heartRateSource", source.wireName)
        }
        lastHeartRateForwardElapsed = 0L
        update {
            it.copy(
                heartRateSource = source,
                watchConnected = false,
                currentBpm = null,
                lastSampleMillis = null,
                lastPhoneReceiveMillis = null,
                watchRelayIntervalSeconds = null,
                watchRelayMode = null,
                xiaomiStatus = if (source == HeartRateSource.XIAOMI_BAND_BLE) {
                    "准备连接小米手环"
                } else {
                    "尚未启用"
                },
                xiaomiScanning = false,
                xiaomiConnected = false,
                xiaomiCandidates = if (source == HeartRateSource.XIAOMI_BAND_BLE) {
                    it.xiaomiCandidates
                } else {
                    emptyList()
                },
                lastError = "--",
            )
        }
    }

    fun isXiaomiMode(): Boolean =
        mutableState.value.heartRateSource == HeartRateSource.XIAOMI_BAND_BLE

    fun savedXiaomiDevice(): Pair<String, String>? {
        val state = mutableState.value
        val address = state.xiaomiDeviceAddress?.takeIf(String::isNotBlank) ?: return null
        return address to (state.xiaomiDeviceName?.takeIf(String::isNotBlank) ?: "小米手环")
    }

    fun saveXiaomiDevice(address: String, name: String) {
        val context = appContext ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString("xiaomiDeviceAddress", address)
            putString("xiaomiDeviceName", name)
        }
        update { it.copy(xiaomiDeviceAddress = address, xiaomiDeviceName = name) }
    }

    fun clearXiaomiCandidates() {
        update { it.copy(xiaomiCandidates = emptyList()) }
    }

    fun addXiaomiCandidate(candidate: XiaomiBandCandidate) {
        if (!isXiaomiMode()) return
        update { state ->
            val candidates = (state.xiaomiCandidates.filterNot { it.address == candidate.address } + candidate)
                .sortedByDescending(XiaomiBandCandidate::signalStrength)
            state.copy(xiaomiCandidates = candidates)
        }
    }

    fun updateXiaomiConnection(
        status: String,
        connected: Boolean,
        scanning: Boolean,
        address: String? = null,
        name: String? = null,
    ) {
        if (!isXiaomiMode() && status != "已切回 Galaxy Watch") return
        update {
            it.copy(
                xiaomiStatus = status,
                xiaomiConnected = connected,
                xiaomiScanning = scanning,
                watchConnected = connected,
                xiaomiDeviceAddress = address ?: it.xiaomiDeviceAddress,
                xiaomiDeviceName = name ?: it.xiaomiDeviceName,
                lastError = "--",
            )
        }
    }

    fun reportXiaomiError(message: String) {
        update {
            it.copy(
                xiaomiStatus = message,
                xiaomiScanning = false,
                lastError = message,
            )
        }
    }

    fun saveTarget(ip: String, port: Int) {
        val context = appContext ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString("targetIp", ip.trim())
            putInt("targetPort", port)
        }
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
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putInt("forwardIntervalSeconds", value)
        }
        lastHeartRateForwardElapsed = 0L
        update { it.copy(forwardIntervalSeconds = value, lastError = "--") }
    }

    fun setForwardingEnabled(enabled: Boolean) {
        val context = appContext ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean("forwardingEnabled", enabled)
        }
        if (!enabled) {
            synchronized(pendingForwardLock) { pendingForwards.clear() }
        } else {
            lastHeartRateForwardElapsed = 0L
        }
        update {
            it.copy(
                forwardingEnabled = enabled,
                forwarding = if (enabled) it.forwarding else false,
                diagnosticRunning = if (enabled) it.diagnosticRunning else false,
                diagnosticStatus = if (!enabled && it.diagnosticRunning) {
                    "诊断已取消：发送到电脑已暂停"
                } else {
                    it.diagnosticStatus
                },
                lastError = "--",
            )
        }
    }

    @Synchronized
    fun handleWatchSample(sourceNodeId: String, bytes: ByteArray) {
        if (mutableState.value.heartRateSource != HeartRateSource.GALAXY_WATCH) return
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
            if (mutableState.value.diagnosticMode) {
                Log.i(TAG, "Duplicate Data Layer sequence=$sequence ignored")
            }
            return
        }
        val diagnosticMode = mutableState.value.diagnosticMode
        if (diagnosticMode) {
            Log.i(TAG, "Watch sample accepted type=${json.optString("type")} sequence=$sequence source=$sourceNodeId")
        }
        val bpm = json.optInt("bpm", -1).takeIf { it > 0 }
        val sampleMillis = json.optLong("sampleEpochMillis", 0L).takeIf { it > 0 }
        val watchRelayInterval = json.optInt("watchRelayIntervalSeconds", 0).takeIf { it in 1..30 }
        val watchRelayMode = json.optString("watchRelayMode").takeIf { it.isNotBlank() }
        val watchAckRequested = json.optBoolean("watchAckRequested", true)
        val effectiveForwardInterval = maxOf(
            mutableState.value.forwardIntervalSeconds,
            watchRelayInterval ?: 0,
        )
        if (diagnosticMode) {
            json.put("diagnosticMode", true)
            json.put("phoneReceivedEpochMillis", phoneReceiveMillis)
            json.put("phoneLocalIp", findLocalIpv4())
            json.put("phoneNetworkType", networkType(context))
            json.put("phoneVpnActive", isVpnActive(context))
        } else {
            RelayDiagnosticFields.names.forEach(json::remove)
        }
        json.put("source", HeartRateSource.GALAXY_WATCH.wireName)
        json.put("phoneForwardIntervalSeconds", effectiveForwardInterval)
        update {
            it.copy(
                watchNodeId = sourceNodeId,
                watchConnected = true,
                currentBpm = bpm,
                receivedCount = it.receivedCount + 1,
                lastSequence = sequence.takeIf { value -> value >= 0 },
                lastSampleMillis = sampleMillis,
                lastPhoneReceiveMillis = phoneReceiveMillis,
                watchRelayIntervalSeconds = watchRelayInterval ?: it.watchRelayIntervalSeconds,
                watchRelayMode = watchRelayMode ?: it.watchRelayMode,
                watchRawBpm = if (diagnosticMode && json.has("rawBpm")) json.optDouble("rawBpm") else it.watchRawBpm,
                watchAccuracy = if (diagnosticMode) {
                    json.optString("accuracy").takeIf(String::isNotBlank)
                } else {
                    it.watchAccuracy
                },
                watchBatteryPercent = if (diagnosticMode && json.has("watchBatteryPercent")) {
                    json.optInt("watchBatteryPercent").takeIf { value -> value >= 0 }
                } else {
                    it.watchBatteryPercent
                },
                watchScreenInteractive = if (diagnosticMode && json.has("watchScreenInteractive")) {
                    json.optBoolean("watchScreenInteractive")
                } else {
                    it.watchScreenInteractive
                },
                lastError = "--",
            )
        }
        val isRealHeartRate = json.optString("type") == "heart_rate"
        syncDiagnosticModeToWatch(context, sourceNodeId, diagnosticMode)
        if (isRealHeartRate && !shouldForwardHeartRate()) {
            update { it.copy(throttledCount = it.throttledCount + 1) }
            return
        }
        forward(context, sourceNodeId, json, isRealHeartRate, watchAckRequested)
    }

    @Synchronized
    fun handleXiaomiHeartRate(address: String, name: String, bpm: Int) {
        if (!isXiaomiMode() || bpm !in 1..300) return
        val context = appContext ?: return
        val now = System.currentTimeMillis()
        val sequence = xiaomiSequence.incrementAndGet()
        val diagnosticMode = mutableState.value.diagnosticMode
        val json = JSONObject()
            .put("version", 1)
            .put("type", "heart_rate")
            .put("source", HeartRateSource.XIAOMI_BAND_BLE.wireName)
            .put("sequence", sequence)
            .put("sampleEpochMillis", now)
            .put("bpm", bpm)
            .put("phoneForwardIntervalSeconds", mutableState.value.forwardIntervalSeconds)
            .put("watchAckRequested", false)
        if (diagnosticMode) {
            json
                .put("diagnosticMode", true)
                .put("sessionId", "xiaomi-ble")
                .put("rawBpm", bpm.toDouble())
                .put("accuracy", "Bluetooth SIG Heart Rate Profile")
                .put("sourceDeviceName", name)
                .put("sourceDeviceAddress", address)
                .put("phoneReceivedEpochMillis", now)
                .put("phoneLocalIp", findLocalIpv4())
                .put("phoneNetworkType", networkType(context))
                .put("phoneVpnActive", isVpnActive(context))
        }
        update {
            it.copy(
                watchNodeId = address,
                watchConnected = true,
                currentBpm = bpm,
                receivedCount = it.receivedCount + 1,
                lastSequence = sequence,
                lastSampleMillis = now,
                lastPhoneReceiveMillis = now,
                watchRelayMode = if (diagnosticMode) "Xiaomi BLE Share HR" else null,
                watchRawBpm = if (diagnosticMode) bpm.toDouble() else null,
                watchAccuracy = if (diagnosticMode) "Bluetooth SIG Heart Rate Profile" else null,
                xiaomiStatus = "正在接收实时心率",
                xiaomiConnected = true,
                xiaomiScanning = false,
                xiaomiDeviceAddress = address,
                xiaomiDeviceName = name,
                lastError = "--",
            )
        }
        if (!shouldForwardHeartRate()) {
            update { it.copy(throttledCount = it.throttledCount + 1) }
            return
        }
        forward(
            context = context,
            watchNodeId = null,
            json = json,
            isHeartRate = true,
            watchAckRequested = false,
        )
    }

    fun runDiagnostics() {
        val context = appContext ?: return
        refreshNetwork()
        val state = mutableState.value
        when {
            !state.diagnosticMode -> {
                update { it.copy(diagnosticRunning = false, diagnosticStatus = "失败：请先开启诊断模式") }
                return
            }
            !state.forwardingEnabled -> {
                update { it.copy(diagnosticRunning = false, diagnosticStatus = "失败：发送到电脑已暂停") }
                return
            }
            state.targetIp.isBlank() -> {
                update { it.copy(diagnosticRunning = false, diagnosticStatus = "失败：尚未设置电脑 IP") }
                return
            }
            state.localIp == "--" -> {
                update { it.copy(diagnosticRunning = false, diagnosticStatus = "失败：手机没有可用的局域网 IPv4") }
                return
            }
        }
        update {
            it.copy(
                diagnosticRunning = true,
                diagnosticStatus = "正在测试 ${state.targetIp}:${state.targetPort}…",
                lastError = "--",
            )
        }
        val now = System.currentTimeMillis()
        val json = JSONObject()
            .put("version", 1)
            .put("type", "phone_diagnostic")
            .put("sessionId", "phone-diagnostic")
            .put("sequence", now)
            .put("sampleEpochMillis", now)
            .put("phoneReceivedEpochMillis", now)
            .put("bpm", 72)
            .put("rawBpm", 72.0)
            .put("accuracy", "TEST")
            .put("watchBatteryPercent", -1)
            .put("watchScreenInteractive", true)
            .put("phoneLocalIp", findLocalIpv4())
            .put("phoneNetworkType", state.networkType)
            .put("phoneVpnActive", state.vpnActive)
            .put("diagnosticMode", true)
            .put("source", state.heartRateSource.wireName)
        forward(
            context,
            null,
            json,
            isHeartRate = false,
            watchAckRequested = false,
            diagnostic = true,
        )
    }

    fun isDiagnosticMode(): Boolean = mutableState.value.diagnosticMode

    fun setDiagnosticMode(enabled: Boolean) {
        val context = appContext ?: return
        update {
            it.copy(
                diagnosticMode = enabled,
                diagnosticRunning = if (enabled) it.diagnosticRunning else false,
                diagnosticStatus = if (enabled) "诊断模式已开启，等待链路数据" else "诊断模式未开启",
                watchRawBpm = if (enabled) it.watchRawBpm else null,
                watchAccuracy = if (enabled) it.watchAccuracy else null,
                watchBatteryPercent = if (enabled) it.watchBatteryPercent else null,
                watchScreenInteractive = if (enabled) it.watchScreenInteractive else null,
                watchRelayMode = if (enabled) it.watchRelayMode else null,
            )
        }
        val nodeId = mutableState.value.watchNodeId
            .takeIf { mutableState.value.heartRateSource == HeartRateSource.GALAXY_WATCH }
            ?.takeUnless { it == "--" }
        if (nodeId != null) syncDiagnosticModeToWatch(context, nodeId, enabled)
    }

    fun sendTestPacket() = runDiagnostics()

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

    private fun forward(
        context: Context,
        watchNodeId: String?,
        json: JSONObject,
        isHeartRate: Boolean,
        watchAckRequested: Boolean,
        diagnostic: Boolean = false,
    ) {
        val target = mutableState.value
        if (!target.forwardingEnabled) {
            update { it.copy(lastError = "已暂停发送到电脑") }
            return
        }
        if (target.targetIp.isBlank()) {
            update { it.copy(lastError = "请先填写电脑 IP") }
            if (watchNodeId != null && watchAckRequested) {
                sendWatchAck(context, watchNodeId, json.optLong("sequence"), false, "电脑 IP 未设置")
            }
            return
        }
        var shouldStartWorker = false
        synchronized(pendingForwardLock) {
            // Real-time heart rate must never build an unbounded retry queue while the PC is offline.
            // Keep the packet currently in flight and replace any waiting packet with the newest sample.
            val request = PendingForward(
                watchNodeId,
                JSONObject(json.toString()),
                target.targetIp,
                target.targetPort,
                isHeartRate,
                watchAckRequested,
                diagnostic,
            )
            // A user-triggered diagnostic must not be overwritten by the next
            // real-time heart-rate sample while the single worker is busy.
            pendingForwards.offer(request, diagnostic)
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
                pendingForwards.poll()
            }
            if (request == null) {
                val reallyFinished = synchronized(pendingForwardLock) {
                    if (pendingForwards.isEmpty()) {
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
        val context = appContext ?: return
        val sequence = request.json.optLong("sequence")
        var pcAck = false
        var error = ""
        try {
            if (!mutableState.value.forwardingEnabled) {
                Log.i(TAG, "Forward skipped after pause sequence=$sequence")
                return
            }
            if (mutableState.value.diagnosticMode || request.diagnostic) {
                Log.i(TAG, "Forwarding sequence=$sequence to ${request.targetIp}:${request.targetPort}")
            }
            DatagramSocket().use { socket ->
                socket.soTimeout = 1_000
                val bytes = request.json.toString().toByteArray(Charsets.UTF_8)
                val address = InetAddress.getByName(request.targetIp)
                // Connect the UDP socket so an unrelated host cannot satisfy this
                // request with a spoofed sequence-matching acknowledgement.
                socket.connect(address, request.targetPort)
                socket.send(DatagramPacket(bytes, bytes.size, address, request.targetPort))
                update { it.copy(forwardedCount = it.forwardedCount + 1) }
                val ackBuffer = ByteArray(1_024)
                val ackPacket = DatagramPacket(ackBuffer, ackBuffer.size)
                socket.receive(ackPacket)
                val ack = JSONObject(String(ackPacket.data, 0, ackPacket.length, Charsets.UTF_8))
                pcAck = ack.optString("type") == "pc_ack" &&
                    ack.optLong("sequence", Long.MIN_VALUE) == sequence
                if (!pcAck) error = "电脑回执内容不匹配"
                if (ack.has("diagnosticMode")) {
                    val requestedMode = ack.optBoolean("diagnosticMode", false)
                    if (requestedMode != mutableState.value.diagnosticMode) {
                        setDiagnosticMode(requestedMode)
                    }
                    val nodeId = if (mutableState.value.heartRateSource == HeartRateSource.GALAXY_WATCH) {
                        request.watchNodeId
                            ?: mutableState.value.watchNodeId.takeUnless { it == "--" }
                    } else {
                        null
                    }
                    if (nodeId != null) syncDiagnosticModeToWatch(context, nodeId, requestedMode)
                }
                if (mutableState.value.diagnosticMode || request.diagnostic) {
                    Log.i(TAG, "PC acknowledgement sequence=$sequence matched=$pcAck")
                }
            }
        } catch (failure: Throwable) {
            val detail = failure.message ?: failure.javaClass.simpleName
            error = if (detail.contains("EPERM", ignoreCase = true) || detail.contains("Operation not permitted", ignoreCase = true)) {
                if (isVpnActive(context)) {
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
                diagnosticRunning = if (request.diagnostic) false else it.diagnosticRunning,
                diagnosticStatus = if (!request.diagnostic) {
                    it.diagnosticStatus
                } else if (pcAck) {
                    "通过：电脑 ${request.targetIp}:${request.targetPort} 已回执"
                } else {
                    "失败：${error.ifBlank { "电脑没有返回有效回执" }}"
                },
            )
        }
        if (request.watchNodeId != null && request.watchAckRequested) {
            sendWatchAck(context, request.watchNodeId, sequence, pcAck, error)
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
            .put("diagnosticMode", mutableState.value.diagnosticMode)
            .toString()
            .toByteArray(Charsets.UTF_8)
        Wearable.getMessageClient(context).sendMessage(nodeId, RelayProtocol.ACK_PATH, payload)
            .addOnSuccessListener {
                if (mutableState.value.diagnosticMode) {
                    Log.i(TAG, "Phone acknowledgement queued to watch node=$nodeId sequence=$sequence pcAck=$pcAck")
                }
            }
            .addOnFailureListener { failure ->
                Log.e(TAG, "Phone acknowledgement failed node=$nodeId sequence=$sequence", failure)
                update { it.copy(lastError = "回传手表失败：${failure.message ?: failure.javaClass.simpleName}") }
            }
    }

    private fun syncDiagnosticModeToWatch(context: Context, nodeId: String, enabled: Boolean) {
        synchronized(pendingForwardLock) {
            if (lastWatchDiagnosticNodeId == nodeId && lastWatchDiagnosticMode == enabled) return
            lastWatchDiagnosticNodeId = nodeId
            lastWatchDiagnosticMode = enabled
        }
        val payload = JSONObject()
            .put("version", 1)
            .put("type", "diagnostic_mode")
            .put("diagnosticMode", enabled)
            .put("phoneEpochMillis", System.currentTimeMillis())
            .toString()
            .toByteArray(Charsets.UTF_8)
        Wearable.getMessageClient(context).sendMessage(nodeId, RelayProtocol.CONTROL_PATH, payload)
            .addOnFailureListener { failure ->
                synchronized(pendingForwardLock) {
                    if (lastWatchDiagnosticNodeId == nodeId && lastWatchDiagnosticMode == enabled) {
                        lastWatchDiagnosticNodeId = null
                        lastWatchDiagnosticMode = null
                    }
                }
                Log.e(TAG, "Failed to sync diagnostic mode to watch", failure)
                update { it.copy(lastError = "同步手表诊断模式失败：${failure.message}") }
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
        val watchNodeId: String?,
        val json: JSONObject,
        val targetIp: String,
        val targetPort: Int,
        val isHeartRate: Boolean,
        val watchAckRequested: Boolean,
        val diagnostic: Boolean,
    )

    private const val TAG = "HR_RELAY"
}
