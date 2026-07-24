package best.nagikokoro.watch6heartrateprobe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventRateLimiterTest {
    @Test
    fun repeatedEventsDoNotPostponeTheNextAllowedWindow() {
        val limiter = EventRateLimiter(60_000L)

        assertTrue(limiter.allow("sensor-warning", 100_000L))
        assertFalse(limiter.allow("sensor-warning", 110_000L))
        assertFalse(limiter.allow("sensor-warning", 159_999L))
        assertTrue(limiter.allow("sensor-warning", 160_000L))
    }

    @Test
    fun separateEventCodesHaveIndependentWindows() {
        val limiter = EventRateLimiter(60_000L)

        assertTrue(limiter.allow("sensor-warning", 100_000L))
        assertTrue(limiter.allow("network-warning", 100_001L))
    }

    @Test
    fun clockRollbackStartsANewWindow() {
        val limiter = EventRateLimiter(60_000L)

        assertTrue(limiter.allow("warning", 100_000L))
        assertTrue(limiter.allow("warning", 90_000L))
    }
}
