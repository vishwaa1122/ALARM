package com.vaishnava.alarm

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.vaishnava.alarm.data.resolveRingtoneTitle
import com.vaishnava.alarm.AlarmStorage
import com.vaishnava.alarm.data.Alarm
import com.vaishnava.alarm.sequencer.MissionSequencer
import kotlinx.coroutines.*
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.timerTask
import kotlin.math.ceil
import com.vaishnava.alarm.sequencer.MissionLogger

class AlarmForegroundService : Service() {

    companion object {
        private const val TAG: String = "AlarmForegroundService"
        @Volatile private var forceRestartSequencer: com.vaishnava.alarm.sequencer.MissionSequencer? = null
        
        fun getForceRestartSequencer(): com.vaishnava.alarm.sequencer.MissionSequencer? {
            return forceRestartSequencer
        }
        
        fun clearForceRestartSequencer() {
            forceRestartSequencer = null
        }
        private const val REPOST_DELAY_MS: Long = 3000L // Reduced to 3 seconds for better persistence
        private const val PREFS_NAME = "alarm_volume_prefs"
        private const val KEY_PREV_VOLUME = "prev_alarm_volume"
        private const val MIN_PERCENT = 50 // Updated to 50% as per memory requirement

        private fun writePersistentLog(message: String) {
            try {
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val logFile = File("/sdcard/Download/alarm_debug.log")
                val writer = FileWriter(logFile, true)
                writer.append("[$timestamp] $message\n")
                writer.close()
            } catch (e: Exception) {
                // Silently fail if we can't write to external storage
            }
        }
    }

    // ========= Dependencies & state =========
    private lateinit var alarmStorage: AlarmStorage
    private lateinit var wakeLock: PowerManager.WakeLock
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var currentAlarmId: Int = -1
    private var currentRepeatDays: IntArray? = null
    private var previousAlarmVolume: Int = -1
    private val launcherScheduled: AtomicBoolean = AtomicBoolean(false)
    private var ringtoneUri: Uri? = null
    private var isWakeCheckLaunch: Boolean = false
    private var audioFocusRequest: android.media.AudioFocusRequest? = null
    
    // Mission data for Direct Boot restoration
    private var currentMissionType: String? = null
    private var currentMissionPassword: String? = null
    private var currentIsProtected: Boolean = false
    private var duckRestoreRunnable: Runnable? = null
    private var alarmVolumeBeforeDuck: Int? = null
    private var ringtoneFallback: Ringtone? = null
    private var dpsWrittenForThisRing: Boolean = false
    private var playbackStarted: Boolean = false
    private var awaitingResume: Boolean = false
    private var resumeReceiver: BroadcastReceiver? = null
    private var playbackVolume: Float = 1.0f
    private var previousInterruptionFilter: Int? = null
    private var isMissedAlarm: Boolean = false
    private var ttsSpokenForCurrentAlarm: Boolean = false
    private var ttsTimer: Timer? = null

    // Handler/Thread
    private lateinit var bgHandler: Handler
    private lateinit var bgThread: HandlerThread

    // ========= Notification tracking =========
    private var lastNotifiedAlarmId: Int? = null
    private var lastNotificationTime: Long = 0L

    // ========= Receivers / Runnables =========

