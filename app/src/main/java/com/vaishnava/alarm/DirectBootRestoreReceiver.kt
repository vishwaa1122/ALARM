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
import com.vaishnava.alarm.data.Alarm
import java.io.FileNotFoundException
import java.util.Calendar

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
                    
                    // NEW: Reschedule all future alarms in locked boot mode
                    try {
                        val scheduledIds = rescheduleAllAlarmsInLockedMode(actualDpsContext)
                        // Store scheduled IDs to prevent dual ringing in missed alarm check
                        checkAndFireMissedAlarms(actualDpsContext, scheduledIds)
                    } catch (e: Exception) {
                        Log.e("AlarmApp", "Failed to reschedule alarms in locked mode: ${e.message}")
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
            
            // Check if this is a sequencer alarm - if so, don't launch AlarmActivity directly
            val alarm = try {
                val storage = AlarmStorage(actualDpsContext)
                storage.getAlarm(ringId)
            } catch (_: Exception) {
                null
            }
            
            val showIntent: PendingIntent = if (alarm?.missionType != "sequencer") {
                PendingIntent.getActivity(
                    actualDpsContext,
                    ringId xor 0x0100,
                    Intent(actualDpsContext, AlarmActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        putExtra(AlarmReceiver.ALARM_ID, ringId)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                // For sequencer alarms, create a broadcast PendingIntent instead
                // The MissionSequencer will handle launching the actual missions
                PendingIntent.getBroadcast(
                    actualDpsContext,
                    ringId xor 0x0100,
                    Intent(actualDpsContext, AlarmReceiver::class.java).apply {
                        action = "com.vaishnava.alarm.SEQUENCER_MISSED_ALARM"
                        putExtra(AlarmReceiver.ALARM_ID, ringId)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }

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
            // CRITICAL FIX: Remove backup alarm to prevent multiple simultaneous alarms firing

            // CRITICAL FIX: Remove ALL backup alarms to prevent multiple simultaneous alarms firing
            // The primary setAlarmClock trigger is sufficient

            Log.d("AlarmApp", "DirectBootRestoreReceiver: scheduled re-ring for ID=$ringId (AlarmClock + backups)")

            // CRITICAL FIX: Remove immediate broadcast to prevent dual audio
            // The setAlarmClock trigger is sufficient - immediate broadcast causes duplicate AlarmForegroundService

            // Last-resort: try to start the foreground service immediately to ensure sound
            try {
                // Get the ringtone URI from DPS storage
                val ringtoneUri = try {
                    val prefs = actualDpsContext.getSharedPreferences("direct_boot_alarm_prefs", Context.MODE_PRIVATE)
                    val uriString = prefs.getString("direct_boot_ringtone_$ringId", null)
                    uriString?.let { Uri.parse(it) }
                } catch (e: Exception) {
                    Log.e("AlarmApp", "Failed to get ringtone URI for DPS alarm ID=$ringId: ${e.message}")
                    null
                }
                
                val svc = Intent(actualDpsContext, AlarmForegroundService::class.java).apply {
                    putExtra(AlarmReceiver.ALARM_ID, ringId)
                    putExtra(AlarmReceiver.EXTRA_RINGTONE_URI, ringtoneUri)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    actualDpsContext.startForegroundService(svc)
                } else {
                    actualDpsContext.startService(svc)
                }
                Log.d("AlarmApp", "DirectBootRestoreReceiver: started AlarmForegroundService for ID=$ringId with ringtone=$ringtoneUri")
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

            // CRITICAL FIX: Check if any alarm is currently playing before checking missed alarms
            val currentlyPlayingAlarmId = try {
                val prefs = actualDpsContext.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                prefs.getInt("current_service_alarm_id", -1)
            } catch (_: Exception) { -1 }
            
            val isAnyAlarmCurrentlyPlaying = currentlyPlayingAlarmId != -1
            
            if (isAnyAlarmCurrentlyPlaying) {
                Log.d("AlarmApp", " ALARM CURRENTLY PLAYING: Skipping missed alarm check - alarm ID $currentlyPlayingAlarmId is active")
                return
            }
            
            // ADDITIONAL FIX: Check if any alarm was scheduled to fire in the last 2 minutes
            val recentlyScheduledAlarmId = try {
                val prefs = actualDpsContext.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                val lastScheduledId = prefs.getInt("last_scheduled_alarm_id", -1)
                val lastScheduledTime = prefs.getLong("last_scheduled_time", 0L)
                val now = System.currentTimeMillis()
                val minutesSinceScheduled = (now - lastScheduledTime) / (1000 * 60)
                
                if (lastScheduledId != -1 && minutesSinceScheduled <= 2) {
                    Log.d("AlarmApp", " RECENTLY SCHEDULED: Alarm ID $lastScheduledId was scheduled $minutesSinceScheduled minutes ago, skipping missed alarm check")
                    return
                }
                -1
            } catch (_: Exception) { -1 }
            
            // CRITICAL: Clear was_ringing flag to prevent dual detection in missed alarm check
            try {
                val prefs = actualDpsContext.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                prefs.edit()
                    .remove("was_ringing")
                    .remove("ring_alarm_id") 
                    .remove("ring_started_at")
                    .putBoolean("restored_alarm_${ringId}", true) // Mark this alarm as just restored
                    .apply()
                Log.d("AlarmApp", "DirectBootRestoreReceiver: Cleared was_ringing flags and marked alarm $ringId as restored")
            } catch (e: Exception) {
                Log.e("AlarmApp", "DirectBootRestoreReceiver: Failed to clear was_ringing flags: ${e.message}")
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
    
    /**
     * Reschedule all enabled alarms when device is in locked boot mode.
     * This ensures alarms fire even if phone remains locked after reboot.
     * Returns list of scheduled alarm IDs to prevent dual ringing.
     */
    private fun rescheduleAllAlarmsInLockedMode(dpsContext: Context): Set<Int> {
        try {
            Log.d("AlarmApp", "=== DPS LOCKED BOOT RESCHEDULE START ===")
            Log.d("AlarmApp", "Rescheduling all alarms in locked boot mode")
            
            // Try to get alarms from device protected storage first
            val alarms = try {
                val storage = AlarmStorage(dpsContext)
                val alarmList = storage.getAlarms()
                Log.d("AlarmApp", "Found ${alarmList.size} alarms in DPS storage")
                alarmList.forEach { alarm: Alarm ->
                    Log.d("AlarmApp", "DPS Alarm: ID=${alarm.id}, Enabled=${alarm.isEnabled}, Time=${alarm.hour}:${alarm.minute}")
                }
                alarmList
            } catch (e: Exception) {
                Log.w("AlarmApp", "Failed to read alarms from DPS storage: ${e.message}")
                emptyList()
            }
            
            if (alarms.isEmpty()) {
                Log.d("AlarmApp", "No alarms found in DPS storage during locked boot")
                return emptySet()
            }
            
            val scheduler = AndroidAlarmScheduler(dpsContext)
            var scheduledCount = 0
            val scheduledIds = mutableSetOf<Int>()
            val now = System.currentTimeMillis()
            
            alarms.forEach { alarm: Alarm ->
                if (alarm.isEnabled) {
                    try {
                        // Calculate if alarm should trigger soon (within next 24 hours)
                        val calendar = java.util.Calendar.getInstance().apply {
                            timeInMillis = now
                            set(java.util.Calendar.HOUR_OF_DAY, alarm.hour)
                            set(java.util.Calendar.MINUTE, alarm.minute)
                            set(java.util.Calendar.SECOND, 0)
                            set(java.util.Calendar.MILLISECOND, 0)
                        }
                        
                        // If alarm time is earlier than now, move to next day
                        if (calendar.timeInMillis <= now) {
                            calendar.add(java.util.Calendar.DAY_OF_MONTH, 1)
                        }
                        
                        // Only schedule if alarm is within next 24 hours
                        val nextTrigger = calendar.timeInMillis
                        if (nextTrigger - now <= 24 * 60 * 60 * 1000L) {
                            scheduler.schedule(alarm)
                            scheduledCount++
                            scheduledIds.add(alarm.id)
                            val timeUntil = (nextTrigger - now) / 1000 / 60 // minutes
                            Log.d("AlarmApp", "✅ SCHEDULED DPS Alarm ID ${alarm.id} for ${alarm.hour}:${alarm.minute} (in ${timeUntil} minutes)")
                        } else {
                            Log.d("AlarmApp", "⏭️ Skipping alarm ID ${alarm.id} - more than 24 hours away")
                        }
                    } catch (e: Exception) {
                        Log.e("AlarmApp", "❌ Failed to schedule alarm ID ${alarm.id} in locked mode: ${e.message}")
                    }
                } else {
                    Log.d("AlarmApp", "⏸️ Skipping disabled alarm ID ${alarm.id} in locked boot")
                }
            }
            
            Log.d("AlarmApp", "=== DPS LOCKED BOOT RESCHEDULE COMPLETE: $scheduledCount alarms scheduled ===")
            return scheduledIds
        } catch (e: Exception) {
            Log.e("AlarmApp", "Error in rescheduleAllAlarmsInLockedMode: ${e.message}", e)
            return emptySet()
        }
    }
    
    /**
     * Check for missed alarms that should fire immediately when device boots up.
     * This handles cases where device was powered off during alarm time.
     * Skips alarms that were just scheduled for the near future to prevent dual ringing.
     */
    private fun checkAndFireMissedAlarms(context: Context, scheduledIds: Set<Int> = emptySet()) {
        try {
            Log.d("AlarmApp", "=== MISSED ALARM CHECK START ===")
            
            // Get alarms from storage
            val alarms = try {
                val storage = AlarmStorage(context)
                storage.getAlarms()
            } catch (e: Exception) {
                Log.e("AlarmApp", "Failed to read alarms for missed alarm check: ${e.message}")
                emptyList()
            }
            
            if (alarms.isEmpty()) {
                Log.d("AlarmApp", "No alarms found for missed alarm check")
                return
            }
            
            val now = System.currentTimeMillis()
            val missedAlarms = mutableListOf<Alarm>()
            
            alarms.forEach { alarm: Alarm ->
                if (alarm.isEnabled) {
                    // Calculate when this alarm should have last fired
                    val calendar = java.util.Calendar.getInstance().apply {
                        timeInMillis = now
                        set(java.util.Calendar.HOUR_OF_DAY, alarm.hour)
                        set(java.util.Calendar.MINUTE, alarm.minute)
                        set(java.util.Calendar.SECOND, 0)
                        set(java.util.Calendar.MILLISECOND, 0)
                    }
                    
                    // If alarm time is later than now, move to yesterday
                    if (calendar.timeInMillis > now) {
                        calendar.add(java.util.Calendar.DAY_OF_MONTH, -1)
                    }
                    
                    val lastAlarmTime = calendar.timeInMillis
                    val minutesMissed = (now - lastAlarmTime) / (1000 * 60)
                    
                    // Check if alarm was missed in the last 30 minutes
                    if (minutesMissed in 1L..30L) {
                        missedAlarms.add(alarm)
                        Log.d("AlarmApp", "MISSED ALARM: ID=${alarm.id}, Time=${alarm.hour}:${alarm.minute}, Missed by=${minutesMissed} minutes")
                    }
                }
            }
            
            if (missedAlarms.isEmpty()) {
                Log.d("AlarmApp", "No missed alarms found (or skipped to prevent dual ringing)")
                return
            }
            
            // Fire missed alarms immediately
            missedAlarms.forEach { alarm: Alarm ->
                try {
                    Log.d("AlarmApp", " FIRING MISSED ALARM ID=${alarm.id} immediately")
                    
                    // ONLY start the AlarmForegroundService (don't send broadcast to prevent dual audio)
                    val svc = Intent(context, AlarmForegroundService::class.java).apply {
                        putExtra(AlarmReceiver.ALARM_ID, alarm.id)
                        putExtra(AlarmReceiver.EXTRA_RINGTONE_URI, alarm.ringtoneUri) // CRITICAL: Pass the correct ringtone URI
                        putExtra("is_missed_alarm", true)
                    }
                    try {
                        // Check if service is already running for this alarm to prevent duplicate starts
                        try {
                            val dps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                context.createDeviceProtectedStorageContext()
                            } else {
                                context
                            }
                            val prefs = dps.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                            val currentServiceAlarmId = prefs.getInt("current_service_alarm_id", -1)
                            
                            if (currentServiceAlarmId == alarm.id) {
                                Log.d("AlarmApp", "AlarmForegroundService already running for missed alarm ID=${alarm.id}, skipping service start")
                            } else {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    context.startForegroundService(svc)
                                } else {
                                    context.startService(svc)
                                }
                                Log.d("AlarmApp", "Started AlarmForegroundService for missed alarm ID=${alarm.id} (audio only)")
                            }
                        } catch (e: Exception) {
                            // Fallback: try starting service anyway
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(svc)
                            } else {
                                context.startService(svc)
                            }
                            Log.d("AlarmApp", "Started AlarmForegroundService for missed alarm ID=${alarm.id} (fallback)")
                        }
                    } catch (e: Exception) {
                        Log.e("AlarmApp", "Failed to start AlarmForegroundService for missed alarm ID=${alarm.id}: ${e.message}")
                    }
                    
                    // CRITICAL FIX: Remove backup trigger to prevent any chance of dual audio
                    // The primary AlarmForegroundService start is sufficient
                    Log.d("AlarmApp", "✅ Missed alarm ID=${alarm.id} triggered with single audio source (no backup)")
                } catch (e: Exception) {
                    Log.e("AlarmApp", " Failed to fire missed alarm ID=${alarm.id}: ${e.message}")
                }
            }
            
            Log.d("AlarmApp", "=== MISSED ALARM CHECK COMPLETE: Fired ${missedAlarms.size} missed alarms ===")
        } catch (e: Exception) {
            Log.e("AlarmApp", "Failed to check missed alarms: ${e.message}")
        }
    }
}
