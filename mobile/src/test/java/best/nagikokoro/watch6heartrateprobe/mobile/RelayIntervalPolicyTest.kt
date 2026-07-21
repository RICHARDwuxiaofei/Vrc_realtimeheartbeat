package best.nagikokoro.watch6heartrateprobe.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayIntervalPolicyTest {
    @Test
    fun nominalFiveSecondArrivalJustBeforeBoundaryIsDue() {
        assertTrue(RelayIntervalPolicy.isDue(10_000L, 14_999L, 5_000L))
    }

    @Test
    fun configuredFiveSecondIntervalDoesNotForwardTooEarly() {
        assertTrue(RelayIntervalPolicy.isDue(10_000L, 14_500L, 5_000L))
        assertFalse(RelayIntervalPolicy.isDue(10_000L, 14_499L, 5_000L))
    }

    @Test
    fun thirtySecondIntervalToleranceIsCapped() {
        assertTrue(RelayIntervalPolicy.isDue(10_000L, 39_500L, 30_000L))
        assertFalse(RelayIntervalPolicy.isDue(10_000L, 39_499L, 30_000L))
    }
}
