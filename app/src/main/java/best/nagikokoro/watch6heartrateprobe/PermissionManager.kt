package best.nagikokoro.watch6heartrateprobe

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.edit

class PermissionManager(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val requiredPermission: String
        get() = if (Build.VERSION.SDK_INT >= 36) {
            READ_HEART_RATE_PERMISSION
        } else {
            Manifest.permission.BODY_SENSORS
        }

    val backgroundHealthPermission: String?
        get() = when {
            Build.VERSION.SDK_INT >= 36 -> READ_HEALTH_DATA_IN_BACKGROUND_PERMISSION
            Build.VERSION.SDK_INT >= 33 -> Manifest.permission.BODY_SENSORS_BACKGROUND
            else -> null
        }

    fun isGranted(): Boolean =
        ContextCompat.checkSelfPermission(appContext, requiredPermission) == PackageManager.PERMISSION_GRANTED

    fun isBackgroundHealthGranted(): Boolean = backgroundHealthPermission?.let {
        ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
    } ?: true

    fun currentState(activity: Activity?): PermissionState {
        if (isGranted()) return PermissionState.GRANTED
        if (activity != null && wasRequested() &&
            !activity.shouldShowRequestPermissionRationale(requiredPermission)
        ) {
            return PermissionState.PERMANENTLY_DENIED
        }
        return PermissionState.NOT_GRANTED
    }

    fun markRequested() {
        preferences.edit { putBoolean(requestedKey(), true) }
    }

    fun wasRequested(): Boolean = preferences.getBoolean(requestedKey(), false)

    private fun requestedKey(): String = "requested_${requiredPermission.replace('.', '_')}"

    companion object {
        const val READ_HEART_RATE_PERMISSION = "android.permission.health.READ_HEART_RATE"
        const val READ_HEALTH_DATA_IN_BACKGROUND_PERMISSION =
            "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
        private const val PREFS_NAME = "permission_probe_state"
    }
}
