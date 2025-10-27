package com.vaishnava.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class AppUpdateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AppUpdateReceiver"
        private const val PREFS_NAME = "alarm_prefs"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.d(TAG, "App updated or reinstalled. Clearing alarm preferences.")
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().clear().apply()
                Log.d(TAG, "Alarm preferences cleared successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing alarm preferences: ${e.message}", e)
            }
        }
    }
}
