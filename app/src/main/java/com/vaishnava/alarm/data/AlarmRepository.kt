package com.vaishnava.alarm.data

import android.content.Context
import com.vaishnava.alarm.data.Alarm // Added this import
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AlarmRepository(private val context: Context) {

    // In a real application, you would use a Room Database here.
    // This is a simple in-memory storage for demonstration purposes.
    private val alarms = mutableMapOf<Int, Alarm>()

    suspend fun getAlarmById(id: Int): Alarm? = withContext(Dispatchers.IO) {
        alarms[id]
    }

    suspend fun update(alarm: Alarm) = withContext(Dispatchers.IO) {
        alarms[alarm.id] = alarm
    }

    suspend fun insert(alarm: Alarm) = withContext(Dispatchers.IO) {
        alarms[alarm.id] = alarm
    }

    suspend fun delete(id: Int) = withContext(Dispatchers.IO) {
        alarms.remove(id)
    }
}
