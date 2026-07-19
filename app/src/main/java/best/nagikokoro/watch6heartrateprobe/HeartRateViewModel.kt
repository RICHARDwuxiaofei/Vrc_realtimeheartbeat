package best.nagikokoro.watch6heartrateprobe

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.health.services.client.data.DataTypeAvailability
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class HeartRateViewModel(application: Application) : AndroidViewModel(application),
    HeartRateMeasureManager.Listener {

    private val permissionManager = PermissionManager(application)
    private val logger = DiagnosticLogger.get(application)
    private val exerciseStore = ExerciseSessionStore.get(application)
    private val backgroundTestRecorder = BackgroundTestRecorder.get(application)
    private val _uiState = MutableStateFlow(
        ProbeUiState(
            selectedMode = if (exerciseStore.state.value.serviceRunning ||
                exerciseStore.state.value.sessionState in setOf(
                    ExerciseSessionState.RESTORING,
                    ExerciseSessionState.STARTING,
                    ExerciseSessionState.ACTIVE,
                    ExerciseSessionState.PAUSED,
                    ExerciseSessionState.ENDING,
                )
            ) {
                ProbeMode.EXERCISE
            } else {
                ProbeMode.MEASURE
            },
            requiredPermission = permissionManager.requiredPermission,
            backgroundHealthPermission = permissionManager.backgroundHealthPermission,
            backgroundHealthPermissionGranted = permissionManager.isBackgroundHealthGranted(),
            isWatchDevice = application.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH),
            screenInteractive = application.isScreenInteractive(),
            exercise = exerciseStore.state.value,
            backgroundTest = backgroundTestRecorder.state.value,
        ),
    )
    val uiState: StateFlow<ProbeUiState> = _uiState.asStateFlow()
    private val measureManager = HeartRateMeasureManager(application, logger, this)

    private var measurementStartElapsedRealtime = 0L
    private var staleWasLogged = false

    init {
        if (!exerciseStore.state.value.serviceRunning &&
            exerciseStore.state.value.sessionState == ExerciseSessionState.ENDING
        ) {
            exerciseStore.update {
                it.copy(
                    sessionState = ExerciseSessionState.ENDED,
                    callbackRegistered = false,
                    endReason = if (it.endReason == "--") "RECOVERED_STALE_ENDING_STATE" else it.endReason,
                    sessionEndMillis = it.sessionEndMillis ?: System.currentTimeMillis(),
                )
            }
        }
        logger.updateState(ProbeStatus.INITIALIZING)
        logger.info(
            "APP_START",
            "Watch6 Heart Rate Probe process started",
            runtimeDiagnosticFields(application, measureManager.isRegistered()) + mapOf(
                "versionName" to BuildConfig.VERSION_NAME,
                "versionCode" to BuildConfig.VERSION_CODE,
                "buildType" to BuildConfig.BUILD_TYPE,
            ),
        )
        logger.info(
            "DEVICE_ENVIRONMENT",
            "Device environment captured",
            runtimeDiagnosticFields(application, measureManager.isRegistered()) + mapOf(
                "manufacturer" to Build.MANUFACTURER,
                "model" to Build.MODEL,
                "apiLevel" to Build.VERSION.SDK_INT,
                "release" to Build.VERSION.RELEASE,
                "isWatch" to _uiState.value.isWatchDevice,
            ),
        )
        viewModelScope.launch {
            logger.visibleEntries.collect { entries ->
                _uiState.update { it.copy(visibleLogs = entries) }
            }
        }
        viewModelScope.launch {
            exerciseStore.state.collect { exercise ->
                _uiState.update {
                    it.copy(
                        selectedMode = if (exercise.serviceRunning ||
                            exercise.sessionState in setOf(
                                ExerciseSessionState.RESTORING,
                                ExerciseSessionState.STARTING,
                                ExerciseSessionState.ACTIVE,
                                ExerciseSessionState.PAUSED,
                                ExerciseSessionState.ENDING,
                            )
                        ) {
                            ProbeMode.EXERCISE
                        } else {
                            it.selectedMode
                        },
                        exercise = exercise,
                        activityPhase = exercise.activityPhase,
                        screenInteractive = exercise.screenInteractive,
                    )
                }
            }
        }
        viewModelScope.launch {
            backgroundTestRecorder.state.collect { test ->
                _uiState.update { it.copy(backgroundTest = test) }
            }
        }
        startUiTicker()
        initializeEnvironment()
    }

    fun isPermissionGranted(): Boolean = permissionManager.isGranted()

    fun isBackgroundHealthPermissionGranted(): Boolean = permissionManager.isBackgroundHealthGranted()

    fun requiredBackgroundHealthPermission(): String? = permissionManager.backgroundHealthPermission

    fun selectMode(mode: ProbeMode) {
        if (measureManager.isRegistered() || exerciseStore.state.value.serviceRunning) {
            logger.warn(
                "MODE_CHANGE_BLOCKED",
                "Mode change blocked while a test session is active",
                diagnosticFields() + mapOf("requestedMode" to mode.name),
            )
            return
        }
        _uiState.update { it.copy(selectedMode = mode) }
        logger.info(
            "MODE_SELECTED",
            "Probe mode selected",
            diagnosticFields() + mapOf("mode" to mode.displayName),
        )
    }

    fun startSelectedMode() {
        when (_uiState.value.selectedMode) {
            ProbeMode.MEASURE -> startMeasurement()
            ProbeMode.EXERCISE -> startExerciseTest()
        }
    }

    fun stopSelectedMode() {
        when (_uiState.value.selectedMode) {
            ProbeMode.MEASURE -> stopMeasurement("USER")
            ProbeMode.EXERCISE -> {
                logger.info(
                    "EXERCISE_STOP_BUTTON_CLICKED",
                    "User requested ExerciseClient test stop",
                    diagnosticFields(),
                )
                ExerciseForegroundService.requestStop(getApplication(), "USER")
            }
        }
    }

    fun selectWearScenario(scenario: WearScenario) {
        if (backgroundTestRecorder.state.value.isActive) {
            logger.warn(
                "BACKGROUND_TEST_SCENARIO_CHANGE_BLOCKED",
                "Wear scenario cannot change during an active background test",
                diagnosticFields() + mapOf("requestedScenario" to scenario.name),
            )
            return
        }
        _uiState.update { it.copy(selectedWearScenario = scenario) }
        logger.info(
            "BACKGROUND_TEST_SCENARIO_SELECTED",
            "Background test wear scenario selected",
            diagnosticFields() + mapOf("scenario" to scenario.name),
        )
    }

    fun startBackgroundTest(type: BackgroundTestType): Boolean {
        val application = getApplication<Application>()
        val exercise = exerciseStore.state.value
        val scenario = _uiState.value.selectedWearScenario
        val result = backgroundTestRecorder.start(
            type = type,
            scenario = scenario,
            exercise = exercise,
            charging = application.isBatteryCharging(),
        )
        result.onSuccess { test ->
            logger.info(
                "BACKGROUND_TEST_STARTED",
                "Persistent background delivery and battery test started",
                diagnosticFields() + mapOf(
                    "sessionId" to test.sessionId,
                    "testType" to type.name,
                    "scenario" to scenario.name,
                    "targetEndMillis" to test.targetEndMillis,
                    "startBatteryPercent" to test.startBatteryPercent,
                    "startCharging" to test.startCharging,
                    "startWorn" to test.startWorn,
                ),
            )
        }.onFailure { failure ->
            logger.error(
                "BACKGROUND_TEST_START_BLOCKED",
                "Background test preflight checks failed",
                failure,
                diagnosticFields() + mapOf(
                    "testType" to type.name,
                    "scenario" to scenario.name,
                    "charging" to application.isBatteryCharging(),
                    "validExerciseSamples" to exercise.sampleCount,
                ),
            )
        }
        return result.isSuccess
    }

    fun stopBackgroundTest() {
        val result = backgroundTestRecorder.finish("USER_STOP")
        if (result == null) {
            logger.warn("BACKGROUND_TEST_STOP_IGNORED", "No background test is active", diagnosticFields())
            return
        }
        logger.info(
            "BACKGROUND_TEST_STOPPED",
            "Background test stopped and local reports were finalized",
            diagnosticFields() + mapOf(
                "sessionId" to result.sessionId,
                "jsonPath" to result.latestReportJsonPath,
                "textPath" to result.latestReportTextPath,
            ),
        )
        ExerciseForegroundService.requestStop(getApplication(), "BACKGROUND_TEST_USER_STOP")
    }

    fun viewBackgroundTestResult() {
        val result = backgroundTestRecorder.refreshLatestReport()
        logger.info(
            "BACKGROUND_TEST_RESULT_VIEWED",
            "Latest persistent background test report loaded for display/export",
            diagnosticFields() + mapOf(
                "sessionId" to result.sessionId,
                "jsonPath" to result.latestReportJsonPath,
                "textPath" to result.latestReportTextPath,
            ),
        )
    }

    fun ensureExerciseSessionRestored() {
        val exercise = exerciseStore.state.value
        if (exercise.sessionState in setOf(
                ExerciseSessionState.RESTORING,
                ExerciseSessionState.STARTING,
                ExerciseSessionState.ACTIVE,
                ExerciseSessionState.PAUSED,
            )
        ) {
            logger.info(
                "EXERCISE_RESTORE_COMMAND",
                "Activity requested restoration of the persisted exercise session",
                diagnosticFields(),
            )
            ExerciseForegroundService.requestRestore(getApplication())
        }
    }

    fun onPermissionCheck(permissionState: PermissionState) {
        _uiState.update {
            it.copy(
                permissionState = permissionState,
                requiredPermission = permissionManager.requiredPermission,
                backgroundHealthPermission = permissionManager.backgroundHealthPermission,
                backgroundHealthPermissionGranted = permissionManager.isBackgroundHealthGranted(),
            )
        }
        logger.info(
            "PERMISSION_CHECK_RESULT",
            "Heart-rate and background health permissions checked",
            diagnosticFields() + mapOf(
                "permission" to permissionManager.requiredPermission,
                "result" to permissionState.name,
                "backgroundPermission" to permissionManager.backgroundHealthPermission,
                "backgroundGranted" to permissionManager.isBackgroundHealthGranted(),
                "apiLevel" to Build.VERSION.SDK_INT,
            ),
        )
        when (permissionState) {
            PermissionState.GRANTED -> if (!measureManager.isRegistered() &&
                _uiState.value.heartRateSupported != TriState.NO &&
                _uiState.value.healthServicesAvailable != TriState.NO
            ) {
                transitionTo(ProbeStatus.READY, "PERMISSION_READY")
            }
            PermissionState.PERMANENTLY_DENIED ->
                transitionTo(ProbeStatus.PERMISSION_PERMANENTLY_DENIED, "PERMISSION_PERMANENT_DENIAL")
            PermissionState.NOT_GRANTED ->
                transitionTo(ProbeStatus.PERMISSION_NOT_GRANTED, "PERMISSION_MISSING")
            PermissionState.UNKNOWN -> Unit
        }
    }

    fun onBackgroundPermissionResult(granted: Boolean) {
        _uiState.update { it.copy(backgroundHealthPermissionGranted = granted) }
        logger.info(
            if (granted) "BACKGROUND_HEALTH_PERMISSION_GRANTED" else "BACKGROUND_HEALTH_PERMISSION_DENIED",
            "Background health permission request completed",
            diagnosticFields() + mapOf(
                "permission" to permissionManager.backgroundHealthPermission,
                "granted" to granted,
            ),
        )
    }

    fun onPermissionRequestStarted(permission: String = permissionManager.requiredPermission) {
        if (permission == permissionManager.requiredPermission) permissionManager.markRequested()
        logger.info(
            "PERMISSION_REQUEST_START",
            "Runtime permission request started",
            diagnosticFields() + mapOf("permission" to permission),
        )
    }

    fun onPermissionRequestResult(granted: Boolean, permanentlyDenied: Boolean) {
        val result = when {
            granted -> PermissionState.GRANTED
            permanentlyDenied -> PermissionState.PERMANENTLY_DENIED
            else -> PermissionState.NOT_GRANTED
        }
        logger.info(
            if (granted) "PERMISSION_GRANTED" else "PERMISSION_DENIED",
            "Heart-rate runtime permission request completed",
            diagnosticFields() + mapOf(
                "permission" to permissionManager.requiredPermission,
                "granted" to granted,
                "permanentlyDenied" to permanentlyDenied,
            ),
        )
        onPermissionCheck(result)
    }

    fun onSettingsOpenedForPermission() {
        logger.warn(
            "PERMISSION_SETTINGS_OPENED",
            "Opening application settings because permission is permanently denied",
            diagnosticFields() + mapOf("permission" to permissionManager.requiredPermission),
        )
    }

    fun startMeasurement() {
        logger.info("START_BUTTON_CLICKED", "MeasureClient Start clicked", diagnosticFields())
        if (!permissionManager.isGranted()) {
            transitionTo(ProbeStatus.PERMISSION_NOT_GRANTED, "START_PERMISSION_REQUIRED")
            return
        }
        if (measureManager.isRegistered()) {
            logger.warn("DUPLICATE_REGISTER_BLOCKED", "Start ignored because callback is registered", diagnosticFields())
            return
        }
        if (!measureManager.isClientInitialized()) {
            transitionTo(ProbeStatus.HEALTH_SERVICES_UNAVAILABLE, "START_NO_HEALTH_SERVICES")
            return
        }
        viewModelScope.launch {
            transitionTo(ProbeStatus.STARTING, "MEASUREMENT_STARTING")
            val now = System.currentTimeMillis()
            val battery = getApplication<Application>().batteryPercent()
            _uiState.update {
                it.copy(
                    rawBpm = null,
                    displayedBpm = null,
                    valueIsStaleOrStopped = false,
                    validSampleCount = 0,
                    invalidSampleCount = 0,
                    lastValidUpdateMillis = null,
                    dataAgeSeconds = null,
                    waitingSeconds = 0,
                    staleCount = 0,
                    maxGapMillis = 0,
                    startBatteryPercent = battery,
                    currentBatteryPercent = battery,
                    sessionStartMillis = now,
                    sessionEndMillis = null,
                    measureAvailability = "UNKNOWN",
                )
            }
            try {
                if (!measureManager.queryHeartRateSupport()) {
                    transitionTo(ProbeStatus.HEART_RATE_NOT_SUPPORTED, "START_HR_NOT_SUPPORTED")
                    return@launch
                }
                if (measureManager.registerCallback("USER_START")) {
                    measurementStartElapsedRealtime = SystemClock.elapsedRealtime()
                    staleWasLogged = false
                    transitionTo(ProbeStatus.WAITING_FOR_FIRST_SAMPLE, "WAITING_AFTER_REGISTER")
                }
            } catch (failure: Throwable) {
                logger.error("MEASUREMENT_START_FAILED", "Measurement startup failed", failure, diagnosticFields())
                transitionTo(ProbeStatus.ERROR, "MEASUREMENT_START_EXCEPTION", failure.message ?: failure.javaClass.name)
            }
        }
    }

    fun stopMeasurement(reason: String = "USER") {
        logger.info(
            "MEASURE_STOP_REQUESTED",
            "MeasureClient stop requested",
            diagnosticFields() + mapOf("reason" to reason),
        )
        if (!measureManager.isRegistered()) {
            logger.warn(
                "DUPLICATE_UNREGISTER_BLOCKED",
                "Stop ignored because no MeasureCallback is registered",
                diagnosticFields() + mapOf("reason" to reason),
            )
            return
        }
        viewModelScope.launch {
            transitionTo(ProbeStatus.STOPPING, "MEASUREMENT_STOPPING")
            try {
                measureManager.unregisterCallback(reason)
                _uiState.update {
                    it.copy(
                        valueIsStaleOrStopped = it.displayedBpm != null,
                        sessionEndMillis = System.currentTimeMillis(),
                    )
                }
                transitionTo(ProbeStatus.STOPPED, "MEASUREMENT_STOPPED")
            } catch (failure: Throwable) {
                logger.error(
                    "MEASUREMENT_STOP_FAILED",
                    "Measurement stop failed",
                    failure,
                    diagnosticFields() + mapOf("reason" to reason),
                )
                transitionTo(ProbeStatus.ERROR, "MEASUREMENT_STOP_EXCEPTION", failure.message ?: failure.javaClass.name)
            }
        }
    }

    fun clearVisibleLog() {
        logger.info("CLEAR_VISIBLE_LOG_CLICKED", "Clear visible log clicked", diagnosticFields())
        logger.clearVisible()
    }

    fun onActivityLifecycle(event: String, details: Map<String, Any?> = emptyMap()) {
        val application = getApplication<Application>()
        exerciseStore.markActivity(event, application.isScreenInteractive())
        _uiState.update {
            it.copy(
                activityPhase = event,
                screenInteractive = application.isScreenInteractive(),
                pid = Process.myPid(),
            )
        }
        logger.info(
            "ACTIVITY_$event",
            "Activity lifecycle: $event",
            diagnosticFields() + details,
        )
    }

    fun onHostStopped() {
        onActivityLifecycle("STOP", mapOf("measureCallbackAction" to "KEEP_REGISTERED"))
        logger.info(
            "ACTIVITY_STOP_NO_UNREGISTER",
            "Activity stopped; MeasureCallback intentionally remains registered",
            diagnosticFields(),
        )
    }

    fun onAmbientChanged(ambient: Boolean, details: Map<String, Any?> = emptyMap()) {
        _uiState.update { it.copy(ambient = ambient, screenInteractive = getApplication<Application>().isScreenInteractive()) }
        logger.info(
            if (ambient) "AMBIENT_ENTER" else "AMBIENT_EXIT",
            if (ambient) "Entered ambient; callbacks remain registered" else "Exited ambient",
            diagnosticFields() + details + mapOf("measureCallbackAction" to "KEEP_REGISTERED"),
        )
    }

    fun onAmbientUpdate() {
        logger.debug(
            "AMBIENT_UPDATE",
            "Periodic ambient display update received; callbacks remain registered",
            diagnosticFields() + mapOf("measureCallbackAction" to "KEEP_REGISTERED"),
        )
    }

    fun onScreenEvent(action: String) {
        val interactive = getApplication<Application>().isScreenInteractive()
        exerciseStore.markActivity(_uiState.value.activityPhase, interactive)
        _uiState.update { it.copy(screenInteractive = interactive) }
        logger.info(
            if (action == android.content.Intent.ACTION_SCREEN_OFF) "SCREEN_OFF" else "SCREEN_ON",
            "Activity receiver observed screen interactive state change",
            diagnosticFields() + mapOf("broadcastAction" to action),
        )
    }

    override fun onHealthServicesAvailability(available: Boolean) {
        _uiState.update { it.copy(healthServicesAvailable = if (available) TriState.YES else TriState.NO) }
    }

    override fun onHeartRateSupport(supported: Boolean) {
        _uiState.update { it.copy(heartRateSupported = if (supported) TriState.YES else TriState.NO) }
    }

    override fun onCallbackRegistrationChanged(registered: Boolean) {
        _uiState.update { it.copy(callbackRegistered = registered) }
    }

    override fun onAvailabilityChanged(availability: DataTypeAvailability) {
        _uiState.update { it.copy(measureAvailability = availability.name) }
        when (availability) {
            DataTypeAvailability.AVAILABLE -> if (_uiState.value.validSampleCount > 0) {
                transitionTo(ProbeStatus.MEASURING, "SENSOR_AVAILABLE")
            }
            DataTypeAvailability.ACQUIRING -> if (_uiState.value.validSampleCount == 0L) {
                transitionTo(ProbeStatus.WAITING_FOR_FIRST_SAMPLE, "SENSOR_ACQUIRING")
            }
            else -> transitionTo(
                ProbeStatus.SENSOR_TEMPORARILY_UNAVAILABLE,
                "SENSOR_AVAILABILITY_${availability.name}",
            )
        }
    }

    override fun onValidSample(rawBpm: Double, displayedBpm: Int, sampleTimeMillis: Long) {
        if (!measureManager.isRegistered()) {
            logger.warn(
                "LATE_SAMPLE_IGNORED",
                "Sample received after callback was marked unregistered",
                diagnosticFields() + mapOf("rawDouble" to rawBpm, "sampleEpochMillis" to sampleTimeMillis),
            )
            return
        }
        val previous = _uiState.value.lastValidUpdateMillis
        val gap = previous?.let { (sampleTimeMillis - it).coerceAtLeast(0) } ?: 0L
        staleWasLogged = false
        _uiState.update {
            it.copy(
                rawBpm = rawBpm,
                displayedBpm = displayedBpm,
                valueIsStaleOrStopped = false,
                validSampleCount = it.validSampleCount + 1,
                lastValidUpdateMillis = sampleTimeMillis,
                dataAgeSeconds = 0,
                maxGapMillis = maxOf(it.maxGapMillis, gap),
                currentBatteryPercent = getApplication<Application>().batteryPercent(),
            )
        }
        transitionTo(ProbeStatus.MEASURING, "VALID_SAMPLE_RECEIVED")
    }

    override fun onInvalidSamples(count: Int, reason: String) {
        _uiState.update { it.copy(invalidSampleCount = it.invalidSampleCount + count) }
        logger.warn(
            "INVALID_SAMPLE_COUNT_UPDATED",
            "Invalid sample counter updated",
            diagnosticFields() + mapOf("added" to count, "reason" to reason, "total" to _uiState.value.invalidSampleCount),
        )
    }

    override fun onMeasurementFailure(eventCode: String, message: String, throwable: Throwable) {
        logger.error(eventCode, message, throwable, diagnosticFields())
        transitionTo(ProbeStatus.ERROR, eventCode, message)
    }

    override fun onCleared() {
        logger.info(
            "VIEWMODEL_CLEAR",
            "HeartRateViewModel is genuinely being cleared",
            diagnosticFields() + mapOf("releaseReason" to "MEASURE_MANAGER_DESTROY"),
        )
        measureManager.releaseBestEffort("MEASURE_MANAGER_DESTROY")
        logger.info(
            "VIEWMODEL_RELEASE_REQUESTED",
            "ViewModel requested MeasureCallback cleanup; Exercise service is unaffected",
            diagnosticFields(),
        )
        super.onCleared()
    }

    private fun startExerciseTest() {
        logger.info("EXERCISE_START_BUTTON_CLICKED", "ExerciseClient Start clicked", diagnosticFields())
        if (!permissionManager.isGranted() || !permissionManager.isBackgroundHealthGranted()) {
            logger.warn(
                "EXERCISE_START_BLOCKED_PERMISSION",
                "Exercise start blocked because health permissions are incomplete",
                diagnosticFields() + mapOf(
                    "heartRateGranted" to permissionManager.isGranted(),
                    "backgroundGranted" to permissionManager.isBackgroundHealthGranted(),
                ),
            )
            return
        }
        if (exerciseStore.state.value.serviceRunning) {
            logger.warn("EXERCISE_DUPLICATE_START_BLOCKED", "Exercise service is already running", diagnosticFields())
            return
        }
        ExerciseForegroundService.requestStart(getApplication())
    }

    private fun initializeEnvironment() {
        val initialPermission = if (permissionManager.isGranted()) PermissionState.GRANTED else PermissionState.NOT_GRANTED
        _uiState.update {
            it.copy(
                permissionState = initialPermission,
                backgroundHealthPermissionGranted = permissionManager.isBackgroundHealthGranted(),
            )
        }
        if (!_uiState.value.isWatchDevice) {
            transitionTo(ProbeStatus.ENVIRONMENT_ERROR, "NOT_A_WATCH", "设备未报告 FEATURE_WATCH")
            return
        }
        if (!measureManager.isClientInitialized()) {
            transitionTo(ProbeStatus.HEALTH_SERVICES_UNAVAILABLE, "CLIENT_INIT_FAILED")
            return
        }
        viewModelScope.launch {
            try {
                val supported = measureManager.queryHeartRateSupport()
                when {
                    !supported -> transitionTo(ProbeStatus.HEART_RATE_NOT_SUPPORTED, "INITIAL_CAP_UNSUPPORTED")
                    initialPermission == PermissionState.GRANTED -> transitionTo(ProbeStatus.READY, "INITIAL_READY")
                    else -> transitionTo(ProbeStatus.PERMISSION_NOT_GRANTED, "INITIAL_PERMISSION_REQUIRED")
                }
            } catch (failure: Throwable) {
                logger.error(
                    "INITIAL_ENVIRONMENT_CHECK_FAILED",
                    "Initial Health Services environment check failed",
                    failure,
                    diagnosticFields(),
                )
                transitionTo(ProbeStatus.HEALTH_SERVICES_UNAVAILABLE, "INITIAL_HEALTH_SERVICES_FAILURE", failure.message)
            }
        }
    }

    private fun startUiTicker() {
        viewModelScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val interactive = getApplication<Application>().isScreenInteractive()
                _uiState.update { state ->
                    if (!measureManager.isRegistered()) {
                        state.copy(nowMillis = now, screenInteractive = interactive)
                    } else {
                        val last = state.lastValidUpdateMillis
                        val ageMillis = last?.let { (now - it).coerceAtLeast(0) }
                        val waiting = if (last == null) {
                            ((SystemClock.elapsedRealtime() - measurementStartElapsedRealtime) / 1_000).coerceAtLeast(0)
                        } else {
                            state.waitingSeconds
                        }
                        state.copy(
                            nowMillis = now,
                            screenInteractive = interactive,
                            waitingSeconds = waiting,
                            dataAgeSeconds = ageMillis?.div(1_000),
                            maxGapMillis = maxOf(state.maxGapMillis, ageMillis ?: 0),
                            currentBatteryPercent = getApplication<Application>().batteryPercent(),
                        )
                    }
                }
                val state = _uiState.value
                val age = state.dataAgeSeconds
                if (measureManager.isRegistered() && age != null && age >= STALE_AFTER_SECONDS && !staleWasLogged) {
                    staleWasLogged = true
                    _uiState.update { it.copy(valueIsStaleOrStopped = true, staleCount = it.staleCount + 1) }
                    logger.warn(
                        "DATA_STALE_TIMEOUT",
                        "No fresh MeasureClient heart-rate sample arrived before timeout",
                        diagnosticFields() + mapOf(
                            "ageSeconds" to age,
                            "thresholdSeconds" to STALE_AFTER_SECONDS,
                            "staleCount" to _uiState.value.staleCount,
                        ),
                    )
                    transitionTo(ProbeStatus.DATA_STALE, "DATA_STALE_TIMEOUT")
                }
                delay(1_000)
            }
        }
    }

    private fun transitionTo(status: ProbeStatus, eventCode: String, descriptionOverride: String? = null) {
        val oldStatus = _uiState.value.status
        val description = descriptionOverride ?: status.userMessage
        logger.updateState(status)
        _uiState.update { it.copy(status = status, errorCode = status.code, errorDescription = description) }
        if (oldStatus != status || descriptionOverride != null) {
            logger.info(
                "STATE_CHANGED",
                "Application state changed",
                diagnosticFields() + mapOf(
                    "trigger" to eventCode,
                    "from" to oldStatus.name,
                    "to" to status.name,
                    "code" to status.code,
                    "description" to description,
                ),
            )
        }
    }

    private fun diagnosticFields(): Map<String, Any?> =
        runtimeDiagnosticFields(getApplication(), measureManager.isRegistered()) + mapOf(
            "selectedMode" to _uiState.value.selectedMode.name,
            "ambient" to _uiState.value.ambient,
        )

    companion object {
        private const val STALE_AFTER_SECONDS = 10L
    }
}
