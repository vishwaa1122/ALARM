  package com.vaishnava.alarm

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import com.vaishnava.alarm.data.Alarm // Added this import
import com.google.android.gms.auth.api.signin.GoogleSignIn

class AlarmStorage(context: Context) {

    private val sharedPreferences:  SharedPreferences
    private val appContext: Context
    private val gson: Gson

    init {
        // Use a device-protected storage context for Direct Boot compatibility
        val deviceProtectedContext = context.createDeviceProtectedStorageContext()
        sharedPreferences = deviceProtectedContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        appContext = deviceProtectedContext
        gson = Gson().newBuilder()
            .registerTypeAdapter(Uri::class.java, UriAdapter())
            .create()
    }

    fun saveAlarms(alarms: List<Alarm>) {
        val json = gson.toJson(alarms)
        sharedPreferences.edit().putString(KEY_ALARMS, json).apply()
        
        Log.d("AlarmStorage", "Saved ${alarms.size} alarms to storage")
        for (alarm in alarms) {
            Log.d("AlarmStorage", "Saved alarm ID: ${alarm.id}, Time: ${alarm.hour}:${alarm.minute}, Enabled: ${alarm.isEnabled}, Ringtone: ${alarm.ringtoneUri}")
        }

        // Also persist ringtone URIs in a device-protected prefs for Direct Boot playback
        try {
            val prefs = appContext.getSharedPreferences("direct_boot_alarm_prefs", Context.MODE_PRIVATE)
            val editor = prefs.edit()
            alarms.forEach { alarm ->
                editor.putString("direct_boot_ringtone_${alarm.id}", alarm.ringtoneUri?.toString())
            }
            editor.apply()
        } catch (_: Exception) { }

        // Attempt cloud backup to Google Drive appData if user is signed in
        try {
            val account = GoogleSignIn.getLastSignedInAccount(appContext)
            if (account != null) {
                CloudAlarmStorage(appContext).saveAlarmsToCloud(alarms) { success ->
                    Log.d("AlarmStorage", "Cloud backup ${if (success) "succeeded" else "failed"}")
                }
            }
        } catch (e: Exception) {
            Log.w("AlarmStorage", "Cloud backup skipped: ${e.message}")
        }
    }

    fun getAlarms(): List<Alarm> {
        val json = sharedPreferences.getString(KEY_ALARMS, null)
        val alarms: List<Alarm> = if (json != null) {
            gson.fromJson(json, object : TypeToken<List<Alarm>>() {}.type)
        } else {
            emptyList()
        }
        
        Log.d("AlarmStorage", "Retrieved ${alarms.size} alarms from storage")
        for (alarm in alarms) {
            Log.d("AlarmStorage", "Retrieved alarm ID: ${alarm.id}, Time: ${alarm.hour}:${alarm.minute}, Enabled: ${alarm.isEnabled}, Ringtone: ${alarm.ringtoneUri}")
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
            
            Log.d("AlarmStorage", "Added/Updated alarm ID: ${alarm.id}, Time: ${alarm.hour}:${alarm.minute}, Enabled: ${alarm.isEnabled}, Ringtone: ${alarm.ringtoneUri}")
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

    fun getAlarm(alarmId: Int): Alarm? {
        return getAlarms().firstOrNull { it.id == alarmId }
    }

    fun disableAlarm(alarmId: Int) {
        val currentAlarms = getAlarms().toMutableList()
        val index = currentAlarms.indexOfFirst { it.id == alarmId }
        if (index != -1) {
            val existing = currentAlarms[index]
            currentAlarms[index] = existing.copy(isEnabled = false)
            saveAlarms(currentAlarms)
        }
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

class UriAdapter : TypeAdapter<Uri>() {
    override fun write(out: JsonWriter, value: Uri?) {
        out.value(value?.toString())
    }

    override fun read(input: JsonReader): Uri? {
        val uriString = input.nextString()
        return if (uriString.isNullOrEmpty()) null else Uri.parse(uriString)
    }
}
