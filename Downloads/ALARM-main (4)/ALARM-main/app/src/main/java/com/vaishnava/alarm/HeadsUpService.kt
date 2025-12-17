package com.vaishnava.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * Short-lived foreground service to pin a high-importance notification near the top
 * of the shade for a few seconds, improving visibility on OEM skins (e.g., MIUI).
 */
class HeadsUpService : Service() {
    companion object {
        private const val CHANNEL_ID = "next_alarm_high"
        private const val NOTIF_ID = 9003
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_CONTENT = "extra_content"
        private const val EXTRA_DURATION_MS = "extra_duration"

        fun start(context: Context, title: String, content: String, durationMs: Long = 12_000L) {
            val i = Intent(context, HeadsUpService::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_CONTENT, content)
                putExtra(EXTRA_DURATION_MS, durationMs)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }
    }

    private val stopHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Next Alarm"
        val content = intent?.getStringExtra(EXTRA_CONTENT) ?: ""
        val duration = intent?.getLongExtra(EXTRA_DURATION_MS, 12_000L) ?: 12_000L

        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
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

        val fsIntent = PendingIntent.getActivity(
            this,
            9002,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("from_next_alarm_notice", true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(android.graphics.Color.parseColor("#1976D2"))
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setFullScreenIntent(fsIntent, false)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()

        startForeground(NOTIF_ID, notif)

        stopHandler.postDelayed({
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Exception) {}
            stopSelf()
        }, duration.coerceIn(3_000L, 20_000L))

        return START_NOT_STICKY
    }
}
