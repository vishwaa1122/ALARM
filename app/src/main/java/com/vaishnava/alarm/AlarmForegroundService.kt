package com.vaishnava.alarm

import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.getSystemService
import java.util.concurrent.atomic.AtomicBoolean
import com.vaishnava.alarm.data.Alarm
import com.vaishnava.alarm.R

class AlarmForegroundService : Service() {
    companion object {
        private const val TAG = "AlarmForegroundService"
        private const val REPOST_DELAY_MS = 5000L // 5 seconds
    }

    private lateinit var alarmStorage: AlarmStorage
    private lateinit var wakeLock: PowerManager.WakeLock
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var currentAlarmId: Int = -1
    private var currentRepeatDays: IntArray? = null
    private var isOneTimeAlarm: Boolean = false
    private var previousAlarmVolume: Int = 0
    private val launcherScheduled = AtomicBoolean(false)

    // Handler and Runnable for periodic reposting
    private lateinit var bgHandler: Handler
    private lateinit var bgThread: HandlerThread
    private val notificationReposter = object : Runnable {
        override fun run() {
            // Repost the notification periodically to ensure it stays visible
            val notification = buildNotification()
            startForeground(1, notification)
            
            // Schedule next repost
            bgHandler.postDelayed(this, REPOST_DELAY_MS)
        }
    }

    // Runnable to launch the activity
    private val activityLauncher = Runnable {
        if (!launcherScheduled.get()) {
            return@Runnable
        }

        try {
            val intent = Intent(this@AlarmForegroundService, AlarmActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(AlarmReceiver.ALARM_ID, currentAlarmId)
                putExtra(AlarmReceiver.EXTRA_RINGTONE_URI, mediaPlayer?.currentUri?.toString())
                putExtra(AlarmReceiver.EXTRA_REPEAT_DAYS, currentRepeatDays)
            }
            startActivity(intent)
        } catch (e: Exception) {
        }
    }

