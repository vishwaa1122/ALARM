package com.vaishnava.alarm

import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat
import com.vaishnava.alarm.data.Alarm

/**
 * Data class to represent the result of Direct Boot restoration operations
 */
data class DirectBootRestorationResult(
    val isSuccessful: Boolean,
    val message: String
)

/**
 * DirectBootAlarmManager - Centralized manager for Direct Boot aware alarm operations
 * 
 * This class ensures that all alarm operations are fully compatible with Android's Direct Boot mode,
 * which allows the device to run in a limited state before the user unlocks it after a restart.
 * 
 * Key Features:
 * - Uses device-protected storage exclusively for Direct Boot compatibility
 * - Provides multiple backup and restoration strategies
 * - Ensures alarms survive force restarts and encrypted storage scenarios
 * - Implements comprehensive logging for debugging restart issues
 */
object DirectBootAlarmManager {
    
    private const val TAG = "DirectBootAlarmManager"
    private const val PREF_NAME = "direct_boot_alarms"
    private const val ALARM_FILE = "direct_boot_alarms.json"
    
    // Add this helper method to check if device-protected storage is available
    fun isDeviceProtectedStorageAvailable(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.isDeviceProtectedStorage ||
                ContextCompat.createDeviceProtectedStorageContext(context) != null
        } else {
            // For older versions, assume it's not available
            false
        }
    }
    
    // Get device-protected context
    fun getDirectBootContext(context: Context): Context {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext() ?: context
        } else {
            // For older versions, return the regular context
            context
        }
    }
    
    /**
     * Save an alarm to device-protected storage
     */
    fun saveAlarmToDirectBootStorage(context: Context, alarm: Alarm) {
        try {
            // Only proceed if device-protected storage is available
            if (!isDeviceProtectedStorageAvailable(context)) {
                android.util.Log.w(TAG, "Device-protected storage not available, skipping Direct Boot save")
                return
            }
            
            val deviceContext = getDirectBootContext(context)
            val prefs = deviceContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            
            // Create a JSON representation of the alarm
            val alarmData = "${alarm.hour}:${alarm.minute}:${System.currentTimeMillis()}:${alarm.isEnabled}:${alarm.days?.joinToString(",") ?: ""}:${alarm.ringtoneUri ?: ""}:false"
            
            prefs.edit()
                .putString("alarm_${alarm.id}", alarmData)
                .apply()
                
            android.util.Log.d(TAG, "Alarm ${alarm.id} saved to Direct Boot storage")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to save alarm to Direct Boot storage: ${e.message}", e)
        }
    }
    
    /**
     * Remove an alarm from device-protected storage
     */
    fun removeAlarmFromDirectBootStorage(context: Context, alarm: Alarm) {
        try {
            // Only proceed if device-protected storage is available
            if (!isDeviceProtectedStorageAvailable(context)) {
                android.util.Log.w(TAG, "Device-protected storage not available, skipping Direct Boot remove")
                return
            }
            
            val deviceContext = getDirectBootContext(context)
            val prefs = deviceContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            
            prefs.edit()
                .remove("alarm_${alarm.id}")
                .apply()
                
            android.util.Log.d(TAG, "Alarm ${alarm.id} removed from Direct Boot storage")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to remove alarm from Direct Boot storage: ${e.message}", e)
        }
    }
    
    /**
     * Restore all alarms from device-protected storage
     */
    fun restoreAllDirectBootAlarms(context: Context): DirectBootRestorationResult {
        try {
            // Only proceed if device-protected storage is available
            if (!isDeviceProtectedStorageAvailable(context)) {
                android.util.Log.w(TAG, "Device-protected storage not available, skipping Direct Boot restoration")
                return DirectBootRestorationResult(false, "Device-protected storage not available")
            }
            
            val deviceContext = getDirectBootContext(context)
            val prefs = deviceContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            
            // Fix for deprecated prefs.all usage
            val allAlarms = mutableMapOf<String, Any?>()
            prefs.all.forEach { entry ->
                allAlarms[entry.key] = entry.value
            }
            
            android.util.Log.d(TAG, "Restoring ${allAlarms.size} alarms from Direct Boot storage")
            
            if (allAlarms.isEmpty()) {
                android.util.Log.d(TAG, "No alarms found in Direct Boot storage to restore")
                return DirectBootRestorationResult(true, "No alarms to restore")
            }
            
            var successCount = 0
            var errorCount = 0
            
            // First, cancel all existing alarms to prevent duplicates
            val alarmScheduler = AndroidAlarmScheduler(context)
            val alarmStorage = AlarmStorage(context)
            
            // Cancel all currently scheduled alarms
            val existingAlarms = alarmStorage.getAlarms()
            existingAlarms.forEach { alarm ->
                try {
                    alarmScheduler.cancel(alarm)
                    android.util.Log.d(TAG, "Cancelled existing alarm ${alarm.id} before restoration")
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Failed to cancel existing alarm ${alarm.id}: ${e.message}", e)
                }
            }
            
            allAlarms.forEach { entry ->
                val key = entry.key
                val value = entry.value
                if (key.startsWith("alarm_") && value is String) {
                    try {
                        val alarmId = key.removePrefix("alarm_").toInt()
                        val parts = value.split(":")
                        
                        if (parts.size >= 4) {
                            val hour = parts[0].toInt()
                            val minute = parts[1].toInt()
                            val isEnabled = parts[3].toBoolean()
                            
                            // Parse days if available
                            val days = if (parts.size >= 5 && parts[4].isNotEmpty()) {
                                parts[4].split(",").map { it.toInt() }
                            } else {
                                emptyList()
                            }
                            
                            // Parse ringtone URI if available
                            val ringtoneUri = if (parts.size >= 6 && parts[5].isNotEmpty()) {
                                android.net.Uri.parse(parts[5])
                            } else {
                                null
                            }
                            
                            // Parse isProtected flag if available (deprecated, ignore)
                            val isProtected = false
                            
                            // Create alarm object
                            val alarm = Alarm(
                                id = alarmId,
                                hour = hour,
                                minute = minute,
                                isEnabled = isEnabled,
                                ringtoneUri = ringtoneUri,
                                days = days,
                                alarmTime = System.currentTimeMillis(),
                                isHidden = false
                            )
                            
                            // Add or update alarm in storage
                            try {
                                val existingAlarm = alarmStorage.getAlarms().find { it.id == alarmId }
                                if (existingAlarm != null) {
                                    alarmStorage.updateAlarm(alarm)
                                    android.util.Log.d(TAG, "Updated existing alarm in storage: ID $alarmId")
                                } else {
                                    alarmStorage.addAlarm(alarm)
                                    android.util.Log.d(TAG, "Added new alarm to storage: ID $alarmId")
                                }
                            } catch (e: Exception) {
                                android.util.Log.e(TAG, "Failed to add/update alarm in storage: ${e.message}", e)
                            }
                            
                            // Schedule the alarm only if it's enabled
                            if (alarm.isEnabled) {
                                try {
                                    alarmScheduler.schedule(alarm)
                                    android.util.Log.d(TAG, "Restored and scheduled Direct Boot alarm: ID $alarmId at ${alarm.hour}:${alarm.minute}")
                                    successCount++
                                } catch (e: Exception) {
                                    android.util.Log.e(TAG, "Failed to schedule alarm $alarmId: ${e.message}", e)
                                    errorCount++
                                }
                            } else {
                                android.util.Log.d(TAG, "Alarm $alarmId is disabled, not scheduling")
                                successCount++ // Count as success since we handled it correctly
                            }
                        } else {
                            android.util.Log.e(TAG, "Invalid alarm data format for key $key: $value")
                            errorCount++
                        }
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "Error restoring alarm $key: ${e.message}", e)
                        errorCount++
                    }
                }
            }
            
            val message = "Restored $successCount alarms, $errorCount errors"
            android.util.Log.d(TAG, message)
            
            return DirectBootRestorationResult(errorCount == 0, message)
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Critical error in Direct Boot alarm restoration: ${e.message}", e)
            return DirectBootRestorationResult(false, "Critical error: ${e.message}")
        }
    }
}
