package best.nagikokoro.watch6heartrateprobe

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class WatchRelayMode(
    val intervalSeconds: Int,
    val displayName: String,
    val description: String,
) {
    REALTIME_1_SECOND(
        intervalSeconds = 1,
        displayName = "1 秒实时",
        description = "约每秒更新，息屏时保持处理器活跃，耗电更高",
    ),
    POWER_SAVER_5_SECONDS(
        intervalSeconds = 5,
        displayName = "5 秒省电",
        description = "心率变化约 5 秒送达，稳定时自动降低重复传输",
    ),
    ULTRA_POWER_SAVER_10_SECONDS(
        intervalSeconds = 10,
        displayName = "10 秒超省电",
        description = "减少手表与手机通信，适合长时间挂 VRChat",
    );

    companion object {
        fun fromStoredValue(value: String?): WatchRelayMode =
            entries.firstOrNull { it.name == value } ?: POWER_SAVER_5_SECONDS

        fun fromIntervalSeconds(seconds: Int): WatchRelayMode = when {
            seconds <= 1 -> REALTIME_1_SECOND
            seconds <= 5 -> POWER_SAVER_5_SECONDS
            else -> ULTRA_POWER_SAVER_10_SECONDS
        }
    }
}

class WatchRelaySettings private constructor(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutableMode = MutableStateFlow(
        WatchRelayMode.fromStoredValue(preferences.getString(KEY_MODE, null)),
    )
    val mode: StateFlow<WatchRelayMode> = mutableMode.asStateFlow()
    var updatedEpochMillis: Long = preferences.getLong(KEY_UPDATED_EPOCH_MILLIS, 0L)
        private set

    @Synchronized
    fun setMode(mode: WatchRelayMode) {
        applyMode(mode, System.currentTimeMillis())
    }

    @Synchronized
    fun applyRemoteMode(mode: WatchRelayMode, remoteUpdatedEpochMillis: Long): Boolean {
        if (remoteUpdatedEpochMillis < updatedEpochMillis) return false
        if (remoteUpdatedEpochMillis == updatedEpochMillis && mode == mutableMode.value) return false
        applyMode(mode, remoteUpdatedEpochMillis)
        return true
    }

    @Synchronized
    private fun applyMode(mode: WatchRelayMode, timestamp: Long) {
        val safeTimestamp = timestamp.coerceAtLeast(0L)
        preferences.edit {
            putString(KEY_MODE, mode.name)
            putLong(KEY_UPDATED_EPOCH_MILLIS, safeTimestamp)
        }
        mutableMode.value = mode
        updatedEpochMillis = safeTimestamp
    }

    companion object {
        private const val PREFS = "watch_relay_settings"
        private const val KEY_MODE = "relayMode"
        private const val KEY_UPDATED_EPOCH_MILLIS = "relayModeUpdatedEpochMillis"

        @Volatile
        private var instance: WatchRelaySettings? = null

        fun get(context: Context): WatchRelaySettings = instance ?: synchronized(this) {
            instance ?: WatchRelaySettings(context.applicationContext).also { instance = it }
        }
    }
}
