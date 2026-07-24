package best.nagikokoro.watch6heartrateprobe.mobile

import org.junit.Assert.assertTrue
import org.junit.Test

class RelayDiagnosticFieldsTest {
    @Test
    fun normalModeRemovesEveryCrossDeviceDiagnosticField() {
        assertTrue(
            RelayDiagnosticFields.names.containsAll(
                setOf(
                    "diagnosticMode",
                    "sessionId",
                    "rawBpm",
                    "accuracy",
                    "watchBatteryPercent",
                    "watchScreenInteractive",
                    "watchRelayMode",
                    "phoneLocalIp",
                    "phoneNetworkType",
                    "phoneVpnActive",
                ),
            ),
        )
    }
}
