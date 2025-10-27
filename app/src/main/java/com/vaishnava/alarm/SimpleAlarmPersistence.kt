package com.vaishnava.alarm

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.vaishnava.alarm.data.Alarm
import java.io.File

/**
 * Simple file-based alarm persistence updated to use device-protected storage when available.
 * Also writes a JSON copy into device-protected SharedPreferences ("direct_boot_alarms")
 * so DirectBootRescheduler can find and reschedule alarms before unlock.
 */
object SimpleAlarmPersistence {

    private const val ALARM_FILE_NAME = "active_alarms.txt"
    private const val TAG = "SimpleAlarmPersistence"
    private val gson = Gson()

    private fun deviceProtectedContextIfAvailable(context: Context): Context {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                context.createDeviceProtectedStorageContext() ?: context
            } catch (e: Exception) {
                context
            }
        } else context
    }

    private fun alarmFile(context: Context): File {
        val ctx = deviceProtectedContextIfAvailable(context)
        // Use filesDir inside device-protected context so file is available pre-unlock
        return File(ctx.filesDir, ALARM_FILE_NAME)
    }

    fun saveAlarms(context: Context, alarms: List<Alarm>) {
        try {
            val file = alarmFile(context)
            val json = gson.toJson(alarms)
            file.parentFile?.mkdirs()
            file.writeText(json)
            DebugLog.log(TAG, "saveAlarms", "Saved ${alarms.size} alarms to file ${file.absolutePath}")

            // ALSO save into device-protected SharedPreferences used by rescheduler
            try {
                val deviceCtx = deviceProtectedContextIfAvailable(context)
                val sp = deviceCtx.getSharedPreferences("direct_boot_alarms", Context.MODE_PRIVATE)
                sp.edit().putString("alarms", json).apply()
                DebugLog.log(TAG, "saveAlarms", "Also saved alarms to DE SharedPreferences")
            } catch (e: Exception) {
                DebugLog.log(TAG, "saveAlarms", "Failed to save to DE SharedPreferences: ${e.message}")
            }
        } catch (e: Exception) {
            DebugLog.log(TAG, "saveAlarms", "Failed to save alarms to file: ${e.message}")
            Log.e(TAG, "Failed to save alarms", e)
        }
    }

    fun loadAlarms(context: Context): List<Alarm> {
        try {
            val file = alarmFile(context)
            if (!file.exists()) {
                DebugLog.log(TAG, "loadAlarms", "No alarm file found at ${file.absolutePath}")
                // fallback to DE SharedPreferences if present
                val deviceCtx = deviceProtectedContextIfAvailable(context)
                val sp = deviceCtx.getSharedPreferences("direct_boot_alarms", Context.MODE_PRIVATE)
                val json = sp.getString("alarms", null)
                if (!json.isNullOrEmpty()) {
                    val type = object : TypeToken<List<Alarm>>() {}.type
                    val alarms: List<Alarm> = gson.fromJson(json, type)
                    DebugLog.log(TAG, "loadAlarms", "Loaded ${alarms.size} alarms from DE SharedPreferences")
                    return alarms
                }
                return emptyList()
            }
            val json = file.readText()
            val type = object : TypeToken<List<Alarm>>() {}.type
            val alarms: List<Alarm> = gson.fromJson(json, type)
            DebugLog.log(TAG, "loadAlarms", "Loaded ${alarms.size} alarms from file")
            return alarms ?: emptyList()
        } catch (e: Exception) {
            DebugLog.log(TAG, "loadAlarms", "Failed to load alarms: ${e.message}")
            return emptyList()
        }
    }

    fun clearAlarmFile(context: Context) {
        try {
            val file = alarmFile(context)
            if (file.exists()) {
                file.delete()
                DebugLog.log(TAG, "clearAlarmFile", "Cleared alarm file ${file.absolutePath}")
            }
            // Also clear DE SharedPreferences backup
            try {
                val deviceCtx = deviceProtectedContextIfAvailable(context)
                val sp = deviceCtx.getSharedPreferences("direct_boot_alarms", Context.MODE_PRIVATE)
                sp.edit().remove("alarms").apply()
                DebugLog.log(TAG, "clearAlarmFile", "Cleared DE SharedPreferences")
            } catch (ignored: Exception) { }
        } catch (e: Exception) {
            DebugLog.log(TAG, "clearAlarmFile", "Failed to clear alarm file: ${e.message}")
        }
    }
}
