package best.nagikokoro.watch6heartrateprobe.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionTimeoutPolicyTest {
    @Test
    fun disabledOrInactiveRelayNeverTimesOut() {
        assertFalse(connectionTimedOut(false, true, 0L, null, 300_000L))
        assertFalse(connectionTimedOut(true, false, 0L, null, 300_000L))
    }

    @Test
    fun fiveMinutesWithoutAConnectionTimesOut() {
        assertFalse(connectionTimedOut(true, true, 1_000L, null, 300_999L))
        assertTrue(connectionTimedOut(true, true, 1_000L, null, 301_000L))
    }

    @Test
    fun successfulConnectionRestartsTheFiveMinuteWindow() {
        assertFalse(connectionTimedOut(true, true, 0L, 250_000L, 549_999L))
        assertTrue(connectionTimedOut(true, true, 0L, 250_000L, 550_000L))
    }
}