    // Duck receiver remains (brief duck behaviour). It tries to duck player volume first;
    // when using ringtone fallback it may adjust the stream temporarily and will restore.
    private val duckReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != "com.vaishnava.alarm.DUCK_ALARM") return

            val factor: Float = intent.getFloatExtra("factor", 0.2f).coerceIn(0.0f, 1.0f)
            val duration: Long = intent.getLongExtra("duration", 2500L).coerceAtLeast(500L)

            duckRestoreRunnable?.let { bgHandler.removeCallbacks(it) }

            try {
                if (mediaPlayer != null) {
                    mediaPlayer?.setVolume(factor, factor)
                } else if (ringtoneFallback != null) {
                    val am: AudioManager? = getSystemService(AudioManager::class.java)
                    alarmVolumeBeforeDuck = am?.getStreamVolume(AudioManager.STREAM_ALARM)
                    val baseVolume = alarmVolumeBeforeDuck ?: previousAlarmVolume
                    if (baseVolume >= 0) {
                        val duckV: Int = (baseVolume * factor).toInt().coerceAtLeast(1)
                        am?.setStreamVolume(AudioManager.STREAM_ALARM, duckV, 0)
                    }
                }
            } catch (_: Exception) { }

            duckRestoreRunnable = Runnable {
                try {
                    if (mediaPlayer != null) {
                        mediaPlayer?.setVolume(playbackVolume, playbackVolume)
                    } else if (ringtoneFallback != null) {
                        val am: AudioManager? = getSystemService(AudioManager::class.java)
                        val restoreVolume = alarmVolumeBeforeDuck ?: previousAlarmVolume
                        if (restoreVolume >= 0) {
                            am?.setStreamVolume(AudioManager.STREAM_ALARM, restoreVolume, 0)
                        }
                    }
                } catch (_: Exception) { }
                alarmVolumeBeforeDuck = null
            }
            bgHandler.postDelayed(duckRestoreRunnable!!, duration)
        }
    }

    // Repost foreground notification periodically with enhanced persistence
    private val notificationReposter: Runnable = object : Runnable {
        private var consecutiveFailures = 0
        private val maxFailures = 3

        override fun run() {
            try {
                val notification: Notification = buildNotification()
                startForeground(1, notification)
                consecutiveFailures = 0 // Reset on success

                // Log successful repost for debugging
                android.util.Log.d(TAG, "Notification reposted successfully for alarmId=$currentAlarmId")

                // Schedule next repost
                bgHandler.postDelayed(this, REPOST_DELAY_MS)
            } catch (e: Exception) {
                consecutiveFailures++
                android.util.Log.e(TAG, "Notification repost failed (attempt $consecutiveFailures/$maxFailures): ${e.message}")

                if (consecutiveFailures < maxFailures) {
                    // Retry with exponential backoff
                    val backoffDelay = REPOST_DELAY_MS * (1 shl consecutiveFailures - 1)
                    bgHandler.postDelayed(this, backoffDelay)
                } else {
                    android.util.Log.e(TAG, "Max notification repost failures reached, giving up")
                    // Try to restart service as last resort
                    try {
                        val restartIntent = Intent(this@AlarmForegroundService, AlarmForegroundService::class.java)
                        restartIntent.putExtra(AlarmReceiver.ALARM_ID, currentAlarmId)
                        startService(restartIntent)
                    } catch (_: Exception) {
                        android.util.Log.e(TAG, "Failed to restart service after notification failures")
                    }
                }
            }
        }
    }

    // Launch AlarmActivity
    private val activityLauncher: Runnable = Runnable {
        if (!launcherScheduled.get()) return@Runnable

        // If this alarm was dismissed very recently, do not re-launch the UI.
        // This prevents duplicate AlarmActivity instances for normal alarms
        // when a stray service restart occurs after dismissal.
        try {
            val dps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                createDeviceProtectedStorageContext()
            } else this@AlarmForegroundService
            val prefs = dps.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
            val lastDismiss = prefs.getLong("dismiss_ts_$currentAlarmId", 0L)
            if (lastDismiss > 0L) {
                val now = System.currentTimeMillis()
                if (now - lastDismiss < 60_000L) {
                    android.util.Log.d(
                        "WakeCheckDebug",
                        "AlarmForegroundService.activityLauncher: suppressing UI relaunch for alarmId=$currentAlarmId due to recent dismiss (within 60s)"
                    )
                    return@Runnable
                }
            }
        } catch (_: Exception) { }

        try {
            // Preview exit guard: if a suppress flag was written very recently, skip launching UI
            try {
                val dps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    createDeviceProtectedStorageContext()
                } else this@AlarmForegroundService
                val prefs = dps.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                val ts = prefs.getLong("preview_exit_block_$currentAlarmId", 0L)
                if (ts > 0L && System.currentTimeMillis() - ts < 60_000L) {
                    launcherScheduled.set(false)
                    return@Runnable
                }
            } catch (_: Exception) { }

            // Use the centralized launchAlarmActivity function
            launchAlarmActivity()
        } catch (_: Exception) { }
    }

    // Launch alarm activity (used by both activityLauncher and unlock receiver)
    private fun launchAlarmActivity() {
        try {
            // Get current mission data to pass to AlarmActivity
            var localMissionType: String? = currentMissionType
            var localMissionPassword: String? = currentMissionPassword
            var localIsProtected: Boolean = currentIsProtected
            
            // CRITICAL FIX: Always get fresh mission data from alarm configuration
            try {
                val alarm = alarmStorage.getAlarm(currentAlarmId)
                if (alarm != null) {
                    when (alarm.missionType) {
                        "sequencer" -> {
                            // CRITICAL DEBUG: Log exactly what's in missionPassword
                            val missionNamesRaw = alarm.missionPassword ?: ""
                            android.util.Log.d("MISSION_DEBUG_LAUNCH", "Raw missionPassword: '$missionNamesRaw'")
                            
                            // For sequencer alarms, parse the mission sequence
                            val missionNames = alarm.missionPassword ?: ""
                            val missionIds = missionNames.split("+").map { it.trim().lowercase() }
                                .map { mission ->
                                    when (mission) {
                                        "tap" -> "tap"
                                        "pwd", "password", "pswd" -> "password"
                                        else -> "" // CRITICAL FIX: Block invalid mission names
                                    }
                                }
                                .filter { it.isNotEmpty() }
                            
                            android.util.Log.d("MISSION_DEBUG_LAUNCH", "Parsed missionIds: $missionIds")
                            
                            // CRITICAL FIX: Block "none" missions - let sequencer handle empty cases
                            localMissionType = missionIds.firstOrNull() ?: ""
                            localMissionPassword = if (localMissionType == "password") MissionSequencer.DEFAULT_GLOBAL_PASSWORD else ""
                            
                            android.util.Log.d("MISSION_DEBUG_LAUNCH", "Final localMissionType: '$localMissionType'")
                        }
                        "tap" -> {
                            localMissionType = "tap"
                            localMissionPassword = ""
                        }
                        "password" -> {
                            localMissionType = "password"
                            localMissionPassword = alarm.missionPassword?.takeIf { it.isNotBlank() } ?: MissionSequencer.DEFAULT_GLOBAL_PASSWORD
                        }
                        "none" -> {
                            // CRITICAL FIX: Block "none" missions from being launched
                            localMissionType = ""
                            localMissionPassword = ""
                        }
                        else -> {
                            localMissionType = alarm.missionType
                            localMissionPassword = alarm.missionPassword
                        }
                    }
                    localIsProtected = alarm.isProtected
                }
            } catch (_: Exception) { }
            
            // CRITICAL FIX: Get complete alarm data to pass to AlarmActivity
            var hour = -1
            var minute = -1
            var repeatDaily = false
            var wakeCheckEnabled = false
            var wakeCheckMinutes = 0
            
            try {
                val alarm = alarmStorage.getAlarm(currentAlarmId)
                if (alarm != null) {
                    hour = alarm.hour
                    minute = alarm.minute
                    repeatDaily = alarm.repeatDaily
                    wakeCheckEnabled = alarm.wakeCheckEnabled
                    wakeCheckMinutes = alarm.wakeCheckMinutes
                }
            } catch (_: Exception) { }
            
            // CRITICAL FIX: Use full-screen intent for lock screen display
            val fullScreenIntent = Intent(this@AlarmForegroundService, AlarmActivity::class.java).apply {
                // Use standard flags - AlarmActivity handles lock screen display
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_NO_HISTORY or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                putExtra(AlarmReceiver.ALARM_ID, currentAlarmId)
                putExtra("extra_ringtone_uri", ringtoneUri?.toString())
                putExtra("extra_repeat_days", currentRepeatDays)
                // CRITICAL FIX: Pass complete mission data to prevent screen-off issues
                putExtra("hour", hour)
                putExtra("minute", minute)
                putExtra("repeatDaily", repeatDaily)
                putExtra("mission_type", localMissionType)
                putExtra("mission_password", localMissionPassword)
                putExtra("is_protected", localIsProtected)
            }
            
            startActivity(fullScreenIntent)
        } catch (_: Exception) {
            try {
                val fallbackIntent = Intent(this@AlarmForegroundService, AlarmActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT or
                                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                                Intent.FLAG_ACTIVITY_NO_HISTORY
                    )
                    putExtra(AlarmReceiver.ALARM_ID, currentAlarmId)
                    putExtra(AlarmReceiver.EXTRA_RINGTONE_URI, ringtoneUri?.toString())
                    putExtra(AlarmReceiver.EXTRA_REPEAT_DAYS, currentRepeatDays)
                    if (isWakeCheckLaunch) putExtra("from_wake_check", true)
                    
                    // Pass mission data for Direct Boot restoration
                    currentMissionType?.let { putExtra("mission_type", it) }
                    currentMissionPassword?.let { putExtra("mission_password", it) }
                    putExtra("is_protected", currentIsProtected)
                }
                startActivity(fallbackIntent)
            } catch (_: Exception) { }
        }
    }

    // Check next-alarm window notification
    private val notificationChecker: Runnable = object : Runnable {
        override fun run() {
            try { showAlarmNotificationIfNeeded() } catch (_: Exception) { }
            bgHandler.postDelayed(this, 2000)
        }
    }

    // ========= Service lifecycle =========

    override fun onCreate() {
        super.onCreate()

        alarmStorage = AlarmStorage(this)

        // Wake lock (PARTIAL while alarm active)
        val powerManager: PowerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AlarmApp::AlarmService"
        )

        bgThread = HandlerThread("AlarmServiceBackground")
        bgThread.start()
        bgHandler = Handler(bgThread.looper)

        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        // Foreground immediately
        val initial: Notification = buildNotification()
        startForeground(1, initial)

        // Register duck receiver
        try {
            val filter = IntentFilter("com.vaishnava.alarm.DUCK_ALARM")
            registerReceiver(duckReceiver, filter)
        } catch (_: Exception) { }

        // Register user unlock receiver for DPS auto-launch scenario
        val unlockReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_USER_UNLOCKED) {
                    android.util.Log.d(TAG, "User unlocked detected - checking if alarm activity should launch")
                    // Only launch if we have an active alarm and haven't shown activity yet
                    if (currentAlarmId != -1 && playbackStarted && !awaitingResume) {
                        try {
                            // Check if this was a DPS auto-launch scenario
                            val dps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                createDeviceProtectedStorageContext() ?: this@AlarmForegroundService
                            } else {
                                this@AlarmForegroundService
                            }
                            val prefs = dps.getSharedPreferences("direct_boot_alarm_prefs", Context.MODE_PRIVATE)
                            val wasDpsLaunch = prefs.getBoolean("dps_auto_launch_$currentAlarmId", false)
                            
                            if (wasDpsLaunch) {
                                android.util.Log.d(TAG, "DPS auto-launch scenario detected - launching alarm activity after unlock")
                                launchAlarmActivity()
                                
                                // Clear the DPS flag to prevent repeated launches
                                prefs.edit().remove("dps_auto_launch_$currentAlarmId").apply()
                            }
                        } catch (e: Exception) {
                            android.util.Log.e(TAG, "Error checking DPS launch state: ${e.message}")
                        }
                    }
                }
            }
        }
        try {
            val unlockFilter = IntentFilter(Intent.ACTION_USER_UNLOCKED)
            registerReceiver(unlockReceiver, unlockFilter)
            android.util.Log.d(TAG, "Registered user unlock receiver")
        } catch (_: Exception) {
            android.util.Log.e(TAG, "Failed to register unlock receiver")
        }

        // Register notification dismiss receiver for proper cleanup
        val dismissReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "com.vaishnava.alarm.NOTIFICATION_DISMISSED") {
                    val dismissedAlarmId = intent.getIntExtra(AlarmReceiver.ALARM_ID, -1)
                    if (dismissedAlarmId == currentAlarmId) {
                        android.util.Log.d(TAG, "Notification dismissed for current alarm, stopping service")
                        stopAlarm()
                    }
                }
            }
        }
        try {
            val dismissFilter = IntentFilter("com.vaishnava.alarm.NOTIFICATION_DISMISSED")
            registerReceiver(dismissReceiver, dismissFilter)
        } catch (_: Exception) { }

        resumeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != Constants.ACTION_RESUME_ALARM_AUDIO) return
                val requestedId = intent.getIntExtra(AlarmReceiver.ALARM_ID, -1)
                if (requestedId != currentAlarmId || !awaitingResume) return
                awaitingResume = false
                resumePlaybackIfNeeded()
            }
        }
        try {
            registerReceiver(resumeReceiver, IntentFilter(Constants.ACTION_RESUME_ALARM_AUDIO))
        } catch (_: Exception) { }
    }

    private fun stopAlarm() {
        try {
            // Prevent any late UI relaunches or notifications
            try {
                launcherScheduled.set(false)
                if (::bgHandler.isInitialized) {
                    bgHandler.removeCallbacks(activityLauncher)
                    bgHandler.removeCallbacks(notificationReposter)
                    bgHandler.removeCallbacks(notificationChecker)
                }
            } catch (_: Exception) { }

            // Stop TTS
            tts?.let { tts ->
                try { tts.stop() } catch (_: Exception) {}
                try { tts.shutdown() } catch (_: Exception) {}
                this@AlarmForegroundService.tts = null
            }

            // Stop periodic TTS
            stopPeriodicTTS()

            // Stop media player
            stopMediaPlayer()

            // Stop vibration
            try {
                vibrator?.cancel()
                vibrator = null
            } catch (_: Exception) {}

            // Clear notifications
            clearNextAlarmNotification()

            // Clear current service alarm ID tracking
            try {
                val dps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    createDeviceProtectedStorageContext() ?: this
                } else {
                    this
                }
                val prefs = dps.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                prefs.edit()
                    .remove("current_service_alarm_id")
                    .apply()
            } catch (_: Exception) { }

            // DPS INVARIANT: Clear current_service_alarm_id only when service truly stops
            // For sequencer alarms, this must persist until entire mission sequence is complete
            restoreInterruptionFilterIfNeeded()

            // Restore previous alarm volume if saved
            try {
                restorePreviousVolumeIfNeeded()
            } catch (_: Exception) {}

            // Release wake lock if held
            if (wakeLock.isHeld) {
                try { wakeLock.release() } catch (_: Exception) {}
            }

            // Stop the service
            stopSelf()
        } catch (e: Exception) {
            try { stopSelf() } catch (_: Exception) {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val TAG = "AlarmForegroundService"
        
        // CRITICAL DEBUG: Log every time AlarmForegroundService is started
        android.util.Log.d("SERVICE_DEBUG_MAIN", "AlarmForegroundService.onStartCommand() called with intent=${intent?.action}")

        val action = intent?.action
        val alarmId = intent?.getIntExtra(AlarmReceiver.ALARM_ID, -1) ?: -1
        
        // CRITICAL: Handle STOP_ALARM_SERVICE action for sequencer completion
        if (action == "com.vaishnava.alarm.STOP_ALARM_SERVICE") {
            Log.d(TAG, "STOP_ALARM_SERVICE received, checking if sequencer is complete")
            
            // CRITICAL FIX: Only stop service if sequencer is actually complete
            val mainActivity = MainActivity.getInstance()
            val sequencer = mainActivity?.missionSequencer ?: getForceRestartSequencer()
            if (sequencer != null && sequencer.isSequencerComplete) {
                Log.d(TAG, "STOP_ALARM_SERVICE: Sequencer is complete, stopping service")
                stopAlarm()
                return START_NOT_STICKY
            } else {
                Log.d(TAG, "STOP_ALARM_SERVICE: Sequencer not complete, ignoring stop request")
                return START_STICKY
            }
        }
        
        // Extract extras and update state
        isWakeCheckLaunch = intent?.getBooleanExtra("from_wake_check", false) == true
        isMissedAlarm = intent?.getBooleanExtra("is_missed_alarm", false) == true
        val isSequencerMissedAlarm = intent?.getBooleanExtra("is_sequencer_missed_alarm", false) == true
        val isForceRestartSequencer = intent?.getBooleanExtra("is_force_restart_sequencer", false) == true

        // Check if this is a DPS auto-launch scenario (no activity shown yet)
        if (alarmId != -1 && !isWakeCheckLaunch && !isMissedAlarm) {
            try {
                val dps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    createDeviceProtectedStorageContext() ?: this
                } else {
                    this
                }
                val prefs = dps.getSharedPreferences("direct_boot_alarm_prefs", Context.MODE_PRIVATE)
                val dpsRingtone = prefs.getString("direct_boot_ringtone_$alarmId", null)
                
                // If DPS ringtone exists, this is likely a DPS auto-launch
                if (dpsRingtone != null) {
                    prefs.edit().putBoolean("dps_auto_launch_$alarmId", true).apply()
                    android.util.Log.d(TAG, "Set DPS auto-launch flag for alarmId=$alarmId")
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error setting DPS launch flag: ${e.message}")
            }
        }
        currentRepeatDays = intent?.getIntArrayExtra(AlarmReceiver.EXTRA_REPEAT_DAYS) ?: intArrayOf()

        // Handle repost notification action
        if (action == Constants.ACTION_REPOST_NOTIFICATION && alarmId != null && alarmId == currentAlarmId) {
            android.util.Log.d(TAG, "Handling repost notification request for alarmId=$alarmId")
            try {
                val notification = buildNotification()
                startForeground(1, notification)
                android.util.Log.d(TAG, "Notification reposted successfully for alarmId=$alarmId")
                return START_STICKY
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to repost notification: ${e.message}", e)
                return START_STICKY
            }
        }

        // Handle stop action
        if (action == Constants.ACTION_STOP_FOREGROUND_SERVICE) {
            stopAlarm()
            return START_NOT_STICKY
        }

        // New start (normal alarm or wake-check follow-up): decide if we should
        // launch AlarmActivity based on the incoming intent. Some restarts
        // (e.g., wake-check timeout) only need audio resumed, not a new UI.
        val skipActivityLaunch = intent?.getBooleanExtra("skip_activity_launch", false) == true
        launcherScheduled.set(false)

        // Acquire indefinite wakelock during alarm
        if (!wakeLock.isHeld) {
            try { wakeLock.acquire() } catch (_: Exception) {}
        }

        // Extract extras
        val newAlarmId = alarmId

        // CRITICAL Fix: Prevent the same alarm from starting multiple times
        if (currentAlarmId == newAlarmId && playbackStarted) {
            return START_STICKY
        }

        // If this is a different alarm from what's currently playing, stop the current one first
        if (currentAlarmId != -1 && newAlarmId != -1 && currentAlarmId != newAlarmId) {
            android.util.Log.d(TAG, "Alarm ID changed from $currentAlarmId to $newAlarmId, stopping current alarm before starting new one")
            stopMediaPlayer()
            stopVibration()
            // Reset TTS flag for new alarm
            ttsSpokenForCurrentAlarm = false
            // Stop periodic TTS for old alarm
            stopPeriodicTTS()
        }

        currentAlarmId = newAlarmId

        // Track current service alarm ID in DPS for missed alarm prevention
        try {
            val dps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                createDeviceProtectedStorageContext() ?: this
            } else {
                this
            }
            val prefs = dps.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
            prefs.edit()
                .putInt("current_service_alarm_id", currentAlarmId)
                .apply()
        } catch (_: Exception) { }
        
        // DPS INVARIANT: Write sequencer alarm ID to DPS before any multimission logic
        // This ensures current_service_alarm_id persists across entire mission sequence
        isWakeCheckLaunch = intent?.getBooleanExtra("from_wake_check", false) == true
        isMissedAlarm = intent?.getBooleanExtra("is_missed_alarm", false) == false
        this.ringtoneUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(AlarmReceiver.EXTRA_RINGTONE_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(AlarmReceiver.EXTRA_RINGTONE_URI)
        }
        
        // Read mission data from intent extras (for Direct Boot restoration)
        val missionType = intent?.getStringExtra("mission_type")
        val missionPassword = intent?.getStringExtra("mission_password")
        val isProtected = intent?.getBooleanExtra("is_protected", false) ?: false
        
        // Store mission data for later use when launching AlarmActivity
        var storedMissionType: String? = null
        var storedMissionPassword: String? = null
        var storedIsProtected: Boolean = false
        
        // Try to get mission data from alarm storage as fallback
        try {
            val alarm = alarmStorage.getAlarm(currentAlarmId)
            storedMissionType = alarm?.missionType
            storedMissionPassword = alarm?.missionPassword
            storedIsProtected = alarm?.isProtected ?: false
        } catch (_: Exception) { }
        
        // CRITICAL FIX: Parse mission data from alarm configuration for both single and multi-mission
        val alarm = alarmStorage.getAlarm(currentAlarmId)
        if (alarm != null) {
            when (alarm.missionType) {
                "sequencer" -> {
                    // For sequencer alarms, parse the mission sequence
                    val missionNames = alarm.missionPassword ?: ""
                    val missionIds = missionNames.split("+").map { it.trim().lowercase() }
                        .map { mission ->
                            when (mission) {
                                "tap" -> "tap"
                                "pwd", "password", "pswd" -> "password"
                                else -> "" // CRITICAL FIX: Block invalid mission names
                            }
                        }
                        .filter { it.isNotEmpty() }
                    
                    // CRITICAL FIX: Block "none" missions - let sequencer handle empty cases
                    currentMissionType = missionIds.firstOrNull() ?: ""
                    currentMissionPassword = if (currentMissionType == "password") MissionSequencer.DEFAULT_GLOBAL_PASSWORD else ""
                }
                "tap" -> {
                    currentMissionType = "tap"
                    currentMissionPassword = ""
                }
                "password" -> {
                    currentMissionType = "password"
                    currentMissionPassword = alarm.missionPassword?.takeIf { it.isNotBlank() } ?: MissionSequencer.DEFAULT_GLOBAL_PASSWORD
                }
                "none" -> {
                    // CRITICAL FIX: Block "none" missions from being launched
                    currentMissionType = ""
                    currentMissionPassword = ""
                }
                else -> {
                    currentMissionType = alarm.missionType
                    currentMissionPassword = alarm.missionPassword
                }
            }
            currentIsProtected = alarm.isProtected
        } else {
            // Fallback to intent data if alarm not found
            currentMissionType = missionType ?: storedMissionType
            currentMissionPassword = missionPassword ?: storedMissionPassword
            currentIsProtected = isProtected ?: storedIsProtected
        }
        
        currentRepeatDays = intent?.getIntArrayExtra(AlarmReceiver.EXTRA_REPEAT_DAYS) ?: intArrayOf()

        // Unified log to in-app overlay
        try {
            MissionLogger.log(
                "Service id=$currentAlarmId wakeCheck=$isWakeCheckLaunch skipActivity=$skipActivityLaunch hasRingtone=${this.ringtoneUri != null} mission=$currentMissionType protected=$currentIsProtected"
            )
        } catch (_: Exception) { }

        dpsWrittenForThisRing = false

        // If this is a wake-up-check follow-up start, but the user has already
        // acknowledged "I'm awake" very recently, abort this service start to
        // prevent any late restart races. AlarmReceiver already guards WAKE_UP
        // broadcasts, but this closes the loop in case the service is started
        // via a stale intent.
        if (isWakeCheckLaunch && currentAlarmId >= 0) {
            try {
                val dps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    createDeviceProtectedStorageContext() ?: this
                } else {
                    this
                }
                val prefs = dps.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                val ackTs = prefs.getLong("wakecheck_ack_ts_$currentAlarmId", 0L)
                if (ackTs > 0L) {
                    val now = System.currentTimeMillis()
                    if (now - ackTs < 10 * 60_000L) {
                        android.util.Log.d(
                            "WakeCheckDebug",
                            "AlarmForegroundService.onStartCommand: ignoring wake-check start for alarmId=$currentAlarmId because 'I'm awake' was acknowledged recently"
                        )
                        stopAlarm()
                        return START_NOT_STICKY
                    }
                }
            } catch (_: Exception) { }
        }

        android.util.Log.d("WakeCheckDebug", "AlarmForegroundService.onStartCommand alarmId=$currentAlarmId isWakeCheckLaunch=$isWakeCheckLaunch")

        // Show 1-minute notice when appropriate
        showAlarmNotificationIfNeeded()

        // Start periodic notification repost
        bgHandler.post(notificationReposter)
        // Start periodic next-alarm checks
        bgHandler.post(notificationChecker)

        // Turn screen on
        turnScreenOn()

        // Handle sequencer missed alarms by rebuilding the mission queue
        if (isSequencerMissedAlarm) {
            android.util.Log.d(TAG, "Handling sequencer missed alarm: rebuilding mission queue for alarmId=$currentAlarmId")
            try {
                val alarm = alarmStorage.getAlarm(currentAlarmId)
                if (alarm != null && alarm.missionType == "sequencer") {
                    // Parse mission names from missionPassword field (e.g., "Tap+Pwd")
                    val missionNames = alarm.missionPassword ?: ""
                    val missionIds = missionNames.split("+").map { it.trim().lowercase() }
                        .map { mission ->
                            when (mission) {
                                "tap" -> "tap"
                                "pwd", "password", "pswd" -> "password"
                                else -> "" // CRITICAL FIX: Block invalid mission names
                            }
                        }
                        .filter { it.isNotEmpty() }
                    
                    android.util.Log.d(TAG, "Parsed mission IDs for sequencer missed alarm: $missionIds")
                    
                    // Create mission specs from the parsed mission IDs
                    val specs = missionIds.map { missionId ->
                        val safeMissionId = missionId
                        val timeoutMs = if (safeMissionId == "tap") 105000L else 30000L
                        android.util.Log.d(TAG, "Creating mission spec for sequencer missed alarm: $safeMissionId (timeout: $timeoutMs)")
                        com.vaishnava.alarm.sequencer.MissionSpec(
                            id = safeMissionId,
                            params = mapOf("mission_type" to safeMissionId),
                            timeoutMs = timeoutMs,
                            retryCount = 3,
                            sticky = true,
                            retryDelayMs = 1000L
                        )
                    }
                    
                    // Get MainActivity and enqueue missions
                    val mainActivity = MainActivity.getInstance()
                    val sequencer = mainActivity?.missionSequencer
                    if (sequencer != null) {
                        sequencer.enqueueAll(specs)
                        android.util.Log.d(TAG, "Enqueued ${specs.size} missions for sequencer missed alarm")
                        
                        // Start the sequencer
                        sequencer.startWhenAlarmFires(alarm.ringtoneUri)
                        android.util.Log.d(TAG, "Started sequencer for missed alarm: alarmId=$currentAlarmId")
                    } else {
                        android.util.Log.w(TAG, "MissionSequencer not available for sequencer missed alarm: alarmId=$currentAlarmId")
                    }
                } else {
                    android.util.Log.w(TAG, "Invalid alarm for sequencer missed alarm: alarmId=$currentAlarmId, missionType=${alarm?.missionType}")
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error handling sequencer missed alarm: alarmId=$currentAlarmId, error: ${e.message}")
            }
        }
        
        // Detect force restart for sequencer alarms and restore exact mission sequence
        if (!isSequencerMissedAlarm && !isMissedAlarm && !isWakeCheckLaunch) {
            try {
                val alarm = alarmStorage.getAlarm(currentAlarmId)
                if (alarm == null) {
                    android.util.Log.w(TAG, "FORCE_RESTART_CHECK: No alarm found for id=$currentAlarmId")
                } else if (alarm.missionType != "sequencer") {
                    // Only care about sequencer alarms
                    android.util.Log.d(TAG, "FORCE_RESTART_CHECK: Not a sequencer alarm, skipping")
                } else {
                    // Get MissionSequencer instance
                    val mainActivity = MainActivity.getInstance()
                    var sequencer = mainActivity?.missionSequencer

                    if (sequencer == null) {
                        // App was killed, try to create sequencer with DPS context
                        android.util.Log.d(TAG, "FORCE_RESTART: MainActivity null, creating MissionSequencer in DPS context for alarmId=$currentAlarmId")
                        val dpsContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                            createDeviceProtectedStorageContext()
                        } else {
                            this@AlarmForegroundService
                        }
                        
                        // CRITICAL FIX: Add notification to show what data is available during force restart
                        try {
                            val alarmStorage = com.vaishnava.alarm.AlarmStorage(dpsContext)
                            val allAlarms = alarmStorage.getAlarms()
                            val sequencerAlarms = allAlarms.filter { it.missionType == "sequencer" }
                            android.util.Log.d("FORCE_RESTART_SERVICE", "AlarmForegroundService: Found ${sequencerAlarms.size} sequencer alarms out of ${allAlarms.size} total")
                            sequencerAlarms.take(3).forEach { alarm ->
                                android.util.Log.d("FORCE_RESTART_SAMPLE", "ID=${alarm.id}, Type=${alarm.missionType}, Password=${alarm.missionPassword}")
                            }
                            
                            // Also check DPS data
                            val dpsPrefs = dpsContext.getSharedPreferences("direct_boot_alarm_prefs", Context.MODE_PRIVATE)
                            sequencerAlarms.forEach { alarm ->
                                val dpsType = dpsPrefs.getString("direct_boot_mission_type_${alarm.id}", "not_found")
                                val dpsPassword = dpsPrefs.getString("direct_boot_mission_password_${alarm.id}", "not_found")
                                android.util.Log.d("FORCE_RESTART_DPS", "Alarm ${alarm.id}: DPS Type=$dpsType, DPS Password=$dpsPassword")
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("FORCE_RESTART_SERVICE", "Failed to read alarm data in force restart", e)
                        }
                        
                        sequencer = com.vaishnava.alarm.sequencer.MissionSequencer(dpsContext)
                        
                        // Store the sequencer for activity to access
                        forceRestartSequencer = sequencer
                        
                        // Wait a moment for async initialization to complete
                        Thread.sleep(100)
                    }

                    // If sequencer already has a current mission, do NOT rebuild – this is not a force restart
                    val existingMission = sequencer.getCurrentMission()
                    if (existingMission != null) {
                        android.util.Log.d(TAG, "FORCE_RESTART_CHECK: Sequencer already has currentMission=${existingMission.id}, skipping restore")
                    } else {
                        android.util.Log.d(TAG, "FORCE_RESTART_DETECTED: Restoring exact mission sequence from missionPassword for alarmId=$currentAlarmId")

                        // --- Parse missionPassword into ordered mission IDs ---

                        // Example missionPassword values:
                        //  "Tap+Pwd", "Pwd+Tap", "tap+password"
                        val missionPasswordRaw = alarm.missionPassword.orEmpty().trim()
                        if (missionPasswordRaw.isEmpty()) {
                            android.util.Log.w(TAG, "FORCE_RESTART_RESTORE_ABORT: missionPassword empty for sequencer alarmId=$currentAlarmId")
                        } else {
                            val missionIds: List<String> = missionPasswordRaw
                                .split("+")
                                .map { it.trim().lowercase() }
                                .mapNotNull { mission ->
                                    when (mission) {
                                        "tap" -> "tap"
                                        "pwd", "password", "pswd" -> "password"
                                        "none", "" -> null
                                        else -> {
                                            android.util.Log.w(TAG, "FORCE_RESTART_RESTORE_WARN: Unknown mission token '$mission' in missionPassword='$missionPasswordRaw'")
                                            null
                                        }
                                    }
                                }

                            if (missionIds.isEmpty()) {
                                android.util.Log.w(TAG, "FORCE_RESTART_RESTORE_ABORT: No valid missions parsed from missionPassword='$missionPasswordRaw' for alarmId=$currentAlarmId")
                            } else {
                                // --- Build MissionSpec list preserving exact original order ---

                                val specs = missionIds.map { missionId ->
                                    val timeoutMs = when (missionId) {
                                        "tap" -> 105_000L   // tap mission timeout
                                        "password" -> 30_000L
                                        else -> 30_000L
                                    }

                                    com.vaishnava.alarm.sequencer.MissionSpec(
                                        id = missionId,
                                        params = mapOf(
                                            "mission_type" to missionId,
                                            "alarm_id" to currentAlarmId.toString()
                                        ),
                                        timeoutMs = timeoutMs,
                                        retryCount = 3,
                                        sticky = true,
                                        retryDelayMs = 1_000L
                                    )
                                }

                                if (specs.isEmpty()) {
                                    android.util.Log.w(TAG, "FORCE_RESTART_RESTORE_ABORT: Built empty MissionSpec list for alarmId=$currentAlarmId")
                                } else {
                                    // --- Enqueue and start sequencer in the correct order ---

                                    // Clear any existing queue to avoid duplicates
                                    sequencer.getQueueStore().clearQueue()
                                    
                                    // Enqueue missions in the exact original order
                                    sequencer.enqueueAll(specs)

                                    // Start the sequencer immediately and process first mission
                                    sequencer.startWhenAlarmFires(alarm.ringtoneUri)
                                    
                                    // CRITICAL FIX: Explicitly start the first mission after enqueue
                                    kotlinx.coroutines.GlobalScope.launch {
                                        delay(100) // Small delay to ensure queue is processed
                                        sequencer.tryProcessNextMission()
                                    }
                                    
                                    // Give more time for the first mission to start and be saved to DPS
                                    Thread.sleep(500)

                                    android.util.Log.d(
                                        TAG,
                                        "FORCE_RESTART_SEQUENCE_RESTORED: ${specs.size} missions restored in original order=$missionIds for alarmId=$currentAlarmId"
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error restoring mission sequence after force restart for alarmId=$currentAlarmId", e)
            }
        }

        // Speak current time via service-level TTS only for initial alarm launches, not wake-check follow-ups
        if (!isWakeCheckLaunch) {
            try {
                // Check if silence_no_sound is selected - if so, don't use TTS
                val isSilenceRingtone = try {
                    val ringtoneUri = this.ringtoneUri?.toString()
                    Log.d(TAG, "SERVICE_TTS_CHECK: alarmId=$currentAlarmId ringtoneUri=$ringtoneUri")

                    val containsSilence = if (ringtoneUri?.startsWith("android.resource://") == true) {
                        // Extract resource ID and check if it's silence_no_sound
                        val uriParts = ringtoneUri.split("/")
                        val resourceId = uriParts.lastOrNull()?.toIntOrNull()
                        if (resourceId != null) {
                            // Check if this resource ID matches silence_no_sound
                            val silenceResourceId = resources.getIdentifier("silence_no_sound", "raw", packageName)
                            resourceId == silenceResourceId
                        } else {
                            // Fallback to string check for resource names in URI
                            ringtoneUri.contains("silence_no_sound")
                        }
                    } else {
                        ringtoneUri?.contains("silence_no_sound") == true
                    }

                    Log.d(TAG, "SERVICE_TTS_CHECK: containsSilence=$containsSilence")

                    // Write to persistent log file
                    writePersistentLog("SERVICE_TTS_CHECK: alarmId=$currentAlarmId ringtoneUri=$ringtoneUri containsSilence=$containsSilence")

                    // Add to app's sequencer log system
                    MainActivity.getInstance()?.addSequencerLog("SERVICE_TTS_CHECK: alarmId=$currentAlarmId ringtoneUri=$ringtoneUri containsSilence=$containsSilence")

                    containsSilence
                } catch (_: Exception) {
                    Log.d(TAG, "SERVICE_TTS_CHECK: Exception checking ringtone")
                    writePersistentLog("SERVICE_TTS_CHECK: Exception checking ringtone for alarmId=$currentAlarmId")
                    MainActivity.getInstance()?.addSequencerLog("SERVICE_TTS_CHECK: Exception checking ringtone for alarmId=$currentAlarmId")
                    false
                }

                if (!isSilenceRingtone) {
                    // Start periodic TTS announcements
                    startPeriodicTTS()
                } else {
                    Log.d(TAG, "Silence ringtone detected - TTS disabled in service")
                }
            } catch (_: Exception) { }
        }

        playbackStarted = false
        awaitingResume = false

        // Only true wake-check launches (from_wake_check=true) should suppress playback.
        // Initial normal alarms must always start playback.
        val resumePending = isWakeCheckLaunch
        ensureDndAllowsAlarm()

        if (resumePending) {
            android.util.Log.d("WakeCheckDebug", "AlarmForegroundService: wake-check gate active; suppressing playback for alarmId=$currentAlarmId (isWakeCheckLaunch=$isWakeCheckLaunch)")
            awaitingResume = true
            writePendingResumeFlag(currentAlarmId, true)
            stopMediaPlayer()
            stopVibration()

            // Ensure the wake-check UI is brought to the front when in wake-check mode
            try {
                val intent = Intent(this@AlarmForegroundService, AlarmActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT or
                                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                Intent.FLAG_ACTIVITY_NO_HISTORY or
                                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    )
                    putExtra(AlarmReceiver.ALARM_ID, currentAlarmId)
                    putExtra("from_wake_check", true)
                }
                startActivity(intent)
                android.util.Log.d("WakeCheckDebug", "AlarmForegroundService: started wake-check activity immediately for alarmId=$currentAlarmId")
            } catch (_: Exception) { }
        } else {
            // Prepare audio & play immediately, using Direct Boot-safe stored ringtone if needed
            val effectiveUri = getEffectiveRingtoneUri()

            // Persist the effective ringtone URI so UI can show the correct title even after fallbacks
            try {
                val dps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    createDeviceProtectedStorageContext() ?: this
                } else {
                    this
                }
                val prefs = dps.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                prefs.edit()
                    .putString("effective_ringtone_uri_$currentAlarmId", effectiveUri.toString())
                    .apply()
            } catch (_: Exception) { }
            android.util.Log.d("WakeCheckDebug", "AlarmForegroundService: starting normal playback for alarmId=$currentAlarmId isWakeCheckLaunch=$isWakeCheckLaunch")
            startPlayback(effectiveUri)

            // Playback watchdog: if for any reason playback didn't start within 2s, force a retry
            try {
                bgHandler.postDelayed({
                    try {
                        if (!playbackStarted && !awaitingResume) {
                            android.util.Log.w(TAG, "Playback watchdog retry for alarmId=$currentAlarmId")
                            val retryUri: Uri = this.ringtoneUri ?: run {
                                val silenceResourceId = resources.getIdentifier("silence_no_sound", "raw", packageName)
                                if (silenceResourceId != 0) {
                                    Uri.parse("android.resource://$packageName/$silenceResourceId")
                                } else {
                                    Uri.parse("android.resource://$packageName/raw/silence_no_sound")
                                }
                            }
                            startPlayback(retryUri)
                        }
                    } catch (_: Exception) { }
                }, 2000L)
            } catch (_: Exception) { }
        }

        // Launch activity immediately and also once after playback has started, unless the caller
        // explicitly requested to skip launching (e.g., wake-check timeout
        // restart from AlarmActivity which already has UI on screen).
        if (!skipActivityLaunch) {
            // CRITICAL FIX: Check if this is a sequencer alarm before launching AlarmActivity
            val alarm = try {
                alarmStorage.getAlarm(currentAlarmId)
            } catch (_: Exception) {
                null
            }
            
            if (alarm?.missionType != "sequencer") {
                // Only launch AlarmActivity immediately for non-sequencer alarms
                try {
                    val immediateIntent = Intent(this, AlarmActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT or
                                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                Intent.FLAG_ACTIVITY_NO_HISTORY or
                                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    )
                    putExtra(AlarmReceiver.ALARM_ID, currentAlarmId)
                    putExtra(AlarmReceiver.EXTRA_RINGTONE_URI, ringtoneUri?.toString())
                    putExtra(AlarmReceiver.EXTRA_REPEAT_DAYS, currentRepeatDays)
                    if (isWakeCheckLaunch) putExtra("from_wake_check", true)
                    
                    // Pass mission data for Direct Boot restoration
                    currentMissionType?.let { putExtra("mission_type", it) }
                    currentMissionPassword?.let { putExtra("mission_password", it) }
                    putExtra("is_protected", currentIsProtected)
                }
                startActivity(immediateIntent)
                android.util.Log.d("WakeCheckDebug", "AlarmForegroundService: started immediate activity for alarmId=$currentAlarmId isWakeCheckLaunch=$isWakeCheckLaunch")
            } catch (_: Exception) { }

                // Also schedule delayed launch as backup
                if (launcherScheduled.compareAndSet(false, true)) {
                    bgHandler.postDelayed(activityLauncher, 50) // Reduced delay to 50ms for faster response
                }
            } else {
                android.util.Log.d("WakeCheckDebug", "AlarmForegroundService: SKIPPING immediate activity launch for sequencer alarm $currentAlarmId - MissionSequencer will handle it")
            }
        }

        // Keep service alive
        return START_STICKY
    }

    override fun onDestroy() {
        try { unregisterReceiver(duckReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(resumeReceiver) } catch (_: Exception) {}

        stopAlarm()

        // Restore audio mode and any saved volume
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            restorePreviousVolumeIfNeeded()
            am?.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {}

        restoreInterruptionFilterIfNeeded()

        // Clear any remaining notifications
        clearNextAlarmNotification()

        try {
            if (audioFocusRequest != null) {
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    am.abandonAudioFocusRequest(audioFocusRequest!!)
                } else {
                    @Suppress("DEPRECATION")
                    am.abandonAudioFocus(null)
                }
                audioFocusRequest = null
            }
        } catch (_: Exception) {}

        try { bgHandler.removeCallbacks(notificationReposter) } catch (_: Exception) {}
        try { bgHandler.removeCallbacks(activityLauncher) } catch (_: Exception) {}
        try { bgHandler.removeCallbacks(notificationChecker) } catch (_: Exception) {}

        try { bgThread.quit() } catch (_: Exception) {}

        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ========= Playback =========

    private fun getCachedRingtoneUri(context: Context, originalUri: Uri, alarmId: Int): Uri {
        if (!originalUri.toString().startsWith("content://")) {
            android.util.Log.d(TAG, "Not a content URI, using original: $originalUri")
            return originalUri
        }

        return try {
            val deviceContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.createDeviceProtectedStorageContext()
            } else {
                context
            }

            val cacheFile = File(deviceContext.filesDir, "ringtone_$alarmId.mp3")

            if (cacheFile.exists() && cacheFile.length() > 0) {
                val cachedUri = Uri.fromFile(cacheFile)
                android.util.Log.d(TAG, "Using cached ringtone: ${cacheFile.absolutePath}")
                cachedUri
            } else {
                android.util.Log.w(TAG, "No cached ringtone found, falling back to silence")
                val silenceResourceId = context.resources.getIdentifier("silence_no_sound", "raw", context.packageName)
                if (silenceResourceId != 0) {
                    Uri.parse("android.resource://${context.packageName}/$silenceResourceId")
                } else {
                    Uri.parse("android.resource://${context.packageName}/raw/silence_no_sound")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error accessing cached ringtone, using silence fallback", e)
            val silenceResourceId = context.resources.getIdentifier("silence_no_sound", "raw", packageName)
            if (silenceResourceId != 0) {
                Uri.parse("android.resource://${context.packageName}/$silenceResourceId")
            } else {
                Uri.parse("android.resource://${context.packageName}/raw/silence_no_sound")
            }
        }
    }

    private fun getEffectiveRingtoneUri(): Uri {
        return try {
            // Try to use the provided ringtone URI first
            this.ringtoneUri?.let { return it }

            // Fallback to silence_no_sound if no ringtone is set
            val silenceResourceId = resources.getIdentifier("silence_no_sound", "raw", packageName)
            if (silenceResourceId != 0) {
                Uri.parse("android.resource://$packageName/$silenceResourceId")
            } else {
                Uri.parse("android.resource://$packageName/raw/silence_no_sound")
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error getting effective ringtone, using silence fallback", e)
            // Final fallback to silence_no_sound
            val silenceResourceId = resources.getIdentifier("silence_no_sound", "raw", packageName)
            if (silenceResourceId != 0) {
                Uri.parse("android.resource://$packageName/$silenceResourceId")
            } else {
                Uri.parse("android.resource://$packageName/raw/silence_no_sound")
            }
        }
    }

    private fun startPlayback(ringtoneUri: Uri) {
        android.util.Log.d(TAG, "Starting playback with URI: $ringtoneUri")

        // Check if this is silence_no_sound - if so, skip playback entirely
        val isSilenceNoSound = ringtoneUri.toString().contains("silence_no_sound")
        if (isSilenceNoSound) {
            android.util.Log.d(TAG, "Silence ringtone detected - skipping audio playback")
            playbackStarted = true
            // DPS write: mark as ringing even though silent
            if (!dpsWrittenForThisRing) {
                try {
                    val dps = createDeviceProtectedStorageContext()
                    val prefs = dps.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putBoolean("was_ringing", true)
                        .putInt("ring_alarm_id", currentAlarmId)
                        .putLong("ring_started_at", System.currentTimeMillis())
                        .commit()
                } catch (_: Exception) { }
                dpsWrittenForThisRing = true
            }
            return
        }

        // Reset any existing player
        try { mediaPlayer?.stop() } catch (_: Throwable) {}
        try { mediaPlayer?.release() } catch (_: Throwable) {}
        mediaPlayer = null

        // Get the appropriate ringtone URI (cached if available)
        // CRITICAL FIX: Always use direct URI to prevent cache fallback to device default
        // This is the same fix that resolved DPS missed alarms - apply to ALL alarms
        val useCache = false // NEVER use cache - always use direct URI like DPS missed alarms

        val finalRingtoneUri = if (useCache) {
            android.util.Log.d(TAG, "Using cache for normal alarm - cache lookup")
            val cachedUri = getCachedRingtoneUri(this@AlarmForegroundService, ringtoneUri, currentAlarmId)
            if (cachedUri.toString().contains("silence_no_sound")) {
                android.util.Log.w(TAG, "Cache returned silence, using provided ringtone URI directly")
                ringtoneUri
            } else {
                cachedUri
            }
        } else {
            // ALWAYS use direct URI - same as DPS missed alarms fix
            android.util.Log.d(TAG, "Using direct URI (DPS-style fix) for alarm: $ringtoneUri")
            ringtoneUri
        }

        android.util.Log.d(TAG, "Final ringtone URI for playback: $finalRingtoneUri")

        val audioManager: AudioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        // Force ringtone mode (unchanged)
        try { audioManager.mode = AudioManager.MODE_RINGTONE } catch (_: Exception) {}

        previousAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        savePreviousVolume(previousAlarmVolume)

        val maxAlarmVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        playbackVolume = 1.0f
        if (maxAlarmVolume > 0) {
            // 50% floor with retries as per memory requirement
            val targetAlarm = (maxAlarmVolume * 0.5f).toInt().coerceAtLeast(1)

            try {
                fun getCurrentAlarm() = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
                var currentAlarm = getCurrentAlarm()

                if (currentAlarm < targetAlarm) {
                    android.util.Log.i(TAG, "ALARM stream below 50% ($currentAlarm < $targetAlarm). Raising...")

                    val maxRetries = 3
                    var attempt = 0
                    while (attempt < maxRetries) {
                        try {
                            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, targetAlarm, 0)
                        } catch (e: Exception) {
                            android.util.Log.e(TAG, "setStreamVolume attempt $attempt failed", e)
                        }

                        try { Thread.sleep(120) } catch (_: InterruptedException) {}

                        currentAlarm = getCurrentAlarm()
                        if (currentAlarm >= targetAlarm) {
                            android.util.Log.i(TAG, "ALARM raised to $currentAlarm on attempt ${attempt + 1}")
                            break
                        } else {
                            android.util.Log.w(TAG, "ALARM still below 50% after attempt ${attempt + 1}: $currentAlarm < $targetAlarm")
                        }
                        attempt++
                    }

                    if (currentAlarm < targetAlarm) {
                        android.util.Log.e(TAG, "Failed to raise ALARM to 50% after $maxRetries attempts (final=$currentAlarm)")
                    }
                } else {
                    android.util.Log.i(TAG, "ALARM already ≥ 50% ($currentAlarm) — unchanged")
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error while enforcing ALARM 50% floor", e)
            }

            // MUSIC stream: same 50% floor logic - keeping media at full gain as per memory requirement
            try {
                val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                if (maxMusic > 0) {
                    // Keep media at full gain as per memory requirement - no floor enforcement
                    val currentMusic = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    android.util.Log.i(TAG, "MUSIC stream at full gain ($currentMusic/$maxMusic) — unchanged")
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Error checking MUSIC stream volume", e)
            }
        }

        // Request focus
        val audioAttributes: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = android.media.AudioFocusRequest.Builder(
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            ).setAudioAttributes(audioAttributes).build()
            audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_ALARM,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            )
        }

        // Try MediaPlayer first; if it fails, use Ringtone fallback
        try {
            val attrs: AudioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val mp = MediaPlayer()
            mp.setAudioAttributes(attrs)
            mp.setDataSource(this, finalRingtoneUri)
            mp.isLooping = true
            mp.setVolume(playbackVolume, playbackVolume) // do not boost; reflect slider
            mp.setOnCompletionListener { p ->
                try {
                    if (p.isPlaying) p.stop()
                    p.seekTo(0)
                    p.start()
                    p.setVolume(playbackVolume, playbackVolume)
                } catch (_: Exception) {
                    val recreated = restartMediaPlayer(p, finalRingtoneUri, 0, attrs)
                    if (recreated != null) mediaPlayer = recreated
                }
            }
            mp.prepare()
            mp.start()
            ensurePlaybackSilencedIfAwaiting()
            // DPS write: audio actually started playing
            if (!dpsWrittenForThisRing) {
                try {
                    val dps = createDeviceProtectedStorageContext()
                    val prefs = dps.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putBoolean("was_ringing", true)
                        .putInt("ring_alarm_id", currentAlarmId)
                        .putLong("ring_started_at", System.currentTimeMillis())
                        .commit()
                } catch (_: Exception) { }
                dpsWrittenForThisRing = true
            }
            mediaPlayer = mp

            // ======== CRITICAL: start periodic TTS only after playback started ========
            try {
                playbackStarted = true // ensure flag set
                android.util.Log.d(TAG, "MediaPlayer started — triggering startPeriodicTTS() for alarmId=$currentAlarmId")
                // Start TTS on background handler to match rest of service scheduling (startPeriodicTTS defers if not ready)
                bgHandler.post { startPeriodicTTS() }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to post startPeriodicTTS after media start: ${e.message}")
            }

        } catch (_: Exception) {
            // Ringtone fallback - try the provided ringtone first, not device default
            try {
                var fallbackUri = finalRingtoneUri
                // CRITICAL FIX: Don't fall back to device default alarm - use the provided ringtone
                // The cache mechanism may have failed, but we still want the selected ringtone

                val ring: Ringtone = try {
                    RingtoneManager.getRingtone(this, fallbackUri)
                } catch (e: Exception) {
                    // If the provided URI fails, try the original ringtoneUri directly
                    android.util.Log.w(TAG, "Fallback URI failed, trying original ringtoneUri: $ringtoneUri")
                    RingtoneManager.getRingtone(this, ringtoneUri)
                }

                ring.audioAttributes = audioAttributes
                ring.isLooping = true
                ring.play()
                ensurePlaybackSilencedIfAwaiting()
                if (!dpsWrittenForThisRing) {
                    try {
                        val dps = createDeviceProtectedStorageContext()
                        val prefs = dps.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                        prefs.edit()
                            .putBoolean("was_ringing", true)
                            .putInt("ring_alarm_id", currentAlarmId)
                            .putLong("ring_started_at", System.currentTimeMillis())
                            .commit()
                    } catch (_: Exception) { }
                    dpsWrittenForThisRing = true
                }
                ringtoneFallback = ring

                // ======== CRITICAL: start periodic TTS only after ringtone started ========
                try {
                    playbackStarted = true
                    android.util.Log.d(TAG, "Ringtone fallback started — triggering startPeriodicTTS() for alarmId=$currentAlarmId")
                    bgHandler.post { startPeriodicTTS() }
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Failed to post startPeriodicTTS after ringtone start: ${e.message}")
                }
            } catch (_: Exception) { }
        }
    }

    private fun ensurePlaybackSilencedIfAwaiting() {
        if (!awaitingResume) {
            playbackStarted = true
            return
        }
        android.util.Log.d("WakeCheckDebug", "AlarmForegroundService.ensurePlaybackSilencedIfAwaiting alarmId=$currentAlarmId awaitingResume=$awaitingResume")
        try { mediaPlayer?.setVolume(0f, 0f) } catch (_: Exception) { }
        try { ringtoneFallback?.stop() } catch (_: Exception) { }
        try { mediaPlayer?.pause() } catch (_: Exception) { }
    }

    private fun resumePlaybackIfNeeded() {
        android.util.Log.d("WakeCheckDebug", "AlarmForegroundService.resumePlaybackIfNeeded alarmId=$currentAlarmId")

        // If no player exists yet (typical for wake-check launches where we suppressed playback),
        // start playback now using the same effective URI as initial normal alarms.
        if (mediaPlayer == null && ringtoneFallback == null) {
            try {
                val effectiveUri: Uri = this.ringtoneUri ?: run {
                    val dps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        createDeviceProtectedStorageContext() ?: this
                    } else {
                        this
                    }
                    val prefs = dps.getSharedPreferences("direct_boot_alarm_prefs", Context.MODE_PRIVATE)
                    val stored = prefs.getString("direct_boot_ringtone_$currentAlarmId", null)
                    stored?.let { Uri.parse(it) } ?: run {
                        val silenceResourceId = resources.getIdentifier("silence_no_sound", "raw", packageName)
                        if (silenceResourceId != 0) {
                            Uri.parse("android.resource://$packageName/$silenceResourceId")
                        } else {
                            Uri.parse("android.resource://$packageName/raw/silence_no_sound")
                        }
                    }
                }
                android.util.Log.d("WakeCheckDebug", "AlarmForegroundService.resumePlaybackIfNeeded starting fresh playback for alarmId=$currentAlarmId")
                startPlayback(effectiveUri)
            } catch (_: Exception) { }
        }

        playbackStarted = true
        try { mediaPlayer?.setVolume(playbackVolume, playbackVolume) } catch (_: Exception) { }
        try { mediaPlayer?.start() } catch (_: Exception) { }
        try {
            if (ringtoneFallback != null && !(ringtoneFallback?.isPlaying ?: false)) {
                ringtoneFallback?.play()
            }
        } catch (_: Exception) { }
        if (vibrator == null) {
            vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        try { vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 500), 0)) } catch (_: Exception) { }
        writePendingResumeFlag(currentAlarmId, false)
    }

    private fun stopMediaPlayer() {
        try { mediaPlayer?.stop() } catch (_: Throwable) {}
        try { mediaPlayer?.release() } catch (_: Throwable) {}
        mediaPlayer = null
        try { ringtoneFallback?.stop() } catch (_: Throwable) {}
        ringtoneFallback = null
    }

    private fun stopVibration() {
        try { vibrator?.cancel() } catch (_: Exception) {}
    }

    private fun readPendingResumeFlag(alarmId: Int): Boolean {
        return runCatching {
            val dps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                createDeviceProtectedStorageContext()
            } else this
            val prefs = dps.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
            prefs.getBoolean("wakecheck_resume_pending_$alarmId", false)
        }.getOrDefault(false)
    }

    private fun writePendingResumeFlag(alarmId: Int, value: Boolean) {
        runCatching {
            val dps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                createDeviceProtectedStorageContext()
            } else this
            val prefs = dps.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("wakecheck_resume_pending_$alarmId", value).apply()
        }
    }

    private fun restartMediaPlayer(
        originalPlayer: MediaPlayer,
        ringtoneUri: Uri,
        resourceId: Int,
        attrs: AudioAttributes
    ): MediaPlayer? {
        return try {
            try { originalPlayer.stop() } catch (_: Exception) {}
            try { originalPlayer.release() } catch (_: Exception) {}

            val newPlayer: MediaPlayer = if (resourceId != 0) {
                MediaPlayer.create(this, resourceId)!!
            } else {
                val mp = MediaPlayer()
                mp.setAudioAttributes(attrs)
                mp.setDataSource(this, ringtoneUri)
                mp.prepare()
                mp
            }
            newPlayer.setAudioAttributes(attrs)
            newPlayer.isLooping = true
            newPlayer.setVolume(playbackVolume, playbackVolume)
            newPlayer.start()
            newPlayer
        } catch (_: Exception) {
            null
        }
    }

    // ---------- FOR FORENSIC TTS SUBSYSTEM (REPLACE existing TTS fields & functions) ----------

