package best.nagikokoro.watch6heartrateprobe

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class DiagnosticSimulationState(
    val active: Boolean = false,
    val bpm: Int? = null,
    val sentCount: Long = 0,
)

class DiagnosticHeartRateSimulator(
    context: Context,
    private val logger: DiagnosticLogger,
) {
    private val appContext = context.applicationContext
    private val relay = WearHeartRateRelay(appContext, logger)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sequence = AtomicLong(System.currentTimeMillis())
    private val mutableState = MutableStateFlow(DiagnosticSimulationState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    @Synchronized
    fun start(): Boolean {
        if (BuildConfig.PRODUCTION_EDITION || job?.isActive == true) return false
        logger.warn(
            "SIMULATED_HEART_RATE_STARTED",
            "Diagnostic simulator started; values are not sensor data",
            mapOf(
                "minimumBpm" to SimulatedHeartRateGenerator.MIN_BPM,
                "maximumBpm" to SimulatedHeartRateGenerator.MAX_BPM,
            ),
        )
        job = scope.launch {
            var bpm = SimulatedHeartRateGenerator.initial()
            while (isActive) {
                val now = System.currentTimeMillis()
                val nextSequence = sequence.incrementAndGet()
                relay.sendSample(
                    sequence = nextSequence,
                    sessionId = "watch-diagnostic-simulator",
                    sampleEpochMillis = now,
                    receivedEpochMillis = now,
                    bpm = bpm,
                    rawBpm = bpm.toDouble(),
                    accuracy = "SIMULATED_NOT_SENSOR_DATA",
                    batteryPercent = batteryPercent(),
                    screenInteractive = screenInteractive(),
                    relayMode = WatchRelayMode.REALTIME_1_SECOND,
                    watchAckRequested = nextSequence % ACK_INTERVAL_SAMPLES == 0L,
                    simulated = true,
                )
                mutableState.value = mutableState.value.copy(
                    active = true,
                    bpm = bpm,
                    sentCount = mutableState.value.sentCount + 1,
                )
                delay(SAMPLE_INTERVAL_MILLIS)
                bpm = SimulatedHeartRateGenerator.next(bpm)
            }
        }
        return true
    }

    @Synchronized
    fun stop() {
        val running = job?.isActive == true
        job?.cancel()
        job = null
        mutableState.value = mutableState.value.copy(active = false, bpm = null)
        if (running) {
            logger.warn(
                "SIMULATED_HEART_RATE_STOPPED",
                "Diagnostic simulator stopped",
                mapOf("sentCount" to mutableState.value.sentCount),
            )
        }
    }

    fun close() {
        stop()
        scope.cancel()
    }

    private fun batteryPercent(): Int =
        appContext.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }
            ?: -1

    private fun screenInteractive(): Boolean =
        appContext.getSystemService(PowerManager::class.java)?.isInteractive ?: false

    companion object {
        private const val SAMPLE_INTERVAL_MILLIS = 1_000L
        private const val ACK_INTERVAL_SAMPLES = 5L
    }
}
