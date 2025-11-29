package com.vaishnava.alarm

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class ExactAlarmPermissionReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED") {
            Log.d("ExactAlarmPermission", "Permission state changed")
            
            // Check if we still have permission
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val hasPermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                alarmManager.canScheduleExactAlarms()
            } else {
                true
            }
            
            Log.d("ExactAlarmPermission", "Current permission status: $hasPermission")
            
            // Reschedule alarms if permission is granted
            if (hasPermission) {
                try {
                    // Get alarm storage and scheduler
                    val alarmStorage = AlarmStorage(context)
                    val alarmScheduler = AndroidAlarmScheduler(context)
                    
                    // Reschedule all enabled alarms
                    val alarms = alarmStorage.getAlarms()
                    alarms.filter { it.isEnabled }.forEach { alarm ->
                        alarmScheduler.schedule(alarm)
                    }
                    
                    Log.d("ExactAlarmPermission", "Rescheduled ${alarms.filter { it.isEnabled }.size} alarms")
                } catch (e: Exception) {
                    Log.e("ExactAlarmPermission", "Error rescheduling alarms", e)
                }
            }
        }
    }
}
