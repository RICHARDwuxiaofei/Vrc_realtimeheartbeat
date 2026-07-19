package best.nagikokoro.watch6heartrateprobe

import android.app.Application
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject

class WatchProbeApplication : Application(), MessageClient.OnMessageReceivedListener {
    override fun onCreate() {
        super.onCreate()
        Wearable.getMessageClient(this).addListener(this)
            .addOnSuccessListener {
                DiagnosticLogger.get(this).info(
                    "PHONE_RELAY_ACK_LISTENER_REGISTERED",
                    "Runtime phone acknowledgement listener registered",
                )
            }
            .addOnFailureListener { failure ->
                DiagnosticLogger.get(this).error(
                    "PHONE_RELAY_ACK_LISTENER_FAILED",
                    "Runtime phone acknowledgement listener registration failed",
                    failure,
                )
            }
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != RelayProtocol.ACK_PATH) return
        runCatching { JSONObject(event.data.toString(Charsets.UTF_8)) }
            .onSuccess { ack ->
                val pcAck = ack.optBoolean("pcAck", false)
                val error = ack.optString("error", "").ifBlank { "--" }
                RelayStatusStore.update {
                    it.copy(
                        lastAckMillis = System.currentTimeMillis(),
                        lastPcAck = pcAck,
                        lastError = error,
                    )
                }
                DiagnosticLogger.get(this).info(
                    "PHONE_RELAY_ACK_RECEIVED",
                    "Phone relay acknowledgement received by runtime listener",
                    mapOf(
                        "sequence" to ack.optLong("sequence", -1L),
                        "pcAck" to pcAck,
                        "error" to error,
                        "sourceNodeId" to event.sourceNodeId,
                    ),
                )
            }
            .onFailure { failure ->
                DiagnosticLogger.get(this).error(
                    "PHONE_RELAY_ACK_PARSE_FAILED",
                    "Phone relay acknowledgement could not be parsed",
                    failure,
                )
            }
    }
}
