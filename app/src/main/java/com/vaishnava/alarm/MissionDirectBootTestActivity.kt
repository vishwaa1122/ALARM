package com.vaishnava.alarm

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import com.vaishnava.alarm.data.Alarm
import android.net.Uri
import java.util.*

class MissionDirectBootTestActivity : Activity() {
    private val TAG = "MissionDirectBootTest"
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        
        val testButton = Button(this).apply {
            text = "Test Mission Direct Boot"
            setOnClickListener {
                testMissionDirectBootFeatures()
            }
        }
        
        val restoreButton = Button(this).apply {
            text = "Restore Mission Alarms"
            setOnClickListener {
                restoreMissionAlarms()
            }
        }
        
        val clearButton = Button(this).apply {
            text = "Clear Corrupted Mission Files"
            setOnClickListener {
                clearCorruptedMissionFiles()
            }
        }
        
        layout.addView(testButton)
        layout.addView(restoreButton)
        layout.addView(clearButton)
        setContentView(layout)
    }
    
    private fun testMissionDirectBootFeatures() {
        try {
            Log.d(TAG, "Testing Mission Direct Boot Features")
            
            // Create test alarm with mission data
            val testAlarm = Alarm(
                id = 999,
                hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
                minute = Calendar.getInstance().get(Calendar.MINUTE) + 1,
                isEnabled = true,
                ringtoneUri = Uri.parse("content://settings/system/alarm_alert"),
                missionType = "password",
                missionPassword = "test123",
                isProtected = true,
                wakeCheckEnabled = true,
                wakeCheckMinutes = 5
            )
            
            // Test saving to Direct Boot storage
            DirectBootAlarmManager.saveAlarmToDirectBootStorage(this, testAlarm)
            Toast.makeText(this, "Saved test mission alarm to Direct Boot storage", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "Saved test mission alarm to Direct Boot storage")
            
            // Test reading from device-protected storage
            val prefs = getSharedPreferences("direct_boot_alarm_prefs", MODE_PRIVATE)
            val missionType = prefs.getString("direct_boot_mission_type_999", null)
            val missionPassword = prefs.getString("direct_boot_mission_password_999", null)
            val isProtected = prefs.getBoolean("direct_boot_is_protected_999", false)
            val wakeCheckEnabled = prefs.getBoolean("direct_boot_wake_check_enabled_999", false)
            val wakeCheckMinutes = prefs.getInt("direct_boot_wake_check_minutes_999", 5)
            
            Log.d(TAG, "Retrieved mission data: type=$missionType, password=$missionPassword, protected=$isProtected, wakeCheck=$wakeCheckEnabled, minutes=$wakeCheckMinutes")
            
            Toast.makeText(this, "Mission data stored: type=$missionType, protected=$isProtected", Toast.LENGTH_LONG).show()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during Mission Direct Boot test: ${e.message}", e)
            Toast.makeText(this, "Mission Direct Boot test failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun restoreMissionAlarms() {
        try {
            Log.d(TAG, "Restoring Mission Alarms from Direct Boot")
            
            // First, clear any corrupted mission files
            val dpsContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                createDeviceProtectedStorageContext()
            } else {
                this
            }
            
            val queueFile = java.io.File(dpsContext.filesDir, "mission_queue.json")
            val currentMissionFile = java.io.File(dpsContext.filesDir, "current_mission.json")
            
            // Check and clear corrupted files
            if (queueFile.exists()) {
                val content = queueFile.readText()
                if (!content.trim().startsWith("[") || !content.trim().endsWith("]")) {
                    Log.w(TAG, "Queue file is corrupted, clearing it. Content: $content")
                    queueFile.delete()
                }
            }
            
            if (currentMissionFile.exists()) {
                val content = currentMissionFile.readText()
                if (!content.trim().startsWith("{") || !content.trim().endsWith("}")) {
                    Log.w(TAG, "Current mission file is corrupted, clearing it. Content: $content")
                    currentMissionFile.delete()
                }
            }
            
            val result = DirectBootAlarmManager.restoreAllDirectBootAlarms(this)
            
            Toast.makeText(this, "Mission Direct Boot restore: ${result.message}", Toast.LENGTH_LONG).show()
            Log.d(TAG, "Mission Direct Boot restore completed: ${result.message}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during Mission Direct Boot restore: ${e.message}", e)
            Toast.makeText(this, "Mission Direct Boot restore failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun clearCorruptedMissionFiles() {
        try {
            Log.d(TAG, "Clearing corrupted mission files")
            
            val dpsContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                createDeviceProtectedStorageContext()
            } else {
                this
            }
            
            val queueFile = java.io.File(dpsContext.filesDir, "mission_queue.json")
            val currentMissionFile = java.io.File(dpsContext.filesDir, "current_mission.json")
            
            var cleared = 0
            
            if (queueFile.exists()) {
                val content = queueFile.readText()
                Log.d(TAG, "Queue file content before clearing: $content")
                queueFile.delete()
                cleared++
                Log.d(TAG, "Deleted queue file")
            }
            
            if (currentMissionFile.exists()) {
                val content = currentMissionFile.readText()
                Log.d(TAG, "Current mission file content before clearing: $content")
                currentMissionFile.delete()
                cleared++
                Log.d(TAG, "Deleted current mission file")
            }
            
            Toast.makeText(this, "Cleared $cleared corrupted mission files", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "Cleared $cleared mission files")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing mission files: ${e.message}", e)
            Toast.makeText(this, "Failed to clear mission files: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
