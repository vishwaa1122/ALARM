package com.vaishnava.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * Lightweight foreground "keeper" service to anchor the process so Boot/Alarm flows
 * remain reliable on aggressive OEMs (e.g., MIUI on Redmi 13C).
 * Starts when there are enabled alarms; stops itself when none remain.
 */
class KeeperService : Service() {

    companion object {
        const val ACTION_PIN_NEXT_ALARM = "com.vaishnava.alarm.PIN_NEXT_ALARM"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_CONTENT = "extra_content"
        const val EXTRA_DURATION = "extra_duration"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var reverting = false

    override fun onCreate() {
        super.onCreate()
        startForeground(9, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PIN_NEXT_ALARM) {
            val title = intent.getStringExtra(EXTRA_TITLE) ?: "Next Alarm"
            val content = intent.getStringExtra(EXTRA_CONTENT) ?: ""
            val duration = intent.getLongExtra(EXTRA_DURATION, 12_000L).coerceIn(3_000L, 20_000L)

            // Build a high-importance foreground notification on the next_alarm_high channel
            val nm: NotificationManager = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(
                    "next_alarm_high",
                    "Next Alarm Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Shows information about your next alarm"
                    enableVibration(true)
                    enableLights(true)
                    setShowBadge(true)
                }
                nm.createNotificationChannel(ch)
            }

            val notif: Notification = NotificationCompat.Builder(this, "next_alarm_high")
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(android.graphics.Color.parseColor("#1976D2"))
                .setContentTitle(title)
                .setContentText(content)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setOngoing(true)
                .build()

            // Use ID 2 so this replaces the normal "Next Alarm" notification
            startForeground(2, notif)

            // Revert after duration to the minimal keeper notification
            if (!reverting) {
                reverting = true
                handler.postDelayed({
                    try {
                        // Cancel the temporary pinned card and revert to base keeper
                        val nm2: NotificationManager = getSystemService(NotificationManager::class.java)
                        nm2.cancel(2)
                        val base = buildNotification()
                        startForeground(9, base)
                    } catch (_: Exception) {}
                    reverting = false
                }, duration)
            }
            return START_STICKY
        }

        // Default behavior: ensure service kept if any alarms enabled; otherwise stop.
        return try {
            val storage = AlarmStorage(createDeviceProtectedStorageContext())
            val anyEnabled = storage.getAlarms().any { it.isEnabled }
            if (!anyEnabled) {
                stopSelf()
                START_NOT_STICKY
            } else {
                START_STICKY
            }
        } catch (_: Exception) {
            START_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val nm: NotificationManager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                "keeper_channel",
                "Alarm Keeper",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps alarms armed and reliable"
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
        return Notification.Builder(this, "keeper_channel")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentTitle("Alarms armed")
            .setContentText("Keeping alarm service ready")
            .build()
    }
}
