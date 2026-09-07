package best.nagikokoro.watch6heartrateprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchSessionWatchdogTest {
    @Test
    fun chargingStopsBeforeTheNoDataTimeout() {
        assertEquals(
            WatchSessionWatchdog.CHARGING_STOP_REASON,
            WatchSessionWatchdog.stopReason(
                charging = true,
                lastSampleMillis = 0L,
                sessionStartMillis = 0L,
                nowMillis = 1L,
            ),
        )
    }

    @Test
    fun fiveMinutesWithoutASampleStopsTheSession() {
        val start = 10_000L
        assertEquals(
            WatchSessionWatchdog.NO_HEART_RATE_STOP_REASON,
            WatchSessionWatchdog.stopReason(
                charging = false,
                lastSampleMillis = null,
                sessionStartMillis = start,
                nowMillis = start + WatchSessionWatchdog.NO_HEART_RATE_TIMEOUT_MILLIS,
            ),
        )
    }

    @Test
    fun aFreshSampleResetsTheNoDataClock() {
        val start = 10_000L
        assertNull(
            WatchSessionWatchdog.stopReason(
                charging = false,
                lastSampleMillis = start + WatchSessionWatchdog.NO_HEART_RATE_TIMEOUT_MILLIS - 1L,
                sessionStartMillis = start,
                nowMillis = start + WatchSessionWatchdog.NO_HEART_RATE_TIMEOUT_MILLIS,
            ),
        )
    }
}