    // Runnable to enforce volume
    private val volumeEnforcer = object : Runnable {
        override fun run() {
            try {
                val audioManager = getSystemService(AudioManager::class.java)
                val currentVolume = audioManager?.getStreamVolume(AudioManager.STREAM_ALARM) ?: 0
                val maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_ALARM) ?: 0
                
                if (currentVolume < maxVolume) {
                } else {
                }
            } catch (e: Exception) {
            }
            
            // Continue enforcing volume while alarm is playing
            if (mediaPlayer?.isPlaying == true) {
                bgHandler.postDelayed(this, 1000) // Check every second
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        
        alarmStorage = AlarmStorage(this)
        
        // Set up wake lock
        val powerManager = getSystemService<PowerManager>()!!
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AlarmApp::AlarmService")
        
        // Set up background thread and handler
        bgThread = HandlerThread("AlarmServiceBackground")
        bgThread.start()
        bgHandler = Handler(bgThread.looper)
        
        // Set up vibrator
        vibrator = getSystemService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle stop action
        if (intent?.action == Constants.ACTION_STOP_FOREGROUND_SERVICE) {
            stopSelf()
            return START_NOT_STICKY
        }
        
        // Acquire wake lock - make it indefinite for the duration of the alarm
        if (!wakeLock.isHeld) {
            // Acquire indefinite wake lock without timeout
            wakeLock.acquire()
        }
        
        // Extract alarm data from intent
        currentAlarmId = intent?.getIntExtra(AlarmReceiver.ALARM_ID, -1) ?: -1
        val ringtoneUriString = intent?.getStringExtra(AlarmReceiver.EXTRA_RINGTONE_URI)
        val ringtoneUri = if (ringtoneUriString != null) {
            // Handle special test cases
            if (ringtoneUriString == "test_ringtone") {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            } else {
                try {
                    Uri.parse(ringtoneUriString)
                } catch (e: Exception) {
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                }
            }
        } else {
            null
        }
        currentRepeatDays = intent?.getIntArrayExtra(AlarmReceiver.EXTRA_REPEAT_DAYS) ?: intArrayOf()
        
        // Show notification when alarm is about to trigger
        showAlarmNotificationIfNeeded()
        
        // Removed isProtectedAlarm since we've removed all special 5 AM alarm handling

        if (ringtoneUri != null) {
            // Special handling for glassy_bell to ensure it works
            if (ringtoneUriString?.contains("glassy_bell") == true) {
            }
        }

        // Fix: Handle null currentRepeatDays correctly
        val isOneTimeAlarm = currentRepeatDays?.isEmpty() ?: true
        
        // All alarms are handled consistently
        val isSpecialAlarm = false
        if (currentAlarmId != -1) {
            try {
                val alarm = alarmStorage.getAlarms().find { it.id == currentAlarmId }
                if (alarm != null) {
                } else {
                }
            } catch (e: Exception) {
            }
        }

        // Build and show notification
        val notification = buildNotification()
        startForeground(1, notification)

        // Start periodic notification reposting
        bgHandler.post(notificationReposter)
        // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call

        if (launcherScheduled.compareAndSet(false, true)) {
            bgHandler.post(activityLauncher)
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
        }

        // Fallback to default alarm sound if ringtone URI is invalid
        val finalRingtoneUri = if (ringtoneUri != null) {
            ringtoneUri
        } else {
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        }

        if (finalRingtoneUri != null) {
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call

            try { mediaPlayer?.stop() } catch (_: Throwable) {}
            try { mediaPlayer?.release() } catch (_: Throwable) {}
            mediaPlayer = null

            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            previousAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, AudioManager.FLAG_PLAY_SOUND)

            bgHandler.post(volumeEnforcer) // start enforcing volume continuously

            // MediaPlayer setup
            try {
                val mp = MediaPlayer()
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                mp.setAudioAttributes(attrs)

                if (finalRingtoneUri.scheme == "android.resource") {
                    val packageName = finalRingtoneUri.authority
                    
                    // Handle both URI formats:
                    // 1. android.resource://package/resourceId (numeric ID)
                    // 2. android.resource://package/raw/resourceName (path format)
                    
                    val pathSegments = finalRingtoneUri.pathSegments
                    var resourceId = 0
                    
                    if (pathSegments.size == 1) {
                        // Format: android.resource://package/resourceId
                        try {
                            resourceId = pathSegments[0].toInt()
                            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
                        } catch (e: NumberFormatException) {
                            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
                        }
                    } else if (pathSegments.size == 2 && pathSegments[0] == "raw") {
                        // Format: android.resource://package/raw/resourceName
                        val resourceName = pathSegments[1]
                        resourceId = resources.getIdentifier(resourceName, "raw", packageName)
                        // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
                    }
                    
                    if (resourceId != 0) {
                        // For reliable looping, we'll handle it manually rather than relying on MediaPlayer's isLooping
                        mediaPlayer = MediaPlayer.create(this, resourceId).apply {
                            setAudioAttributes(attrs)
                            isLooping = false // We'll handle looping manually for better control
                            // Add completion listener to ensure audio keeps playing
                            setOnCompletionListener { mp ->
                                try {
                                    // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
                                    // Always restart for alarms, regardless of isOneTimeAlarm
                                    if (mp.isPlaying) {
                                        mp.stop()
                                    }
                                    mp.seekTo(0)
                                    mp.start()
                                    // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
                                } catch (e: Exception) {
                                    // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.e call
                                    // Try to recreate the MediaPlayer if restart fails
                                    val recreatedPlayer = restartMediaPlayer(mp, finalRingtoneUri, resourceId, attrs)
                                    if (recreatedPlayer != null) {
                                        mediaPlayer = recreatedPlayer
                                        // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
                                    }
                                }
                            }
                            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
                            start()
                        }
                        // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
                    } else {
                        // Fallback to manual setup
                        // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
                        val mp = MediaPlayer()
                        mp.setAudioAttributes(attrs)
                        mp.setDataSource(this, finalRingtoneUri)
                        mp.isLooping = false // We'll handle looping manually for better control
                        // Add completion listener to ensure audio keeps playing
                        mp.setOnCompletionListener { mp ->
                            try {
                                // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
                                // Always restart for alarms, regardless of isOneTimeAlarm
                                if (mp.isPlaying) {
                                    mp.stop()
                                }
                                mp.seekTo(0)
                                mp.start()
                                // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
                            } catch (e: Exception) {
                                // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.e call
                                // Try to recreate the MediaPlayer if restart fails
                                val recreatedPlayer = restartMediaPlayer(mp, finalRingtoneUri, 0, attrs)
                                if (recreatedPlayer != null) {
                                    mediaPlayer = recreatedPlayer
                                    // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call")
                                }
                            }
                        }
                        // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
                        mp.prepare()
                        mp.start()
                        mediaPlayer = mp
                        // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call: $finalRingtoneUri")
                    }
                } else {
                    // Handle non-resource URIs (e.g., system ringtones)
                    val mp = MediaPlayer()
                    mp.setAudioAttributes(attrs)
                    mp.setDataSource(this, finalRingtoneUri)
                    mp.isLooping = false // We'll handle looping manually for better control
                    // Add completion listener to ensure audio keeps playing
                    mp.setOnCompletionListener { mp ->
                        try {
                            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
                            // Always restart for alarms, regardless of isOneTimeAlarm
                            if (mp.isPlaying) {
                                mp.stop()
                            }
                            mp.seekTo(0)
                            mp.start()
                            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
                        } catch (e: Exception) {
                            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.e call
                            // Try to recreate the MediaPlayer if restart fails
                            val recreatedPlayer = restartMediaPlayer(mp, finalRingtoneUri, 0, attrs)
                            if (recreatedPlayer != null) {
                                mediaPlayer = recreatedPlayer
                                // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call")
                            }
                        }
                    }
                    // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
                    mp.prepare()
                    mp.start()
                    mediaPlayer = mp
                    // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call: $finalRingtoneUri")
                }
            } catch (e: Exception) {
                // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
                // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call}", e)
                
                // Try fallback with Ringtone
                try {
                    // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
                    val ringtone = RingtoneManager.getRingtone(this, finalRingtoneUri)
                    ringtone.audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    ringtone.isLooping = true
                    ringtone.play()
                    // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
                } catch (e2: Exception) {
                    // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
                }
            }
        }

        // Vibration pattern
        try {
            val vibrationPattern = longArrayOf(0, 500, 500, 500) // Start immediately, vibrate 500ms, pause 500ms, vibrate 500ms
            if (vibrator?.hasVibrator() == true) {
                // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    val vibrationEffect = VibrationEffect.createWaveform(vibrationPattern, intArrayOf(0, 2, 1, 2), 0) // Repeat from index 0
                    vibrator?.vibrate(vibrationEffect)
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(vibrationPattern, 0) // Repeat from index 0
                }
                // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
            } else {
                // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
            }
        } catch (e: Exception) {
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
        }