// Fields (replace prior TTS-related fields)
private var tts: android.speech.tts.TextToSpeech? = null
private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) } // MUST use main/UI thread for TTS
private val ttsActiveUtterances = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())!!
private val ttsCounter = AtomicInteger(0)
private val ttsListenerSet = AtomicBoolean(false)
private var isPeriodicTTSRunning = false
private var ttsPeriodicRunnable: Runnable? = null
private var ttsPollRunnable: Runnable? = null
private var ttsWatchdogRunnable: Runnable? = null

private val ttsPauseMs: Long = 15_000L
private val ttsInitialDelayMs: Long = 20_000L
private val ttsUtteranceWatchdogMs: Long = 18_000L
private val ttsPollIntervalMs: Long = 300L

// Helper: forensic log wrapper
private fun ttsForensicLog(msg: String) {
    android.util.Log.d(TAG, "TTS-FOR: $msg")
    try { writePersistentLog("TTS-FOR: $msg") } catch (_: Exception) {}
}

// Ensure single TTS initialization on main thread
private fun ensureTTSInitialized(onReady: (() -> Unit)? = null) {
    mainHandler.post {
        try {
            if (tts == null) {
                ttsForensicLog("Initializing TTS instance")
                tts = android.speech.tts.TextToSpeech(this@AlarmForegroundService) { status ->
                    mainHandler.post {
                        ttsForensicLog("TTS init status: $status")
                        if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                            if (!ttsListenerSet.get()) {
                                setupTTSListenerMainThread()
                            }
                            onReady?.invoke()
                        } else {
                            ttsForensicLog("TTS init failed with status $status")
                            onReady?.invoke() // still attempt to proceed to avoid deadlock
                        }
                    }
                }
            } else {
                onReady?.invoke()
            }
        } catch (e: Exception) {
            ttsForensicLog("ensureTTSInitialized exception: ${e.message}")
            onReady?.invoke()
        }
    }
}

