package com.vaishnava.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class NotificationRepostReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "NotificationRepostReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) {
            Log.e(TAG, "Context or intent is null")
            return
        }

        try {
            val action = intent.action
            val alarmId = intent.getIntExtra("ALARM_ID", -1)
            
            Log.d(TAG, "Received intent with action: $action for alarm ID: $alarmId")
            
            // Handle notification dismissed action
            if (action == Constants.ACTION_NOTIFICATION_DISMISSED) {
                Log.d(TAG, "Notification dismissed for alarm ID: $alarmId, reposting...")
                
                // Instead of creating a new notification, we'll send a broadcast to the AlarmForegroundService
                // to repost the notification. This ensures the service continues to manage the audio.
                val repostIntent = Intent(context, AlarmForegroundService::class.java)
                repostIntent.action = Constants.ACTION_REPOST_NOTIFICATION
                repostIntent.putExtra("ALARM_ID", alarmId)
                
                // Start the service with the repost action
                context.startService(repostIntent)
                
                Log.d(TAG, "Sent repost request to AlarmForegroundService for alarm ID: $alarmId")
            }
            // Handle boot completed action
            else if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
                Log.d(TAG, "Boot completed, ensuring alarm service is running...")
                // The BootReceiver should handle alarm restoration
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle intent: ${e.message}", e)
        }
    }
}
