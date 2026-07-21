package best.nagikokoro.watch6heartrateprobe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayIntervalPolicyTest {
    @Test
    fun firstSampleIsAlwaysDue() {
        assertTrue(RelayIntervalPolicy.isDue(0L, 1_000L, 5_000L))
    }

    @Test
    fun nominalFiveSecondBatchJustBeforeBoundaryIsDue() {
        assertTrue(RelayIntervalPolicy.isDue(10_000L, 14_999L, 5_000L))
    }

    @Test
    fun fiveSecondIntervalAllowsAtMostFiveHundredMillisecondsEarly() {
        assertTrue(RelayIntervalPolicy.isDue(10_000L, 14_500L, 5_000L))
        assertFalse(RelayIntervalPolicy.isDue(10_000L, 14_499L, 5_000L))
    }

    @Test
    fun shorterIntervalsUseTenPercentTolerance() {
        assertTrue(RelayIntervalPolicy.isDue(10_000L, 10_900L, 1_000L))
        assertFalse(RelayIntervalPolicy.isDue(10_000L, 10_899L, 1_000L))
    }

    @Test
    fun outOfOrderTimestampIsNotDue() {
        assertFalse(RelayIntervalPolicy.isDue(10_000L, 9_999L, 5_000L))
    }
}
