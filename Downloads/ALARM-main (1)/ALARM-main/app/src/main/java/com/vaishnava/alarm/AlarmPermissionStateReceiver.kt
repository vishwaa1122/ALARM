package com.vaishnava.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.app.AlarmManager

class AlarmPermissionStateReceiver : BroadcastReceiver() {
    private val TAG = "AlarmPermissionReceiver"

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        if (intent.action == "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED") {
            Log.d(TAG, "SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED received.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val canSchedule = alarmManager.canScheduleExactAlarms()
                Log.d(TAG, "AlarmManager.canScheduleExactAlarms() after state change: $canSchedule")

                if (canSchedule) {
                    // Permission was granted, re-schedule critical exact alarms
                    rescheduleCriticalAlarms(context)
                } else {
                    // Permission was denied or revoked
                    Log.w(TAG, "Exact alarm permission denied or revoked.")
                }
            }
        }
    }

    private fun rescheduleCriticalAlarms(context: Context) {
        Log.d(TAG, "Rescheduling all critical alarms...")
        // Implement your logic to re-schedule all necessary exact alarms here.
        // This would typically involve iterating through your stored alarms
        // and calling alarmManager.setExact() or setExactAndAllowWhileIdle() for each.
        // Example:
        // val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // val pendingIntent = ... // Your alarm's PendingIntent
        // val triggerTime = ... // Your alarm's trigger time
        // alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
    }
}
