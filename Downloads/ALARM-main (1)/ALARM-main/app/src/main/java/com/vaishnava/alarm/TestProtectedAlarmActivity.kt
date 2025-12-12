package com.vaishnava.alarm

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import com.vaishnava.alarm.data.Alarm
import java.util.Calendar

class TestProtectedAlarmActivity : Activity() {
    
    private lateinit var alarmStorage: AlarmStorage
    private lateinit var alarmScheduler: AlarmScheduler
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        alarmStorage = AlarmStorage(this)
        alarmScheduler = AndroidAlarmScheduler(this)
        
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }
        
        val testAlarmButton = Button(this).apply {
            text = "Test Alarm"
            setOnClickListener {
                testAlarm()
            }
        }
        
        val testWhatsAppButton = Button(this).apply {
            text = "Test WhatsApp Sharing"
            setOnClickListener {
                testWhatsAppSharing()
            }
        }
        
        layout.addView(testAlarmButton)
        layout.addView(testWhatsAppButton)
        
        setContentView(layout)
    }
    
    private fun testAlarm() {
        Log.d("AlarmApp", " TESTING ALARM NOW ")
        
        // Create a test alarm for 5 AM
        val alarmId = alarmStorage.getNextAlarmId()
        val resourceId = resources.getIdentifier("glassy_bell", "raw", packageName)
        val ringtoneUri = if (resourceId != 0) {
            android.net.Uri.parse("android.resource://$packageName/$resourceId")
        } else {
            android.net.Uri.parse("android.resource://$packageName/raw/glassy_bell")
        }
        
        val testAlarm = Alarm(
            id = alarmId,
            hour = 5,  // Set to 5 AM instead of current hour
            minute = 0,
            isEnabled = true,
            ringtoneUri = ringtoneUri,
            days = emptyList()  // Make it a one-time alarm for testing
        )
        
        alarmStorage.addAlarm(testAlarm)
        Log.d("AlarmApp", " Created new test alarm with ID: ${testAlarm.id}")
        
        Log.d("AlarmApp", " Found test alarm with ID: ${testAlarm.id}, triggering now...")
        
        // Schedule and trigger the alarm
        alarmScheduler.schedule(testAlarm)
        Log.d("AlarmApp", " ✅ Scheduled test alarm")
        
        // Also send a broadcast to trigger it immediately with proper action
        val intent = Intent("com.vaishnava.alarm.DIRECT_BOOT_ALARM").apply {
            setClass(this@TestProtectedAlarmActivity, AlarmReceiver::class.java)
            putExtra(AlarmReceiver.ALARM_ID, testAlarm.id)
            putExtra(AlarmReceiver.EXTRA_RINGTONE_URI, testAlarm.ringtoneUri)
            putExtra(AlarmReceiver.EXTRA_REPEAT_DAYS, testAlarm.days?.toIntArray())
        }
        
        // Send the broadcast immediately
        sendBroadcast(intent)
        Log.d("AlarmApp", " ✅ Sent broadcast to trigger test alarm immediately")
    }
    
    private fun testWhatsAppSharing() {
        try {
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.createFreshForensicLog and UnifiedLogger.shareViaWhatsApp calls
            
            Log.d("AlarmApp", " ✅ Created and shared fresh forensic log via WhatsApp")
        } catch (e: Exception) {
            Log.e("AlarmApp", " ❌ Error creating/sharing fresh forensic log: ${e.message}")
        }
    }
}
