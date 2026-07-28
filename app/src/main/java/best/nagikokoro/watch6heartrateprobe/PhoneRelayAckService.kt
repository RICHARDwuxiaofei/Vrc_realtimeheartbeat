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
                if (control.has("diagnosticMode")) {
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
                }
                applyRelayInterval(control)
            }.onFailure { failure ->
                RelayStatusStore.update { it.copy(lastError = "手机控制指令解析失败: ${failure.message}") }
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
            applyRelayInterval(ack)
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

    private fun applyRelayInterval(message: JSONObject) {
        val seconds = message.optInt("relayIntervalSeconds", 0)
        if (seconds !in setOf(1, 5, 10)) return
        val updatedEpochMillis = message.optLong("relayIntervalUpdatedEpochMillis", 0L)
        val settings = WatchRelaySettings.get(this)
        val changed = settings.applyRemoteMode(
            WatchRelayMode.fromIntervalSeconds(seconds),
            updatedEpochMillis,
        )
        if (!changed) return
        if (ExerciseSessionStore.get(this).state.value.serviceRunning) {
            ExerciseForegroundService.requestRelayModeUpdate(this)
        }
        DiagnosticLogger.get(this).info(
            "RELAY_INTERVAL_SYNCED_FROM_PHONE",
            "Phone changed the watch and phone relay interval",
            mapOf(
                "relayIntervalSeconds" to seconds,
                "updatedEpochMillis" to updatedEpochMillis,
            ),
        )
    }
}
