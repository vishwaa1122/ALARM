package com.vaishnava.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Lightweight foreground "keeper" service to anchor the process so Boot/Alarm flows
 * remain reliable on aggressive OEMs (e.g., MIUI on Redmi 13C).
 * Starts when there are enabled alarms; stops itself when none remain.
 */
class KeeperService : Service() {

    override fun onCreate() {
        super.onCreate()
        startForeground(9, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // If there are no enabled alarms, stop self.
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
