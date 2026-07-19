package best.nagikokoro.watch6heartrateprobe

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Process
import androidx.core.content.ContextCompat
import androidx.health.services.client.ExerciseClient
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.clearUpdateCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DataTypeAvailability
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseInfo
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseTrackedStatus.Companion.OTHER_APP_IN_PROGRESS
import androidx.health.services.client.data.ExerciseTrackedStatus.Companion.OWNED_EXERCISE_IN_PROGRESS
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.health.services.client.endExercise
import androidx.health.services.client.getCapabilities
import androidx.health.services.client.getCurrentExerciseInfo
import androidx.health.services.client.startExercise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class ExerciseForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val startInFlight = AtomicBoolean(false)
    private val endInFlight = AtomicBoolean(false)
    private lateinit var logger: DiagnosticLogger
    private lateinit var store: ExerciseSessionStore
    private lateinit var backgroundTestRecorder: BackgroundTestRecorder
    private lateinit var exerciseClient: ExerciseClient
    private lateinit var heartRateRelay: WearHeartRateRelay
    private var staleTicker: Job? = null
    private var staleLatched = false

    private val callback = object : ExerciseUpdateCallback {
        override fun onRegistered() {
            store.update { it.copy(callbackRegistered = true) }
            logger.info(
                "EXERCISE_CALLBACK_REGISTERED",
                "Exercise update callback registered",
                serviceFields("SERVICE_CREATE"),
            )
        }

        override fun onRegistrationFailed(throwable: Throwable) {
            backgroundTestRecorder.recordError("EXERCISE_CALLBACK_REGISTER_FAILED", throwable)
            store.update {
                it.copy(
                    callbackRegistered = false,
                    sessionState = ExerciseSessionState.ERROR,
                    lastError = throwable.message ?: throwable.javaClass.name,
                )
            }
            logger.error(
                "EXERCISE_CALLBACK_REGISTER_FAILED",
                "Exercise update callback registration failed",
                throwable,
                serviceFields("REGISTRATION_FAILED"),
            )
        }

        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            try {
                val callbackReceiveMillis = System.currentTimeMillis()
                val bootEpoch = callbackReceiveMillis - android.os.SystemClock.elapsedRealtime()
                val recordedPoints = update.latestMetrics.getData(DataType.HEART_RATE_BPM).map { point ->
                    RecordedHeartRatePoint(
                        sampleEpochMillis = bootEpoch + point.timeDurationFromBoot.toMillis(),
                        bpm = point.value,
                        accuracy = point.accuracy.toString(),
                    )
                }
                backgroundTestRecorder.recordCallbackBatch(callbackReceiveMillis, recordedPoints)
                val stateInfo = update.exerciseStateInfo
                val stateName = stateInfo.state.name
                logger.info(
                    "EXERCISE_UPDATE",
                    "Exercise state update received",
                    serviceFields("UPDATE") + mapOf(
                        "healthServicesState" to stateName,
                        "endReasonId" to stateInfo.endReason,
                        "dataTypes" to update.latestMetrics.dataTypes.joinToString { it.name },
                    ),
                )
                store.update {
                    it.copy(
                        sessionState = when {
                            stateInfo.state.isEnded || stateInfo.state.isEnding -> ExerciseSessionState.ENDING
                            stateInfo.state.isPaused -> ExerciseSessionState.PAUSED
                            else -> ExerciseSessionState.ACTIVE
                        },
                        endReason = if (stateInfo.endReason == 0) it.endReason else "HEALTH_SERVICES_${stateInfo.endReason}",
                    )
                }
                processHeartRatePoints(update)
                if (stateInfo.state.isEnded && !endInFlight.get()) {
                    finishAfterExternalEnd("HEALTH_SERVICES_${stateInfo.endReason}_$stateName")
                }
            } catch (failure: Throwable) {
                backgroundTestRecorder.recordError("EXERCISE_UPDATE_PROCESSING_FAILED", failure)
                logger.error(
                    "EXERCISE_UPDATE_PROCESSING_FAILED",
                    "Exercise update processing failed",
                    failure,
                    serviceFields("UPDATE_EXCEPTION"),
                )
            }
        }

        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {
            logger.debug(
                "EXERCISE_LAP_IGNORED",
                "Unexpected lap summary received during heart-rate-only test",
                serviceFields("LAP"),
            )
        }

        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {
            val availabilityName = if (availability is DataTypeAvailability) {
                availability.name
            } else {
                availability.toString()
            }
            store.update { it.copy(availability = availabilityName) }
            backgroundTestRecorder.recordAvailability(availabilityName)
            logger.info(
                "EXERCISE_AVAILABILITY_CHANGED",
                "Exercise data availability changed",
                serviceFields("AVAILABILITY") + mapOf(
                    "dataType" to dataType.name,
                    "availability" to availabilityName,
                    "availabilityId" to availability.id,
                ),
            )
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val interactive = context.isScreenInteractive()
            store.update { it.copy(screenInteractive = interactive) }
            backgroundTestRecorder.recordScreen(interactive)
            logger.info(
                if (intent.action == Intent.ACTION_SCREEN_OFF) "SCREEN_OFF" else "SCREEN_ON",
                "Screen interactive state changed while exercise service is running",
                serviceFields("SCREEN_BROADCAST") + mapOf(
                    "broadcastAction" to intent.action,
                    "screenInteractive" to interactive,
                ),
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        logger = DiagnosticLogger.get(this)
        store = ExerciseSessionStore.get(this)
        backgroundTestRecorder = BackgroundTestRecorder.get(this)
        exerciseClient = HealthServices.getClient(this).exerciseClient
        heartRateRelay = WearHeartRateRelay(this, logger)
        createNotificationChannel()
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        store.update {
            it.copy(
                serviceRunning = true,
                screenInteractive = isScreenInteractive(),
                currentBatteryPercent = batteryPercent(),
            )
        }
        logger.info(
            "FOREGROUND_SERVICE_CREATED",
            "Exercise foreground service created",
            serviceFields("ON_CREATE"),
        )
        backgroundTestRecorder.onServiceCreated()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(store.state.value.bpm),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(store.state.value.bpm))
        }
        logger.info(
            "FOREGROUND_SERVICE_COMMAND",
            "Exercise foreground service received a command",
            serviceFields("ON_START_COMMAND") + mapOf(
                "action" to (intent?.action ?: "PROCESS_RESTART"),
                "startId" to startId,
                "flags" to flags,
            ),
        )
        when (intent?.action) {
            ACTION_STOP -> requestEnd(intent.getStringExtra(EXTRA_REASON) ?: "USER")
            ACTION_START -> startOrRestore(explicitStart = true)
            else -> startOrRestore(explicitStart = false)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        staleTicker?.cancel()
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { exerciseClient.clearUpdateCallbackAsync(callback) }
        store.update {
            it.copy(
                serviceRunning = false,
                callbackRegistered = false,
                sessionState = if (endInFlight.get() && it.sessionState == ExerciseSessionState.ENDING) {
                    ExerciseSessionState.ENDED
                } else {
                    it.sessionState
                },
                sessionEndMillis = if (endInFlight.get()) it.sessionEndMillis ?: System.currentTimeMillis() else it.sessionEndMillis,
            )
        }
        logger.info(
            "FOREGROUND_SERVICE_DESTROYED",
            "Exercise foreground service destroyed; exercise is not ended unless Stop was requested",
            serviceFields("ON_DESTROY") + mapOf("endInFlight" to endInFlight.get()),
        )
        serviceScope.cancel()
        super.onDestroy()
    }

    @SuppressLint("WrongConstant", "RestrictedApi")
    private fun startOrRestore(explicitStart: Boolean) {
        if (!startInFlight.compareAndSet(false, true)) {
            logger.warn(
                "EXERCISE_DUPLICATE_START_BLOCKED",
                "Duplicate exercise start or restore was blocked",
                serviceFields("DUPLICATE_START"),
            )
            return
        }
        serviceScope.launch {
            try {
                store.update {
                    it.copy(sessionState = if (explicitStart) ExerciseSessionState.STARTING else ExerciseSessionState.RESTORING)
                }
                exerciseClient.setUpdateCallback(DIRECT_EXECUTOR, callback)
                logger.info(
                    "EXERCISE_CALLBACK_REGISTER_START",
                    "Registering ExerciseUpdateCallback",
                    serviceFields(if (explicitStart) "USER_START" else "SERVICE_RESTORE"),
                )

                val current = exerciseClient.getCurrentExerciseInfo()
                logger.info(
                    "EXERCISE_CURRENT_INFO",
                    "Current exercise ownership queried",
                    serviceFields("CURRENT_INFO") + currentInfoFields(current),
                )
                when (current.exerciseTrackedStatus) {
                    OWNED_EXERCISE_IN_PROGRESS -> restoreOwnedExercise(current)
                    OTHER_APP_IN_PROGRESS -> failAndStop(
                        "EXERCISE_OTHER_APP_ACTIVE",
                        IllegalStateException("Another app owns the active exercise"),
                    )
                    else -> {
                        val shouldStart = explicitStart || store.state.value.sessionState in setOf(
                            ExerciseSessionState.STARTING,
                            ExerciseSessionState.RESTORING,
                            ExerciseSessionState.ACTIVE,
                            ExerciseSessionState.PAUSED,
                        )
                        if (shouldStart && explicitStart) {
                            startNewExercise()
                        } else {
                            finishWithoutExercise("NO_OWNED_EXERCISE_TO_RESTORE")
                        }
                    }
                }
            } catch (failure: Throwable) {
                failAndStop("EXERCISE_START_OR_RESTORE_FAILED", failure)
            } finally {
                startInFlight.set(false)
            }
        }
    }

    private suspend fun startNewExercise() {
        val permissionManager = PermissionManager(this)
        check(permissionManager.isGranted()) { "READ_HEART_RATE is not granted" }
        check(permissionManager.isBackgroundHealthGranted()) {
            "Background health permission is not granted: ${permissionManager.backgroundHealthPermission}"
        }

        val capabilities = exerciseClient.getCapabilities()
        val supportedTypes = capabilities.supportedExerciseTypes
        logger.info(
            "EXERCISE_CAPABILITIES",
            "ExerciseClient capabilities queried",
            serviceFields("CAPABILITY") + mapOf(
                "supportedExerciseTypes" to supportedTypes.sortedBy { it.name }.joinToString { it.name },
                "supportedTypeCount" to supportedTypes.size,
            ),
        )
        val preferred = listOf(
            ExerciseType.WORKOUT,
            ExerciseType.WALKING,
            ExerciseType.EXERCISE_CLASS,
            ExerciseType.STRENGTH_TRAINING,
        )
        val chosen = (preferred + supportedTypes.sortedBy { it.name }).distinct().firstOrNull { type ->
            type in supportedTypes &&
                DataType.HEART_RATE_BPM in capabilities.getExerciseTypeCapabilities(type).supportedDataTypes
        } ?: throw IllegalStateException("No supported ExerciseType exposes HEART_RATE_BPM")
        val chosenCapabilities = capabilities.getExerciseTypeCapabilities(chosen)
        logger.info(
            "EXERCISE_TYPE_SELECTED",
            "Exercise type selected for continuous heart-rate comparison",
            serviceFields("TYPE_SELECTED") + mapOf(
                "exerciseType" to chosen.name,
                "heartRateSupported" to (DataType.HEART_RATE_BPM in chosenCapabilities.supportedDataTypes),
                "requestedDataTypes" to DataType.HEART_RATE_BPM.name,
                "gpsEnabled" to false,
                "autoPauseEnabled" to false,
            ),
        )

        val startTime = System.currentTimeMillis()
        val battery = batteryPercent()
        store.update {
            ExerciseSessionSnapshot(
                serviceRunning = true,
                callbackRegistered = it.callbackRegistered,
                sessionState = ExerciseSessionState.STARTING,
                exerciseType = chosen.name,
                startBatteryPercent = battery,
                currentBatteryPercent = battery,
                sessionStartMillis = startTime,
                activityPhase = it.activityPhase,
                screenInteractive = isScreenInteractive(),
            )
        }
        val config = ExerciseConfig.builder(chosen)
            .setDataTypes(setOf(DataType.HEART_RATE_BPM))
            .setIsAutoPauseAndResumeEnabled(false)
            .setIsGpsEnabled(false)
            .build()
        exerciseClient.startExercise(config)
        store.update { it.copy(sessionState = ExerciseSessionState.ACTIVE) }
        logger.info(
            "EXERCISE_SESSION_STARTED",
            "ExerciseClient heart-rate-only session started successfully",
            serviceFields("START_SUCCESS") + mapOf("exerciseType" to chosen.name),
        )
        startStaleTicker()
    }

    private fun restoreOwnedExercise(current: ExerciseInfo) {
        store.update {
            it.copy(
                serviceRunning = true,
                sessionState = ExerciseSessionState.ACTIVE,
                exerciseType = current.exerciseType.name,
                sessionEndMillis = null,
                currentBatteryPercent = batteryPercent(),
                screenInteractive = isScreenInteractive(),
            )
        }
        logger.info(
            "EXERCISE_SESSION_RESTORED",
            "Existing app-owned ExerciseClient session restored",
            serviceFields("RESTORE_SUCCESS") + currentInfoFields(current),
        )
        startStaleTicker()
    }

    private fun processHeartRatePoints(update: ExerciseUpdate) {
        val points = update.latestMetrics.getData(DataType.HEART_RATE_BPM)
            .sortedBy { it.timeDurationFromBoot }
        if (points.isEmpty()) return
        val bootEpoch = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()
        points.forEach { point ->
            val raw = point.value
            val sampleMillis = bootEpoch + point.timeDurationFromBoot.toMillis()
            val previous = store.state.value.lastSampleMillis
            if (previous != null && sampleMillis <= previous) {
                logger.debug(
                    "EXERCISE_HR_DUPLICATE_SKIPPED",
                    "Repeated or out-of-order exercise heart-rate point skipped",
                    serviceFields("SAMPLE_DUPLICATE") + mapOf(
                        "sampleEpochMillis" to sampleMillis,
                        "previousSampleEpochMillis" to previous,
                        "rawDouble" to raw,
                    ),
                )
                return@forEach
            }
            if (!raw.isFinite() || raw !in MIN_VALID_BPM..MAX_VALID_BPM) {
                logger.warn(
                    "EXERCISE_HR_INVALID",
                    "Invalid ExerciseClient heart-rate point ignored",
                    serviceFields("SAMPLE_INVALID") + mapOf(
                        "sampleEpochMillis" to sampleMillis,
                        "rawDouble" to raw,
                    ),
                )
                return@forEach
            }
            val gap = previous?.let { (sampleMillis - it).coerceAtLeast(0) } ?: 0L
            staleLatched = false
            val battery = batteryPercent()
            store.update {
                it.copy(
                    sessionState = ExerciseSessionState.ACTIVE,
                    bpm = raw.roundToInt(),
                    rawBpm = raw,
                    sampleCount = it.sampleCount + 1,
                    lastSampleMillis = sampleMillis,
                    maxGapMillis = maxOf(it.maxGapMillis, gap),
                    currentBatteryPercent = battery,
                    screenInteractive = isScreenInteractive(),
                )
            }
            val snapshot = store.state.value
            logger.info(
                "EXERCISE_HR_SAMPLE",
                "Real ExerciseClient heart-rate sample accepted",
                serviceFields("SAMPLE") + mapOf(
                    "rawDouble" to raw,
                    "displayedBpm" to raw.roundToInt(),
                    "sampleEpochMillis" to sampleMillis,
                    "receivedEpochMillis" to System.currentTimeMillis(),
                    "gapMillis" to gap,
                    "sampleCount" to snapshot.sampleCount,
                    "maxGapMillis" to snapshot.maxGapMillis,
                    "accuracy" to point.accuracy,
                ),
            )
            heartRateRelay.sendSample(
                sequence = snapshot.sampleCount,
                sessionId = snapshot.sessionStartMillis?.toString() ?: "unknown",
                sampleEpochMillis = sampleMillis,
                receivedEpochMillis = System.currentTimeMillis(),
                bpm = raw.roundToInt(),
                rawBpm = raw,
                accuracy = point.accuracy.toString(),
                batteryPercent = battery ?: -1,
                screenInteractive = snapshot.screenInteractive,
            )
        }
    }

    private fun startStaleTicker() {
        staleTicker?.cancel()
        staleTicker = serviceScope.launch {
            while (isActive && store.state.value.sessionState in setOf(
                    ExerciseSessionState.ACTIVE,
                    ExerciseSessionState.PAUSED,
                )
            ) {
                val snapshot = store.state.value
                val ageMillis = snapshot.lastSampleMillis?.let {
                    (System.currentTimeMillis() - it).coerceAtLeast(0)
                }
                store.update {
                    it.copy(
                        currentBatteryPercent = batteryPercent(),
                        screenInteractive = isScreenInteractive(),
                    )
                }
                if (ageMillis != null && ageMillis >= STALE_AFTER_MILLIS && !staleLatched) {
                    staleLatched = true
                    store.update { it.copy(staleCount = it.staleCount + 1) }
                    backgroundTestRecorder.recordStale(ageMillis)
                    logger.warn(
                        "EXERCISE_DATA_STALE",
                        "ExerciseClient heart-rate data exceeded stale threshold",
                        serviceFields("STALE") + mapOf(
                            "dataAgeMillis" to ageMillis,
                            "thresholdMillis" to STALE_AFTER_MILLIS,
                            "staleCount" to store.state.value.staleCount,
                        ),
                    )
                }
                if (backgroundTestRecorder.tick()) {
                    backgroundTestRecorder.finish("TARGET_DURATION_REACHED")
                    requestEnd("TEST_DURATION_REACHED")
                    break
                }
                delay(1_000L)
            }
        }
    }

    @SuppressLint("WrongConstant", "RestrictedApi")
    private fun requestEnd(reason: String) {
        if (!endInFlight.compareAndSet(false, true)) {
            logger.warn(
                "EXERCISE_DUPLICATE_END_BLOCKED",
                "Duplicate exercise end was blocked",
                serviceFields("DUPLICATE_END") + mapOf("reason" to reason),
            )
            return
        }
        if (backgroundTestRecorder.state.value.isActive) {
            backgroundTestRecorder.finish(
                reason = if (reason == "TEST_DURATION_REACHED") "TARGET_DURATION_REACHED" else reason,
            )
        }
        serviceScope.launch {
            store.update { it.copy(sessionState = ExerciseSessionState.ENDING, endReason = reason) }
            logger.info(
                "EXERCISE_END_REQUESTED",
                "Exercise session end requested",
                serviceFields("END_REQUEST") + mapOf("reason" to reason),
            )
            try {
                val current = exerciseClient.getCurrentExerciseInfo()
                if (current.exerciseTrackedStatus == OWNED_EXERCISE_IN_PROGRESS) {
                    exerciseClient.endExercise()
                }
                clearCallback("USER_END_$reason")
                store.update {
                    it.copy(
                        serviceRunning = false,
                        callbackRegistered = false,
                        sessionState = ExerciseSessionState.ENDED,
                        endReason = reason,
                        sessionEndMillis = System.currentTimeMillis(),
                        currentBatteryPercent = batteryPercent(),
                    )
                }
                logger.info(
                    "EXERCISE_SESSION_ENDED",
                    "Exercise session ended normally",
                    serviceFields("END_SUCCESS") + mapOf("reason" to reason),
                )
            } catch (failure: Throwable) {
                logger.error(
                    "EXERCISE_END_FAILED",
                    "Exercise session end failed",
                    failure,
                    serviceFields("END_FAILURE") + mapOf("reason" to reason),
                )
                store.update {
                    it.copy(
                        sessionState = ExerciseSessionState.ERROR,
                        lastError = failure.message ?: failure.javaClass.name,
                    )
                }
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun finishAfterExternalEnd(reason: String) {
        if (!endInFlight.compareAndSet(false, true)) return
        backgroundTestRecorder.finish(reason, abnormal = true)
        serviceScope.launch {
            clearCallback(reason)
            store.update {
                it.copy(
                    serviceRunning = false,
                    callbackRegistered = false,
                    sessionState = ExerciseSessionState.ENDED,
                    endReason = reason,
                    sessionEndMillis = System.currentTimeMillis(),
                    currentBatteryPercent = batteryPercent(),
                )
            }
            logger.warn(
                "EXERCISE_SESSION_ENDED_EXTERNALLY",
                "Health Services ended the exercise without the app Stop command",
                serviceFields("EXTERNAL_END") + mapOf("reason" to reason),
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun clearCallback(reason: String) {
        logger.info(
            "EXERCISE_CALLBACK_UNREGISTER_START",
            "Clearing ExerciseUpdateCallback",
            serviceFields("CALLBACK_CLEAR") + mapOf("reason" to reason),
        )
        exerciseClient.clearUpdateCallback(callback)
        store.update { it.copy(callbackRegistered = false) }
        logger.info(
            "EXERCISE_CALLBACK_UNREGISTERED",
            "ExerciseUpdateCallback cleared",
            serviceFields("CALLBACK_CLEARED") + mapOf("reason" to reason),
        )
    }

    private fun failAndStop(eventCode: String, failure: Throwable) {
        backgroundTestRecorder.recordError(eventCode, failure)
        backgroundTestRecorder.finish(eventCode, abnormal = true)
        logger.error(
            eventCode,
            "Exercise foreground service could not maintain the requested session",
            failure,
            serviceFields("FAILURE"),
        )
        store.update {
            it.copy(
                serviceRunning = false,
                callbackRegistered = false,
                sessionState = ExerciseSessionState.ERROR,
                lastError = failure.message ?: failure.javaClass.name,
                endReason = eventCode,
                sessionEndMillis = System.currentTimeMillis(),
            )
        }
        runCatching { exerciseClient.clearUpdateCallbackAsync(callback) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun finishWithoutExercise(reason: String) {
        backgroundTestRecorder.finish(reason, abnormal = true)
        store.update {
            it.copy(
                serviceRunning = false,
                callbackRegistered = false,
                sessionState = ExerciseSessionState.ENDED,
                endReason = reason,
                sessionEndMillis = System.currentTimeMillis(),
            )
        }
        logger.warn(
            "EXERCISE_RESTORE_NOT_FOUND",
            "No app-owned exercise existed during service restore",
            serviceFields("RESTORE_EMPTY") + mapOf("reason" to reason),
        )
        runCatching { exerciseClient.clearUpdateCallbackAsync(callback) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun serviceFields(source: String): Map<String, Any?> {
        val snapshot = store.state.value
        return mapOf(
            "source" to source,
            "pid" to Process.myPid(),
            "serviceRunning" to snapshot.serviceRunning,
            "exerciseState" to snapshot.sessionState.name,
            "exerciseCallbackRegistered" to snapshot.callbackRegistered,
            "screenInteractive" to isScreenInteractive(),
            "activityPhase" to snapshot.activityPhase,
        )
    }

    private fun currentInfoFields(info: ExerciseInfo): Map<String, Any?> = mapOf(
        "trackedStatus" to info.exerciseTrackedStatus,
        "exerciseType" to info.exerciseType.name,
    )

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL,
                "Heart-rate screen-off test",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows that the ExerciseClient heart-rate test is still active"
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(bpm: Int?): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("ExerciseClient screen-off test")
            .setContentText(bpm?.let { "$it BPM · session active" } ?: "Waiting for heart rate")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val ACTION_START = "best.nagikokoro.watch6heartrateprobe.action.START_EXERCISE"
        private const val ACTION_STOP = "best.nagikokoro.watch6heartrateprobe.action.STOP_EXERCISE"
        private const val EXTRA_REASON = "reason"
        private const val NOTIFICATION_CHANNEL = "exercise_hr_probe"
        private const val NOTIFICATION_ID = 6001
        private const val MIN_VALID_BPM = 20.0
        private const val MAX_VALID_BPM = 300.0
        private const val STALE_AFTER_MILLIS = 10_000L
        private val DIRECT_EXECUTOR = Executor { it.run() }

        fun requestStart(context: Context) {
            val intent = Intent(context, ExerciseForegroundService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun requestRestore(context: Context) {
            val intent = Intent(context, ExerciseForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun requestStop(context: Context, reason: String) {
            val intent = Intent(context, ExerciseForegroundService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_REASON, reason)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
