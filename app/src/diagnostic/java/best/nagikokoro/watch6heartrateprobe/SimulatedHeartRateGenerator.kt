package best.nagikokoro.watch6heartrateprobe

import kotlin.random.Random

object SimulatedHeartRateGenerator {
    const val MIN_BPM = 60
    const val MAX_BPM = 80

    fun initial(random: Random = Random.Default): Int =
        random.nextInt(68, 73)

    fun next(currentBpm: Int, random: Random = Random.Default): Int =
        nextWithStep(currentBpm, random.nextInt(-2, 3))

    internal fun nextWithStep(currentBpm: Int, step: Int): Int {
        val current = currentBpm.coerceIn(MIN_BPM, MAX_BPM)
        val candidate = current + step.coerceIn(-2, 2)
        return when {
            candidate < MIN_BPM -> MIN_BPM + (MIN_BPM - candidate)
            candidate > MAX_BPM -> MAX_BPM - (candidate - MAX_BPM)
            else -> candidate
        }
    }
}
