package best.nagikokoro.watch6heartrateprobe

enum class ProbeMode(val displayName: String) {
    MEASURE("MeasureClient Probe"),
    EXERCISE("ExerciseClient Screen-off Test"),
}

enum class ProbeStatus(val code: String, val userMessage: String) {
    INITIALIZING("S_INIT_000", "正在初始化诊断环境"),
    ENVIRONMENT_ERROR("E_ENV_001", "当前设备环境不满足测量要求"),
    PERMISSION_NOT_GRANTED("E_PERM_001", "尚未授予心率读取权限"),
    PERMISSION_PERMANENTLY_DENIED("E_PERM_002", "心率权限已被永久拒绝，请在系统设置中授权"),
    HEALTH_SERVICES_UNAVAILABLE("E_HS_001", "Health Services 不可用"),
    HEART_RATE_NOT_SUPPORTED("E_CAP_001", "设备未报告支持 HEART_RATE_BPM"),
    SENSOR_TEMPORARILY_UNAVAILABLE("E_SENSOR_001", "心率传感器暂时不可用，请确认手表已佩戴"),
    READY("S_READY_000", "环境就绪，可以开始测量"),
    STARTING("S_START_000", "正在检查能力并注册回调"),
    WAITING_FOR_FIRST_SAMPLE("S_WAIT_000", "回调已注册，正在等待首个心率样本"),
    MEASURING("S_MEASURE_000", "正在接收实时心率"),
    DATA_STALE("E_DATA_001", "心率数据已超时"),
    STOPPING("S_STOP_000", "正在注销测量回调"),
    STOPPED("S_STOPPED_000", "测量已停止"),
    ERROR("E_UNKNOWN_001", "发生未分类错误，请查看诊断日志"),
}

enum class PermissionState(val displayText: String) {
    UNKNOWN("UNKNOWN"),
    GRANTED("GRANTED"),
    NOT_GRANTED("NOT_GRANTED"),
    PERMANENTLY_DENIED("PERMANENTLY_DENIED"),
}

enum class TriState(val displayText: String) {
    UNKNOWN("UNKNOWN"),
    YES("YES"),
    NO("NO"),
}

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class DiagnosticEntry(
    val timestampMillis: Long,
    val level: LogLevel,
    val eventCode: String,
    val appState: ProbeStatus,
    val message: String,
    val parameters: Map<String, Any?> = emptyMap(),
)

data class ProbeUiState(
    val selectedMode: ProbeMode = ProbeMode.MEASURE,
    val status: ProbeStatus = ProbeStatus.INITIALIZING,
    val permissionState: PermissionState = PermissionState.UNKNOWN,
    val backgroundHealthPermission: String? = null,
    val backgroundHealthPermissionGranted: Boolean = false,
    val requiredPermission: String = "",
    val deviceManufacturer: String = android.os.Build.MANUFACTURER,
    val deviceModel: String = android.os.Build.MODEL,
    val apiLevel: Int = android.os.Build.VERSION.SDK_INT,
    val pid: Int = android.os.Process.myPid(),
    val isWatchDevice: Boolean = false,
    val screenInteractive: Boolean = true,
    val ambient: Boolean = false,
    val activityPhase: String = "CREATE",
    val healthServicesAvailable: TriState = TriState.UNKNOWN,
    val heartRateSupported: TriState = TriState.UNKNOWN,
    val measureAvailability: String = "UNKNOWN",
    val callbackRegistered: Boolean = false,
    val rawBpm: Double? = null,
    val displayedBpm: Int? = null,
    val valueIsStaleOrStopped: Boolean = false,
    val validSampleCount: Long = 0,
    val invalidSampleCount: Long = 0,
    val lastValidUpdateMillis: Long? = null,
    val dataAgeSeconds: Long? = null,
    val waitingSeconds: Long = 0,
    val staleCount: Long = 0,
    val maxGapMillis: Long = 0,
    val startBatteryPercent: Int? = null,
    val currentBatteryPercent: Int? = null,
    val sessionStartMillis: Long? = null,
    val sessionEndMillis: Long? = null,
    val nowMillis: Long = System.currentTimeMillis(),
    val exercise: ExerciseSessionSnapshot = ExerciseSessionSnapshot(),
    val backgroundTest: BackgroundTestSnapshot = BackgroundTestSnapshot(),
    val selectedWearScenario: WearScenario = WearScenario.OFF_WRIST_BASELINE,
    val errorCode: String = ProbeStatus.INITIALIZING.code,
    val errorDescription: String = ProbeStatus.INITIALIZING.userMessage,
    val visibleLogs: List<DiagnosticEntry> = emptyList(),
) {
    val canStart: Boolean
        get() = when (selectedMode) {
            ProbeMode.MEASURE -> !callbackRegistered && status !in setOf(
                ProbeStatus.STARTING,
                ProbeStatus.STOPPING,
                ProbeStatus.HEALTH_SERVICES_UNAVAILABLE,
                ProbeStatus.HEART_RATE_NOT_SUPPORTED,
                ProbeStatus.ENVIRONMENT_ERROR,
            )
            ProbeMode.EXERCISE -> !exercise.serviceRunning && exercise.sessionState !in setOf(
                ExerciseSessionState.STARTING,
                ExerciseSessionState.RESTORING,
                ExerciseSessionState.ENDING,
            )
        }

    val canStop: Boolean
        get() = when (selectedMode) {
            ProbeMode.MEASURE -> callbackRegistered && status != ProbeStatus.STOPPING
            ProbeMode.EXERCISE -> exercise.serviceRunning && exercise.sessionState != ExerciseSessionState.ENDING
        }
}
