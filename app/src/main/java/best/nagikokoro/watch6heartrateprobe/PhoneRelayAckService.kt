package best.nagikokoro.watch6heartrateprobe

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject

class PhoneRelayAckService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != RelayProtocol.ACK_PATH) return
        runCatching {
            JSONObject(event.data.toString(Charsets.UTF_8))
        }.onSuccess { ack ->
            RelayStatusStore.update {
                it.copy(
                    lastAckMillis = System.currentTimeMillis(),
                    lastPcAck = ack.optBoolean("pcAck", false),
                    lastError = ack.optString("error", "--").ifBlank { "--" },
                )
            }
            DiagnosticLogger.get(this).info(
                "PHONE_RELAY_ACK_RECEIVED",
                "Phone relay acknowledgement received",
                mapOf(
                    "sequence" to ack.optLong("sequence", -1L),
                    "pcAck" to ack.optBoolean("pcAck", false),
                    "error" to ack.optString("error", ""),
                ),
            )
        }.onFailure { failure ->
            RelayStatusStore.update { it.copy(lastError = "手机 ACK 解析失败: ${failure.message}") }
        }
    }
}
