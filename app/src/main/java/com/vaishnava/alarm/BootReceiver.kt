package com.vaishnava.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.Calendar

class BootReceiver : BroadcastReceiver() {
    private var isFirstRunAfterUpdate = false

    private fun isFirstRunAfterUpdate(context: Context): Boolean {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val currentVersionCode = context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        val savedVersionCode = prefs.getInt("version_code", -1)
        
        if (currentVersionCode != savedVersionCode) {
            // Update the saved version code
            prefs.edit().putInt("version_code", currentVersionCode).apply()
            return true
        }
        return false
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        Log.d(
            "AlarmApp",
            "BootReceiver onReceive triggered. Current time: ${Calendar.getInstance().time}. Intent action: ${intent?.action}"
        )

        if (intent == null || context == null) return

        var action = intent.action
        val isRelevantAction =
            action == Intent.ACTION_BOOT_COMPLETED ||
                    action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
                    action == Intent.ACTION_USER_UNLOCKED ||
                    action == Intent.ACTION_PACKAGE_FULLY_REMOVED

        if (action == Intent.ACTION_PACKAGE_FULLY_REMOVED) {
            val packageName = intent.data?.schemeSpecificPart
            if (packageName == context.packageName) {
                Log.d("AlarmApp", "BootReceiver: App fully removed, performing cleanup.")
                DirectBootRestoreReceiver.cleanupOnUninstall(context)
            }
            return
        }

        if (!isRelevantAction) {
            Log.d("AlarmApp", "BootReceiver received unexpected intent action: ${intent.action}")
            return
        }

        Log.d("AlarmApp", "Boot/UserUnlocked event, handling reschedule and potential active restore")

        // Context (non-null local)
        val ctx: Context = requireNotNull(context)
        
        // Check if this is the first run after an update
        isFirstRunAfterUpdate = isFirstRunAfterUpdate(ctx)
        if (isFirstRunAfterUpdate) {
            Log.d("AlarmApp", "First run after app update. Will not restore alarms.")
            return
        }

        // Compute DPS context immutably (no reassign)
        val deviceProtectedContext: Context =
            if (!ctx.isDeviceProtectedStorage) {
                Log.d(
                    "AlarmApp",
                    "BootReceiver: Context is NOT device protected storage aware. Moving to device protected storage context."
                )
                ctx.createDeviceProtectedStorageContext()
            } else {
                Log.d("AlarmApp", "BootReceiver: Context is device protected storage aware.")
                ctx
            }

        // If we are in USER_UNLOCKED, migrate any alarms from credential-protected storage
        if (action == Intent.ACTION_USER_UNLOCKED) {
            try {
                val normalPrefs = ctx.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)
                val json = normalPrefs.getString("alarms", null)
                if (!json.isNullOrEmpty()) {
                    val dpsPrefs = deviceProtectedContext.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)
                    dpsPrefs.edit().putString("alarms", json).apply()
                    Log.d(
                        "AlarmApp",
                        "Migrated alarms from credential-protected to device-protected storage"
                    )
                } else {
                    Log.d("AlarmApp", "No alarms found in credential-protected storage to migrate")
                }
            } catch (e: Exception) {
                Log.e("AlarmApp", "Migration on USER_UNLOCKED failed: ${e.message}")
            }
        }

        val alarmStorage = AlarmStorage(deviceProtectedContext)
        val alarmScheduler = AndroidAlarmScheduler(deviceProtectedContext)

        Log.d("AlarmApp", "BootReceiver: Attempting to get alarms from storage.")
        val alarms = try {
            alarmStorage.getAlarms()
        } catch (e: Exception) {
            Log.e("AlarmApp", "Failed to read alarms from storage: ${e.message}")
            emptyList()
        }

        Log.d("AlarmApp", "BootReceiver: Found ${alarms.size} alarms to re-schedule.")
        if (alarms.isEmpty()) {
            Log.d("AlarmApp", "BootReceiver: No alarms found in storage to re-schedule.")
        }

        alarms.forEach { alarm ->
            if (alarm.isEnabled) {
                Log.d(
                    "AlarmApp",
                    "BootReceiver: Scheduling alarm with ID: ${alarm.id} for ${alarm.hour}:${alarm.minute}"
                )
                Log.d(
                    "AlarmApp",
                    "BootReceiver: Scheduling alarm ID: ${alarm.id} at ${alarm.hour}:${alarm.minute}"
                )
                try {
                    alarmScheduler.schedule(alarm)
                    Log.d(
                        "AlarmApp",
                        "BootReceiver: Re-scheduled alarm with ID: ${alarm.id} for ${alarm.hour}:${alarm.minute}"
                    )
                    Log.d("AlarmApp", "✅ BootReceiver successfully scheduled alarm ID: ${alarm.id}")
                } catch (e: Exception) {
                    Log.e(
                        "AlarmApp",
                        "❌ Failed to schedule alarm ID: ${alarm.id}: ${e.message}"
                    )
                }
            } else {
                Log.d("AlarmApp", "BootReceiver: Skipping disabled alarm with ID: ${alarm.id}")
                Log.d("AlarmApp", "⚠️ BootReceiver skipping disabled alarm ID: ${alarm.id}")
            }
        }

        // ANDROID 15 FIX: Do NOT start foreground services from boot receiver
        // KeeperService will be started when alarms actually fire, not from boot
        Log.d("AlarmApp", "BootReceiver: Skipping foreground service start (Android 15+ restriction)")

        // If an alarm was actively ringing at the time of reboot, restore it now (even before unlock)
        try {
            val prefs = deviceProtectedContext.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
            val wasRinging = prefs.getBoolean("was_ringing", false)
            val ringId = prefs.getInt("ring_alarm_id", -1)

            if (wasRinging && ringId != -1) {
                // Post restore notification
                try {
                    val nm = deviceProtectedContext.getSystemService(android.app.NotificationManager::class.java)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        val ch = android.app.NotificationChannel(
                            "restore_channel",
                            "Alarm Restore",
                            android.app.NotificationManager.IMPORTANCE_HIGH
                        )
                        nm.createNotificationChannel(ch)
                    }
                    val notif = androidx.core.app.NotificationCompat.Builder(
                        deviceProtectedContext,
                        "restore_channel"
                    )
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle("Alarm restored after restart")
                        .setContentText("Resuming your alarm")
                        .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                        .build()
                    nm.notify(1001, notif)
                } catch (_: Exception) {
                    // ignore
                }

                // Re-ring via setAlarmClock(now + 1000)
                try {
                    val am = deviceProtectedContext.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                    val showIntent = android.app.PendingIntent.getActivity(
                        deviceProtectedContext,
                        ringId xor 0x0100,
                        Intent(deviceProtectedContext, AlarmActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            putExtra(AlarmReceiver.ALARM_ID, ringId)
                        },
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                    )
                    val fireIntent = android.app.PendingIntent.getBroadcast(
                        deviceProtectedContext,
                        ringId,
                        Intent(deviceProtectedContext, AlarmReceiver::class.java).apply {
                            action = "com.vaishnava.alarm.DIRECT_BOOT_ALARM"
                            data = android.net.Uri.parse("alarm://$ringId")
                            putExtra(AlarmReceiver.ALARM_ID, ringId)
                        },
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                    )
                    val nowTs = System.currentTimeMillis()
                    val triggerAt = nowTs + 1000
                    val info = android.app.AlarmManager.AlarmClockInfo(triggerAt, showIntent)
                    am.setAlarmClock(info, fireIntent)
                    Log.d("AlarmApp", "Re-ring scheduled via setAlarmClock in ~1s for ID: $ringId")
                    // Also schedule a silent backup in case AlarmClock path is throttled
                    try {
                        am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAt + 500, fireIntent)
                        Log.d("AlarmApp", "Backup setExactAndAllowWhileIdle scheduled for ID: $ringId")
                    } catch (_: Exception) {
                        // ignore
                    }

                    // Extra backup: WAKE_UP broadcast at ~1.5s with unique requestCode
                    try {
                        val wakeIntent = android.app.PendingIntent.getBroadcast(
                            deviceProtectedContext,
                            ringId xor 0x4A11,
                            Intent(deviceProtectedContext, AlarmReceiver::class.java).apply {
                                action = "com.vaishnava.alarm.WAKE_UP"
                                data = android.net.Uri.parse("alarm-wakeup://$ringId")
                                putExtra(AlarmReceiver.ALARM_ID, ringId)
                            },
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                        )
                        am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, nowTs + 1500, wakeIntent)
                    } catch (_: Exception) { }

                    // Immediate in-app broadcast fallback to trigger AlarmReceiver right now
                    try {
                        val immediate = Intent(deviceProtectedContext, AlarmReceiver::class.java).apply {
                            action = "com.vaishnava.alarm.DIRECT_BOOT_ALARM"
                            data = android.net.Uri.parse("alarm-immediate://$ringId")
                            putExtra(AlarmReceiver.ALARM_ID, ringId)
                        }
                        deviceProtectedContext.sendBroadcast(immediate)
                    } catch (_: Exception) { }
                } catch (e: Exception) {
                    // Fallback path rebuilds its own AlarmManager and PendingIntent
                    try {
                        val am2 = deviceProtectedContext.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                        val fireIntent2 = android.app.PendingIntent.getBroadcast(
                            deviceProtectedContext,
                            ringId,
                            Intent(deviceProtectedContext, AlarmReceiver::class.java).apply {
                                action = "com.vaishnava.alarm.DIRECT_BOOT_ALARM"
                                data = android.net.Uri.parse("alarm://$ringId")
                                putExtra(AlarmReceiver.ALARM_ID, ringId)
                            },
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                        )
                        val triggerAt2 = System.currentTimeMillis() + 1500
                        am2.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAt2, fireIntent2)
                        Log.d("AlarmApp", "Fallback setExactAndAllowWhileIdle scheduled for ID: $ringId")
                    } catch (_: Exception) {
                        try {
                            val am3 = deviceProtectedContext.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                            val fireIntent3 = android.app.PendingIntent.getBroadcast(
                                deviceProtectedContext,
                                ringId,
                                Intent(deviceProtectedContext, AlarmReceiver::class.java).apply {
                                    action = "com.vaishnava.alarm.DIRECT_BOOT_ALARM"
                                    data = android.net.Uri.parse("alarm://$ringId")
                                    putExtra(AlarmReceiver.ALARM_ID, ringId)
                                },
                                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                            )
                            val triggerAt3 = System.currentTimeMillis() + 2000
                            am3.set(android.app.AlarmManager.RTC_WAKEUP, triggerAt3, fireIntent3)
                        } catch (_: Exception) {
                            // ignore
                        }
                    }
                }
            }

            // Add trailing statement so the prior 'if' is not the last expression of this try
            Log.d("AlarmApp", "BootReceiver: restore check complete")
        } catch (e: Exception) {
            Log.e("AlarmApp", "Failed to restore active alarm on boot: ${e.message}")
        }
    }
}