// Start periodic TTS (single speak -> wait for done -> pause -> next)
private fun startPeriodicTTS() {
    // DEFENSIVE GUARD: don't start TTS until playbackStarted is true and we're not awaiting resume.
    // If called too early, retry after 500ms. This prevents starting TTS during service start race.
    if (!playbackStarted || awaitingResume || currentAlarmId == -1) {
        android.util.Log.d(TAG, "startPeriodicTTS called too early (playbackStarted=$playbackStarted, awaitingResume=$awaitingResume, alarmId=$currentAlarmId). Deferring start.")
        // Retry once shortly — use bgHandler to maintain same threading model
        try {
            bgHandler.postDelayed({ startPeriodicTTS() }, 500L)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to defer startPeriodicTTS: ${e.message}")
        }
        return
    }

    // Prevent multiple starts
    if (isPeriodicTTSRunning) {
        ttsForensicLog("startPeriodicTTS: already running")
        return
    }
    stopPeriodicTTS()
    isPeriodicTTSRunning = true
    ttsForensicLog("startPeriodicTTS: STARTED for alarmId=$currentAlarmId")

    var announcementCount = 0

    ttsPeriodicRunnable = object : Runnable {
        override fun run() {
            if (!isPeriodicTTSRunning) {
                ttsForensicLog("periodic runnable: stopped flag set")
                return
            }

            if (!playbackStarted || awaitingResume || currentAlarmId == -1) {
                ttsForensicLog("periodic runnable: conditions not met (playbackStarted=$playbackStarted, awaitingResume=$awaitingResume, alarmId=$currentAlarmId). Stopping.")
                isPeriodicTTSRunning = false
                return
            }

            announcementCount++
            val utteranceId = "TTS_${System.currentTimeMillis()}_${ttsCounter.incrementAndGet()}_#${announcementCount}"
            val nowStr = java.text.SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
            val message = "The time is $nowStr. Time to wake up."

            ttsForensicLog("periodic runnable: preparing utteranceId=$utteranceId message='$message'")

            // Ensure TTS initialized & speak on main thread
            ensureTTSInitialized {
                mainHandler.post {
                    try {
                        // Force stop any previous speaks to avoid overlapping audio on problematic engines
                        try {
                            ttsForensicLog("calling tts.stop() before speak for utteranceId=$utteranceId")
                            tts?.stop()
                        } catch (_: Exception) { }

                        // register utterance as active BEFORE speak (for safety)
                        ttsActiveUtterances.add(utteranceId)
                        ttsForensicLog("registered active utterance: $utteranceId (total active=${ttsActiveUtterances.size})")

                        // watchdog to protect stuck utterance
                        ttsWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
                        ttsWatchdogRunnable = Runnable {
                            ttsForensicLog("watchdog fired for utteranceId=$utteranceId — forcing cleanup")
                            ttsActiveUtterances.remove(utteranceId)
                            cancelTTSPollMain()
                            scheduleNextTTSWithPauseMain()
                        }
                        mainHandler.postDelayed(ttsWatchdogRunnable!!, ttsUtteranceWatchdogMs)

                        // Speak (API21+). Use QUEUE_FLUSH to ensure engine resets and only this plays
                        tts?.speak(message, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                        // start poll to double-check completion (some engines miss callbacks)
                        startTTSPollMain(utteranceId)
                        ttsForensicLog("issued tts.speak for utteranceId=$utteranceId")
                    } catch (e: Exception) {
                        ttsForensicLog("exception while speaking utteranceId=$utteranceId: ${e.message}")
                        ttsActiveUtterances.remove(utteranceId)
                        cancelTTSPollMain()
                        scheduleNextTTSWithPauseMain()
                    }
                }
            }
        }
    }

    // initial delay (keep 20s like before)
    bgHandler.postDelayed(ttsPeriodicRunnable!!, ttsInitialDelayMs)
}

// Poller runs on main thread and monitors tts.isSpeaking() for the exact utterance
private fun startTTSPollMain(expectedUtteranceId: String) {
    // cancel previous poll if any
    cancelTTSPollMain()
    ttsPollRunnable = Runnable {
        try {
            val speaking = try { tts?.isSpeaking == true } catch (_: Exception) { false }
            // If engine reports not speaking and our active set does not contain expected utterance -> treat as done
            if (!speaking && !ttsActiveUtterances.contains(expectedUtteranceId)) {
                // already handled by listener path possibly
                ttsForensicLog("poll: not speaking and utterance not active: $expectedUtteranceId -> scheduling next")
                cancelTTSWatchdogMain()
                cancelTTSPollMain()
                scheduleNextTTSWithPauseMain()
                return@Runnable
            }

            // If engine not speaking but our active set still contains it - normalize (clear)
            if (!speaking && ttsActiveUtterances.contains(expectedUtteranceId)) {
                ttsForensicLog("poll: engine not speaking but active set contains $expectedUtteranceId; normalizing (removing) and scheduling next")
                ttsActiveUtterances.remove(expectedUtteranceId)
                cancelTTSWatchdogMain()
                cancelTTSPollMain()
                scheduleNextTTSWithPauseMain()
                return@Runnable
            }

            // If engine speaking and active set lacks it (rare), add it
            if (speaking && !ttsActiveUtterances.contains(expectedUtteranceId)) {
                ttsForensicLog("poll: engine isSpeaking==true but active set missing $expectedUtteranceId; adding to active set")
                ttsActiveUtterances.add(expectedUtteranceId)
            }

            // continue polling
            mainHandler.postDelayed(ttsPollRunnable!!, ttsPollIntervalMs)
        } catch (e: Exception) {
            ttsForensicLog("poll exception for $expectedUtteranceId: ${e.message}")
            cancelTTSWatchdogMain()
            cancelTTSPollMain()
            ttsActiveUtterances.remove(expectedUtteranceId)
            scheduleNextTTSWithPauseMain()
        }
    }

    mainHandler.postDelayed(ttsPollRunnable!!, ttsPollIntervalMs)
}

private fun cancelTTSPollMain() {
    ttsPollRunnable?.let { mainHandler.removeCallbacks(it) }
    ttsPollRunnable = null
}

private fun cancelTTSWatchdogMain() {
    ttsWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
    ttsWatchdogRunnable = null
}

// schedule next announcement only from main thread (guaranteed pause)
private fun scheduleNextTTSWithPauseMain() {
    if (!isPeriodicTTSRunning) {
        ttsForensicLog("scheduleNext: periodic stopped, not scheduling")
        return
    }
    ttsForensicLog("scheduling next TTS in ${ttsPauseMs}ms")
    mainHandler.postDelayed({
        // run the periodic runnable on the background handler (original design)
        try {
            ttsPeriodicRunnable?.let { bgHandler.post(it) }
        } catch (e: Exception) {
            ttsForensicLog("failed to post periodic runnable to bgHandler: ${e.message}")
            // fallback: run on main thread if bgHandler fails
            ttsPeriodicRunnable?.run()
        }
    }, ttsPauseMs)
}

// Setup UtteranceProgressListener on main thread (must be on main)
private fun setupTTSListenerMainThread() {
    mainHandler.post {
        try {
            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    if (utteranceId == null) return
                    ttsForensicLog("Listener onStart for $utteranceId")
                    ttsActiveUtterances.add(utteranceId)
                }

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == null) return
                    ttsForensicLog("Listener onDone for $utteranceId")
                    ttsActiveUtterances.remove(utteranceId)
                    cancelTTSWatchdogMain()
                    cancelTTSPollMain()
                    scheduleNextTTSWithPauseMain()
                }

                override fun onError(utteranceId: String?) {
                    if (utteranceId == null) return
                    ttsForensicLog("Listener onError for $utteranceId")
                    ttsActiveUtterances.remove(utteranceId)
                    cancelTTSWatchdogMain()
                    cancelTTSPollMain()
                    scheduleNextTTSWithPauseMain()
                }
            })
            ttsListenerSet.set(true)
            ttsForensicLog("TTS listener installed on main thread")
        } catch (e: Exception) {
            ttsForensicLog("setupTTSListenerMainThread exception: ${e.message}")
        }
    }
}

