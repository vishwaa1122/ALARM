package com.vaishnava.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.Calendar

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        Log.d("AlarmApp", "BootReceiver onReceive triggered. Current time: ${Calendar.getInstance().time}. Intent action: ${intent?.action}")
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED || intent?.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            Log.d("AlarmApp", "Boot completed or locked boot completed, re-scheduling alarms.")
            context?.let {
                val deviceProtectedContext = if (!it.isDeviceProtectedStorage) {
                    Log.d("AlarmApp", "BootReceiver: Context is NOT device protected storage aware. Moving to device protected storage context.")
                    it.createDeviceProtectedStorageContext()
                } else {
                    Log.d("AlarmApp", "BootReceiver: Context is device protected storage aware.")
                    it
                }

                val alarmStorage = AlarmStorage(deviceProtectedContext)
                val alarmScheduler = AndroidAlarmScheduler(deviceProtectedContext)
                
                Log.d("AlarmApp", "BootReceiver: Attempting to get alarms from storage.")
                val alarms = alarmStorage.getAlarms()
                Log.d("AlarmApp", "BootReceiver: Found ${alarms.size} alarms to re-schedule.")

                if (alarms.isEmpty()) {
                    Log.d("AlarmApp", "BootReceiver: No alarms found in storage to re-schedule.")
                }

                alarms.forEach { alarm ->
                    if (alarm.isEnabled) {
                        Log.d("AlarmApp", "BootReceiver: Scheduling alarm with ID: ${alarm.id} for ${alarm.hour}:${alarm.minute}")
                        
                        // Log all alarms consistently
                        Log.d("AlarmApp", "BootReceiver: Scheduling alarm ID: ${alarm.id} at ${alarm.hour}:${alarm.minute}")
                        
                        // Use regular scheduling for all alarms
                        alarmScheduler.schedule(alarm)

                        Log.d("AlarmApp", "BootReceiver: Re-scheduled alarm with ID: ${alarm.id} for ${alarm.hour}:${alarm.minute}")
                        
                        // Log success for all alarms consistently
                        Log.d("AlarmApp", "✅ BootReceiver successfully scheduled alarm ID: ${alarm.id}")
                    } else {
                        Log.d("AlarmApp", "BootReceiver: Skipping disabled alarm with ID: ${alarm.id}")
                        
                        // Log for all disabled alarms consistently
                        Log.d("AlarmApp", "⚠️ BootReceiver skipping disabled alarm ID: ${alarm.id}")
                    }
                }
                
                // All alarms are handled consistently
            } ?: run {
                Log.e("AlarmApp", "Context is null in BootReceiver.onReceive")
            }
        } else {
            Log.d("AlarmApp", "BootReceiver received unexpected intent action: ${intent?.action}")
        }
    }
    // All alarms are handled consistently
}
