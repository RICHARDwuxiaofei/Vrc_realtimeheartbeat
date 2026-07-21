package best.nagikokoro.watch6heartrateprobe

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ExerciseSessionState {
    IDLE,
    RESTORING,
    STARTING,
    ACTIVE,
    PAUSED,
    ENDING,
    ENDED,
    ERROR,
}

data class ExerciseSessionSnapshot(
    val serviceRunning: Boolean = false,
    val callbackRegistered: Boolean = false,
    val sessionState: ExerciseSessionState = ExerciseSessionState.IDLE,
    val exerciseType: String = "--",
    val bpm: Int? = null,
    val rawBpm: Double? = null,
    val sampleCount: Long = 0,
    val lastSampleMillis: Long? = null,
    val staleCount: Long = 0,
    val maxGapMillis: Long = 0,
    val startBatteryPercent: Int? = null,
    val currentBatteryPercent: Int? = null,
    val sessionStartMillis: Long? = null,
    val sessionEndMillis: Long? = null,
    val availability: String = "UNKNOWN",
    val endReason: String = "--",
    val lastError: String = "--",
    val activityPhase: String = "PROCESS_START",
    val screenInteractive: Boolean = true,
    val relayMode: WatchRelayMode = WatchRelayMode.POWER_SAVER_5_SECONDS,
)

class ExerciseSessionStore private constructor(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<ExerciseSessionSnapshot> = _state.asStateFlow()

    @Synchronized
    fun update(transform: (ExerciseSessionSnapshot) -> ExerciseSessionSnapshot) {
        val next = transform(_state.value)
        _state.value = next
        persist(next)
    }

    fun markActivity(phase: String, interactive: Boolean) = update {
        it.copy(activityPhase = phase, screenInteractive = interactive)
    }

    private fun load(): ExerciseSessionSnapshot = ExerciseSessionSnapshot(
        serviceRunning = preferences.getBoolean("serviceRunning", false),
        callbackRegistered = preferences.getBoolean("callbackRegistered", false),
        sessionState = runCatching {
            ExerciseSessionState.valueOf(preferences.getString("sessionState", "IDLE") ?: "IDLE")
        }.getOrDefault(ExerciseSessionState.IDLE),
        exerciseType = preferences.getString("exerciseType", "--") ?: "--",
        bpm = preferences.getIntOrNull("bpm"),
        rawBpm = preferences.getDoubleOrNull("rawBpm"),
        sampleCount = preferences.getLong("sampleCount", 0),
        lastSampleMillis = preferences.getLongOrNull("lastSampleMillis"),
        staleCount = preferences.getLong("staleCount", 0),
        maxGapMillis = preferences.getLong("maxGapMillis", 0),
        startBatteryPercent = preferences.getIntOrNull("startBatteryPercent"),
        currentBatteryPercent = preferences.getIntOrNull("currentBatteryPercent"),
        sessionStartMillis = preferences.getLongOrNull("sessionStartMillis"),
        sessionEndMillis = preferences.getLongOrNull("sessionEndMillis"),
        availability = preferences.getString("availability", "UNKNOWN") ?: "UNKNOWN",
        endReason = preferences.getString("endReason", "--") ?: "--",
        lastError = preferences.getString("lastError", "--") ?: "--",
        activityPhase = preferences.getString("activityPhase", "PROCESS_START") ?: "PROCESS_START",
        screenInteractive = preferences.getBoolean("screenInteractive", true),
        relayMode = WatchRelayMode.fromStoredValue(preferences.getString("relayMode", null)),
    )

    private fun persist(value: ExerciseSessionSnapshot) {
        preferences.edit {
            putBoolean("serviceRunning", value.serviceRunning)
            putBoolean("callbackRegistered", value.callbackRegistered)
            putString("sessionState", value.sessionState.name)
            putString("exerciseType", value.exerciseType)
            putNullableInt("bpm", value.bpm)
            putNullableDouble("rawBpm", value.rawBpm)
            putLong("sampleCount", value.sampleCount)
            putNullableLong("lastSampleMillis", value.lastSampleMillis)
            putLong("staleCount", value.staleCount)
            putLong("maxGapMillis", value.maxGapMillis)
            putNullableInt("startBatteryPercent", value.startBatteryPercent)
            putNullableInt("currentBatteryPercent", value.currentBatteryPercent)
            putNullableLong("sessionStartMillis", value.sessionStartMillis)
            putNullableLong("sessionEndMillis", value.sessionEndMillis)
            putString("availability", value.availability)
            putString("endReason", value.endReason)
            putString("lastError", value.lastError)
            putString("activityPhase", value.activityPhase)
            putBoolean("screenInteractive", value.screenInteractive)
            putString("relayMode", value.relayMode.name)
        }
    }

    companion object {
        private const val PREFS = "exercise_session_state"

        @Volatile
        private var instance: ExerciseSessionStore? = null

        fun get(context: Context): ExerciseSessionStore = instance ?: synchronized(this) {
            instance ?: ExerciseSessionStore(context.applicationContext).also { instance = it }
        }
    }
}

private fun android.content.SharedPreferences.getIntOrNull(key: String): Int? =
    if (contains(key)) getInt(key, 0) else null

private fun android.content.SharedPreferences.getLongOrNull(key: String): Long? =
    if (contains(key)) getLong(key, 0) else null

private fun android.content.SharedPreferences.getDoubleOrNull(key: String): Double? =
    if (contains(key)) java.lang.Double.longBitsToDouble(getLong(key, 0)) else null

private fun android.content.SharedPreferences.Editor.putNullableInt(key: String, value: Int?) {
    if (value == null) remove(key) else putInt(key, value)
}

private fun android.content.SharedPreferences.Editor.putNullableLong(key: String, value: Long?) {
    if (value == null) remove(key) else putLong(key, value)
}

private fun android.content.SharedPreferences.Editor.putNullableDouble(key: String, value: Double?) {
    if (value == null) remove(key) else putLong(key, java.lang.Double.doubleToRawLongBits(value))
}
