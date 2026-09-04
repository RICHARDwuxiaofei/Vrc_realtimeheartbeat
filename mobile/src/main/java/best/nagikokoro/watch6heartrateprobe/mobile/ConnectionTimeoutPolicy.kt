package best.nagikokoro.watch6heartrateprobe.mobile

internal const val CONNECTION_TIMEOUT_MILLIS = 5 * 60_000L

internal fun connectionTimedOut(
    enabled: Boolean,
    active: Boolean,
    monitoringStartedElapsedMillis: Long?,
    lastConnectedElapsedMillis: Long?,
    nowElapsedMillis: Long,
    timeoutMillis: Long = CONNECTION_TIMEOUT_MILLIS,
): Boolean {
    if (!enabled || !active) return false
    val baseline = lastConnectedElapsedMillis ?: monitoringStartedElapsedMillis ?: return false
    return nowElapsedMillis - baseline >= timeoutMillis
}
