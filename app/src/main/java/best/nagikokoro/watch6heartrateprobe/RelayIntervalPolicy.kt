package best.nagikokoro.watch6heartrateprobe

/**
 * Prevents a nominal five-second producer from aliasing into ten-second sends
 * when sensor timestamps arrive a few milliseconds before the exact boundary.
 */
internal object RelayIntervalPolicy {
    private const val MAX_EARLY_TOLERANCE_MILLIS = 500L

    fun isDue(
        previousTimestampMillis: Long,
        currentTimestampMillis: Long,
        intervalMillis: Long,
    ): Boolean {
        require(intervalMillis > 0L) { "intervalMillis must be positive" }
        if (previousTimestampMillis == 0L) return true

        val elapsedMillis = currentTimestampMillis - previousTimestampMillis
        if (elapsedMillis < 0L) return false

        val toleranceMillis = minOf(intervalMillis / 10L, MAX_EARLY_TOLERANCE_MILLIS)
        return elapsedMillis >= intervalMillis - toleranceMillis
    }
}
