package com.vaishnava.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.getSystemService
import com.vaishnava.alarm.data.Alarm
import java.util.Calendar
import java.util.concurrent.TimeUnit

class AndroidAlarmScheduler(private val context: Context) : AlarmScheduler {

    private val alarmManager = context.getSystemService<AlarmManager>()!!
    
    override fun schedule(alarm: Alarm) {
        Log.d("AlarmScheduler", "Scheduling alarm ID: ${alarm.id} for ${alarm.hour}:${alarm.minute}")
        Log.d("AlarmScheduler", "Alarm details - ID: ${alarm.id}, Days: ${alarm.days}, IsEnabled: ${alarm.isEnabled}")
        
        // Cancel any existing alarm with the same ID to prevent duplicates
        cancelExistingAlarm(alarm.id)
        
        // Use the regular AlarmReceiver for all alarms
        val receiverClass = AlarmReceiver::class.java
        
        val action = "com.vaishnava.alarm.DIRECT_BOOT_ALARM"
        
        val intent = Intent(context, receiverClass).apply {
            this.action = action
            // Add a unique data URI to ensure proper intent matching
            data = android.net.Uri.parse("alarm://${alarm.id}")
            putExtra(AlarmReceiver.ALARM_ID, alarm.id)
            putExtra(AlarmReceiver.EXTRA_RINGTONE_URI, alarm.ringtoneUri)
            putExtra(AlarmReceiver.EXTRA_REPEAT_DAYS, alarm.days?.toIntArray())
        }

        // Debug the intent before creating PendingIntent
        Log.d("AlarmScheduler", "Creating intent with:")
        Log.d("AlarmScheduler", "  Action: ${intent.action}")
        Log.d("AlarmScheduler", "  Data: ${intent.data}")
        Log.d("AlarmScheduler", "  ALARM_ID: ${AlarmReceiver.ALARM_ID}")
        Log.d("AlarmScheduler", "  Alarm ID value: ${alarm.id}")
        Log.d("AlarmScheduler", "  EXTRA_RINGTONE_URI: ${AlarmReceiver.EXTRA_RINGTONE_URI}")
        Log.d("AlarmScheduler", "  Ringtone URI value: ${alarm.ringtoneUri}")
        Log.d("AlarmScheduler", "  EXTRA_REPEAT_DAYS: ${AlarmReceiver.EXTRA_REPEAT_DAYS}")
        Log.d("AlarmScheduler", "  Repeat days value: ${alarm.days?.toIntArray()?.contentToString()}")

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarm.id, // Use alarm.id for unique request code
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Debug the PendingIntent
        Log.d("AlarmScheduler", "Created PendingIntent with request code: ${alarm.id}")
        
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, alarm.hour)
            set(Calendar.MINUTE, alarm.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        var triggerAtMillis = calendar.timeInMillis
        Log.d("AlarmScheduler", "Initial alarm time calculated: ${calendar.time} (timestamp: $triggerAtMillis)")
        Log.d("AlarmScheduler", "Current time: ${Calendar.getInstance().time} (timestamp: $now)")

        if (alarm.days.isNullOrEmpty()) { // One-time alarm
            Log.d("AlarmScheduler", "Processing one-time alarm")
            if (triggerAtMillis <= now) {
                // If the alarm time is in the past or exactly now, schedule for tomorrow
                triggerAtMillis += TimeUnit.DAYS.toMillis(1)
                Log.d("AlarmScheduler", "Alarm time is in the past, rescheduled for tomorrow")
            }
        } else { // Repeating alarm
            Log.d("AlarmScheduler", "Processing repeating alarm with days: ${alarm.days}")
            var nextAlarmTime = Long.MAX_VALUE
            
            // Find the next valid day of the week for all alarms
            val calendar = Calendar.getInstance()
            val currentDayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
            val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
            val currentMinute = calendar.get(Calendar.MINUTE)
            
            Log.d("AlarmScheduler", "Current day: $currentDayOfWeek, time: $currentHour:$currentMinute")
            
            // Check if today is a valid day and the alarm time hasn't passed yet
            if (alarm.days.contains(currentDayOfWeek)) {
                // Today is a valid day, check if the time has passed
                if (alarm.hour > currentHour || 
                    (alarm.hour == currentHour && alarm.minute > currentMinute)) {
                    // Alarm time is still in the future today
                    calendar.set(Calendar.HOUR_OF_DAY, alarm.hour)
                    calendar.set(Calendar.MINUTE, alarm.minute)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                    val todayTime = calendar.timeInMillis
                    
                    if (todayTime < nextAlarmTime) {
                        nextAlarmTime = todayTime
                    }
                    Log.d("AlarmScheduler", "Found valid alarm time for today: ${calendar.time}")
                } else {
                    Log.d("AlarmScheduler", "Alarm time for today has already passed")
                    // If the alarm time has passed today, schedule for tomorrow
                    calendar.add(Calendar.DAY_OF_YEAR, 1)
                    calendar.set(Calendar.HOUR_OF_DAY, alarm.hour)
                    calendar.set(Calendar.MINUTE, alarm.minute)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                    val tomorrowTime = calendar.timeInMillis
                    
                    if (tomorrowTime < nextAlarmTime) {
                        nextAlarmTime = tomorrowTime
                    }
                    Log.d("AlarmScheduler", "Scheduled for tomorrow: ${calendar.time}")
                }
            } else {
                Log.d("AlarmScheduler", "Today ($currentDayOfWeek) is not a valid alarm day")
                // Look for the next valid day
                var foundNextDay = false
                for (i in 1..7) {
                    val checkDay = (currentDayOfWeek + i - 1) % 7 + 1
                    if (alarm.days.contains(checkDay)) {
                        calendar.timeInMillis = now
                        calendar.add(Calendar.DAY_OF_YEAR, i)
                        calendar.set(Calendar.HOUR_OF_DAY, alarm.hour)
                        calendar.set(Calendar.MINUTE, alarm.minute)
                        calendar.set(Calendar.SECOND, 0)
                        calendar.set(Calendar.MILLISECOND, 0)
                        
                        val dayTime = calendar.timeInMillis
                        if (dayTime < nextAlarmTime) {
                            nextAlarmTime = dayTime
                        }
                        Log.d("AlarmScheduler", "Found valid alarm time for day $checkDay: ${calendar.time}")
                        foundNextDay = true
                        break
                    }
                }
                
                // If we still haven't found a time, use the first valid day
                if (!foundNextDay) {
                    Log.d("AlarmScheduler", "No valid day found this week, using first valid day")
                    val firstValidDay = alarm.days.minOrNull() ?: currentDayOfWeek
                    calendar.timeInMillis = now
                    // Find the next occurrence of this day
                    var daysToAdd = 0
                    for (i in 1..7) {
                        val checkDay = (currentDayOfWeek + i - 1) % 7 + 1
                        if (checkDay == firstValidDay) {
                            daysToAdd = i
                            break
                        }
                    }
                    if (daysToAdd == 0) {
                        daysToAdd = 7 // Next week
                    }
                    calendar.add(Calendar.DAY_OF_YEAR, daysToAdd)
                    calendar.set(Calendar.HOUR_OF_DAY, alarm.hour)
                    calendar.set(Calendar.MINUTE, alarm.minute)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                    
                    nextAlarmTime = calendar.timeInMillis
                    Log.d("AlarmScheduler", "Using first valid day: ${calendar.time}")
                }
            }
            
            triggerAtMillis = nextAlarmTime
            Log.d("AlarmScheduler", "Selected next alarm time: ${Calendar.getInstance().apply { timeInMillis = nextAlarmTime }.time} (timestamp: $nextAlarmTime)")
        }

        try {
            // Prefer setAlarmClock for highest reliability and system UI integration
            val showIntent = android.app.PendingIntent.getActivity(
                context,
                alarm.id,
                Intent(context, AlarmActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra(AlarmReceiver.ALARM_ID, alarm.id)
                    putExtra(AlarmReceiver.EXTRA_RINGTONE_URI, alarm.ringtoneUri)
                    putExtra(AlarmReceiver.EXTRA_REPEAT_DAYS, alarm.days?.toIntArray())
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent)
            alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
            Log.d("AlarmScheduler", "✅ Alarm ID ${alarm.id} scheduled via setAlarmClock")
        } catch (e: Exception) {
            Log.e("AlarmScheduler", "❌ Failed to schedule alarm ID ${alarm.id}: ${e.message}", e)
            
            // Try fallback method
            try {
                Log.d("AlarmScheduler", "Trying fallback method setExactAndAllowWhileIdle...")
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                Log.d("AlarmScheduler", "✅ Alarm ID ${alarm.id} scheduled via fallback setExactAndAllowWhileIdle")
            } catch (e2: Exception) {
                Log.e("AlarmScheduler", "❌ All scheduling methods failed for alarm ID ${alarm.id}: ${e2.message}", e2)
                
                // Last resort fallback
                try {
                    Log.d("AlarmScheduler", "Trying last resort method with set...")
                    alarmManager.set(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                    Log.d("AlarmScheduler", "✅ Alarm ID ${alarm.id} scheduled via last resort set() method")
                } catch (e3: Exception) {
                    Log.e("AlarmScheduler", "❌ All scheduling methods failed completely for alarm ID ${alarm.id}: ${e3.message}", e3)
                }
            }
        }

        Log.d("AlarmApp", "Alarm scheduled for: ${Calendar.getInstance().apply { timeInMillis = triggerAtMillis }.time} with ID: ${alarm.id} and ringtone: ${alarm.ringtoneUri}")
    }

    override fun cancel(alarm: Alarm) {
        // Cancel from the regular receiver only
        cancelFromReceiver(alarm, AlarmReceiver::class.java, "com.vaishnava.alarm.DIRECT_BOOT_ALARM")
    }
    
    private fun cancelFromReceiver(alarm: Alarm, receiverClass: Class<*>, action: String) {
        val intent = Intent(context, receiverClass).apply {
            this.action = action
            // Add the same data URI for proper matching
            data = android.net.Uri.parse("alarm://${alarm.id}")
            putExtra(AlarmReceiver.ALARM_ID, alarm.id)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarm.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        Log.d("AlarmApp", "Alarm with ID ${alarm.id} cancelled from ${receiverClass.simpleName}.")
    }
    
    /**
     * Cancel any existing alarm with the same ID to prevent duplicates
     */
    private fun cancelExistingAlarm(alarmId: Int) {
        try {
            // Try to cancel from the regular receiver
            val intent1 = Intent(context, AlarmReceiver::class.java).apply {
                action = "com.vaishnava.alarm.DIRECT_BOOT_ALARM"
                // Add the same data URI for proper matching
                data = android.net.Uri.parse("alarm://$alarmId")
                putExtra(AlarmReceiver.ALARM_ID, alarmId)
            }
            val pendingIntent1 = PendingIntent.getBroadcast(
                context,
                alarmId,
                intent1,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            
            if (pendingIntent1 != null) {
                alarmManager.cancel(pendingIntent1)
                pendingIntent1.cancel()
                Log.d("AlarmScheduler", "Cancelled existing alarm with ID: $alarmId from AlarmReceiver")
            }
        } catch (e: Exception) {
            Log.e("AlarmScheduler", "Error cancelling existing alarm: ${e.message}", e)
        }
    }
}
