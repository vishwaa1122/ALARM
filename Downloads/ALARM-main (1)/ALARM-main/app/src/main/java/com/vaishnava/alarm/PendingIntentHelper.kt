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
        // CRITICAL FIX: Use alarmId as base but allow for mission-specific uniqueness
        // The main broadcast PendingIntent can use alarmId since mission routing happens in AlarmReceiver
        return PendingIntent.getBroadcast(context, alarmId, intent, flags)
    }
    
    // CRITICAL FIX: New method to create mission-specific PendingIntents for showIntent
    fun buildMissionShowPendingIntent(context: Context, alarmId: Int, missionType: String?, missionPassword: String?): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val missionSignature = "${alarmId}_${missionType}_${missionPassword}"
        val uniqueRequestCode = missionSignature.hashCode()
        
        // CRITICAL FIX: Block "none" missions from being launched via PendingIntent
        val safeMissionType = if (missionType == "none" || missionType?.contains("none") == true) "" else (missionType ?: "")
        
        val intent = Intent(context, com.vaishnava.alarm.AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra("alarm_id", alarmId)
            putExtra("mission_type", safeMissionType)
            putExtra("mission_password", missionPassword ?: "")
            putExtra("mission_signature", missionSignature)
        }
        
        return PendingIntent.getActivity(context, uniqueRequestCode, intent, flags)
    }
}
