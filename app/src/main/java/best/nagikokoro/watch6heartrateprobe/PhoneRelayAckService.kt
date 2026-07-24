package best.nagikokoro.watch6heartrateprobe

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject

class PhoneRelayAckService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == RelayProtocol.CONTROL_PATH) {
            runCatching {
                JSONObject(event.data.toString(Charsets.UTF_8))
            }.onSuccess { control ->
                val enabled = control.optBoolean("diagnosticMode", false)
                val changed = RelayDiagnosticModeStore.setEnabled(enabled)
                RelayStatusStore.update { it.copy(diagnosticMode = enabled) }
                if (changed) {
                    DiagnosticLogger.get(this).info(
                        "RELAY_DIAGNOSTIC_MODE_CHANGED",
                        "Phone changed relay diagnostic payload mode",
                        mapOf("enabled" to enabled),
                    )
                }
            }.onFailure { failure ->
                RelayStatusStore.update { it.copy(lastError = "诊断模式指令解析失败: ${failure.message}") }
            }
            return
        }
        if (event.path != RelayProtocol.ACK_PATH) return
        runCatching {
            JSONObject(event.data.toString(Charsets.UTF_8))
        }.onSuccess { ack ->
            if (ack.has("diagnosticMode")) {
                RelayDiagnosticModeStore.setEnabled(ack.optBoolean("diagnosticMode", false))
            }
            RelayStatusStore.update {
                it.copy(
                    lastAckMillis = System.currentTimeMillis(),
                    lastPcAck = ack.optBoolean("pcAck", false),
                    lastError = ack.optString("error", "--").ifBlank { "--" },
                    diagnosticMode = if (ack.has("diagnosticMode")) {
                        ack.optBoolean("diagnosticMode", false)
                    } else {
                        it.diagnosticMode
                    },
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
