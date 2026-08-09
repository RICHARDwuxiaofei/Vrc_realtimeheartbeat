package best.nagikokoro.watch6heartrateprobe

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject

object WatchRelayFrequencySync {
    fun sendCurrent(context: Context) {
        val appContext = context.applicationContext
        val settings = WatchRelaySettings.get(appContext)
        val payload = JSONObject()
            .put("version", RelayProtocol.PROTOCOL_VERSION)
            .put("type", "relay_interval")
            .put("relayIntervalSeconds", settings.mode.value.intervalSeconds)
            .put("relayIntervalUpdatedEpochMillis", settings.updatedEpochMillis)
            .put("autoStopOnTimeoutEnabled", settings.autoStopOnTimeoutEnabled.value)
            .put("watchEpochMillis", System.currentTimeMillis())
            .toString()
            .toByteArray(Charsets.UTF_8)

        Wearable.getCapabilityClient(appContext)
            .getCapability(RelayProtocol.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener { capability ->
                val node = capability.nodes.firstOrNull { it.isNearby } ?: return@addOnSuccessListener
                Wearable.getMessageClient(appContext)
                    .sendMessage(node.id, RelayProtocol.CONTROL_PATH, payload)
                    .addOnFailureListener { failure ->
                        RelayStatusStore.update {
                            it.copy(lastError = "同步手机发送频率失败: ${failure.message}")
                        }
                    }
            }
            .addOnFailureListener { failure ->
                RelayStatusStore.update {
                    it.copy(lastError = "查找手机同步节点失败: ${failure.message}")
                }
            }
    }
}
