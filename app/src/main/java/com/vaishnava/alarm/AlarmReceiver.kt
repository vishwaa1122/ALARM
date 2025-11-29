package com.vaishnava.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.vaishnava.alarm.data.Alarm
import com.vaishnava.alarm.Constants
import java.util.concurrent.Executors
import com.vaishnava.alarm.sequencer.MissionLogger
import com.vaishnava.alarm.MainActivity
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "AlarmReceiver"
        const val ALARM_ID = "alarm_id"
        const val EXTRA_RINGTONE_URI = "extra_ringtone_uri"
        const val EXTRA_REPEAT_DAYS = "extra_repeat_days"
        
        private fun writeExternalLog(context: Context, message: String) {
            try {
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val logFile = java.io.File(context.filesDir, "alarm_debug.log")
                val writer = java.io.FileWriter(logFile.absolutePath, true)
                writer.append("[$timestamp] [RECEIVER] $message\n")
                writer.close()
            } catch (e: Exception) {
                // Silently fail if we can't write to storage
            }
        }
    }

    private val bg = Executors.newSingleThreadExecutor()

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) {
            Log.w(TAG, "onReceive: null intent")
            return
        }

        val action = intent.action ?: ""
        // Forensic logging of inbound intent
        try {
            val extrasKeys = intent.extras?.keySet()?.joinToString()
            Log.d(
                TAG,
                "onReceive: action=$action data=${intent.data} extrasKeys=$extrasKeys requestCode_hint=${intent.getIntExtra(ALARM_ID, -1)}"
            )
        } catch (_: Exception) { }

        // Get alarmId early for use in action handlers
        val alarmId = try {
            intent.getIntExtra(ALARM_ID, -1)
        } catch (e: Exception) {
            -1
        }

        // Handle notification actions
        when (action) {
            "com.vaishnava.alarm.NOTIFICATION_DISMISSED" -> {
                if (alarmId >= 0) {
                    // Forward to NotificationRepostReceiver to handle reposting
                    Log.d(TAG, "Forwarding notification dismiss to NotificationRepostReceiver for alarm $alarmId")
                    val repostIntent = Intent(Constants.ACTION_NOTIFICATION_DISMISSED).apply {
                        putExtra("ALARM_ID", alarmId)
                        setClass(context, NotificationRepostReceiver::class.java)
                    }
                    context.sendBroadcast(repostIntent)
                }
                return
            }
        }

        // allow multiple action names used in app
        if (action.isNotEmpty() && !action.contains("DIRECT_BOOT") && !action.contains("ALARM_FIRE") && !action.contains("com.vaishnava.alarm")) {
            // not our alarm action; still support defensive behavior
        }

        // Global top-level guard: if this alarm's wake-up-check flow has been
        // finalized (user pressed "I'm awake") very recently, ignore any
        // further *wake-check WAKE_UP* broadcasts for this alarmId. Normal
        // DIRECT_BOOT_ALARM / primary alarm fires must still be allowed so
        // that future cycles can ring again.
        if (alarmId >= 0) {
            val isWakeCheckWakeUp = (action == "com.vaishnava.alarm.WAKE_UP" &&
                    (intent.data?.scheme == "alarm-wakecheck"))
            if (isWakeCheckWakeUp) {
                try {
                    val dpsContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                        context.createDeviceProtectedStorageContext()
                    } else {
                        context
                    }
                    val prefs = dpsContext.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                    val finalized = prefs.getBoolean("wakecheck_finalized_$alarmId", false)
                    if (finalized) {
                        val ackTs = prefs.getLong("wakecheck_ack_ts_$alarmId", 0L)
                        if (ackTs > 0L) {
                            val now = System.currentTimeMillis()
                            if (now - ackTs < 10 * 60_000L) {
                                android.util.Log.d(
                                    "WakeCheckDebug",
                                    "AlarmReceiver: top-level suppression for alarmId=$alarmId because wakecheck_finalized is true and ack is recent (wake-check WAKE_UP)"
                                )
                                return
                            }
                        }
                    }
                } catch (_: Exception) { }
            }
        }

        // Only treat WAKE_UP with the dedicated alarm-wakecheck:// scheme as a true wake-up-check follow-up.
        // Boot/restore backups also use WAKE_UP but with alarm-wakeup:// / alarm-wakeup2://, and must behave as normal alarm fires.
        val isWakeUpFollowUp = action == "com.vaishnava.alarm.WAKE_UP" &&
                (intent.data?.scheme == "alarm-wakecheck")

        // If this is a wake-up-check follow-up but the user has already pressed
        // "I'm awake" very recently, ignore this event entirely. This protects
        // against races where the OS delivers a WAKE_UP broadcast even after
        // we cancelled the PendingIntent in acknowledgeWakeCheck().
        if (isWakeUpFollowUp && alarmId >= 0) {
            try {
                val dpsContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    context.createDeviceProtectedStorageContext()
                } else {
                    context
                }
                val prefs = dpsContext.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                val ackTs = prefs.getLong("wakecheck_ack_ts_$alarmId", 0L)
                if (ackTs > 0L) {
                    val now = System.currentTimeMillis()
                    if (now - ackTs < 10 * 60_000L) {
                        android.util.Log.d(
                            "WakeCheckDebug",
                            "AlarmReceiver: ignoring WAKE_UP wakecheck follow-up for alarmId=$alarmId because 'I'm awake' was acknowledged recently"
                        )
                        return
                    }
                }
            } catch (_: Exception) { }
        }

        // If this alarm was just dismissed very recently, or if a wake-up-check gate is currently active,
        // ignore *immediate* normal re-fires so that duplicate alarm rings do not overlap with wake-up-check UI.
        // Do not suppress true wake-up-check follow-ups (alarm-wakecheck://), since those are part of the intended flow.
        // Critically: never suppress the primary DIRECT_BOOT_ALARM action even if gateActive is stuck true.
        if (!isWakeUpFollowUp && alarmId >= 0) {
            try {
                val dpsContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    context.createDeviceProtectedStorageContext()
                } else {
                    context
                }
                val prefs = dpsContext.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                val lastDismiss = prefs.getLong("dismiss_ts_$alarmId", 0L)
                val gateActive = prefs.getBoolean("wakecheck_gate_active_$alarmId", false)

                val now = System.currentTimeMillis()
                if (lastDismiss > 0L && now - lastDismiss < 10_000L) {
                    // Short suppression (~10s) only to collapse duplicate immediate deliveries
                    android.util.Log.d(
                        "WakeCheckDebug",
                        "AlarmReceiver: suppressing normal re-fire for alarmId=$alarmId action=$action due to very recent dismiss (within 10s)"
                    )
                    return
                }
                if (gateActive && action != "com.vaishnava.alarm.DIRECT_BOOT_ALARM") {
                    android.util.Log.d(
                        "WakeCheckDebug",
                        "AlarmReceiver: suppressing normal re-fire for alarmId=$alarmId action=$action because wake-check gate is active"
                    )
                    return
                }
            } catch (_: Exception) {}
        }

        // Try to retrieve persisted alarm from AlarmStorage (if present)
        val storageAlarm: Alarm? = try {
            val storage = AlarmStorage(context)
            storage.getAlarm(alarmId)
        } catch (e: Exception) {
            Log.w(TAG, "AlarmStorage lookup failed: ${e.message}")
            null
        }

        // fallback: build from intent extras
        val fallbackAlarm: Alarm? = try {
            val hour = intent.getIntExtra("hour", -1)
            val minute = intent.getIntExtra("minute", -1)
            val ringtone: Uri? = try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RINGTONE_URI, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RINGTONE_URI)
                }
            } catch (_: Exception) { null }
            val repeatDaysArray = intent.getIntArrayExtra(EXTRA_REPEAT_DAYS)
            val daysList = repeatDaysArray?.toList()
            val repeatDaily = intent.getBooleanExtra("repeatDaily", false)
            if (alarmId >= 0 && hour >= 0 && minute >= 0) {
                Alarm(id = alarmId, hour = hour, minute = minute, days = daysList, isEnabled = true, ringtoneUri = ringtone, repeatDaily = repeatDaily)
            } else null
        } catch (e: Exception) {
            null
        }

        val alarm = storageAlarm ?: fallbackAlarm
        if (alarm == null) {
            Log.w(TAG, "onReceive: No alarm data available for id=$alarmId")
            return
        }

        try {
            Log.d(
                TAG,
                "onReceive: RESOLVED_ALARM id=${alarm.id} time=${alarm.hour}:${alarm.minute} days=${alarm.days} repeatDaily=${alarm.repeatDaily} ringtone=${alarm.ringtoneUri} wakeCheck=${alarm.wakeCheckEnabled}"
            )
        } catch (_: Exception) { }

        // Unified log to in-app overlay
        try {
            MissionLogger.log(
                "Receiver id=${alarm.id} action=$action scheme=${intent.data?.scheme ?: ""} protected=${storageAlarm?.isProtected == true} mission=${storageAlarm?.missionType ?: ""} wakeCheck=${storageAlarm?.wakeCheckEnabled == true}"
            )
        } catch (_: Exception) { }

        // 1) Start foreground service to manage alarm lifecycle
        try {
            val svc = Intent(context, AlarmForegroundService::class.java).apply {
                putExtra(ALARM_ID, alarm.id)
                putExtra(EXTRA_RINGTONE_URI, alarm.ringtoneUri)
                putExtra(EXTRA_REPEAT_DAYS, alarm.days?.toIntArray())
                if (isWakeUpFollowUp) {
                    putExtra("from_wake_check", true)
                }
            }
            try {
                context.startForegroundService(svc)
            } catch (e: Exception) {
                context.startService(svc)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AlarmForegroundService: ${e.message}", e)
        }
        
        // 1a) Start UI Activity immediately for protected alarms to bypass background restrictions
        if (alarm.isProtected == true && !isWakeUpFollowUp) {
            try {
                Log.i("LLM-DBG","UI_START_ATTEMPT target=AlarmActivity flags=NEW_TASK|CLEAR_TOP from=AlarmReceiver")
                val act = Intent(context, AlarmActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    )
                    putExtra(ALARM_ID, alarm.id)
                    putExtra(EXTRA_RINGTONE_URI, alarm.ringtoneUri)
                    putExtra(EXTRA_REPEAT_DAYS, alarm.days?.toIntArray())
                }
                context.startActivity(act)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start AlarmActivity for protected alarm: ${e.message}", e)
            }
        }
        
        // Start sequencer missions when alarm fires (not wake-up follow-ups)
        if (!isWakeUpFollowUp) {
            try {
                MainActivity.getInstance()?.missionSequencer?.startWhenAlarmFires()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start mission sequencer: ${e.message}", e)
            }
        }

        // 1b) For wake-up-check follow-ups, also start AlarmActivity directly so the "I'm awake" UI always shows
        if (isWakeUpFollowUp) {
            try {
                val act = Intent(context, AlarmActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT or
                                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    )
                    putExtra(ALARM_ID, alarm.id)
                    putExtra("from_wake_check", true)
                    // Ensure ringtone Uri is available so UI can always resolve name
                    putExtra(EXTRA_RINGTONE_URI, alarm.ringtoneUri)
                }
                context.startActivity(act)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start AlarmActivity for wake-up-check follow-up: ${e.message}", e)
            }
        }

        // 2) If alarm repeats (repeatDaily || days non-empty) schedule next occurrence immediately.
        // NOTE: Only the *primary* alarm fire (DIRECT_BOOT / normal) should reschedule the next
        // day. Wake-up-check follow-ups (alarm-wakecheck:// scheme) must NEVER reschedule the
        // base alarm, otherwise a single day can result in multiple next-day entries.
        val shouldRepeat = alarm.repeatDaily || (alarm.days != null && alarm.days.isNotEmpty())
        if (shouldRepeat && !isWakeUpFollowUp) {
            bg.execute {
                try {
                    val scheduler = AndroidAlarmScheduler(context)
                    scheduler.schedule(alarm) // schedule() cancels existing and schedules next occurrence
                    Log.d(TAG, "Rescheduled next occurrence for alarm ${alarm.id}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to reschedule next occurrence for alarm ${alarm.id}: ${e.message}", e)
                }
            }
        } else {
            // One-shot: if wake-up check is enabled, keep enabled until user explicitly acknowledges
            if (alarm.wakeCheckEnabled) {
                Log.d(TAG, "One-shot alarm ${alarm.id} has wakeCheckEnabled=true; skipping auto-disable")
            } else {
                // One-shot without wake-check: mark disabled as before
                try {
                    val storage = AlarmStorage(context)
                    try {
                        storage.disableAlarm(alarm.id)
                        Log.d(TAG, "Marked one-shot alarm ${alarm.id} disabled in storage")
                    } catch (e: Exception) {
                        try {
                            storage.updateAlarm(alarm.copy(isEnabled = false))
                            Log.d(TAG, "Updated one-shot alarm ${alarm.id} disabled via updateAlarm()")
                        } catch (ex: Exception) {
                            Log.w(TAG, "Could not mark one-shot disabled in storage: ${ex.message}")
                        }
                    }
                } catch (_: Exception) {
                    // storage not available – safe to ignore
                }
            }
        }
    }

    // helper
    private fun List<Int>?.toIntArray(): IntArray? = this?.let { IntArray(it.size) { i -> it[i] } }
}
