package best.nagikokoro.watch6heartrateprobe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartRateRelayPolicyTest {
    @Test
    fun changedValueUsesSelectedCadence() {
        assertTrue(
            HeartRateRelayPolicy.isDue(
                previousTimestampMillis = 10_000L,
                currentTimestampMillis = 14_500L,
                previousBpm = 70,
                currentBpm = 71,
                mode = WatchRelayMode.POWER_SAVER_5_SECONDS,
            ),
        )
    }

    @Test
    fun unchangedValueUsesTwoIntervalKeepalive() {
        assertFalse(
            HeartRateRelayPolicy.isDue(
                previousTimestampMillis = 10_000L,
                currentTimestampMillis = 15_000L,
                previousBpm = 70,
                currentBpm = 70,
                mode = WatchRelayMode.POWER_SAVER_5_SECONDS,
            ),
        )
        assertTrue(
            HeartRateRelayPolicy.isDue(
                previousTimestampMillis = 10_000L,
                currentTimestampMillis = 19_500L,
                previousBpm = 70,
                currentBpm = 70,
                mode = WatchRelayMode.POWER_SAVER_5_SECONDS,
            ),
        )
    }

    @Test
    fun realtimeModeNeverSlowsUnchangedValues() {
        assertTrue(
            HeartRateRelayPolicy.isDue(
                previousTimestampMillis = 10_000L,
                currentTimestampMillis = 10_900L,
                previousBpm = 70,
                currentBpm = 70,
                mode = WatchRelayMode.REALTIME_1_SECOND,
            ),
        )
    }
}
