package best.nagikokoro.watch6heartrateprobe.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class HeartRateSourceTest {
    @Test
    fun unknownPreferenceFallsBackToGalaxyWatch() {
        assertEquals(HeartRateSource.GALAXY_WATCH, HeartRateSource.fromPreference("future-source"))
    }

    @Test
    fun xiaomiPreferenceRoundTrips() {
        assertEquals(
            HeartRateSource.XIAOMI_BAND_BLE,
            HeartRateSource.fromPreference(HeartRateSource.XIAOMI_BAND_BLE.wireName),
        )
    }
}
