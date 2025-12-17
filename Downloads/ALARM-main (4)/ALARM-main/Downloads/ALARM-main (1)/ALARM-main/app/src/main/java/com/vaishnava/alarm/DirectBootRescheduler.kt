package com.vaishnava.alarm

import android.content.Context
import android.util.Log

/**
 * DirectBootRescheduler
 *
 * Re-schedules alarms from the single source of truth `AlarmStorage` (which
 * already uses device-protected storage internally). Designed to be called from
 * a BootReceiver that receives LOCKED_BOOT_COMPLETED / BOOT_COMPLETED.
 *
 * Usage (example from BootReceiver):
 *   DirectBootRescheduler(context).rescheduleAlarms()
 */
class DirectBootRescheduler(private val context: Context) {

    companion object {
        private const val TAG = "DirectBootRescheduler"
    }

    /**
     * Reschedule all alarms stored in AlarmStorage (device-protected).
     */
    fun rescheduleAlarms() {
        try {
            DebugLog.log(TAG, "rescheduleAlarms", "Attempting to reschedule alarms from AlarmStorage.")

            // AlarmStorage internally uses device-protected storage context
            val storage = AlarmStorage(context)
            val alarms = storage.getAlarms()

            if (alarms.isEmpty()) {
                DebugLog.log(TAG, "rescheduleAlarms", "No alarms found in AlarmStorage.")
                return
            }

            val scheduler = AndroidAlarmScheduler(context.applicationContext)
            var rescheduledCount = 0

            for (alarm in alarms) {
                try {
                    if (alarm.isEnabled) {
                        scheduler.schedule(alarm)
                        rescheduledCount++
                        DebugLog.log(TAG, "rescheduleAlarms", "Rescheduled alarm ID: ${alarm.id}")
                    } else {
                        DebugLog.log(TAG, "rescheduleAlarms", "Skipping disabled alarm ID: ${alarm.id}")
                    }
                } catch (inner: Exception) {
                    DebugLog.log(TAG, "rescheduleAlarms", "Failed to reschedule alarm id=${alarm.id}: ${inner.message}")
                    Log.e(TAG, "Failed to reschedule alarm id=${alarm.id}", inner)
                }
            }

            DebugLog.log(TAG, "rescheduleAlarms", "Rescheduling completed. Count=$rescheduledCount")
        } catch (t: Throwable) {
            DebugLog.log(TAG, "rescheduleAlarms", "Unexpected error: ${t.message}")
            Log.e(TAG, "DirectBootRescheduler.rescheduleAlarms failed", t)
        }
    }
}
