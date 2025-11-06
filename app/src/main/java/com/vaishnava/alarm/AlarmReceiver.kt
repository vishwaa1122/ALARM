package com.vaishnava.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import java.util.Calendar

class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ALARM_ID = "ALARM_ID"
        const val EXTRA_RINGTONE_URI = "extra_ringtone_uri"
        const val EXTRA_REPEAT_DAYS = "extra_repeat_days"
        private const val ALARM_ACTION = "com.vaishnava.alarm.DIRECT_BOOT_ALARM"
        private const val WAKE_UP_ACTION = "com.vaishnava.alarm.WAKE_UP"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.alarm calls
        
        if (context == null || intent == null) {
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.e call
            return
        }
        
        // Process alarm intents - strict action checking
        // Only accept the specific alarm action or boot completed action
        val isValidAlarmAction = intent.action == ALARM_ACTION || 
                                intent.action == "android.intent.action.BOOT_COMPLETED" ||
                                intent.action == WAKE_UP_ACTION
        
        if (!isValidAlarmAction) {
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.d call
            return
        }
        
        // Handle wake-up action specifically
        if (intent.action == WAKE_UP_ACTION) {
            // Launch the alarm activity directly
            val alarmId = intent.getIntExtra(ALARM_ID, -1)
            if (alarmId != -1) {
                val activityIntent = Intent(context, AlarmActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or 
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or 
                            Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(ALARM_ID, alarmId)
                }
                try {
                    context.startActivity(activityIntent)
                } catch (e: Exception) {
                    // If we can't start the activity, at least make a noise
                    try {
                        val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                        val ringtone = RingtoneManager.getRingtone(context, ringtoneUri)
                        ringtone.isLooping = true
                        ringtone.play()
                        
                        // Stop the ringtone after 30 seconds to prevent battery drain
                        Handler(Looper.getMainLooper()).postDelayed({
                            try {
                                if (ringtone.isPlaying) {
                                    ringtone.stop()
                                }
                            } catch (e: Exception) {
                                // Ignore errors when stopping
                            }
                        }, 30000)
                    } catch (e2: Exception) {
                        // If we can't play a ringtone, at least vibrate
                        try {
                            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 500), 0))
                            } else {
                                @Suppress("DEPRECATION")
                                vibrator.vibrate(longArrayOf(0, 500, 500), 0)
                            }
                        } catch (e3: Exception) {
                            // If we can't vibrate, we've done our best
                        }
                    }
                }
            }
            return
        }
        
        // Acquire a wake lock to ensure we have time to process the alarm
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AlarmApp::AlarmReceiver"
        )
        wakeLock.acquire(60 * 1000L) // Hold for 60 seconds
        
        try {
            // Debug the intent extras in detail
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.d calls
            intent.extras?.let { extras ->
                for (key in extras.keySet()) {
                    val value = extras.get(key)
                    // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.d call
                }
            } ?: run {
                // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.d call
            }
            
            val alarmId = intent.getIntExtra(ALARM_ID, -1)
            val ringtoneUri = intent.getStringExtra(EXTRA_RINGTONE_URI)
            // Fix: Handle null repeatDays correctly
            val repeatDays = intent.getIntArrayExtra(EXTRA_REPEAT_DAYS) ?: intArrayOf()

            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.alarm and UnifiedLogger.d calls

            // All alarms are handled consistently
            if (alarmId != -1) {
                try {
                    val alarmStorage = AlarmStorage(context)
                    val alarm = alarmStorage.getAlarms().find { it.id == alarmId }
                    if (alarm != null) {
                        // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.d calls
                        
                        // Always reschedule repeating alarms (including daily 5 AM alarms)
                        // Cancel one-time alarms after firing
                        if (alarm.days.isNullOrEmpty()) {
                            // For one-time alarms, cancel after firing
                            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.d call
                            val scheduler = AndroidAlarmScheduler(context)
                            scheduler.cancel(alarm)
                        } else {
                            // For repeating alarms (including 5 AM daily alarms), always reschedule
                            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.d call
                            val scheduler = AndroidAlarmScheduler(context)
                            scheduler.schedule(alarm)
                        }
                    } else {
                        // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.e call
                    }
                } catch (e: Exception) {
                    // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.e call
                }
            }

            if (alarmId != -1) {
                // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.d call
                val serviceIntent = Intent(context, AlarmForegroundService::class.java).apply {
                    putExtra(ALARM_ID, alarmId)
                    putExtra(EXTRA_RINGTONE_URI, ringtoneUri)
                    putExtra(EXTRA_REPEAT_DAYS, repeatDays)

                }
                // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.d call
                
                try {
                    // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.service and UnifiedLogger.success calls
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                    // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.success call
                } catch (e: Exception) {
                    // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.e calls
                    
                    // Try fallback with regular startService
                    try {
                        // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.service and UnifiedLogger.success calls
                        context.startService(serviceIntent)
                        // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.success call
                    } catch (e2: Exception) {
                        // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.e calls
                        
                        // Last resort: try to directly launch the AlarmActivity
                        try {
                            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.service and UnifiedLogger.success calls
                            val activityIntent = Intent(context, AlarmActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                putExtra(ALARM_ID, alarmId)
                                putExtra(EXTRA_RINGTONE_URI, ringtoneUri)
                                putExtra(EXTRA_REPEAT_DAYS, repeatDays)
                            }
                            context.startActivity(activityIntent)
                            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.success call
                        } catch (e3: Exception) {
                            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.e call
                        }
                    }
                }

            } else {
                // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.e call
            }
            
            // All alarms are handled consistently by the scheduler
            // No special rescheduling needed here
        } finally {
            // Release the wake lock
            try {
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
            } catch (e: Exception) {
                // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.e call
            }
        }
        
        // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.alarm call
    }
}
