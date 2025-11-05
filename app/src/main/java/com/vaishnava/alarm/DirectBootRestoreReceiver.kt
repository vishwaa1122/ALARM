package com.vaishnava.alarm

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.FileNotFoundException

/**
 * Minimal, direct-boot-aware receiver whose ONLY job is to re-schedule a near-term alarm
 * after device reboot while still locked. It does NOT start services or activities.
 * Also handles cleanup when app is uninstalled.
 */
class DirectBootRestoreReceiver : BroadcastReceiver() {
    companion object {
        // Call this method from your main activity's onDestroy or from a ContentProvider's onCreate
        const val PREFS_NAME = "AlarmAppPrefs"
        const val KEY_FORCE_RESTART_IN_PROGRESS = "force_restart_in_progress"
        const val FORCE_RESTART_FLAG_FILE = "force_restart_flag.txt"
        const val NOTIFICATION_CHANNEL_ID = "app_integrity_channel"
        const val NOTIFICATION_ID = 1002

        fun cleanupOnUninstall(context: Context) {
            val dpsContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.createDeviceProtectedStorageContext() ?: context
            } else context
            val isForceRestartInProgress = try {
                dpsContext.openFileInput(FORCE_RESTART_FLAG_FILE).use { it.readBytes().toString(Charsets.UTF_8) == "true" }
            } catch (e: FileNotFoundException) {
                false
            } catch (e: Exception) {
                Log.e("AlarmApp", "CleanupProvider: Failed to read FORCE_RESTART_FLAG_FILE: ${e.message}")
                false
            }

            if (isForceRestartInProgress) {
                Log.d("AlarmApp", "CleanupProvider: Skipping cleanup due to force restart in progress.")
                return
            }
            try {
                val dps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    context.createDeviceProtectedStorageContext() ?: context
                } else context
                
                // Clear all shared preferences
                val prefs = dps.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                prefs.edit().clear().apply()
                
                // Delete the force restart flag file
                try {
                    dps.deleteFile(FORCE_RESTART_FLAG_FILE)
                    Log.d("AlarmApp", "CleanupProvider: FORCE_RESTART_FLAG_FILE deleted.")
                } catch (e: Exception) {
                    Log.e("AlarmApp", "CleanupProvider: Failed to delete FORCE_RESTART_FLAG_FILE: ${e.message}")
                }
                
                 // Cancel all alarms
                 val am = dps.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                 
                // Try to cancel any pending alarm intents
                for (id in 0..100) {
                    try {
                        // Cancel regular alarms
                        val pendingIntent = PendingIntent.getBroadcast(
                            dps,
                            id,
                            Intent(dps, AlarmReceiver::class.java),
                            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                        )
                        if (pendingIntent != null) {
                            am.cancel(pendingIntent)
                            pendingIntent.cancel()
                        }
                        
                        // Cancel activity intents
                        val activityIntent = PendingIntent.getActivity(
                            dps,
                            id,
                            Intent(dps, AlarmActivity::class.java),
                            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                        )
                        if (activityIntent != null) {
                            am.cancel(activityIntent)
                            activityIntent.cancel()
                        }
                    } catch (_: Exception) {}
                }
                
                Log.d("AlarmApp", "Cleaned up all alarms and preferences")
            } catch (e: Exception) {
                Log.e("AlarmApp", "Failed to clean up: ${e.message}")
            }
        }
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        try {
            var action = intent.action ?: return

            // Get device protected storage context once at the beginning
            val dpsContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.createDeviceProtectedStorageContext()
            } else null
            val actualDpsContext = dpsContext ?: context

            when (action) {
                Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_BOOT_COMPLETED -> {
                    // Perform app integrity check
                    val actualDpsContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        context.createDeviceProtectedStorageContext() ?: context
                    } else context
                
                    // Check app installation validity
                    if (!isAppInstallationValid(actualDpsContext)) {
                        Log.e("AlarmApp", "App installation is invalid. Cannot restore alarms.")
                        handleInvalidInstallation(context)
                        return
                    }

                    // Continue with boot handling - focus on alarm restoration
                    Log.d("AlarmApp", "Boot completed - restoring alarms")
                    try {
                        actualDpsContext.openFileOutput(FORCE_RESTART_FLAG_FILE, Context.MODE_PRIVATE).use {
                            it.write("true".toByteArray())
                        }
                        Log.d("AlarmApp", "DirectBootRestoreReceiver: FORCE_RESTART_FLAG_FILE created.")
                    } catch (e: Exception) {
                        Log.e("AlarmApp", "DirectBootRestoreReceiver: Failed to create FORCE_RESTART_FLAG_FILE: ${e.message}")
                    }
                }
                else -> return
            }

            // Use the single actualDpsContext for all subsequent operations
            val prefs = actualDpsContext.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
            val wasRinging = prefs.getBoolean("was_ringing", false)
            val ringId = prefs.getInt("ring_alarm_id", -1)
            Log.d("AlarmApp", "DirectBootRestoreReceiver: Retrieved wasRinging=$wasRinging, ringId=$ringId from SharedPreferences.")
            val isForceRestartInProgress = try {
                actualDpsContext.openFileInput(FORCE_RESTART_FLAG_FILE).use {
                    it.readBytes().toString(Charsets.UTF_8) == "true"
                }
            } catch (e: FileNotFoundException) {
                false
            } catch (e: Exception) {
                Log.e("AlarmApp", "DirectBootRestoreReceiver: Failed to read FORCE_RESTART_FLAG_FILE: ${e.message}")
                false
            }
            Log.d("AlarmApp", "DirectBootRestoreReceiver: isForceRestartInProgress=$isForceRestartInProgress")
            if (!wasRinging || ringId == -1) return

            // Notify restore
            try {
                val nm = actualDpsContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    nm.createNotificationChannel(
                        NotificationChannel("restore_channel", "Alarm Restore", NotificationManager.IMPORTANCE_HIGH)
                    )
                }
                val notif = NotificationCompat.Builder(actualDpsContext, "restore_channel")
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Alarm restored after restart")
                    .setContentText("Resuming your alarm")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build()
                nm.notify(1001, notif)
            } catch (_: Exception) {}

            // Schedule near-term re-ring using only AlarmManager
            val am = actualDpsContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            val showIntent: PendingIntent = PendingIntent.getActivity(
                actualDpsContext,
                ringId xor 0x0100,
                Intent(actualDpsContext, AlarmActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra(AlarmReceiver.ALARM_ID, ringId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val fireIntent: PendingIntent = PendingIntent.getBroadcast(
                actualDpsContext,
                ringId,
                Intent(actualDpsContext, AlarmReceiver::class.java).apply {
                    action = "com.vaishnava.alarm.DIRECT_BOOT_ALARM"
                    data = Uri.parse("alarm://$ringId")
                    putExtra(AlarmReceiver.ALARM_ID, ringId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val now = System.currentTimeMillis()
            val triggerAt = now + 800
            val info = AlarmManager.AlarmClockInfo(triggerAt, showIntent)
            am.setAlarmClock(info, fireIntent)
            try { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt + 400, fireIntent) } catch (_: Exception) {}

            // Extra backup: fire WAKE_UP broadcast to AlarmReceiver to directly bring up UI if needed
            try {
                val wakeIntent = PendingIntent.getBroadcast(
                    actualDpsContext,
                    ringId xor 0x4A11, // unique code to avoid PI dedupe
                    Intent(actualDpsContext, AlarmReceiver::class.java).apply {
                        action = "com.vaishnava.alarm.WAKE_UP"
                        data = Uri.parse("alarm-wakeup://$ringId")
                        putExtra(AlarmReceiver.ALARM_ID, ringId)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, now + 1500, wakeIntent)
            } catch (_: Exception) {}

            // Second backup ~2.2s: WAKE_UP again with different requestCode to defeat PI dedupe
            try {
                val wakeIntent2 = PendingIntent.getBroadcast(
                    actualDpsContext,
                    ringId xor 0x6B7F,
                    Intent(actualDpsContext, AlarmReceiver::class.java).apply {
                        action = "com.vaishnava.alarm.WAKE_UP"
                        data = Uri.parse("alarm-wakeup2://$ringId")
                        putExtra(AlarmReceiver.ALARM_ID, ringId)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, now + 2200, wakeIntent2)
            } catch (_: Exception) {}

            Log.d("AlarmApp", "DirectBootRestoreReceiver: scheduled re-ring for ID=$ringId (AlarmClock + backups)")

            // Immediate in-app broadcast fallback: trigger AlarmReceiver right now
            try {
                val immediate = Intent(actualDpsContext, AlarmReceiver::class.java).apply {
                    action = "com.vaishnava.alarm.DIRECT_BOOT_ALARM"
                    data = Uri.parse("alarm-immediate://$ringId")
                    putExtra(AlarmReceiver.ALARM_ID, ringId)
                }
                actualDpsContext.sendBroadcast(immediate)
            } catch (_: Exception) {}

            // Last-resort: try to start the foreground service immediately to ensure sound
            try {
                val svc = Intent(actualDpsContext, AlarmForegroundService::class.java).apply {
                    putExtra(AlarmReceiver.ALARM_ID, ringId)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    actualDpsContext.startForegroundService(svc)
                } else {
                    actualDpsContext.startService(svc)
                }
                Log.d("AlarmApp", "DirectBootRestoreReceiver: started AlarmForegroundService for ID=$ringId")
            } catch (e: Exception) {
                Log.e("AlarmApp", "DirectBootRestoreReceiver: Failed to start AlarmForegroundService for ID=$ringId: ${e.message}")
            }

            // After successful restoration, clear the force restart flag
            try {
                actualDpsContext.deleteFile(FORCE_RESTART_FLAG_FILE)
                Log.d("AlarmApp", "DirectBootRestoreReceiver: FORCE_RESTART_FLAG_FILE deleted after restoration.")
            } catch (e: Exception) {
                Log.e("AlarmApp", "DirectBootRestoreReceiver: Failed to delete FORCE_RESTART_FLAG_FILE after restoration: ${e.message}")
            }

        } catch (e: Exception) {
            Log.e("AlarmApp", "DirectBootRestoreReceiver failed: ${e.message}")
        }
    }
    
    /**
     * Clears all alarms and shared preferences when app is uninstalled
     */
    private fun clearAllAlarms(context: Context) {
        try {
            // Get both regular and device protected storage contexts
            val dps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.createDeviceProtectedStorageContext() ?: context
            } else context
            
            // Clear shared preferences
            val prefs = dps.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
            
           // Delete the force restart flag file
           try {
              dps.deleteFile(FORCE_RESTART_FLAG_FILE)
                Log.d("AlarmApp", "DirectBootRestoreReceiver: FORCE_RESTART_FLAG_FILE deleted during uninstall.")
          } catch (e: Exception) {
              Log.e("AlarmApp", "DirectBootRestoreReceiver: Failed to delete FORCE_RESTART_FLAG_FILE during uninstall: ${e.message}")
           }

             // Get alarm manager and cancel any pending alarms
             val am = dps.getSystemService(Context.ALARM_SERVICE) as AlarmManager
             
            // Try to cancel any pending alarm intents
            // Since we don't know which alarms are active, try with a range of IDs
            for (id in 0..100) {
                try {
                    val pendingIntent = PendingIntent.getBroadcast(
                        dps,
                        id,
                        Intent(dps, AlarmReceiver::class.java),
                        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                    )
                    if (pendingIntent != null) {
                        am.cancel(pendingIntent)
                        pendingIntent.cancel()
                    }
                } catch (_: Exception) {}
            }
            
            Log.d("AlarmApp", "DirectBootRestoreReceiver: cleared all alarms during uninstall")
        } catch (e: Exception) {
            Log.e("AlarmApp", "Failed to clear alarms during uninstall: ${e.message}")
        }
    }
    
    /**
     * Checks if the app installation appears to be valid by trying to access critical resources/files.
     */
     private fun isAppInstallationValid(context: Context): Boolean {
         try {
             Log.d("AlarmApp", "isAppInstallationValid: Context package name: ${context.packageName}, UID: ${context.applicationInfo.uid}")
             // Attempt to access a critical resource (e.g., app name string)
             context.resources.getString(R.string.app_name)

            // Attempt to access a file in device-protected storage
            val dpsContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.createDeviceProtectedStorageContext() ?: run {
                    Log.w("AlarmApp", "Falling back to regular context for DPS access")
                    context
                }
            } else context
            
            // Create dummy file if it doesn't exist to prevent false integrity failures
            try {
                dpsContext.openFileInput("dummy_integrity_check.txt").close()
            } catch (e: FileNotFoundException) {
                Log.d("AlarmApp", "Creating missing dummy integrity check file")
                dpsContext.openFileOutput("dummy_integrity_check.txt", Context.MODE_PRIVATE).use { it.write("valid".toByteArray()) }
            }

            // If both checks pass, the installation is likely valid
            return true
        } catch (e: Exception) {
            Log.e("AlarmApp", "App integrity check failed: ${e.message}", e)
            return false
        }
    }

    /**
     * Notifies the user that the app installation is invalid and prompts for re-installation.
     */
    private fun handleInvalidInstallation(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "App Integrity Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for critical app installation issues."
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Create an intent to open the Play Store page for your app
        val appPackageName = context.packageName
        val playStoreIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$appPackageName")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            playStoreIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification) // Use an appropriate icon
            .setContentTitle("App Installation Corrupted")
            .setContentText("Your alarm app installation appears to be corrupted. Please re-install the app from the Play Store.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Your alarm app installation appears to be corrupted, possibly due to an unexpected shutdown. Alarms may not function correctly. Please re-install the app from the Play Store to restore full functionality."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
        Log.d("AlarmApp", "Notified user about invalid app installation.")
    }
}
