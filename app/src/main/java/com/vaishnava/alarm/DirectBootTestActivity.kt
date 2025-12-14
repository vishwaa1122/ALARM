package com.vaishnava.alarm

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import com.vaishnava.alarm.data.Alarm

class DirectBootTestActivity : Activity() {
    private val TAG = "DirectBootTest"
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val button = Button(this).apply {
            text = "Test Direct Boot Features"
        }
        
        setContentView(button)
        
        button.setOnClickListener {
            testDirectBootFeatures()
        }
    }
    
    private fun testDirectBootFeatures() {
        try {
            // Test if device-protected storage is available
            val isDirectBootAvailable = DirectBootAlarmManager.isDeviceProtectedStorageAvailable(this)
            Log.d(TAG, "Direct Boot available: $isDirectBootAvailable")
            
            // Test saving an alarm to Direct Boot storage
            // Use silence_no_sound for the test alarm
            val resourceId = resources.getIdentifier("silence_no_sound", "raw", packageName)
            val ringtoneUriString = if (resourceId != 0) {
                "android.resource://$packageName/$resourceId"
            } else {
                "android.resource://$packageName/raw/silence_no_sound"
            }
            val ringtoneUri = Uri.parse(ringtoneUriString)
            // Use a dynamic alarm ID to prevent duplicate key issues
            val alarmStorage = AlarmStorage(this)
            val alarmId = alarmStorage.getNextAlarmId()
            val testAlarm = Alarm(
                id = alarmId,
                hour = 10,
                minute = 30,
                isEnabled = true,
                ringtoneUri = ringtoneUri,
                days = listOf(1, 3, 5), // Monday, Wednesday, Friday
                alarmTime = System.currentTimeMillis(),
                isHidden = false
            )
            
            DirectBootAlarmManager.saveAlarmToDirectBootStorage(this, testAlarm)
            Toast.makeText(this, "Saved test alarm to Direct Boot storage", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "Saved test alarm to Direct Boot storage")
            
            // Test restoring alarms
            val result = DirectBootAlarmManager.restoreAllDirectBootAlarms(this)
            Log.d(TAG, "Restoration result: ${result.message}")
            
            Toast.makeText(this, "Direct Boot test completed", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error during Direct Boot test: ${e.message}", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
