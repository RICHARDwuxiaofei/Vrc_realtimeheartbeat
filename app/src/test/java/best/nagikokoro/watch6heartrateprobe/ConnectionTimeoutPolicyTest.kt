package best.nagikokoro.watch6heartrateprobe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionTimeoutPolicyTest {
    @Test
    fun fiveMinutesWithoutEndToEndConnectionTimesOut() {
        assertFalse(connectionTimedOut(true, true, 10_000L, null, 309_999L))
        assertTrue(connectionTimedOut(true, true, 10_000L, null, 310_000L))
    }

    @Test
    fun recentPcAcknowledgementKeepsTheWatchRunning() {
        assertFalse(connectionTimedOut(true, true, 0L, 200_000L, 499_999L))
        assertTrue(connectionTimedOut(true, true, 0L, 200_000L, 500_000L))
    }
}
