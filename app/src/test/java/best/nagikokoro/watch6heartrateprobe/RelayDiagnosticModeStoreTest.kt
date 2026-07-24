package best.nagikokoro.watch6heartrateprobe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayDiagnosticModeStoreTest {
    @Test
    fun reportsOnlyRealModeTransitions() {
        RelayDiagnosticModeStore.setEnabled(false)

        assertTrue(RelayDiagnosticModeStore.setEnabled(true))
        assertTrue(RelayDiagnosticModeStore.enabled)
        assertFalse(RelayDiagnosticModeStore.setEnabled(true))
        assertTrue(RelayDiagnosticModeStore.setEnabled(false))
        assertFalse(RelayDiagnosticModeStore.enabled)
    }
}
