package com.vaishnava.alarm

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.util.Log

class AlarmDataService : Service() {
    
    companion object {
        private const val TAG = "AlarmDataService"
        const val PREFS_NAME = "alarm_data_cache"
    }
    
    private lateinit var prefs: SharedPreferences
    
    override fun onCreate() {
        super.onCreate()
        prefs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            createDeviceProtectedStorageContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } else {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
        Log.d(TAG, "AlarmDataService created")
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        return null // Not needed for this service
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "CACHE_ALARM_DATA" -> {
                val alarmId = intent.getIntExtra("alarm_id", -1)
                val missionType = intent.getStringExtra("mission_type") ?: ""
                val missionPassword = intent.getStringExtra("mission_password") ?: ""
                
                // Cache alarm data for instant access when screen is off
                prefs.edit().apply {
                    putString("mission_type_$alarmId", missionType)
                    putString("mission_password_$alarmId", missionPassword)
                    putLong("cache_time_$alarmId", System.currentTimeMillis())
                    apply()
                }
                
                Log.d(TAG, "Cached alarm data: alarmId=$alarmId, missionType=$missionType")
            }
            "GET_ALARM_DATA" -> {
                val alarmId = intent.getIntExtra("alarm_id", -1)
                val missionType = prefs.getString("mission_type_$alarmId", "")
                val missionPassword = prefs.getString("mission_password_$alarmId", "")
                
                Log.d(TAG, "Retrieved alarm data: alarmId=$alarmId, missionType=$missionType")
                
                // Return cached data to AlarmActivity
                val resultIntent = Intent().apply {
                    putExtra("mission_type", missionType)
                    putExtra("mission_password", missionPassword)
                }
                
                // Send broadcast back to AlarmActivity
                sendBroadcast(resultIntent, "ALARM_DATA_RESPONSE")
            }
            else -> return START_NOT_STICKY
        }
        
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "AlarmDataService destroyed")
    }
}
