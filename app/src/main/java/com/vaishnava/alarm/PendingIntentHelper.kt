package com.vaishnava.alarm.util


import android.app.PendingIntent
import android.content.Context
import android.content.Intent


object PendingIntentHelper {
    fun buildAlarmIntent(context: Context, alarmId: Int, alarmUri: String?): Intent {
        val intent = Intent(context, com.vaishnava.alarm.AlarmReceiver::class.java)
        intent.action = "com.vaishnava.alarm.ACTION_ALARM"
        intent.putExtra("alarm_id", alarmId)
        if (alarmUri != null) intent.putExtra("alarm_uri", alarmUri)
        return intent
    }


    fun buildAlarmPendingIntent(context: Context, alarmId: Int, alarmUri: String?): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val intent = buildAlarmIntent(context, alarmId, alarmUri)
        return PendingIntent.getBroadcast(context, alarmId, intent, flags)
    }
}
