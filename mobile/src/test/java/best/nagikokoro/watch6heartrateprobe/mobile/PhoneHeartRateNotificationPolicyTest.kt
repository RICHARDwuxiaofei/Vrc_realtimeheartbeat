package best.nagikokoro.watch6heartrateprobe.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneHeartRateNotificationPolicyTest {
    @Test
    fun firstSampleIsPublishedImmediately() {
        assertTrue(PhoneHeartRateNotificationPolicy.shouldUpdate(Long.MIN_VALUE, 1_000L))
    }

    @Test
    fun samplesWithinFiveSecondsAreCoalesced() {
        assertFalse(PhoneHeartRateNotificationPolicy.shouldUpdate(10_000L, 14_999L))
        assertTrue(PhoneHeartRateNotificationPolicy.shouldUpdate(10_000L, 15_000L))
    }

    @Test
    fun elapsedRealtimeResetDoesNotSuppressAnUpdate() {
        assertTrue(PhoneHeartRateNotificationPolicy.shouldUpdate(10_000L, 9_000L))
    }
}
