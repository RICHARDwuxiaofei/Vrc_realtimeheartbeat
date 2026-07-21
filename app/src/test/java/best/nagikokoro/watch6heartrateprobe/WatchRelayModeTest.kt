package best.nagikokoro.watch6heartrateprobe

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchRelayModeTest {
    @Test
    fun invalidStoredValueFallsBackToPowerSaver() {
        assertEquals(
            WatchRelayMode.POWER_SAVER_5_SECONDS,
            WatchRelayMode.fromStoredValue("UNKNOWN"),
        )
    }

    @Test
    fun intervalMapsToSupportedModes() {
        assertEquals(WatchRelayMode.REALTIME_1_SECOND, WatchRelayMode.fromIntervalSeconds(1))
        assertEquals(WatchRelayMode.POWER_SAVER_5_SECONDS, WatchRelayMode.fromIntervalSeconds(5))
    }
}
