package best.nagikokoro.watch6heartrateprobe

import android.os.SystemClock
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
                applyAutoStopSetting(control)
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
            applyAutoStopSetting(ack)
            applyRelayInterval(ack)
            val claimedPcAck = ack.optBoolean("pcAck", false)
            val confirmedBpm = ConfirmedHeartRateContract.exactBpm(ack.opt("confirmedBpm"))
            val pcAck = claimedPcAck && confirmedBpm != null
            val error = if (claimedPcAck && confirmedBpm == null) {
                "电脑回执缺少有效的 BPM 对照值"
            } else {
                ack.optString("error", "--").ifBlank { "--" }
            }
            RelayStatusStore.update {
                it.copy(
                    lastAckMillis = System.currentTimeMillis(),
                    lastPcAck = pcAck,
                    lastPcConfirmedBpm = if (pcAck) confirmedBpm else it.lastPcConfirmedBpm,
                    lastSuccessfulPcAckElapsedMillis = if (pcAck) {
                        SystemClock.elapsedRealtime()
                    } else {
                        it.lastSuccessfulPcAckElapsedMillis
                    },
                    lastError = error,
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
                    "pcAck" to pcAck,
                    "confirmedBpm" to confirmedBpm,
                    "error" to error,
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

    private fun applyAutoStopSetting(message: JSONObject) {
        if (!message.has("autoStopOnTimeoutEnabled")) return
        val enabled = message.optBoolean("autoStopOnTimeoutEnabled", true)
        val changed = WatchRelaySettings.get(this).applyRemoteAutoStopOnTimeout(enabled)
        if (changed) {
            DiagnosticLogger.get(this).info(
                "AUTO_STOP_TIMEOUT_SYNCED_FROM_PHONE",
                "Phone changed the watch connection-timeout setting",
                mapOf("enabled" to enabled),
            )
        }
    }
}
