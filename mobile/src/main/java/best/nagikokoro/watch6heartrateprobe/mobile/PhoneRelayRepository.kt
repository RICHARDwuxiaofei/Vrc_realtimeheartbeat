package best.nagikokoro.watch6heartrateprobe.mobile

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
)

object PhoneRelayRepository {
    private const val PREFS = "phone_relay_settings"
    private val executor = Executors.newSingleThreadExecutor()
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
        update { it.copy(localIp = findLocalIpv4(), networkType = networkType(context)) }
    }

    fun handleWatchSample(sourceNodeId: String, bytes: ByteArray) {
        val context = appContext ?: return
        val phoneReceiveMillis = System.currentTimeMillis()
        val json = try {
            JSONObject(bytes.toString(Charsets.UTF_8))
        } catch (failure: Throwable) {
            update { it.copy(lastError = "手表数据格式错误：${failure.message}") }
            return
        }
        Log.i(TAG, "Watch sample accepted type=${json.optString("type")} sequence=${json.optLong("sequence")} source=$sourceNodeId")
        val sequence = json.optLong("sequence", -1L)
        if (sequence >= 0 && mutableState.value.lastSequence == sequence) {
            Log.i(TAG, "Duplicate Data Layer sequence=$sequence ignored")
            return
        }
        val bpm = json.optInt("bpm", -1).takeIf { it > 0 }
        val sampleMillis = json.optLong("sampleEpochMillis", 0L).takeIf { it > 0 }
        json.put("phoneReceivedEpochMillis", phoneReceiveMillis)
        json.put("phoneLocalIp", findLocalIpv4())
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
        forward(context, sourceNodeId, json)
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
        forward(context, null, json)
    }

    private fun forward(context: Context, watchNodeId: String?, json: JSONObject) {
        val target = mutableState.value
        if (target.targetIp.isBlank()) {
            update { it.copy(lastError = "请先填写电脑 IP") }
            if (watchNodeId != null) sendWatchAck(context, watchNodeId, json.optLong("sequence"), false, "电脑 IP 未设置")
            return
        }
        update { it.copy(forwarding = true) }
        executor.execute {
            var pcAck = false
            var error = ""
            try {
                Log.i(TAG, "Forwarding sequence=${json.optLong("sequence")} to ${target.targetIp}:${target.targetPort}")
                DatagramSocket().use { socket ->
                    socket.soTimeout = 1_000
                    val bytes = json.toString().toByteArray(Charsets.UTF_8)
                    val address = InetAddress.getByName(target.targetIp)
                    socket.send(DatagramPacket(bytes, bytes.size, address, target.targetPort))
                    update { it.copy(forwardedCount = it.forwardedCount + 1) }
                    val ackBuffer = ByteArray(1_024)
                    val ackPacket = DatagramPacket(ackBuffer, ackBuffer.size)
                    socket.receive(ackPacket)
                    val ack = JSONObject(String(ackPacket.data, 0, ackPacket.length, Charsets.UTF_8))
                    pcAck = ack.optString("type") == "pc_ack" &&
                        ack.optLong("sequence", Long.MIN_VALUE) == json.optLong("sequence", Long.MAX_VALUE)
                    if (!pcAck) error = "电脑回执内容不匹配"
                    Log.i(TAG, "PC acknowledgement sequence=${json.optLong("sequence")} matched=$pcAck")
                }
            } catch (failure: Throwable) {
                error = "电脑未回执：${failure.message ?: failure.javaClass.simpleName}"
                Log.e(TAG, "UDP forwarding failed sequence=${json.optLong("sequence")}", failure)
            }
            val ackMillis = if (pcAck) System.currentTimeMillis() else null
            update {
                it.copy(
                    forwarding = false,
                    pcAckCount = it.pcAckCount + if (pcAck) 1 else 0,
                    lastPcAckMillis = ackMillis ?: it.lastPcAckMillis,
                    lastError = error.ifBlank { "--" },
                )
            }
            if (watchNodeId != null) {
                sendWatchAck(context, watchNodeId, json.optLong("sequence"), pcAck, error)
            }
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

    private const val TAG = "HR_RELAY"
}
