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
import java.util.Date
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.concurrent.TimeUnit

class AndroidAlarmScheduler(private val context: Context) : AlarmScheduler {

    private val alarmManager = context.getSystemService<AlarmManager>()!!

    override fun schedule(alarm: Alarm) {
        Log.d("AlarmScheduler", "Scheduling alarm ID: ${alarm.id} for ${alarm.hour}:${alarm.minute}")
        Log.d("AlarmScheduler", "Alarm details - ID: ${alarm.id}, Days: ${alarm.days}, IsEnabled: ${alarm.isEnabled}, repeatDaily=${alarm.repeatDaily}")

        // Cancel existing with same ID to prevent duplicates
        cancelExistingAlarm(alarm.id)

        // Check exact alarm permission status
        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
        } else {
            true
        }
        Log.d("AlarmScheduler", "canScheduleExactAlarms() = $canExact")
        
        // CRITICAL: Check exact alarm permission before scheduling
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !canExact) {
            Log.e("AlarmScheduler", "❌ EXACT ALARM PERMISSION DENIED - Cannot schedule exact alarm ID ${alarm.id}")
            appendAlarmLog("exact_permission_denied id=${alarm.id}")
            
            // Throw SecurityException to be caught by caller and handled gracefully
            throw SecurityException("Exact alarm permission not granted: SCHEDULE_EXACT_ALARM or USE_EXACT_ALARM required")
        }

        val action = "com.vaishnava.alarm.DIRECT_BOOT_ALARM"
        val receiverClass = AlarmReceiver::class.java

        val intent = Intent(context, receiverClass).apply {
            this.action = action
            data = android.net.Uri.parse("alarm://${alarm.id}")
            putExtra(AlarmReceiver.ALARM_ID, alarm.id)
            putExtra(AlarmReceiver.EXTRA_RINGTONE_URI, alarm.ringtoneUri)
            // convert days to intArray for extras
            putExtra(AlarmReceiver.EXTRA_REPEAT_DAYS, alarm.days?.toIntArray())
            putExtra("hour", alarm.hour)
            putExtra("minute", alarm.minute)
            putExtra("repeatDaily", alarm.repeatDaily)
        }

        Log.d(
            "AlarmScheduler",
            "PI_SCHEDULE: id=${alarm.id} action=${intent.action} data=${intent.data} extrasKeys=${intent.extras?.keySet()?.joinToString()}"
        )

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarm.id,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, alarm.hour)
            set(Calendar.MINUTE, alarm.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        var triggerAtMillis = computeNextTriggerMillis(now, alarm)
        Log.d("AlarmScheduler", "Computed next trigger: ${Date(triggerAtMillis)} (now=${Date(now)})")
        appendAlarmLog((if (alarm.days.isNullOrEmpty() && !alarm.repeatDaily) "one-time" else "repeating") + " id=${alarm.id} next=${Date(triggerAtMillis)}")

        try {
            // Show intent for setAlarmClock: open the AlarmActivity when tapped
            val showActivityIntent = Intent(context, AlarmActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(AlarmReceiver.ALARM_ID, alarm.id)
                putExtra(AlarmReceiver.EXTRA_RINGTONE_URI, alarm.ringtoneUri)
                putExtra(AlarmReceiver.EXTRA_REPEAT_DAYS, alarm.days?.toIntArray())
            }

            Log.d(
                "AlarmScheduler",
                "PI_SHOW: id=${alarm.id} data=${showActivityIntent.data} extrasKeys=${showActivityIntent.extras?.keySet()?.joinToString()}"
            )

            val showIntent = android.app.PendingIntent.getActivity(
                context,
                alarm.id,
                showActivityIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent)
            alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
            Log.d("AlarmScheduler", "✅ Alarm ID ${alarm.id} scheduled via setAlarmClock")
            appendAlarmLog("scheduled id=${alarm.id} at=${Date(triggerAtMillis)} via=setAlarmClock")
        } catch (se: SecurityException) {
            // Explicit handling for exact-alarms permission issues
            Log.e("AlarmScheduler", "❌ SecurityException scheduling alarm ID ${alarm.id}: ${se.message}", se)
            appendAlarmLog("security_exception id=${alarm.id} msg=${se.message}")
        } catch (e: Exception) {
            Log.e("AlarmScheduler", "❌ Failed to schedule alarm ID ${alarm.id} via setAlarmClock: ${e.message}", e)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                } else {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                }
                Log.d("AlarmScheduler", "✅ Alarm ID ${alarm.id} scheduled via fallback setExactAndAllowWhileIdle/setExact")
                appendAlarmLog("scheduled id=${alarm.id} at=${Date(triggerAtMillis)} via=setExact")
            } catch (se2: SecurityException) {
                Log.e("AlarmScheduler", "❌ SecurityException on fallback exact scheduling for alarm ID ${alarm.id}: ${se2.message}", se2)
                appendAlarmLog("security_exception_fallback id=${alarm.id} msg=${se2.message}")
            } catch (e2: Exception) {
                Log.e("AlarmScheduler", "❌ All scheduling methods failed for alarm ID ${alarm.id}: ${e2.message}", e2)
                try {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                    appendAlarmLog("scheduled id=${alarm.id} at=${Date(triggerAtMillis)} via=set")
                } catch (e3: Exception) {
                    Log.e("AlarmScheduler", "❌ Last-resort set() failed for alarm ID ${alarm.id}: ${e3.message}", e3)
                }
            }
        }

        Log.d("AlarmApp", "Alarm scheduled for: ${Date(triggerAtMillis)} with ID: ${alarm.id} and ringtone: ${alarm.ringtoneUri}")
    }

    override fun cancel(alarm: Alarm) {
        cancelFromReceiver(alarm, AlarmReceiver::class.java, "com.vaishnava.alarm.DIRECT_BOOT_ALARM")
    }

    private fun cancelFromReceiver(alarm: Alarm, receiverClass: Class<*>, action: String) {
        val intent = Intent(context, receiverClass).apply {
            this.action = action
            data = android.net.Uri.parse("alarm://${alarm.id}")
            putExtra(AlarmReceiver.ALARM_ID, alarm.id)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarm.id,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            Log.d("AlarmApp", "Alarm with ID ${alarm.id} cancelled from ${receiverClass.simpleName}.")
        }
    }

    private fun cancelExistingAlarm(alarmId: Int) {
        try {
            val intent = Intent(context, AlarmReceiver::class.java).apply {
                action = "com.vaishnava.alarm.DIRECT_BOOT_ALARM"
                data = android.net.Uri.parse("alarm://$alarmId")
                putExtra(AlarmReceiver.ALARM_ID, alarmId)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                alarmId,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
                Log.d("AlarmScheduler", "Cancelled existing alarm with ID: $alarmId from AlarmReceiver")
            }
        } catch (e: Exception) {
            Log.e("AlarmScheduler", "Error cancelling existing alarm: ${e.message}", e)
        }
    }

    private fun computeNextTriggerMillis(nowMillis: Long, alarm: Alarm): Long {
        val now = Calendar.getInstance().apply { timeInMillis = nowMillis }

        // Build allowedDays: if alarm.days present use it; else if repeatDaily -> all days; else empty
        val allowedDays: IntArray = when {
            !alarm.days.isNullOrEmpty() -> alarm.days!!.toIntArray()
            alarm.repeatDaily -> intArrayOf(
                Calendar.SUNDAY, Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
                Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY
            )
            else -> intArrayOf()
        }

        fun calForOffset(offsetDays: Int): Calendar {
            return Calendar.getInstance().apply {
                timeInMillis = nowMillis
                add(Calendar.DAY_OF_YEAR, offsetDays)
                set(Calendar.HOUR_OF_DAY, alarm.hour)
                set(Calendar.MINUTE, alarm.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
        }

        // No allowed days -> one-shot semantics (today if future else next day)
        if (allowedDays.isEmpty()) {
            val todayCal = calForOffset(0)
            return if (todayCal.timeInMillis > nowMillis) {
                todayCal.timeInMillis
            } else {
                calForOffset(1).timeInMillis
            }
        }

        // Search next 14 days for nearest allowed day (handles edge-cases)
        for (offset in 0..13) {
            val c = calForOffset(offset)
            val dow = c.get(Calendar.DAY_OF_WEEK)
            if (allowedDays.any { it == dow }) {
                if (offset == 0) {
                    // today -> only accept if time not passed
                    if (c.timeInMillis > nowMillis) return c.timeInMillis
                } else {
                    return c.timeInMillis
                }
            }
        }

        // fallback: next day at alarm time
        return calForOffset(1).timeInMillis
    }

    private fun appendAlarmLog(message: String) {
        try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val line = "${sdf.format(Date())} | $message\n"
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            val file = java.io.File(base, "alarm_debug.log")
            java.io.FileOutputStream(file, true).use { it.write(line.toByteArray()) }
        } catch (_: Exception) { }
    }

    // helper extension (always returns a non-null array)
    private fun List<Int>?.toIntArray(): IntArray =
        if (this == null) IntArray(0) else IntArray(this.size) { i -> this[i] }
}