        // Special handling for one-time alarms
        if (isOneTimeAlarm) {
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
        } else {
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
        }

        return START_STICKY // Restart service if killed
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
        
        // Clean up MediaPlayer
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
        } catch (e: Exception) {
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
        }

        // Stop vibration
        try {
            vibrator?.cancel()
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
        } catch (e: Exception) {
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
        }

        // Restore previous alarm volume
        try {
            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, previousAlarmVolume, 0)
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
        } catch (e: Exception) {
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
        }

        // Remove notification reposting callback
        bgHandler.removeCallbacks(notificationReposter)
        // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call

        // Remove activity launcher callback
        bgHandler.removeCallbacks(activityLauncher)
        // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call

        // Remove volume enforcer callback
        bgHandler.removeCallbacks(volumeEnforcer)
        // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call

        // Release wake lock
        if (wakeLock.isHeld) {
            try {
                wakeLock.release()
                // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
            } catch (e: Exception) {
                // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
            }
        }

        // Quit background thread
        try {
            bgThread.quit()
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
        } catch (e: Exception) {
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
        }

        super.onDestroy()
    }

    private fun buildNotification(): android.app.Notification {
        createNotificationChannel()

        // Build the notification
        val notificationBuilder = android.app.Notification.Builder(this, "alarm_channel")
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(android.graphics.Color.parseColor("#1976D2")) // Use brand color for notification
            .setContentTitle("Alarm Active")
            .setContentText("Your alarm is ringing")
            .setPriority(android.app.Notification.PRIORITY_HIGH)
            .setCategory(android.app.Notification.CATEGORY_ALARM)
            .setFullScreenIntent(createFullScreenIntent(), true) // This is key for lock screen display
            .setOngoing(true)
            .setAutoCancel(false)

        return notificationBuilder.build()
    }

    private fun createFullScreenIntent(): android.app.PendingIntent {
        val intent = Intent(this, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(AlarmReceiver.ALARM_ID, currentAlarmId)
            putExtra(AlarmReceiver.EXTRA_RINGTONE_URI, mediaPlayer?.currentUri?.toString())
            putExtra(AlarmReceiver.EXTRA_REPEAT_DAYS, currentRepeatDays)
        }
        
        return android.app.PendingIntent.getActivity(
            this,
            currentAlarmId,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                "alarm_channel",
                "Alarm Notifications",
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for active alarms"
                enableVibration(false) // We handle vibration ourselves
                enableLights(true)
            }
            val notificationManager = getSystemService(android.app.NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showAlarmNotificationIfNeeded() {
        if (currentAlarmId != -1) {
            try {
                val alarm = alarmStorage.getAlarms().find { it.id == currentAlarmId }
                if (alarm != null) {
                    val timeUntilAlarm = getTimeUntilNextAlarmInMinutes(alarm)
                    // Show notification when alarm is about to trigger (within 1 minute)
                    if (timeUntilAlarm <= 1) {
                        showNotification(
                            "Next Alarm",
                            "Your next alarm is at ${formatTime12Hour(alarm.hour, alarm.minute)}"
                        )
                    }
                }
            } catch (e: Exception) {
                // Handle any errors silently
            }
        }
    }

    private fun getTimeUntilNextAlarmInMinutes(alarm: com.vaishnava.alarm.data.Alarm): Long {
        val nextAlarmTime = calculateNextAlarmTime(alarm)
        val now = System.currentTimeMillis()
        val diffInMillis = nextAlarmTime - now
        return diffInMillis / (1000 * 60)
    }

    private fun calculateNextAlarmTime(alarm: com.vaishnava.alarm.data.Alarm): Long {
        val now = java.util.Calendar.getInstance()
        val alarmCalendar = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, alarm.hour)
            set(java.util.Calendar.MINUTE, alarm.minute)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }

        if (!alarm.days.isNullOrEmpty()) {
            val currentDay = now.get(java.util.Calendar.DAY_OF_WEEK)
            val currentHour = now.get(java.util.Calendar.HOUR_OF_DAY)
            val currentMinute = now.get(java.util.Calendar.MINUTE)

            if (alarm.days.contains(currentDay)) {
                if (alarm.hour > currentHour ||
                    (alarm.hour == currentHour && alarm.minute > currentMinute)
                ) {
                    return alarmCalendar.timeInMillis
                }
            }

            for (i in 1..7) {
                val checkDay = (currentDay + i - 1) % 7 + 1
                if (alarm.days.contains(checkDay)) {
                    alarmCalendar.add(java.util.Calendar.DAY_OF_YEAR, i)
                    return alarmCalendar.timeInMillis
                }
            }
        } else {
            if (alarmCalendar.timeInMillis <= now.timeInMillis) {
                alarmCalendar.add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
            return alarmCalendar.timeInMillis
        }

        return alarmCalendar.timeInMillis
    }

    private fun formatTime12Hour(hour: Int, minute: Int): String {
        val period = if (hour >= 12) "PM" else "AM"
        val formattedHour = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        return String.format("%d:%02d %s", formattedHour, minute, period)
    }

    private fun showNotification(title: String, content: String) {
        val notificationManager = getSystemService(android.app.NotificationManager::class.java)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                "next_alarm_channel",
                "Next Alarm",
                android.app.NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Shows information about your next alarm"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = androidx.core.app.NotificationCompat.Builder(this, "next_alarm_channel")
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(android.graphics.Color.parseColor("#1976D2"))
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(2, notification)
    }

    /**
     * Attempt to recreate a MediaPlayer if the normal restart fails
     */
    private fun restartMediaPlayer(
        originalPlayer: MediaPlayer,
        ringtoneUri: Uri,
        resourceId: Int,
        attrs: AudioAttributes
    ): MediaPlayer? {
        return try {
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
            
            // Release the original player
            try {
                originalPlayer.stop()
            } catch (_: Exception) {}
            try {
                originalPlayer.release()
            } catch (_: Exception) {}
            
            // Create a new player
            val newPlayer = if (resourceId != 0) {
                MediaPlayer.create(this, resourceId)
            } else {
                val mp = MediaPlayer()
                mp.setAudioAttributes(attrs)
                mp.setDataSource(this, ringtoneUri)
                mp.prepare()
                mp
            }
            
            newPlayer.setAudioAttributes(attrs)
            newPlayer.isLooping = false
            newPlayer.start()
            
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
            newPlayer
        } catch (e: Exception) {
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call
            null
        }
    }
}

// Extension property to get the current URI from a MediaPlayer
private val MediaPlayer.currentUri: Uri?
    get() = try {
        // This is a simplified approach - in reality, MediaPlayer doesn't expose the URI directly
        // We would need to track it separately in a real implementation
        null
    } catch (e: Exception) {
        null
    }
