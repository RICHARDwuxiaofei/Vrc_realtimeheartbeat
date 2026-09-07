package best.nagikokoro.watch6heartrateprobe.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import best.nagikokoro.watch6heartrateprobe.R

/**
 * Publishes the latest phone-side heart rate for the notification shade.
 *
 * Watch samples can arrive more frequently than the user needs to see in the
 * shade, so the notification is replaced at most once every five seconds.
 */
internal object PhoneHeartRateNotification {
    private const val CHANNEL_ID = "phone_heart_rate"
    private const val NOTIFICATION_ID = 6201
    private var lastPostedElapsedMillis = Long.MIN_VALUE

    @SuppressLint("MissingPermission")
    @Synchronized
    fun publish(context: Context, bpm: Int, source: HeartRateSource, force: Boolean = false) {
        val appContext = context.applicationContext
        if (!canPost(appContext)) return
        val now = SystemClock.elapsedRealtime()
        if (!force && !PhoneHeartRateNotificationPolicy.shouldUpdate(lastPostedElapsedMillis, now)) return
        ensureChannel(appContext)
        val sourceText = when (source) {
            HeartRateSource.GALAXY_WATCH -> "Galaxy Watch"
            HeartRateSource.XIAOMI_BAND_BLE -> AppLocale.text(appContext, "小米手环 BLE")
        }
        val launchIntent = appContext.packageManager
            .getLaunchIntentForPackage(appContext.packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(AppLocale.text(appContext, "手机心率通知"))
            .setContentText(
                "${AppLocale.text(appContext, "当前心率")}: $bpm BPM · $sourceText",
            )
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (launchIntent != null) {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    appContext,
                    NOTIFICATION_ID,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        runCatching { NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, builder.build()) }
            .onSuccess { lastPostedElapsedMillis = now }
    }

    @Synchronized
    fun clear(context: Context) {
        lastPostedElapsedMillis = Long.MIN_VALUE
        runCatching { NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_ID) }
    }

    private fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                AppLocale.text(context, "手机心率通知"),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = AppLocale.text(context, "每五秒更新一次当前心率")
                setShowBadge(false)
            },
        )
    }
}

internal object PhoneHeartRateNotificationPolicy {
    const val UPDATE_INTERVAL_MILLIS = 5_000L

    fun shouldUpdate(lastPostedElapsedMillis: Long, nowElapsedMillis: Long): Boolean {
        if (lastPostedElapsedMillis == Long.MIN_VALUE) return true
        val elapsed = nowElapsedMillis - lastPostedElapsedMillis
        return elapsed < 0L || elapsed >= UPDATE_INTERVAL_MILLIS
    }
}
