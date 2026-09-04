package best.nagikokoro.watch6heartrateprobe.mobile

internal object HeartRatePayloadContract {
    fun exactBpm(value: Any?): Int? {
        val bpm = when (value) {
            is Int -> value
            is Long -> value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
            else -> null
        }
        return bpm?.takeIf { it in 1..300 }
    }

    fun acknowledgementMatches(
        type: String,
        sequence: Long,
        expectedSequence: Long,
        bpm: Int?,
        expectedBpm: Int?,
    ): Boolean =
        type == "pc_ack" &&
            sequence == expectedSequence &&
            bpm != null &&
            bpm == expectedBpm
}