private fun stopPeriodicTTS() {
    mainHandler.post {
        ttsForensicLog("Stopping periodic TTS (main thread)")
        isPeriodicTTSRunning = false
        // cancel bg periodic runnable too
        ttsPeriodicRunnable?.let { try { bgHandler.removeCallbacks(it) } catch (_: Exception) {} }
        // cancel any main thread polls/watchdogs
        cancelTTSPollMain()
        cancelTTSWatchdogMain()
        // clear active utterances set
        ttsActiveUtterances.clear()
        try { tts?.stop() } catch (_: Exception) {}
    }
}

// Shutdown TTS when service fully stops (call in onDestroy/stopAlarm)
private fun shutdownTTSForensic() {
    mainHandler.post {
        ttsForensicLog("shutdownTTSForensic called")
        try {
            tts?.stop()
        } catch (_: Exception) {}
        try {
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null
        ttsListenerSet.set(false)
        ttsActiveUtterances.clear()
    }
}

    // ========= Notifications =========

    private fun buildNotification(): Notification {
        createNotificationChannel()
        val fullScreen: PendingIntent = createFullScreenIntent()

        val inWakeCheckGate = isWakeCheckLaunch && awaitingResume
        android.util.Log.d("WakeCheckDebug", "AlarmForegroundService.buildNotification alarmId=$currentAlarmId inWakeCheckGate=$inWakeCheckGate")

        // Create delete intent for notification dismiss handling
        val deleteIntent = Intent(this, AlarmReceiver::class.java).apply {
            action = "com.vaishnava.alarm.NOTIFICATION_DISMISSED"
            putExtra(AlarmReceiver.ALARM_ID, currentAlarmId)
        }
        val deletePendingIntent = PendingIntent.getBroadcast(
            this, currentAlarmId + 3000, deleteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, "alarm_channel_v2")
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(android.graphics.Color.parseColor("#1976D2"))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreen, true)
            .setDeleteIntent(deletePendingIntent) // Handle notification dismiss

        if (inWakeCheckGate) {
            builder
                .setContentTitle("Wake-up check active")
                .setContentText("Confirm that you're awake in the app")
                .setCategory(NotificationCompat.CATEGORY_SYSTEM)
        } else {
            builder
                .setContentTitle("Alarm Active")
                .setContentText("Your alarm is ringing - Tap to stop")
                .setDefaults(NotificationCompat.DEFAULT_ALL) // Enable sound, vibration, lights
        }

        return builder.build()
    }

    private fun createFullScreenIntent(): PendingIntent {
        // CRITICAL FIX: Always get fresh mission data from alarm configuration
        var localMissionType: String? = null
        var localMissionPassword: String? = null
        var localIsProtected: Boolean = false
        
        try {
            val alarm = alarmStorage.getAlarm(currentAlarmId)
            if (alarm != null) {
                when (alarm.missionType) {
                    "sequencer" -> {
                        // CRITICAL DEBUG: Log exactly what's in missionPassword
                        val missionNamesRaw = alarm.missionPassword ?: ""
                        android.util.Log.d("MISSION_DEBUG", "Raw missionPassword: '$missionNamesRaw'")
                        
                        // For sequencer alarms, parse the mission sequence
                        val missionNames = alarm.missionPassword ?: ""
                        val missionIds = missionNames.split("+").map { it.trim().lowercase() }
                            .map { mission ->
                                when (mission) {
                                    "tap" -> "tap"
                                    "pwd", "password", "pswd" -> "password"
                                    else -> "" // CRITICAL FIX: Block invalid mission names
                                }
                            }
                            .filter { it.isNotEmpty() }
                        
                        android.util.Log.d("MISSION_DEBUG", "Parsed missionIds: $missionIds")
                        
                        // CRITICAL FIX: Block "none" missions - let sequencer handle empty cases
                        localMissionType = missionIds.firstOrNull() ?: ""
                        localMissionPassword = if (localMissionType == "password") MissionSequencer.DEFAULT_GLOBAL_PASSWORD else ""
                        
                        android.util.Log.d("MISSION_DEBUG", "Final localMissionType: '$localMissionType'")
                    }
                    "tap" -> {
                        localMissionType = "tap"
                        localMissionPassword = ""
                    }
                    "password" -> {
                        localMissionType = "password"
                        localMissionPassword = alarm.missionPassword?.takeIf { it.isNotBlank() } ?: MissionSequencer.DEFAULT_GLOBAL_PASSWORD
                    }
                    "none" -> {
                        // CRITICAL FIX: Block "none" missions from being launched
                        localMissionType = ""
                        localMissionPassword = ""
                    }
                    else -> {
                        localMissionType = alarm.missionType
                        localMissionPassword = alarm.missionPassword
                    }
                }
                localIsProtected = alarm.isProtected
            }
        } catch (_: Exception) { }
        
        val intent = Intent(this, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(AlarmReceiver.ALARM_ID, currentAlarmId)
            putExtra(AlarmReceiver.EXTRA_RINGTONE_URI, ringtoneUri?.toString())
            putExtra(AlarmReceiver.EXTRA_REPEAT_DAYS, currentRepeatDays)
            if (isWakeCheckLaunch) putExtra("from_wake_check", true)
            
            // Pass mission data for Direct Boot restoration
            localMissionType?.let { putExtra("mission_type", it) }
            localMissionPassword?.let { putExtra("mission_password", it) }
            putExtra("is_protected", localIsProtected)
            
            // Check if this is a sequencer alarm and add sequencer flags
            val alarm = try {
                alarmStorage.getAlarm(currentAlarmId)
            } catch (_: Exception) {
                null
            }
            
            if (alarm?.missionType == "sequencer") {
                putExtra(com.vaishnava.alarm.sequencer.MissionSequencer.EXTRA_FROM_SEQUENCER, true)
                putExtra("sequencer_context", "notification_press")
                
                // Get current mission info from MissionSequencer
                try {
                    val mainActivity = MainActivity.getInstance()
                    val sequencer = mainActivity?.missionSequencer
                    val currentMission = sequencer?.getCurrentMission()
                    
                    if (currentMission != null) {
                        val actualMissionType = currentMission.params["mission_type"] ?: currentMission.id
                        putExtra("mission_id", currentMission.id)
                        putExtra("mission_type", actualMissionType)
                    }
                } catch (_: Exception) {}
            }
        }
        return PendingIntent.getActivity(
            this,
            currentAlarmId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // CRITICAL Fix: Delete existing channel to remove sound permanently
            // Android doesn't allow updating notification sound after channel creation
            try {
                notificationManager.deleteNotificationChannel("alarm_channel")
            } catch (e: Exception) { }

            val channel = NotificationChannel(
                "alarm_channel_v2", // Use new ID to ensure no sound
                "Alarm Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for active alarms with full-screen alerts"
                enableVibration(true)
                enableLights(true)
                setShowBadge(true)
                // CRITICAL Fix: Remove notification sound to prevent dual audio
                // The service already plays the correct ringtone, notification shouldn't play sound
                setSound(null, null) // Disable notification sound completely
            }

            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showNotification(title: String, content: String) {
        val nm: NotificationManager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "next_alarm_high",
                "Next Alarm Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Shows information about your next alarm"
                enableVibration(true)
                enableLights(true)
                setShowBadge(true)
                // Set proper sound for next alarm notifications
                val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                setSound(soundUri, audioAttributes)
                // Allow bypassing DND for important notifications
                setBypassDnd(true)
                // Show on lock screen
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(channel)
        }

        // Create a person for messaging style
        val me = androidx.core.app.Person.Builder()
            .setName("Alarm Assistant")
            .setImportant(true)
            .build()

        val messaging = NotificationCompat.MessagingStyle(me)
            .addMessage(content, System.currentTimeMillis(), me)
            .setConversationTitle("Alarm Reminder")

        // Build the notification with enhanced visibility
        val n: Notification = NotificationCompat.Builder(this, "next_alarm_high")
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(android.graphics.Color.parseColor("#FF5722")) // Use orange color for urgency
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(messaging)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setDefaults(NotificationCompat.DEFAULT_ALL) // Enable sound, vibration, lights
            .setOnlyAlertOnce(false) // Allow multiple alerts for different time windows
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(false) // Allow dismissal
            .setTimeoutAfter(30000) // Auto-dismiss after 30 seconds
            .build()

        nm.notify(2, n)
    }

    private fun clearNextAlarmNotification() {
        val nm: NotificationManager = getSystemService(NotificationManager::class.java)
        nm.cancel(2)
        lastNotifiedAlarmId = null
    }

    // ========= Next-alarm helpers =========

    private fun showAlarmNotificationIfNeeded() {
        try {
            val nextAlarm: Alarm? = getNextScheduledAlarm()
            if (nextAlarm != null) {
                val timeUntil: Double = getTimeUntilNextAlarmInMinutes(nextAlarm)
                val nowMs: Long = System.currentTimeMillis()

                // Enhanced timing windows for better user experience
                when {
                    // 1-minute notice: "Alarm in 1 minute"
                    timeUntil <= 1.0 && timeUntil > 0.5 && lastNotifiedAlarmId != nextAlarm.id -> {
                        showNotification(
                            "Alarm Soon",
                            "Your alarm will ring in 1 minute"
                        )
                        lastNotifiedAlarmId = nextAlarm.id
                        lastNotificationTime = nowMs
                    }
                    // 30-second notice: "Alarm in 30 seconds"
                    timeUntil <= 0.5 && timeUntil > 0.25 && lastNotifiedAlarmId == nextAlarm.id -> {
                        showNotification(
                            "Alarm Very Soon",
                            "Your alarm will ring in 30 seconds!"
                        )
                        lastNotificationTime = nowMs
                    }
                    // 10-second final notice: "Alarm in 10 seconds"
                    timeUntil <= 0.25 && timeUntil > 0.0 && (nowMs - lastNotificationTime > 15000) -> {
                        showNotification(
                            "Alarm Imminent",
                            "Your alarm will ring in 10 seconds!"
                        )
                        lastNotificationTime = nowMs
                    }
                    // Clear notification if alarm is more than 2 minutes away or has passed
                    timeUntil > 2.0 || timeUntil <= 0.0 -> {
                        clearNextAlarmNotification()
                        lastNotifiedAlarmId = null
                    }
                }
            } else {
                // No alarms scheduled, clear any existing notifications
                clearNextAlarmNotification()
                lastNotifiedAlarmId = null
            }
        } catch (_: Exception) { }
    }

    private fun getNextScheduledAlarm(): Alarm? {
        val alarms: List<Alarm> = alarmStorage.getAlarms().filter { it.isEnabled }
        if (alarms.isEmpty()) return null

        var next: Alarm? = null
        var nextTime: Long = Long.MAX_VALUE
        for (a in alarms) {
            val t: Long = calculateNextAlarmTime(a)
            if (t < nextTime) {
                nextTime = t
                next = a
            }
        }
        return next
    }

    private fun calculateNextAlarmTime(alarm: Alarm): Long {
        val now: Calendar = Calendar.getInstance()
        val alarmCal: Calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, alarm.hour)
            set(Calendar.MINUTE, alarm.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val days: List<Int>? = alarm.days
        if (!days.isNullOrEmpty()) {
            val currentDay: Int = now.get(Calendar.DAY_OF_WEEK)
            val currentHour: Int = now.get(Calendar.HOUR_OF_DAY)
            val currentMinute: Int = now.get(Calendar.MINUTE)

            if (days.contains(currentDay)) {
                if (alarm.hour > currentHour || (alarm.hour == currentHour && alarm.minute > currentMinute)) {
                    return alarmCal.timeInMillis
                }
            }

            for (i in 1..7) {
                val checkDay: Int = (currentDay + i - 1) % 7 + 1
                if (days.contains(checkDay)) {
                    alarmCal.add(Calendar.DAY_OF_YEAR, i)
                    return alarmCal.timeInMillis
                }
            }
        } else {
            if (alarmCal.timeInMillis <= now.timeInMillis) {
                alarmCal.add(Calendar.DAY_OF_YEAR, 1)
            }
            return alarmCal.timeInMillis
        }

        return alarmCal.timeInMillis
    }

    private fun getTimeUntilNextAlarmInMinutes(alarm: Alarm): Double {
        val nextTime: Long = calculateNextAlarmTime(alarm)
        val now: Long = System.currentTimeMillis()
        val diff: Long = nextTime - now
        return diff.toDouble() / (1000.0 * 60.0)
    }

    private fun formatTime12Hour(hour: Int, minute: Int): String {
        val period: String = if (hour >= 12) "PM" else "AM"
        val formattedHour: Int = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        return String.format("%d:%02d %s", formattedHour, minute, period)
    }

    // ========= Screen wake =========

    private fun turnScreenOn() {
        try {
            val pm: PowerManager = getSystemService(PowerManager::class.java)
            @Suppress("DEPRECATION")
            val wl: PowerManager.WakeLock = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                "AlarmApp::SvcScreenOn"
            )
            wl.acquire(3000)
            wl.release()
        } catch (_: Exception) { }
    }

    // ========= Volume save/restore helpers =========

    private fun savePreviousVolume(vol: Int) {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putInt(KEY_PREV_VOLUME, vol).apply()
        } catch (_: Exception) { }
    }

    private fun restorePreviousVolumeIfNeeded() {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.contains(KEY_PREV_VOLUME)) {
                val prev = prefs.getInt(KEY_PREV_VOLUME, -1)
                if (prev >= 0) {
                    val am = getSystemService(AUDIO_SERVICE) as? AudioManager
                    val max = am?.getStreamMaxVolume(AudioManager.STREAM_ALARM) ?: -1
                    val toSet = when {
                        max >= 0 && prev > max -> max
                        prev < 0 -> 0
                        else -> prev
                    }
                    try { am?.setStreamVolume(AudioManager.STREAM_ALARM, toSet, 0) } catch (_: Exception) {}
                }
                prefs.edit().remove(KEY_PREV_VOLUME).apply()
            } else {
                // fallback: if previousAlarmVolume variable was set earlier, restore that
                if (previousAlarmVolume >= 0) {
                    try {
                        val am = getSystemService(AUDIO_SERVICE) as? AudioManager
                        am?.setStreamVolume(AudioManager.STREAM_ALARM, previousAlarmVolume, 0)
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) { }
    }

    private fun ensureDndAllowsAlarm() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (!nm.isNotificationPolicyAccessGranted) {
                android.util.Log.w(TAG, "Notification policy access not granted; cannot override DND")
                return
            }

            if (previousInterruptionFilter == null) {
                previousInterruptionFilter = nm.currentInterruptionFilter
            }

            val current = nm.currentInterruptionFilter
            if (current != NotificationManager.INTERRUPTION_FILTER_ALARMS &&
                current != NotificationManager.INTERRUPTION_FILTER_ALL) {
                try {
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
                    android.util.Log.d(TAG, "Set interruption filter to ALARMS for active alarm")
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Failed to set interruption filter", e)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "ensureDndAllowsAlarm crash", e)
        }
    }

    private fun restoreInterruptionFilterIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val previous = previousInterruptionFilter ?: return
        try {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm == null || !nm.isNotificationPolicyAccessGranted) {
                previousInterruptionFilter = null
                return
            }
            try {
                nm.setInterruptionFilter(previous)
                android.util.Log.d(TAG, "Restored interruption filter to $previous")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to restore interruption filter", e)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "restoreInterruptionFilterIfNeeded crash", e)
        } finally {
            previousInterruptionFilter = null
        }
    }

}
