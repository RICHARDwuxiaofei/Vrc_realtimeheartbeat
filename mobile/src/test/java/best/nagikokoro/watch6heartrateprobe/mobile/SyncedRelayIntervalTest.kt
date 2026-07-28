package best.nagikokoro.watch6heartrateprobe.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncedRelayIntervalTest {
    @Test
    fun legacyIntervalsMapToTheThreeSharedWatchModes() {
        assertEquals(1, SyncedRelayInterval.normalize(1))
        assertEquals(5, SyncedRelayInterval.normalize(2))
        assertEquals(5, SyncedRelayInterval.normalize(5))
        assertEquals(10, SyncedRelayInterval.normalize(10))
        assertEquals(10, SyncedRelayInterval.normalize(30))
    }

    @Test
    fun newestSelectionWinsAndOldInstallsUseTheVisibleWatchChoice() {
        assertEquals(
            RelayIntervalDecision.APPLY_REMOTE,
            SyncedRelayInterval.decide(5, 100, 1, 101),
        )
        assertEquals(
            RelayIntervalDecision.SEND_LOCAL,
            SyncedRelayInterval.decide(1, 101, 5, 100),
        )
        assertEquals(
            RelayIntervalDecision.APPLY_REMOTE,
            SyncedRelayInterval.decide(5, 0, 10, 0),
        )
        assertEquals(
            RelayIntervalDecision.NONE,
            SyncedRelayInterval.decide(5, 100, 5, 100),
        )
    }
}
