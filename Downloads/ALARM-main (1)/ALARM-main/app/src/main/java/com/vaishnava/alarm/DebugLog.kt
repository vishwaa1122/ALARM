package com.vaishnava.alarm

import android.util.Log
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLog {
    private const val TAG = "DirectBootAlarm"

    fun log(phase: String, event: String, message: String? = null) {
        val json = JSONObject().apply {
            put("phase", phase)
            put("event", event)
            put("ts", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date()))
            message?.let { put("message", it) }
        }
        Log.d(TAG, json.toString())
    }
}
