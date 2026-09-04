package best.nagikokoro.watch6heartrateprobe

internal object ConfirmedHeartRateContract {
    fun exactBpm(value: Any?): Int? {
        val bpm = when (value) {
            is Int -> value
            is Long -> value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
            else -> null
        }
        return bpm?.takeIf { it in 1..300 }
    }
}
