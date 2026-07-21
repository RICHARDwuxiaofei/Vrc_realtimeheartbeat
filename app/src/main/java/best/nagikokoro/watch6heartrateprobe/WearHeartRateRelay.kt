package best.nagikokoro.watch6heartrateprobe

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class WearHeartRateRelay(
    context: Context,
    private val logger: DiagnosticLogger,
) {
    private val capabilityClient = Wearable.getCapabilityClient(context.applicationContext)
    private val messageClient = Wearable.getMessageClient(context.applicationContext)
    private val resolving = AtomicBoolean(false)
    private var cachedNode: Node? = null
    private var cacheExpiresMillis = 0L

    fun sendSample(
        sequence: Long,
        sessionId: String,
        sampleEpochMillis: Long,
        receivedEpochMillis: Long,
        bpm: Int,
        rawBpm: Double,
        accuracy: String,
        batteryPercent: Int,
        screenInteractive: Boolean,
        relayMode: WatchRelayMode = WatchRelayMode.POWER_SAVER_5_SECONDS,
        messageType: String = "heart_rate",
    ) {
        val node = cachedNode
        if (node != null && node.isNearby && System.currentTimeMillis() < cacheExpiresMillis) {
            sendToNode(
                node,
                samplePayload(
                    sequence,
                    sessionId,
                    sampleEpochMillis,
                    receivedEpochMillis,
                    bpm,
                    rawBpm,
                    accuracy,
                    batteryPercent,
                    screenInteractive,
                    relayMode,
                    messageType,
                ),
            )
            return
        }
        if (!resolving.compareAndSet(false, true)) return
        capabilityClient
            .getCapability(RelayProtocol.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener { capability ->
                val nearby = capability.nodes.firstOrNull { it.isNearby }
                cachedNode = nearby
                cacheExpiresMillis = System.currentTimeMillis() + NODE_CACHE_MILLIS
                RelayStatusStore.update {
                    it.copy(
                        phoneNearby = nearby != null,
                        phoneName = nearby?.displayName ?: "--",
                        lastError = if (nearby == null) "未找到附近的手机伴侣应用" else "--",
                    )
                }
                if (nearby == null) {
                    logger.warn(
                        "PHONE_RELAY_NOT_NEARBY",
                        "No nearby phone companion capability is reachable",
                        mapOf("capability" to RelayProtocol.PHONE_CAPABILITY),
                    )
                } else {
                    logger.info(
                        "PHONE_RELAY_DISCOVERED",
                        "Nearby phone companion discovered over Data Layer",
                        mapOf("nodeId" to nearby.id, "nodeName" to nearby.displayName, "isNearby" to nearby.isNearby),
                    )
                    sendToNode(
                        nearby,
                        samplePayload(
                            sequence,
                            sessionId,
                            sampleEpochMillis,
                            receivedEpochMillis,
                            bpm,
                            rawBpm,
                            accuracy,
                            batteryPercent,
                            screenInteractive,
                            relayMode,
                            messageType,
                        ),
                    )
                }
            }
            .addOnFailureListener { failure ->
                RelayStatusStore.update {
                    it.copy(phoneNearby = false, failedCount = it.failedCount + 1, lastError = failure.message ?: failure.javaClass.name)
                }
                logger.error(
                    "PHONE_RELAY_DISCOVERY_FAILED",
                    "Failed to query the phone relay capability",
                    failure,
                    mapOf("capability" to RelayProtocol.PHONE_CAPABILITY),
                )
            }
            .addOnCompleteListener { resolving.set(false) }
    }

    private fun sendToNode(node: Node, payload: ByteArray) {
        messageClient.sendMessage(node.id, RelayProtocol.SAMPLE_PATH, payload)
            .addOnSuccessListener {
                RelayStatusStore.update { status ->
                    status.copy(
                        phoneNearby = true,
                        phoneName = node.displayName,
                        sentCount = status.sentCount + 1,
                        lastSentMillis = System.currentTimeMillis(),
                        lastError = "--",
                    )
                }
                logger.info(
                    "PHONE_RELAY_MESSAGE_QUEUED",
                    "Data Layer message queued to the nearby phone",
                    mapOf("nodeId" to node.id, "nodeName" to node.displayName, "payloadBytes" to payload.size),
                )
            }
            .addOnFailureListener { failure ->
                cachedNode = null
                RelayStatusStore.update { status ->
                    status.copy(
                        phoneNearby = false,
                        failedCount = status.failedCount + 1,
                        lastError = failure.message ?: failure.javaClass.name,
                    )
                }
                logger.error(
                    "PHONE_RELAY_SEND_FAILED",
                    "Heart-rate sample could not be queued to the phone",
                    failure,
                    mapOf("nodeId" to node.id, "payloadBytes" to payload.size),
                )
            }
    }

    private fun samplePayload(
        sequence: Long,
        sessionId: String,
        sampleEpochMillis: Long,
        receivedEpochMillis: Long,
        bpm: Int,
        rawBpm: Double,
        accuracy: String,
        batteryPercent: Int,
        screenInteractive: Boolean,
        relayMode: WatchRelayMode,
        messageType: String,
    ): ByteArray = JSONObject()
        .put("version", RelayProtocol.PROTOCOL_VERSION)
        .put("type", messageType)
        .put("sessionId", sessionId)
        .put("sequence", sequence)
        .put("sampleEpochMillis", sampleEpochMillis)
        .put("watchReceivedEpochMillis", receivedEpochMillis)
        .put("bpm", bpm)
        .put("rawBpm", rawBpm)
        .put("accuracy", accuracy)
        .put("watchBatteryPercent", batteryPercent)
        .put("watchScreenInteractive", screenInteractive)
        .put("watchRelayMode", relayMode.name)
        .put("watchRelayIntervalSeconds", relayMode.intervalSeconds)
        .toString()
        .toByteArray(Charsets.UTF_8)

    fun sendDiagnosticTest() {
        val now = System.currentTimeMillis()
        sendSample(
            sequence = now,
            sessionId = "watch-relay-test",
            sampleEpochMillis = now,
            receivedEpochMillis = now,
            bpm = 72,
            rawBpm = 72.0,
            accuracy = "RELAY_TEST_NOT_SENSOR_DATA",
            batteryPercent = -1,
            screenInteractive = true,
            messageType = "relay_test",
        )
    }

    companion object {
        private const val NODE_CACHE_MILLIS = 30_000L
    }
}
