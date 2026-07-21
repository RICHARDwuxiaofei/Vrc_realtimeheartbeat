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
        description = "Health Services 批量交付，适合长时间运行",
    );

    companion object {
        fun fromStoredValue(value: String?): WatchRelayMode =
            entries.firstOrNull { it.name == value } ?: POWER_SAVER_5_SECONDS

        fun fromIntervalSeconds(seconds: Int): WatchRelayMode =
            if (seconds <= 1) REALTIME_1_SECOND else POWER_SAVER_5_SECONDS
    }
}

class WatchRelaySettings private constructor(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutableMode = MutableStateFlow(
        WatchRelayMode.fromStoredValue(preferences.getString(KEY_MODE, null)),
    )
    val mode: StateFlow<WatchRelayMode> = mutableMode.asStateFlow()

    @Synchronized
    fun setMode(mode: WatchRelayMode) {
        preferences.edit { putString(KEY_MODE, mode.name) }
        mutableMode.value = mode
    }

    companion object {
        private const val PREFS = "watch_relay_settings"
        private const val KEY_MODE = "relayMode"

        @Volatile
        private var instance: WatchRelaySettings? = null

        fun get(context: Context): WatchRelaySettings = instance ?: synchronized(this) {
            instance ?: WatchRelaySettings(context.applicationContext).also { instance = it }
        }
    }
}
