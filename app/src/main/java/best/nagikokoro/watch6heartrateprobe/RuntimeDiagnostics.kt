package best.nagikokoro.watch6heartrateprobe

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.Process

fun Context.isScreenInteractive(): Boolean =
    (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

fun Context.batteryPercent(): Int? {
    val status = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
    val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    return if (level >= 0 && scale > 0) ((level * 100.0) / scale).toInt() else null
}

fun Context.isBatteryCharging(): Boolean? {
    val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
    val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
    return plugged != 0 || status == BatteryManager.BATTERY_STATUS_CHARGING ||
        status == BatteryManager.BATTERY_STATUS_FULL
}

fun runtimeDiagnosticFields(
    context: Context,
    measureCallbackRegistered: Boolean? = null,
): Map<String, Any?> {
    val exercise = ExerciseSessionStore.get(context).state.value
    return buildMap {
        put("pid", Process.myPid())
        put("screenInteractive", context.isScreenInteractive())
        put("serviceRunning", exercise.serviceRunning)
        put("exerciseState", exercise.sessionState.name)
        put("exerciseCallbackRegistered", exercise.callbackRegistered)
        put("activityPhase", exercise.activityPhase)
        measureCallbackRegistered?.let { put("callbackRegistered", it) }
    }
}
