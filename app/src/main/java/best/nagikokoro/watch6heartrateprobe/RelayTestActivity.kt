package best.nagikokoro.watch6heartrateprobe

import android.app.Activity
import android.os.Bundle

/** Debug helper for an ADB-triggered transport test that never reads the heart-rate sensor. */
class RelayTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) {
            val logger = DiagnosticLogger.get(this)
            logger.info(
                "PHONE_RELAY_TEST_REQUESTED",
                "ADB diagnostic transport test requested; no sensor data is read",
                mapOf("source" to "RelayTestActivity"),
            )
            WearHeartRateRelay(this, logger).sendDiagnosticTest()
        }
        finish()
    }
}
