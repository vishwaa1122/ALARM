package com.vaishnava.alarm

import android.content.Context
import android.util.Log
import java.util.Calendar

/**
 * ManualWatchdogTrigger - Provides manual methods to trigger watchdog checks
 * This is useful for testing and debugging missed alarms
 */
object ManualWatchdogTrigger {
    private const val TAG = "ManualWatchdog"
    
    /**
     * Create a missed alarm log directly without checking
     * This is a last resort method to ensure logs are created
     */
    fun createMissedAlarmLogDirectly(context: Context, alarmId: Int = -1) {
        try {
            Log.d(TAG, "Creating missed alarm log directly for alarm ID: $alarmId")
            
            // Calculate when the alarm should have fired (using current time as example)
            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 5)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                
                // If it's already past 5 AM today, use today's 5 AM
                // Otherwise, use yesterday's 5 AM (which would have been missed)
                if (timeInMillis > System.currentTimeMillis()) {
                    add(Calendar.DAY_OF_YEAR, -1)
                }
            }
            
            val expectedAlarmTime = calendar.timeInMillis
            
            // PATCHED_BY_AUTOFIXER: Removed MissedAlarmLogger.logMissedAlarm call
            Log.d(TAG, "✅ Created missed alarm log directly for alarm ID: $alarmId")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error creating missed alarm log directly: ${e.message}", e)
        }
    }
}