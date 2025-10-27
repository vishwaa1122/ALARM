package com.vaishnava.alarm

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.vaishnava.alarm.data.Alarm // Added this import

class AlarmStorage(context: Context) {

    private val sharedPreferences: SharedPreferences

    init {
        // Use a device-protected storage context for Direct Boot compatibility
        val deviceProtectedContext = context.createDeviceProtectedStorageContext()
        sharedPreferences = deviceProtectedContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun saveAlarms(alarms: List<Alarm>) {
        val json = Gson().toJson(alarms)
        sharedPreferences.edit().putString(KEY_ALARMS, json).apply()
        
        Log.d("AlarmStorage", "Saved ${alarms.size} alarms to storage")
        for (alarm in alarms) {
            Log.d("AlarmStorage", "Saved alarm ID: ${alarm.id}, Time: ${alarm.hour}:${alarm.minute}, Enabled: ${alarm.isEnabled}")
        }
    }

    fun getAlarms(): List<Alarm> {
        val json = sharedPreferences.getString(KEY_ALARMS, null)
        val alarms: List<Alarm> = if (json != null) {
            Gson().fromJson(json, object : TypeToken<List<Alarm>>() {}.type)
        } else {
            emptyList()
        }
        
        Log.d("AlarmStorage", "Retrieved ${alarms.size} alarms from storage")
        for (alarm in alarms) {
            Log.d("AlarmStorage", "Retrieved alarm ID: ${alarm.id}, Time: ${alarm.hour}:${alarm.minute}, Enabled: ${alarm.isEnabled}")
        }
        
        return alarms
    }

    fun addAlarm(alarm: Alarm) {
        synchronized(this) {
            val currentAlarms = getAlarms().toMutableList()
            // All alarms are handled consistently
            val finalAlarm: Alarm = alarm
            
            Log.d("AlarmStorage", "addAlarm called with ID: ${alarm.id}")
            Log.d("AlarmStorage", "Current alarms count: ${currentAlarms.size}")
            
            // Check if an alarm with the same ID already exists
            val existingIndex = currentAlarms.indexOfFirst { it.id == finalAlarm.id }
            if (existingIndex != -1) {
                // If an alarm with the same ID exists, update it instead of adding a duplicate
                Log.d("AlarmStorage", "Updating existing alarm with ID: ${finalAlarm.id}")
                currentAlarms[existingIndex] = finalAlarm
            } else {
                // If no alarm with the same ID exists, add the new alarm
                Log.d("AlarmStorage", "Adding new alarm with ID: ${finalAlarm.id}")
                currentAlarms.add(finalAlarm)
            }
            saveAlarms(currentAlarms)
            
            Log.d("AlarmStorage", "Added/Updated alarm ID: ${alarm.id}, Time: ${alarm.hour}:${alarm.minute}, Enabled: ${alarm.isEnabled}")
        }
    }

    fun updateAlarm(updatedAlarm: Alarm) {
        val currentAlarms = getAlarms().toMutableList()
        val index = currentAlarms.indexOfFirst { it.id == updatedAlarm.id }
        if (index != -1) {
            val finalAlarm: Alarm = updatedAlarm
            
            currentAlarms[index] = finalAlarm
            saveAlarms(currentAlarms)
        }
    }

    fun deleteAlarm(alarmId: Int) {
        val currentAlarms = getAlarms().toMutableList()
        currentAlarms.removeIf { it.id == alarmId }
        saveAlarms(currentAlarms)
    }

    fun getNextAlarmId(): Int {
        synchronized(this) {
            val currentAlarms = getAlarms()
            Log.d("AlarmStorage", "Current alarms when generating ID: ${currentAlarms.size}")
            for (alarm in currentAlarms) {
                Log.d("AlarmStorage", "Existing alarm ID: ${alarm.id}")
            }
            val nextId = if (currentAlarms.isEmpty()) {
                1
            } else {
                val maxId = currentAlarms.maxOf { it.id }
                Log.d("AlarmStorage", "Max existing ID: $maxId")
                maxId + 1
            }
            Log.d("AlarmStorage", "getNextAlarmId() returning: $nextId")
            return nextId
        }
    }

    companion object {
        private const val PREF_NAME = "alarm_prefs"
        private const val KEY_ALARMS = "alarms"
    }
}
