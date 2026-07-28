package best.nagikokoro.watch6heartrateprobe

import org.json.JSONObject

object RelaySamplePayload {
    fun build(
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
        relayIntervalUpdatedEpochMillis: Long,
        watchAckRequested: Boolean,
        messageType: String,
        diagnosticMode: Boolean,
        simulated: Boolean = false,
    ): ByteArray {
        val payload = JSONObject()
            .put("version", RelayProtocol.PROTOCOL_VERSION)
            .put("type", messageType)
            .put("sequence", sequence)
            .put("sampleEpochMillis", sampleEpochMillis)
            .put("bpm", bpm)
            .put("watchRelayIntervalSeconds", relayMode.intervalSeconds)
            .put("watchRelayIntervalUpdatedEpochMillis", relayIntervalUpdatedEpochMillis)
            .put("watchAckRequested", watchAckRequested)
        if (simulated) {
            payload
                .put("simulated", true)
                .put("source", RelayProtocol.SIMULATED_SOURCE)
        }
        if (diagnosticMode) {
            payload
                .put("diagnosticMode", true)
                .put("sessionId", sessionId)
                .put("watchReceivedEpochMillis", receivedEpochMillis)
                .put("rawBpm", rawBpm)
                .put("accuracy", accuracy)
                .put("watchBatteryPercent", batteryPercent)
                .put("watchScreenInteractive", screenInteractive)
                .put("watchRelayMode", relayMode.name)
        }
        return payload.toString().toByteArray(Charsets.UTF_8)
    }
}
