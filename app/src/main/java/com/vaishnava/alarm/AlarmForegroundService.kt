package com.vaishnava.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.audiofx.LoudnessEnhancer
import android.speech.tts.TextToSpeech
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.vaishnava.alarm.data.Alarm
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class AlarmForegroundService : Service() {

    companion object {
        private const val TAG: String = "AlarmForegroundService"
        private const val REPOST_DELAY_MS: Long = 5000L // 5 seconds
    }

    // Boost receiver to increase volume beyond max using LoudnessEnhancer
    private val boostReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != "com.vaishnava.alarm.BOOST_ALARM") return
            val gainMb: Int = intent.getIntExtra("gainMb", 1500).coerceIn(0, 3000)
            try {
                boostActive = true
                currentGainMb = gainMb
                if (mediaPlayer != null) {
                    if (loudnessEnhancer == null) {
                        loudnessEnhancer = LoudnessEnhancer(mediaPlayer!!.audioSessionId)
                    }
                    loudnessEnhancer?.setTargetGain(gainMb)
                    loudnessEnhancer?.enabled = true
                } else if (ringtoneFallback != null) {
                    // Fallback path: ensure alarm stream at max (already enforced elsewhere)
                    val am: AudioManager? = getSystemService(AudioManager::class.java)
                    val maxV: Int = am?.getStreamMaxVolume(AudioManager.STREAM_ALARM) ?: 7
                    am?.setStreamVolume(AudioManager.STREAM_ALARM, maxV, 0)
                }
            } catch (_: Exception) { }
            // Start ramping up automatically
            try {
                bgHandler.removeCallbacks(boostRampRunnable)
                if (currentGainMb < 3000) {
                    bgHandler.postDelayed(boostRampRunnable, 10_000)
                }
            } catch (_: Exception) { }
        }
    }

    // ========= Dependencies & state =========
    private lateinit var alarmStorage: AlarmStorage
    private lateinit var wakeLock: PowerManager.WakeLock
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var currentAlarmId: Int = -1
    private var currentRepeatDays: IntArray? = null
    private var previousAlarmVolume: Int = 0
    private val launcherScheduled: AtomicBoolean = AtomicBoolean(false)
    private var ringtoneUri: Uri? = null
    private var audioFocusRequest: android.media.AudioFocusRequest? = null
    private var duckRestoreRunnable: Runnable? = null
    private var ringtoneFallback: Ringtone? = null
    private var tts: TextToSpeech? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var boostActive: Boolean = false
    private var currentGainMb: Int = 0
    private var dpsWrittenForThisRing: Boolean = false
    private val boostRampRunnable: Runnable = object : Runnable {
        override fun run() {
            if (!boostActive) return
            try {
                // Increase by 250 mB (0.25 dB) steps until 3000 mB (30 dB)
                currentGainMb = (currentGainMb + 250).coerceAtMost(3000)
                if (mediaPlayer != null) {
                    loudnessEnhancer?.setTargetGain(currentGainMb)
                    loudnessEnhancer?.enabled = true
                } else if (ringtoneFallback != null) {
                    val am: AudioManager? = getSystemService(AudioManager::class.java)
                    val maxV: Int = am?.getStreamMaxVolume(AudioManager.STREAM_ALARM) ?: 7
                    am?.setStreamVolume(AudioManager.STREAM_ALARM, maxV, 0)
                }
            } catch (_: Exception) { }
            // Keep ramping every 10 seconds while active until cap
            if (boostActive && currentGainMb < 3000) {
                bgHandler.postDelayed(this, 10_000)
            }
        }
    }

    // Handler/Thread
    private lateinit var bgHandler: Handler
    private lateinit var bgThread: HandlerThread

    // ========= Notification tracking =========
    private var lastNotifiedAlarmId: Int? = null
    private var lastNotificationTime: Long = 0L

    // ========= Receivers / Runnables =========

    private val duckReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != "com.vaishnava.alarm.DUCK_ALARM") return

            val factor: Float = intent.getFloatExtra("factor", 0.2f).coerceIn(0.0f, 1.0f)
            val duration: Long = intent.getLongExtra("duration", 2500L).coerceAtLeast(500L)

            // Stop enforcing volume while ducked
            try { bgHandler.removeCallbacks(volumeEnforcer) } catch (_: Exception) {}
            duckRestoreRunnable?.let { bgHandler.removeCallbacks(it) }

            try {
                if (mediaPlayer != null) {
                    mediaPlayer?.setVolume(factor, factor)
                } else if (ringtoneFallback != null) {
                    val am: AudioManager? = getSystemService(AudioManager::class.java)
                    val maxV: Int = am?.getStreamMaxVolume(AudioManager.STREAM_ALARM) ?: 7
                    val duckV: Int = (maxV * factor).toInt().coerceAtLeast(1)
                    am?.setStreamVolume(AudioManager.STREAM_ALARM, duckV, 0)
                }
            } catch (_: Exception) { }

            duckRestoreRunnable = Runnable {
                try {
                    if (mediaPlayer != null) {
                        mediaPlayer?.setVolume(1.0f, 1.0f)
                    } else if (ringtoneFallback != null) {
                        val am: AudioManager? = getSystemService(AudioManager::class.java)
                        val maxV: Int = am?.getStreamMaxVolume(AudioManager.STREAM_ALARM) ?: 7
                        am?.setStreamVolume(AudioManager.STREAM_ALARM, maxV, 0)
                    }
                } catch (_: Exception) { }
                // Resume volume enforcement
                bgHandler.post(volumeEnforcer)
            }
            bgHandler.postDelayed(duckRestoreRunnable!!, duration)
        }
    }

    // Repost foreground notification periodically
    private val notificationReposter: Runnable = object : Runnable {
        override fun run() {
            val notification: Notification = buildNotification()
            startForeground(1, notification)
            bgHandler.postDelayed(this, REPOST_DELAY_MS)
        }
    }

    // Launch AlarmActivity
    private val activityLauncher: Runnable = Runnable {
        if (!launcherScheduled.get()) return@Runnable
        try {
            val intent = Intent(this@AlarmForegroundService, AlarmActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK or
                            Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                )
                putExtra(AlarmReceiver.ALARM_ID, currentAlarmId)
                putExtra(AlarmReceiver.EXTRA_RINGTONE_URI, ringtoneUri?.toString())
                putExtra(AlarmReceiver.EXTRA_REPEAT_DAYS, currentRepeatDays)
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                val fallbackIntent = Intent(this@AlarmForegroundService, AlarmActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    )
                    putExtra(AlarmReceiver.ALARM_ID, currentAlarmId)
                    putExtra(AlarmReceiver.EXTRA_RINGTONE_URI, ringtoneUri?.toString())
                    putExtra(AlarmReceiver.EXTRA_REPEAT_DAYS, currentRepeatDays)
                }
                startActivity(fallbackIntent)
            } catch (_: Exception) {
                try {
                    val wakeIntent = Intent("com.vaishnava.alarm.WAKE_UP").apply {
                        addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        putExtra(AlarmReceiver.ALARM_ID, currentAlarmId)
                    }
                    sendBroadcast(wakeIntent)
                } catch (_: Exception) { }
            }
        }
    }

    // Enforce max volume
    private val volumeEnforcer: Runnable = object : Runnable {
        override fun run() {
            try {
                val audioManager: AudioManager? = getSystemService(AudioManager::class.java)

                // Ensure audio mode
                try {
                    if (audioManager?.mode != AudioManager.MODE_RINGTONE) {
                        audioManager?.mode = AudioManager.MODE_RINGTONE
                    }
                } catch (_: Exception) { }

                val currentVolume: Int = audioManager?.getStreamVolume(AudioManager.STREAM_ALARM) ?: 0
                val maxVolume: Int = audioManager?.getStreamMaxVolume(AudioManager.STREAM_ALARM) ?: 0
                if (currentVolume < maxVolume) {
                    audioManager?.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)
                }

                // Also bump music stream (some OEMs route alarm via MUSIC)
                try {
                    val musicCurrent: Int = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                    val musicMax: Int = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
                    if (musicCurrent < musicMax) {
                        audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, musicMax, 0)
                    }
                } catch (_: Exception) { }
            } catch (_: Exception) { }

            if (mediaPlayer?.isPlaying == true) {
                bgHandler.postDelayed(this, 500)
            }
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
        val powerManager: PowerManager = getSystemService()!!
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AlarmApp::AlarmService"
        )

        bgThread = HandlerThread("AlarmServiceBackground")
        bgThread.start()
        bgHandler = Handler(bgThread.looper)

        vibrator = getSystemService()

        // Foreground immediately
        val initial: Notification = buildNotification()
        startForeground(1, initial)

        // Register duck receiver
        try {
            val filter = IntentFilter("com.vaishnava.alarm.DUCK_ALARM")
            registerReceiver(duckReceiver, filter)
        } catch (_: Exception) { }
        // Register boost receiver
        try {
            val filter = IntentFilter("com.vaishnava.alarm.BOOST_ALARM")
            registerReceiver(boostReceiver, filter)
        } catch (_: Exception) { }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle stop
        if (intent?.action == Constants.ACTION_STOP_FOREGROUND_SERVICE) {
            clearNextAlarmNotification()
            stopSelf()
            return START_NOT_STICKY
        }

        // Acquire indefinite wakelock during alarm
        if (!wakeLock.isHeld) {
            try { wakeLock.acquire() } catch (_: Exception) { }
        }

        // Extract extras
        currentAlarmId = intent?.getIntExtra(AlarmReceiver.ALARM_ID, -1) ?: -1
        this.ringtoneUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(AlarmReceiver.EXTRA_RINGTONE_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(AlarmReceiver.EXTRA_RINGTONE_URI)
        }
        currentRepeatDays = intent?.getIntArrayExtra(AlarmReceiver.EXTRA_REPEAT_DAYS) ?: intArrayOf()

        dpsWrittenForThisRing = false

        // Show 1-minute notice when appropriate
        showAlarmNotificationIfNeeded()

        // Start periodic notification repost
        bgHandler.post(notificationReposter)
        // Start periodic next-alarm checks
        bgHandler.post(notificationChecker)

        // Turn screen on
        turnScreenOn()

        // Speak current time immediately via service-level TTS to minimize delay
        try {
            val nowStr: String = try {
                val fmt = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                fmt.format(java.util.Date())
            } catch (_: Exception) { "now" }
            val message = "The time is $nowStr. It is time to wake up now."
            // Light duck for clarity
            try {
                val duck = Intent("com.vaishnava.alarm.DUCK_ALARM").apply {
                    putExtra("factor", 0.25f)
                    putExtra("duration", 2000L)
                }
                sendBroadcast(duck)
            } catch (_: Exception) { }
            if (tts == null) {
                tts = TextToSpeech(this) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        try { tts?.language = Locale.getDefault() } catch (_: Exception) {}
                        try {
                            val attrs = android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                            tts?.setAudioAttributes(attrs)
                        } catch (_: Exception) { }
                        try { tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "WAKE_MSG_SVC") } catch (_: Exception) { }
                    }
                }
            }
            else {
                try { tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "WAKE_MSG_SVC") } catch (_: Exception) { }
            }
        } catch (_: Exception) { }

        // Prepare audio & play
        startPlayback(this.ringtoneUri ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))

        // Launch activity once, after playback has started
        if (launcherScheduled.compareAndSet(false, true)) {
            bgHandler.post(activityLauncher)
        }

        // Keep service alive
        return START_STICKY
    }

    override fun onDestroy() {
        try { unregisterReceiver(duckReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(boostReceiver) } catch (_: Exception) {}

        // Media cleanup
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {}
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null

        // Stop vibration
        try { vibrator?.cancel() } catch (_: Exception) {}

        // Restore volumes/mode/focus
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_ALARM, previousAlarmVolume, 0)
        } catch (_: Exception) {}

        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {}

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

        try { clearNextAlarmNotification() } catch (_: Exception) {}

        // Remove callbacks
        try { bgHandler.removeCallbacks(notificationReposter) } catch (_: Exception) {}
        try { bgHandler.removeCallbacks(activityLauncher) } catch (_: Exception) {}
        try { bgHandler.removeCallbacks(volumeEnforcer) } catch (_: Exception) {}
        try { bgHandler.removeCallbacks(notificationChecker) } catch (_: Exception) {}

        // Release wakelock
        if (wakeLock.isHeld) {
            try { wakeLock.release() } catch (_: Exception) {}
        }

        // Quit thread
        try { bgThread.quit() } catch (_: Exception) {}

        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        try { loudnessEnhancer?.enabled = false; loudnessEnhancer?.release(); loudnessEnhancer = null } catch (_: Exception) {}
        try { boostActive = false; bgHandler.removeCallbacks(boostRampRunnable) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ========= Playback =========

    private fun startPlayback(finalRingtoneUri: Uri) {
        // Reset any existing player
        try { mediaPlayer?.stop() } catch (_: Throwable) {}
        try { mediaPlayer?.release() } catch (_: Throwable) {}
        mediaPlayer = null

        val audioManager: AudioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        // Force ringtone mode
        try { audioManager.mode = AudioManager.MODE_RINGTONE } catch (_: Exception) {}

        // Save and set volumes
        previousAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        val maxAlarm: Int = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarm, 0)
        try {
            val musicMax: Int = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, musicMax, 0)
        } catch (_: Exception) {}

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

        // Start enforcing volume
        bgHandler.post(volumeEnforcer)

        // Try MediaPlayer first; if it fails, use Ringtone fallback
        try {
            val attrs: AudioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            if (finalRingtoneUri.scheme == "android.resource") {
                // Handle resource URIs
                val packageName: String? = finalRingtoneUri.authority
                val segments: List<String> = finalRingtoneUri.pathSegments ?: emptyList()
                var resourceId: Int = 0

                if (segments.size == 1) {
                    resourceId = try { segments[0].toInt() } catch (_: NumberFormatException) { 0 }
                } else if (segments.size == 2 && segments[0] == "raw") {
                    val name = segments[1]
                    resourceId = resources.getIdentifier(name, "raw", packageName)
                }

                if (resourceId != 0) {
                    val mp: MediaPlayer = MediaPlayer.create(this, resourceId)
                    mp.setAudioAttributes(attrs)
                    mp.isLooping = false
                    mp.setVolume(1.0f, 1.0f)
                    mp.setOnCompletionListener { p ->
                        try {
                            if (p.isPlaying) p.stop()
                            p.seekTo(0)
                            p.start()
                        } catch (_: Exception) {
                            val recreated = restartMediaPlayer(p, finalRingtoneUri, resourceId, attrs)
                            if (recreated != null) mediaPlayer = recreated
                        }
                    }
                    mp.start()
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
                    try {
                        // Prepare loudness enhancer but leave disabled until asked
                        loudnessEnhancer?.release()
                        loudnessEnhancer = LoudnessEnhancer(mp.audioSessionId).apply {
                            enabled = false
                        }
                    } catch (_: Exception) { }
                    mediaPlayer = mp
                } else {
                    // Fallthrough to generic path
                    val mp = MediaPlayer()
                    mp.setAudioAttributes(attrs)
                    mp.setDataSource(this, finalRingtoneUri)
                    mp.isLooping = false
                    mp.setVolume(1.0f, 1.0f)
                    mp.setOnCompletionListener { p ->
                        try {
                            if (p.isPlaying) p.stop()
                            p.seekTo(0)
                            p.start()
                        } catch (_: Exception) {
                            val recreated = restartMediaPlayer(p, finalRingtoneUri, 0, attrs)
                            if (recreated != null) mediaPlayer = recreated
                        }
                    }
                    mp.prepare()
                    mp.start()
                    try {
                        loudnessEnhancer?.release()
                        loudnessEnhancer = LoudnessEnhancer(mp.audioSessionId).apply { enabled = false }
                    } catch (_: Exception) { }
                    mediaPlayer = mp
                }
            } else {
                // Non-resource Uris
                val mp = MediaPlayer()
                mp.setAudioAttributes(attrs)
                mp.setDataSource(this, finalRingtoneUri)
                mp.isLooping = false
                mp.setVolume(1.0f, 1.0f)
                mp.setOnCompletionListener { p ->
                    try {
                        if (p.isPlaying) p.stop()
                        p.seekTo(0)
                        p.start()
                    } catch (_: Exception) {
                        val recreated = restartMediaPlayer(p, finalRingtoneUri, 0, attrs)
                        if (recreated != null) mediaPlayer = recreated
                    }
                }
                mp.prepare()
                mp.start()
                try {
                    loudnessEnhancer?.release()
                    loudnessEnhancer = LoudnessEnhancer(mp.audioSessionId).apply { enabled = false }
                } catch (_: Exception) { }
                mediaPlayer = mp
            }
        } catch (_: Exception) {
            // Ringtone fallback
            try {
                val ring: Ringtone = RingtoneManager.getRingtone(this, finalRingtoneUri)
                ring.audioAttributes = audioAttributes
                ring.isLooping = true
                ring.play()
                // DPS write for fallback path: audio actually started
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
            } catch (_: Exception) { }
        }
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
            newPlayer.isLooping = false
            newPlayer.setVolume(1.0f, 1.0f)
            newPlayer.start()
            newPlayer
        } catch (_: Exception) {
            null
        }
    }

    // ========= Notifications =========

    private fun buildNotification(): Notification {
        createNotificationChannel()
        val fullScreen: PendingIntent = createFullScreenIntent()

        return NotificationCompat.Builder(this, "alarm_channel")
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(android.graphics.Color.parseColor("#1976D2"))
            .setContentTitle("Alarm Active")
            .setContentText("Your alarm is ringing")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreen, true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)
            .build()
    }

    private fun createFullScreenIntent(): PendingIntent {
        val intent = Intent(this, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(AlarmReceiver.ALARM_ID, currentAlarmId)
            putExtra(AlarmReceiver.EXTRA_RINGTONE_URI, ringtoneUri?.toString())
            putExtra(AlarmReceiver.EXTRA_REPEAT_DAYS, currentRepeatDays)
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
            val channel = NotificationChannel(
                "alarm_channel",
                "Alarm Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for active alarms"
                enableVibration(false)
                enableLights(true)
            }
            val nm: NotificationManager = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun showNotification(title: String, content: String) {
        val nm: NotificationManager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "next_alarm_channel",
                "Next Alarm",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Shows information about your next alarm" }
            nm.createNotificationChannel(channel)
        }

        val n: Notification = NotificationCompat.Builder(this, "next_alarm_channel")
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(android.graphics.Color.parseColor("#1976D2"))
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
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

                // Only when inside (0, 1.0] minute window
                if (timeUntil <= 1.0 && timeUntil > 0.0) {
                    if (lastNotifiedAlarmId != nextAlarm.id) {
                        showNotification(
                            "Next Alarm",
                            formatTime12Hour(nextAlarm.hour, nextAlarm.minute)
                        )
                        lastNotifiedAlarmId = nextAlarm.id
                        lastNotificationTime = nowMs
                    }
                } else {
                    if (timeUntil > 1.2) {
                        lastNotifiedAlarmId = null
                    }
                }
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
}
