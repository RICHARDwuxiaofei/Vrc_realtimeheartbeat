package best.nagikokoro.watch6heartrateprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackgroundTestStatisticsTest {
    @Test
    fun medianUsesMiddleValueForOddSampleCount() {
        assertEquals(20.0, median(listOf(10, 20, 90))!!, 0.0)
    }

    @Test
    fun medianAveragesMiddleValuesForEvenSampleCount() {
        assertEquals(25.0, median(listOf(10, 20, 30, 90))!!, 0.0)
    }

    @Test
    fun medianReturnsNullForNoSamples() {
        assertNull(median(emptyList()))
    }

    @Test
    fun p95UsesNearestRank() {
        assertEquals(95L, percentile((1L..100L).toList(), 0.95))
    }

    @Test
    fun targetWindowRequiresBothEdgesToBeCovered() {
        assertEquals(true, isTargetWindowCovered(913, 3))
        assertEquals(false, isTargetWindowCovered(913, 72_003))
        assertEquals(false, isTargetWindowCovered(null, 3))
    }

    @Test
    fun drainTimeoutWaitsForPostTargetScreenOnAndCallbackGrace() {
        val targetEnd = 600_000L
        assertEquals(false, shouldTailDrainTimeout(1_000_000L, targetEnd, null))
        assertEquals(false, shouldTailDrainTimeout(1_000_000L, targetEnd, 590_000L))
        assertEquals(false, shouldTailDrainTimeout(1_000_000L, targetEnd, 990_000L))
        assertEquals(true, shouldTailDrainTimeout(1_000_000L, targetEnd, 980_000L))
    }

    @Test
    fun onlyRealtimeDeliveryExperimentRequestsWakeLock() {
        assertEquals(false, BackgroundTestType.SCREEN_OFF_10_MIN.requiresDeliveryWakeLock)
        assertEquals(true, BackgroundTestType.REALTIME_DELIVERY_10_MIN.requiresDeliveryWakeLock)
        assertEquals(false, BackgroundTestType.BATTERY_20_MIN.requiresDeliveryWakeLock)
        assertEquals(false, BackgroundTestType.FORMAL_60_MIN.requiresDeliveryWakeLock)
    }
}
