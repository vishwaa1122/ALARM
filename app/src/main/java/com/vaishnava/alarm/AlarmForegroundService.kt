package com.vaishnava.alarm

import android.app.Service
import android.content.Context
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
    private var ringtoneUri: Uri? = null
    private var audioFocusRequest: android.media.AudioFocusRequest? = null

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
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                putExtra(AlarmReceiver.ALARM_ID, currentAlarmId)
                putExtra(AlarmReceiver.EXTRA_RINGTONE_URI, ringtoneUri?.toString())
                putExtra(AlarmReceiver.EXTRA_REPEAT_DAYS, currentRepeatDays)
            }
            startActivity(intent)
        } catch (e: Exception) {
            // If the normal startActivity fails, try a more aggressive approach
            try {
                val fallbackIntent = Intent(this@AlarmForegroundService, AlarmActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or 
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or 
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                    putExtra(AlarmReceiver.ALARM_ID, currentAlarmId)
                    putExtra(AlarmReceiver.EXTRA_RINGTONE_URI, ringtoneUri?.toString())
                    putExtra(AlarmReceiver.EXTRA_REPEAT_DAYS, currentRepeatDays)
                }
                startActivity(fallbackIntent)
            } catch (fallbackException: Exception) {
                // Last resort: try to broadcast an intent that might wake up the device
                try {
                    val wakeIntent = Intent("com.vaishnava.alarm.WAKE_UP").apply {
                        addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        putExtra(AlarmReceiver.ALARM_ID, currentAlarmId)
                    }
                    sendBroadcast(wakeIntent)
                } catch (broadcastException: Exception) {
                    // If all else fails, log the error but don't crash
                }
            }
        }
    }

    // Runnable to enforce volume
    private val volumeEnforcer = object : Runnable {
        override fun run() {
            try {
                val audioManager = getSystemService(AudioManager::class.java)
                
                // Ensure audio mode is set correctly
                try {
                    if (audioManager?.mode != AudioManager.MODE_RINGTONE) {
                        audioManager?.mode = AudioManager.MODE_RINGTONE
                    }
                } catch (e: Exception) {
                    // Ignore audio mode errors
                }
                
                val currentVolume = audioManager?.getStreamVolume(AudioManager.STREAM_ALARM) ?: 0
                val maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_ALARM) ?: 0
                
                if (currentVolume < maxVolume) {
                    // Set volume to max if it's not already
                    audioManager?.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)
                }
                
                // Also enforce music stream volume
                try {
                    val musicCurrentVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                    val musicMaxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
                    if (musicCurrentVolume < musicMaxVolume) {
                        audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, musicMaxVolume, 0)
                    }
                } catch (e: Exception) {
                    // Ignore music stream errors
                }
            } catch (e: Exception) {
                // Ignore errors
            }
            
            // Continue enforcing volume while alarm is playing
            if (mediaPlayer?.isPlaying == true) {
                bgHandler.postDelayed(this, 500) // Check every 500ms for more responsive volume control
            }
        }
    }
    
    // Runnable to check for upcoming alarms and show notifications
    private val notificationChecker = object : Runnable {
        override fun run() {
            try {
                showAlarmNotificationIfNeeded()
            } catch (e: Exception) {
                // Ignore errors
            }
            
            // Continue checking for notifications
            bgHandler.postDelayed(this, 2000) // Check every 2 seconds
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
            // Clear the next alarm notification when alarm is stopped
            clearNextAlarmNotification()
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
        ringtoneUri = if (ringtoneUriString != null) {
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

        // Start periodic notification checking
        bgHandler.post(notificationChecker)
        // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call

        // Turn on the screen immediately when the alarm starts
        turnScreenOn()

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
            
            // Set audio mode to ensure alarm uses the correct stream
            try {
                audioManager.mode = AudioManager.MODE_RINGTONE
            } catch (e: Exception) {
                // Ignore if we can't set audio mode
            }
            
            previousAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0) // No flags for immediate volume change
            
            // Also set the music stream to max to ensure maximum loudness
            // Some devices route alarm sounds through the music stream for better loudness
            try {
                val musicMaxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, musicMaxVolume, 0)
            } catch (e: Exception) {
                // Ignore if we can't set music stream volume
            }

            // Request audio focus for alarm
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            
            // Use AudioFocusRequest for API 26+
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                audioFocusRequest = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(audioAttributes)
                    .build()
                audioManager.requestAudioFocus(audioFocusRequest!!)
            } else {
                // Fallback for older versions
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(null, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            }

            bgHandler.post(volumeEnforcer) // start enforcing volume continuously

            // MediaPlayer setup
            try {
                val mp = MediaPlayer()
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
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
                            setVolume(1.0f, 1.0f) // Set volume to maximum
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
                            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call
                            start()
                        }
                        // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call
                    } else {
                        // Fallback to manual setup
                        // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call
                        val mp = MediaPlayer()
                        mp.setAudioAttributes(attrs)
                        mp.setDataSource(this, finalRingtoneUri)
                        mp.isLooping = false // We'll handle looping manually for better control
                        mp.setVolume(1.0f, 1.0f) // Set volume to maximum
                        // Add completion listener to ensure audio keeps playing
                        mp.setOnCompletionListener { mp ->
                            try {
                                // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call
                                // Always restart for alarms, regardless of isOneTimeAlarm
                                if (mp.isPlaying) {
                                    mp.stop()
                                }
                                mp.seekTo(0)
                                mp.start()
                                // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call
                            } catch (e: Exception) {
                                // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.e call
                                // Try to recreate the MediaPlayer if restart fails
                                val recreatedPlayer = restartMediaPlayer(mp, finalRingtoneUri, 0, attrs)
                                if (recreatedPlayer != null) {
                                    mediaPlayer = recreatedPlayer
                                    // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call
                                }
                            }
                        }
                        // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call
                        mp.prepare()
                        mp.start()
                        mediaPlayer = mp
                        // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call: $finalRingtoneUri")
                    }
                } else {
                    // Handle non-resource URIs (e.g., system ringtones)
                    val mp = MediaPlayer()
                    mp.setAudioAttributes(attrs)
                    mp.setDataSource(this, finalRingtoneUri)
                    mp.isLooping = false // We'll handle looping manually for better control
                    mp.setVolume(1.0f, 1.0f) // Set volume to maximum
                    // Add completion listener to ensure audio keeps playing
                    mp.setOnCompletionListener { mp ->
                        try {
                            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call
                            // Always restart for alarms, regardless of isOneTimeAlarm
                            if (mp.isPlaying) {
                                mp.stop()
                            }
                            mp.seekTo(0)
                            mp.start()
                            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call
                        } catch (e: Exception) {
                            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.e call
                            // Try to recreate the MediaPlayer if restart fails
                            val recreatedPlayer = restartMediaPlayer(mp, finalRingtoneUri, 0, attrs)
                            if (recreatedPlayer != null) {
                                mediaPlayer = recreatedPlayer
                                // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call")
                            }
                        }
                    }
                    // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call
                    mp.prepare()
                    mp.start()
                    mediaPlayer = mp
                    // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call: $finalRingtoneUri")
                }
            } catch (e: Exception) {
                // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call
                // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call}", e)
                
                // Try fallback with Ringtone
                try {
                    // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call
                    val ringtone = RingtoneManager.getRingtone(this, finalRingtoneUri)
                    ringtone.audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    ringtone.isLooping = true
                    ringtone.play()
                    // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call
                } catch (e2: Exception) {
                    // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call
                }
                
                // Start volume enforcer even for Ringtone fallback
                bgHandler.post(volumeEnforcer)
            }
        }

        // Vibration pattern
        try {
            val vibrationPattern = longArrayOf(0, 500, 500, 500) // Start immediately, vibrate 500ms, pause 500ms, vibrate 500ms
            if (vibrator?.hasVibrator() == true) {
                // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    val vibrationEffect = VibrationEffect.createWaveform(vibrationPattern, intArrayOf(0, 2, 1, 2), 0) // Repeat from index 0
                    vibrator?.vibrate(vibrationEffect)
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(vibrationPattern, 0) // Repeat from index 0
                }
                // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call
            } else {
                // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call
            }
        } catch (e: Exception) {
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call
        }

        // Special handling for one-time alarms
        if (isOneTimeAlarm) {
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call
        } else {
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call
        }

        return START_STICKY // Restart service if killed
    }

    private fun turnScreenOn() {
        try {
            // Acquire a wake lock to turn the screen on
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or 
                PowerManager.ACQUIRE_CAUSES_WAKEUP or 
                PowerManager.ON_AFTER_RELEASE, 
                "AlarmApp::TurnScreenOn"
            )
            wakeLock.acquire(5000) // Acquire for 5 seconds
            wakeLock.release()
        } catch (e: Exception) {
            // If we can't acquire a wake lock, at least try to turn the screen on
            try {
                // Send a broadcast to wake up the device
                val wakeIntent = Intent("com.vaishnava.alarm.WAKE_UP").apply {
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    putExtra(AlarmReceiver.ALARM_ID, currentAlarmId)
                }
                sendBroadcast(wakeIntent)
            } catch (e2: Exception) {
                // If all else fails, we've done our best
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call
        
        // Clean up MediaPlayer
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call
        } catch (e: Exception) {
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call
        }

        // Stop vibration
        try {
            vibrator?.cancel()
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call
        } catch (e: Exception) {
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call
        }

        // Restore previous alarm volume
        try {
            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, previousAlarmVolume, 0)
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call
        } catch (e: Exception) {
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call
        }
        
        // Restore music stream volume if we changed it
        try {
            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            // We don't store the previous music volume, so we'll just leave it as is
            // In a real app, we would store and restore the previous music volume
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call
        } catch (e: Exception) {
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call
        }
        
        // Reset audio mode
        try {
            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            audioManager.mode = AudioManager.MODE_NORMAL
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call
        } catch (e: Exception) {
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call
        }

        // Release audio focus
        try {
            if (audioFocusRequest != null) {
                val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
                // Use abandonAudioFocusRequest for API 26+
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    audioManager.abandonAudioFocusRequest(audioFocusRequest!!)
                } else {
                    // Fallback for older versions
                    @Suppress("DEPRECATION")
                    audioManager.abandonAudioFocus(null)
                }
                audioFocusRequest = null
            }
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call
        } catch (e: Exception) {
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call
        }

        // Clear the next alarm notification
        try {
            clearNextAlarmNotification()
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call
        } catch (e: Exception) {
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call
        }

        // Remove notification reposting callback
        bgHandler.removeCallbacks(notificationReposter)
        // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call

        // Remove activity launcher callback
        bgHandler.removeCallbacks(activityLauncher)
        // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call

        // Remove volume enforcer callback
        bgHandler.removeCallbacks(volumeEnforcer)
        // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call
        
        // Remove notification checker callback
        bgHandler.removeCallbacks(notificationChecker)
        // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call

        // Release wake lock
        if (wakeLock.isHeld) {
            try {
                wakeLock.release()
                // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call
            } catch (e: Exception) {
                // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call
            }
        }

        // Quit background thread
        try {
            bgThread.quit()
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call
        } catch (e: Exception) {
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call
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
            putExtra(AlarmReceiver.EXTRA_RINGTONE_URI, ringtoneUri?.toString())
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

    // =====================================================
    // Fields
    // =====================================================
    private var lastNotifiedAlarmId: Int? = null
    private var lastNotificationTime: Long = 0

    // =====================================================
    // Main Function (original kept; new logic appended)
    // =====================================================
    private fun showAlarmNotificationIfNeeded() {
        try {
            val nextAlarm = getNextScheduledAlarm()
            if (nextAlarm != null) {
                val timeUntilAlarm = getTimeUntilNextAlarmInMinutes(nextAlarm)
                val now = System.currentTimeMillis()

                // =====================================================
                // ✅ ORIGINAL LOGIC  (ONLY BOUNDARY FIXED)
                // Was: if (timeUntilAlarm <= 1.05 && timeUntilAlarm > 0.95)
                // NOW: trigger only when < 1.0 min
                // =====================================================
                if (timeUntilAlarm <= 1.0 && timeUntilAlarm > 0.0) {
                    // Only show notification once per alarm when it enters the 1-minute window
                    // Allow notification if it's a different alarm or if we haven't shown it for this alarm
                    if (lastNotifiedAlarmId != nextAlarm.id) {
                        showNotification(
                            "Next Alarm",
                            "${formatTime12Hour(nextAlarm.hour, nextAlarm.minute)}"
                        )
                        lastNotifiedAlarmId = nextAlarm.id
                        lastNotificationTime = now
                    }
                } else {
                    // Reset the notification tracking when the alarm is more than 1 minute away
                    if (timeUntilAlarm > 1.2) {
                        lastNotifiedAlarmId = null
                    }
                }
                // ✅ END ORIGINAL LOGIC
                // =====================================================
            }
        } catch (e: Exception) {
            // Handle any errors silently
        }
    }

    // =====================================================
    // Helper (NEW) — compute millis until next occurrence
    // =====================================================
    private fun getMillisUntilNextAlarm(alarm: com.vaishnava.alarm.data.Alarm): Long {
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
            set(java.util.Calendar.HOUR_OF_DAY, alarm.hour)
            set(java.util.Calendar.MINUTE, alarm.minute)
        }

        val now = System.currentTimeMillis()
        var target = cal.timeInMillis
        if (target <= now) {
            // if today's time already passed, roll to next day
            target += 24L * 60 * 60 * 1000
        }
        return target - now
    }

    private fun getTimeUntilNextAlarmInMinutes(alarm: com.vaishnava.alarm.data.Alarm): Double {
        val nextAlarmTime = calculateNextAlarmTime(alarm)
        val now = System.currentTimeMillis()
        val diffInMillis = nextAlarmTime - now
        return diffInMillis.toDouble() / (1000.0 * 60.0)
    }
    
    private fun getNextScheduledAlarm(): com.vaishnava.alarm.data.Alarm? {
        val alarms = alarmStorage.getAlarms().filter { it.isEnabled }
        if (alarms.isEmpty()) return null

        var nextAlarm: com.vaishnava.alarm.data.Alarm? = null
        var nextAlarmTime = Long.MAX_VALUE

        for (alarm in alarms) {
            val alarmTime = calculateNextAlarmTime(alarm)
            if (alarmTime < nextAlarmTime) {
                nextAlarmTime = alarmTime
                nextAlarm = alarm
            }
        }

        return nextAlarm
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

    private fun clearNextAlarmNotification() {
        val notificationManager = getSystemService(android.app.NotificationManager::class.java)
        notificationManager.cancel(2)
        
        // Also reset the notification tracking
        lastNotifiedAlarmId = null
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
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call
            
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
            newPlayer.setVolume(1.0f, 1.0f) // Set volume to maximum
            newPlayer.start()
            
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.call
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
    
    
