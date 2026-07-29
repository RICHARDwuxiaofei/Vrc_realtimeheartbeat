package best.nagikokoro.watch6heartrateprobe.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import best.nagikokoro.watch6heartrateprobe.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PhoneRelayNotificationService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notificationTicker: Job? = null

    override fun onCreate() {
        super.onCreate()
        PhoneRelayRepository.initialize(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification(System.currentTimeMillis())
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        startNotificationTicker()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        notificationTicker?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startNotificationTicker() {
        if (notificationTicker?.isActive == true) return
        notificationTicker = serviceScope.launch {
            while (isActive) {
                delay(NOTIFICATION_UPDATE_INTERVAL_MILLIS)
                val now = System.currentTimeMillis()
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(now))
            }
        }
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL,
                AppLocale.text(this, "心率运行状态"),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = AppLocale.text(
                    this@PhoneRelayNotificationService,
                    "每 5 秒显示当前心率和中转状态",
                )
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(now: Long): Notification {
        val state = PhoneRelayRepository.state.value
        val notificationState = phoneRelayNotificationState(state, now)
        val bpmText = notificationState.bpm
            ?.let { "$it BPM" }
            ?: AppLocale.text(this, "等待心率")
        val runningText = AppLocale.text(
            this,
            if (state.forwardingEnabled) "运行中" else "已暂停发送到电脑",
        )
        val sourceText = if (state.heartRateSource == HeartRateSource.XIAOMI_BAND_BLE) {
            AppLocale.text(this, "小米手环 BLE")
        } else {
            "Galaxy Watch"
        }
        val pcText = when (notificationState.pcState) {
            PcNotificationState.PAUSED -> AppLocale.text(this, "已暂停发送到电脑")
            PcNotificationState.NOT_CONFIGURED -> AppLocale.text(this, "尚未设置电脑")
            PcNotificationState.CONFIRMED -> AppLocale.text(this, "电脑已确认")
            PcNotificationState.WAITING -> AppLocale.text(this, "等待电脑回执")
        }
        val title = "${AppLocale.text(this, "心率中转站")} · $runningText"
        val content = "$bpmText · $sourceText · $pcText"
        val openIntent = requireNotNull(packageManager.getLaunchIntentForPackage(packageName)).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(true)
            .setWhen(now)
            .build()
    }

    companion object {
        private const val NOTIFICATION_CHANNEL = "phone_relay_status"
        private const val NOTIFICATION_ID = 7301
        private const val NOTIFICATION_UPDATE_INTERVAL_MILLIS = 5_000L

        fun requestStart(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, PhoneRelayNotificationService::class.java),
            )
        }
    }
}
