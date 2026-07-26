package best.nagikokoro.watch6heartrateprobe

/**
 * Sends changed values at the selected cadence, while allowing unchanged BPM
 * values to use a two-interval keepalive. This preserves responsiveness and
 * cuts redundant Data Layer radio traffic when the reading is stable.
 */
internal object HeartRateRelayPolicy {
    fun isDue(
        previousTimestampMillis: Long,
        currentTimestampMillis: Long,
        previousBpm: Int?,
        currentBpm: Int,
        mode: WatchRelayMode,
    ): Boolean {
        val valueChanged = previousBpm == null || previousBpm != currentBpm
        val intervalMultiplier = if (mode == WatchRelayMode.REALTIME_1_SECOND || valueChanged) 1L else 2L
        return RelayIntervalPolicy.isDue(
            previousTimestampMillis = previousTimestampMillis,
            currentTimestampMillis = currentTimestampMillis,
            intervalMillis = mode.intervalSeconds * 1_000L * intervalMultiplier,
        )
    }
}
