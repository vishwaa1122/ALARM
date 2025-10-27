package com.vaishnava.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.vaishnava.alarm.util.PermissionUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ForensicAlarmReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "ChronaForensics"
        private const val CHANNEL_ID = "forensic_logs"
        private const val NOTIFICATION_ID = 1001
        private const val WHATSAPP_NUMBER = "918431573944" // Country code + number without +
    }

    override fun onReceive(context: Context, intent: Intent) {
        try {
            val forceLogging = intent.getBooleanExtra("force_logging", false)
            val alarmId = intent.getIntExtra("alarm_id", -1)
            val logFilename = intent.getStringExtra("log_filename")
            
            // PATCHED_BY_AUTOFIXER: Removed comment about UnifiedLogger timestamped filename
            // Use the timestamped filename passed from caller, or default to static name
            val filename = logFilename ?: "Chrona_Forensic_Report.txt"
            
            // Always perform forensic logging
            performForensicLogging(context, intent)
        } catch (e: Exception) {
            Log.e("ForensicAlarmReceiver", "Error in onReceive: ${e.message}", e)
        }
    }

    private fun performForensicLogging(context: Context, intent: Intent) {
        try {
            Log.d(TAG, "Performing forensic logging")
            
            // Create forensic log file
            val logFile = createForensicLog(context, intent)
            
            // Show notification with share option
            showNotification(context, logFile)
            
            Log.d(TAG, "Forensic logging completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error performing forensic logging", e)
        }
    }

    private fun createForensicLog(context: Context, intent: Intent): File {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val alarmId = intent.getIntExtra("alarm_id", -1)
        
        val externalDir = context.getExternalFilesDir(null)
            ?: throw IllegalStateException("External directory is null")
        
        // Ensure directory exists
        if (!externalDir.exists()) {
            externalDir.mkdirs()
        }
        
        // Use the timestamped filename passed from UnifiedLogger, or default to static name
        val logFileName = intent.getStringExtra("log_filename") ?: "Chrona_Forensic_Report.txt"
        val file = File(externalDir, logFileName)
        
        try {
            file.writeText("")
            file.appendText("==== CHRONA ALARM FORENSIC REPORT ====\n")
            file.appendText("Timestamp: $timestamp\n")
            file.appendText("Alarm ID: $alarmId\n")
            file.appendText("=====================================\n\n")
            
            file.appendText("DEVICE INFORMATION:\n")
            file.appendText("- Manufacturer: ${Build.MANUFACTURER}\n")
            file.appendText("- Model: ${Build.MODEL}\n")
            file.appendText("- Android Version: ${Build.VERSION.RELEASE}\n")
            file.appendText("- SDK Version: ${Build.VERSION.SDK_INT}\n\n")
            
            file.appendText("APP INFORMATION:\n")
            file.appendText("- Package: ${context.packageName}\n")
            file.appendText("- Version: ${getAppVersion(context)}\n\n")
            
            // Add battery optimization info
            file.appendText("BATTERY OPTIMIZATION:\n")
            file.appendText("- Ignoring optimizations: ${com.vaishnava.alarm.util.PermissionUtils.isIgnoringBatteryOptimizations(context)}\n\n")
            
            // Copy recent entries from the full log
            val fullLog = File(externalDir, "Chrona_Alarm_Full_Log.txt")
            if (fullLog.exists()) {
                file.appendText("RECENT LOG ENTRIES:\n")
                file.appendText(fullLog.readText())
            }
            
            Log.d(TAG, "Created forensic log: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error creating forensic log", e)
            throw e
        }
        
        return file
    }

    private fun getAppVersion(context: Context): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
    }

    private fun showNotification(context: Context, logFile: File) {
        try {
            createNotificationChannel(context)
            
            // Create intent for sharing via WhatsApp directly to specific number
            val shareIntent = createDirectWhatsAppIntent(context, logFile)
            
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                shareIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(android.graphics.Color.parseColor("#1976D2")) // Use brand color for notification
                .setContentTitle("Chrona Alarm Forensic Report")
                .setContentText("Tap to send report directly to WhatsApp")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
            
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)
            
            Log.d(TAG, "Notification shown successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error showing notification", e)
        }
    }

    private fun createDirectWhatsAppIntent(context: Context, file: File): Intent {
        try {
            // Read the content of the file
            val fileContent = file.readText()
            
            // Create WhatsApp intent to send directly to the specific number
            val whatsappIntent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://wa.me/$WHATSAPP_NUMBER?text=${Uri.encode(fileContent)}")
                setPackage("com.whatsapp")
            }
            
            // Check if WhatsApp is installed
            if (whatsappIntent.resolveActivity(context.packageManager) != null) {
                Log.d(TAG, "WhatsApp is installed, using direct intent to $WHATSAPP_NUMBER")
                return whatsappIntent
            } else {
                // Fallback to general sharing if WhatsApp is not installed
                Log.d(TAG, "WhatsApp not installed, using general sharing")
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                
                return Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Chrona Alarm Forensic Report")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating direct WhatsApp intent, falling back to file sharing", e)
            // Ultimate fallback - just share the file
            return try {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Chrona Alarm Forensic Report")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } catch (fallbackException: Exception) {
                Log.e(TAG, "Error in fallback sharing", fallbackException)
                Intent(Intent.ACTION_VIEW) // At least open something
            }
        }
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Forensic Logs",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for forensic logging reports"
            }
            
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
