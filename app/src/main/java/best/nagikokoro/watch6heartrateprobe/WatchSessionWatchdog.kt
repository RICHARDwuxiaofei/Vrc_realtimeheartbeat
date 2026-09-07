package best.nagikokoro.watch6heartrateprobe

/**
 * Safety policy for a running heart-rate session.
 *
 * A short gap remains a stale signal for diagnostics, but the session is
 * stopped only after five minutes without a valid sample. A charging watch
 * is stopped immediately so a health foreground session is not kept alive on
 * its charging cradle.
 */
object WatchSessionWatchdog {
    const val NO_HEART_RATE_TIMEOUT_MILLIS = 5 * 60_000L
    const val CHARGING_STOP_REASON = "CHARGING_DETECTED"
    const val NO_HEART_RATE_STOP_REASON = "NO_HEART_RATE_5_MINUTES"

    fun stopReason(
        charging: Boolean?,
        lastSampleMillis: Long?,
        sessionStartMillis: Long?,
        nowMillis: Long,
    ): String? {
        if (charging == true) return CHARGING_STOP_REASON
        val referenceMillis = lastSampleMillis ?: sessionStartMillis ?: return null
        val ageMillis = (nowMillis - referenceMillis).coerceAtLeast(0L)
        return NO_HEART_RATE_STOP_REASON.takeIf { ageMillis >= NO_HEART_RATE_TIMEOUT_MILLIS }
    }
}
