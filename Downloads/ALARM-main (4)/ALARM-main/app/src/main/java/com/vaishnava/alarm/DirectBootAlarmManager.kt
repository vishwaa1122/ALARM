package com.vaishnava.alarm

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import com.vaishnava.alarm.data.Alarm
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

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
    /**
     * Cache a ringtone to device-protected storage
     * @return true if caching was successful, false otherwise
     */
    private fun cacheRingtone(context: Context, ringtoneUri: Uri, alarmId: Int): Boolean {
        if (!ringtoneUri.toString().startsWith("content://")) {
            android.util.Log.d(TAG, "Not a content URI, skipping caching: $ringtoneUri")
            return false
        }

        return try {
            val deviceContext = getDirectBootContext(context)
            val cacheDir = deviceContext.filesDir
            val outputFile = File(cacheDir, "ringtone_$alarmId.mp3")
            
            // Delete existing cache file if it exists
            if (outputFile.exists()) {
                outputFile.delete()
            }
            
            // Open input stream from content URI
            context.contentResolver.openInputStream(ringtoneUri)?.use { inputStream ->
                // Create output stream to cache file
                FileOutputStream(outputFile).use { outputStream ->
                    // Copy the file
                    inputStream.copyTo(outputStream)
                    outputStream.fd.sync() // Ensure all data is written to disk
                    android.util.Log.d(TAG, "Successfully cached ringtone to: ${outputFile.absolutePath}")
                    true
                } ?: run {
                    android.util.Log.e(TAG, "Failed to create output stream for caching")
                    false
                }
            } ?: run {
                android.util.Log.e(TAG, "Failed to open input stream for URI: $ringtoneUri")
                false
            }
        } catch (e: SecurityException) {
            android.util.Log.e(TAG, "Security exception while caching ringtone", e)
            false
        } catch (e: IOException) {
            android.util.Log.e(TAG, "IO exception while caching ringtone", e)
            false
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Unexpected error while caching ringtone", e)
            false
        }
    }

    fun saveAlarmToDirectBootStorage(context: Context, alarm: Alarm) {
        try {
            // Only proceed if device-protected storage is available
            if (!isDeviceProtectedStorageAvailable(context)) {
                android.util.Log.w(TAG, "Device-protected storage not available, skipping Direct Boot save")
                return
            }
            
            val deviceContext = getDirectBootContext(context)
            val prefs = deviceContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            
            val ringtoneUriString = alarm.ringtoneUri?.toString() ?: ""
            val isContentUri = ringtoneUriString.startsWith("content://")
            
            // Cache the ringtone if it's a content URI
            var isCached = false
            if (isContentUri && alarm.ringtoneUri != null) {
                isCached = cacheRingtone(context, alarm.ringtoneUri!!, alarm.id)
            }
            
            // Save alarm data
            prefs.edit()
                .putString("alarm_${alarm.id}", "${alarm.hour}:${alarm.minute}:${System.currentTimeMillis()}:" +
                    "${alarm.isEnabled}:${alarm.days?.joinToString(",") ?: ""}:" +
                    "$ringtoneUriString:${if (isContentUri) "1" else "0"}:" +
                    "${alarm.isProtected}:${alarm.missionType ?: "none"}:${alarm.missionPassword ?: ""}:" +
                    "${alarm.wakeCheckEnabled}:${alarm.wakeCheckMinutes}")
                .putString("alarm_ringtone_${alarm.id}", ringtoneUriString)
                .putBoolean("alarm_ringtone_is_content_${alarm.id}", isContentUri)
                .putBoolean("alarm_ringtone_cached_${alarm.id}", isCached)
                .putString("alarm_mission_type_${alarm.id}", alarm.missionType ?: "none")
                .putString("alarm_mission_password_${alarm.id}", alarm.missionPassword ?: "")
                .putBoolean("alarm_is_protected_${alarm.id}", alarm.isProtected)
                .putBoolean("alarm_wake_check_enabled_${alarm.id}", alarm.wakeCheckEnabled)
                .putInt("alarm_wake_check_minutes_${alarm.id}", alarm.wakeCheckMinutes)
                .apply()
                
            android.util.Log.d(TAG, "Alarm ${alarm.id} saved to Direct Boot storage with ringtone: $ringtoneUriString (isContent: $isContentUri, cached: $isCached), mission: ${alarm.missionType}, protected: ${alarm.isProtected}")
            
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
                .remove("alarm_ringtone_${alarm.id}")
                .remove("alarm_ringtone_is_content_${alarm.id}")
                .remove("alarm_ringtone_cached_${alarm.id}")
                .remove("alarm_mission_type_${alarm.id}")
                .remove("alarm_mission_password_${alarm.id}")
                .remove("alarm_is_protected_${alarm.id}")
                .remove("alarm_wake_check_enabled_${alarm.id}")
                .remove("alarm_wake_check_minutes_${alarm.id}")
                .apply()
                
            android.util.Log.d(TAG, "Alarm ${alarm.id} removed from Direct Boot storage")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to remove alarm from Direct Boot storage: ${e.message}", e)
        }
    }
    
    /**
     * Restore all alarms from device-protected storage
     * @return DirectBootRestorationResult indicating success/failure and a message
     */
    fun restoreAllDirectBootAlarms(context: Context): DirectBootRestorationResult {
        var successCount = 0
        var errorCount = 0
        
        try {
            // Only proceed if device-protected storage is available
            if (!isDeviceProtectedStorageAvailable(context)) {
                val message = "Device-protected storage not available"
                android.util.Log.w(TAG, message)
                return DirectBootRestorationResult(false, message)
            }
            
            val deviceContext = getDirectBootContext(context)
            val prefs = deviceContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val allEntries = prefs.all
            
            // Filter for alarm entries (excluding ringtone entries)
            val alarmEntries = allEntries.filter { it.key.startsWith("alarm_") && !it.key.startsWith("alarm_ringtone_") }
            
            if (alarmEntries.isEmpty()) {
                val message = "No alarms found in Direct Boot storage"
                android.util.Log.d(TAG, message)
                return DirectBootRestorationResult(true, message)
            }
            
            android.util.Log.d(TAG, "Found ${alarmEntries.size} alarms in Direct Boot storage")
            
            val alarmStorage = AlarmStorage(context)
            val alarmScheduler = AndroidAlarmScheduler(context)
            
            // Process each alarm
            for ((key, value) in alarmEntries) {
                try {
                    val alarmId = key.removePrefix("alarm_").toInt()
                    val parts = (value as String).split(":")
                    
                    if (parts.size >= 11) {
                        val hour = parts[0].toInt()
                        val minute = parts[1].toInt()
                        val timestamp = parts[2].toLong()
                        val isEnabled = parts[3].toBoolean()
                        val daysStr = parts[4]
                        val ringtoneUriStr = parts[5]
                        val isHidden = parts[6].toBoolean()
                        val isProtected = parts[7].toBoolean()
                        val missionType = parts[8]
                        val missionPassword = parts[9]
                        val wakeCheckEnabled = parts[10].toBoolean()
                        val wakeCheckMinutes = parts[11].toIntOrNull() ?: 5
                        
                        // FIX: If missionPassword contains "+" but missionType is not "sequencer", 
                        // this is likely a sequencer mission that was misclassified
                        val correctedMissionType = if (missionPassword.contains("+") && missionType != "sequencer") {
                            android.util.Log.w(TAG, "DirectBootAlarmManager: Correcting mission type from '$missionType' to 'sequencer' for alarm $alarmId (missionPassword contains sequence)")
                            "sequencer"
                        } else {
                            missionType
                        }
                        
                        val days = if (daysStr.isNotBlank()) {
                            daysStr.split(",").map { it.toInt() }
                        } else {
                            emptyList()
                        }
                        
                        // Try to get the ringtone from the separate key first
                        var ringtoneUri: android.net.Uri? = null
                        val savedRingtone = prefs.getString("alarm_ringtone_$alarmId", null)
                        val isContentUri = prefs.getBoolean("alarm_ringtone_is_content_${alarmId}", false)
                        
                        // Get mission data from separate keys for better reliability
                        val savedMissionType = prefs.getString("alarm_mission_type_${alarmId}", correctedMissionType)
                        val savedMissionPassword = prefs.getString("alarm_mission_password_${alarmId}", missionPassword)
                        val savedIsProtected = prefs.getBoolean("alarm_is_protected_${alarmId}", isProtected)
                        val savedWakeCheckEnabled = prefs.getBoolean("alarm_wake_check_enabled_${alarmId}", wakeCheckEnabled)
                        val savedWakeCheckMinutes = prefs.getInt("alarm_wake_check_minutes_${alarmId}", wakeCheckMinutes)
                        
                        // FINAL FIX: Always ensure sequencer missions have correct mission type
                        // ANY mission password containing "+" is a sequence (tap+password, password+tap, etc.)
                        val finalMissionType = if (savedMissionPassword?.contains("+") == true) {
                            android.util.Log.d(TAG, "DirectBootAlarmManager: Ensuring sequencer mission type for alarm $alarmId (password contains sequence: $savedMissionPassword)")
                            "sequencer"
                        } else {
                            savedMissionType
                        }
                        
                        // First, try to use the cached ringtone in device-protected storage
                        try {
                            val cachedRingtone = File(deviceContext.filesDir, "ringtone_${alarmId}.mp3")
                            if (cachedRingtone.exists()) {
                                ringtoneUri = android.net.Uri.fromFile(cachedRingtone)
                                android.util.Log.d(TAG, "Using cached ringtone from device storage for alarm $alarmId: $ringtoneUri")
                            }
                        } catch (e: Exception) {
                            android.util.Log.e(TAG, "Failed to load cached ringtone for alarm $alarmId", e)
                        }
                        
                        // If no cached version, try the saved URI
                        if (ringtoneUri == null && !savedRingtone.isNullOrEmpty()) {
                            try {
                                ringtoneUri = android.net.Uri.parse(savedRingtone)
                                // If this is a content URI and we're in Direct Boot mode, try to use the default alarm sound instead
                                if (isContentUri && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && 
                                    context.isDeviceProtectedStorage) {
                                    android.util.Log.w(TAG, "Content URI in Direct Boot mode, falling back to default alarm sound for $alarmId")
                                    ringtoneUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                                } else {
                                    android.util.Log.d(TAG, "Restored ringtone from storage for alarm $alarmId: $ringtoneUri")
                                }
                            } catch (e: Exception) {
                                android.util.Log.e(TAG, "Failed to parse saved ringtone URI: $savedRingtone", e)
                            }
                        }
                        
                        // Fall back to the ringtone in the alarm data if separate key not found
                        if (ringtoneUri == null && ringtoneUriStr.isNotBlank()) {
                            try {
                                ringtoneUri = android.net.Uri.parse(ringtoneUriStr)
                                // If this is a content URI and we're in Direct Boot mode, use default sound
                                if (ringtoneUriStr.startsWith("content://") && 
                                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && 
                                    context.isDeviceProtectedStorage) {
                                    android.util.Log.w(TAG, "Content URI in Direct Boot mode, falling back to default alarm sound for $alarmId")
                                    ringtoneUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                                } else {
                                    android.util.Log.d(TAG, "Restored ringtone from alarm data for alarm $alarmId: $ringtoneUri")
                                }
                            } catch (e: Exception) {
                                android.util.Log.e(TAG, "Failed to parse ringtone URI from alarm data: $ringtoneUriStr", e)
                            }
                        }
                        
                        // If still no ringtone, use default
                        if (ringtoneUri == null) {
                            ringtoneUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                            android.util.Log.w(TAG, "No valid ringtone found for alarm $alarmId, using default")
                        }
                        
                        val alarm = Alarm(
                            id = alarmId,
                            hour = hour,
                            minute = minute,
                            isEnabled = isEnabled,
                            ringtoneUri = ringtoneUri,
                            days = if (days.isNotEmpty()) days else null,
                            alarmTime = timestamp,
                            isHidden = isHidden,
                            isProtected = savedIsProtected,
                            missionType = if (finalMissionType == "none") null else finalMissionType,
                            missionPassword = if (finalMissionType == "sequencer") savedMissionPassword else savedMissionPassword,
                            wakeCheckEnabled = savedWakeCheckEnabled,
                            wakeCheckMinutes = savedWakeCheckMinutes
                        )
                        
                        // Check if the alarm already exists with the same settings
                        val existingAlarms = alarmStorage.getAlarms()
                        val existingAlarm = existingAlarms.find { it.id == alarmId }
                        
                        if (existingAlarm == null || 
                            existingAlarm.hour != alarm.hour || 
                            existingAlarm.minute != alarm.minute ||
                            existingAlarm.isEnabled != alarm.isEnabled ||
                            existingAlarm.missionType != alarm.missionType ||
                            existingAlarm.missionPassword != alarm.missionPassword ||
                            existingAlarm.isProtected != alarm.isProtected ||
                            existingAlarm.wakeCheckEnabled != alarm.wakeCheckEnabled) {
                            
                            // Update the alarm in storage
                            if (existingAlarm != null) {
                                alarmStorage.updateAlarm(alarm)
                            } else {
                                alarmStorage.addAlarm(alarm)
                            }
                            
                            // Schedule the alarm if enabled
                            if (isEnabled) {
                                alarmScheduler.schedule(alarm)
                                android.util.Log.d(TAG, "Restored and scheduled Direct Boot alarm: ID $alarmId at ${alarm.hour}:${alarm.minute}, mission: ${alarm.missionType}, protected: ${alarm.isProtected}")
                            } else {
                                android.util.Log.d(TAG, "Restored disabled alarm: ID $alarmId at ${alarm.hour}:${alarm.minute}")
                            }
                            successCount++
                        } else {
                            android.util.Log.d(TAG, "Alarm $alarmId already exists with same settings, skipping")
                            successCount++
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
            
            val message = "Restored $successCount alarms, $errorCount errors"
            android.util.Log.d(TAG, message)
            
            return DirectBootRestorationResult(errorCount == 0, message)
            
        } catch (e: Exception) {
            val errorMessage = "Critical error in Direct Boot alarm restoration: ${e.message}"
            android.util.Log.e(TAG, errorMessage, e)
            return DirectBootRestorationResult(false, errorMessage)
        }
    }
}
