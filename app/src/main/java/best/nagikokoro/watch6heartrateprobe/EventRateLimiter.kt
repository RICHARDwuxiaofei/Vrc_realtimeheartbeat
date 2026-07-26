package best.nagikokoro.watch6heartrateprobe

/** Thread-safe fixed-window limiter used to keep repeated diagnostics bounded. */
internal class EventRateLimiter(private val intervalMillis: Long) {
    private val lastAcceptedMillis = mutableMapOf<String, Long>()

    init {
        require(intervalMillis > 0L) { "intervalMillis must be positive" }
    }

    @Synchronized
    fun allow(key: String, nowMillis: Long): Boolean {
        val previous = lastAcceptedMillis[key]
        if (previous != null && nowMillis >= previous && nowMillis - previous < intervalMillis) {
            return false
        }
        lastAcceptedMillis[key] = nowMillis
        return true
    }
}
