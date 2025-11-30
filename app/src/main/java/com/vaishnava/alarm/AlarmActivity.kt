@file:Suppress("DEPRECATION")

package com.vaishnava.alarm

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.VideoView
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaishnava.alarm.ui.AutoSizeText
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.layout.onSizeChanged
import com.vaishnava.alarm.ui.theme.AlarmTheme
import com.vaishnava.alarm.data.WakeCheckStore
import com.vaishnava.alarm.data.resolveRingtoneTitle
import com.vaishnava.alarm.data.Alarm
import com.vaishnava.alarm.AlarmStorage
import com.vaishnava.alarm.AlarmReceiver
import com.vaishnava.alarm.AndroidAlarmScheduler
import com.vaishnava.alarm.sequencer.MissionSequencer
import kotlinx.coroutines.delay
import java.util.*
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class AlarmActivity : ComponentActivity() {

    companion object {
        private const val TAG = "AlarmActivity"

        private fun writePersistentLog(message: String) {
            try {
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val context = MainActivity.getInstance()
                val logFile = File(context?.filesDir, "alarm_debug.log")
                val writer = FileWriter(logFile, true)
                writer.append("[$timestamp] [ALARM_ACTIVITY] $message\n")
                writer.close()
            } catch (e: Exception) {
                // Silently fail if we can't write to storage
            }
        }

        // Add external logging for mission events
        fun logMissionEvent(message: String) {
            try {
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val context = MainActivity.getInstance()
                val logFile = File(context?.filesDir, "alarm_debug.log")
                val writer = FileWriter(logFile, true)
                writer.append("[$timestamp] [MISSION] $message\n")
                writer.close()
            } catch (e: Exception) {
                // Silently fail if we can't write to storage
            }
        }
    }

    // -------------------- Sequencer Context --------------------
    private var isSequencerMission: Boolean = false
    private var sequencerContext: String = ""

    // -------------------- TTS --------------------
    private var tts: android.speech.tts.TextToSpeech? = null
    private var ttsMessage: String = ""
    private val ttsHandler = Handler(Looper.getMainLooper())
    // DISABLED: ttsRunnable removed - legacy TTS repeater causing continuous repetition
    private var lastSpokenTime: String = ""

    private val writeSettingsState = mutableStateOf(false)

    // Bring-to-front management for background/swipe scenarios
    private val bringToFrontHandler = Handler(Looper.getMainLooper())
    private val bringToFrontRunnable = Runnable {
        try {
            if (!activityDismissed && !isDismissed) {
                bringToFront()
            }
        } catch (_: Exception) { }
    }

    private fun startTtsRepeater() {
        // DISABLED: Legacy TTS repeater causing continuous repetition
        // This independent timer conflicted with the forensic TTS system in AlarmForegroundService
        // TTS is now handled exclusively by the service with proper completion guards
        Log.d(TAG, "Legacy TTS repeater disabled - using service-based forensic TTS system")
    }

    private fun exitPreview() {
        if (!isPreview) return
        try {
            activityDismissed = true
            isDismissed = true
            runCatching {
                val dps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) createDeviceProtectedStorageContext() else this
                val prefs = dps.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                prefs.edit().putLong("preview_exit_block_$alarmId", System.currentTimeMillis()).apply()
            }
            // DISABLED: ttsRunnable cleanup - legacy TTS repeater removed
            try { tts?.stop(); tts?.shutdown(); tts = null } catch (_: Exception) {}
            try { ttsHandler.removeCallbacksAndMessages(null) } catch (_: Exception) {}
            try {
                val svc = Intent(this@AlarmActivity, AlarmForegroundService::class.java).apply {
                    action = Constants.ACTION_STOP_FOREGROUND_SERVICE
                    putExtra(AlarmReceiver.ALARM_ID, alarmId)
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)
            } catch (_: Exception) {}
            try {
                val am = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                // Primary fire PI
                runCatching {
                    val piFire = android.app.PendingIntent.getBroadcast(
                        this, alarmId,
                        Intent(this, AlarmReceiver::class.java).apply {
                            action = "com.vaishnava.alarm.DIRECT_BOOT_ALARM"; data = android.net.Uri.parse("alarm://$alarmId"); putExtra(AlarmReceiver.ALARM_ID, alarmId)
                        },
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                    ); am.cancel(piFire); piFire.cancel()
                }
                // Wake-check follow-up PI
                runCatching {
                    val wake = android.app.PendingIntent.getBroadcast(
                        this, alarmId xor 0x9C0A,
                        Intent(this, AlarmReceiver::class.java).apply {
                            action = "com.vaishnava.alarm.WAKE_UP"; data = android.net.Uri.parse("alarm-wakecheck://$alarmId"); putExtra(AlarmReceiver.ALARM_ID, alarmId)
                        },
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                    ); am.cancel(wake); wake.cancel()
                }
                // WAKE_UP backup variants used in restore paths
                runCatching {
                    val wake1 = android.app.PendingIntent.getBroadcast(
                        this, alarmId xor 0x4A11,
                        Intent(this, AlarmReceiver::class.java).apply {
                            action = "com.vaishnava.alarm.WAKE_UP"; data = android.net.Uri.parse("alarm-wakeup://$alarmId"); putExtra(AlarmReceiver.ALARM_ID, alarmId)
                        },
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                    ); am.cancel(wake1); wake1.cancel()
                }
                runCatching {
                    val wake2 = android.app.PendingIntent.getBroadcast(
                        this, alarmId xor 0x6B7F,
                        Intent(this, AlarmReceiver::class.java).apply {
                            action = "com.vaishnava.alarm.WAKE_UP"; data = android.net.Uri.parse("alarm-wakeup2://$alarmId"); putExtra(AlarmReceiver.ALARM_ID, alarmId)
                        },
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                    ); am.cancel(wake2); wake2.cancel()
                }
                // AlarmClock showIntent (getActivity) used by scheduler
                runCatching {
                    val showPi = android.app.PendingIntent.getActivity(
                        this, alarmId,
                        Intent(this, AlarmActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            putExtra(AlarmReceiver.ALARM_ID, alarmId)
                        },
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                    ); am.cancel(showPi); showPi.cancel()
                }
            } catch (_: Exception) {}
        } catch (_: Exception) {}
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) finishAndRemoveTask() else finish()
            overridePendingTransition(0, 0)
        } catch (_: Exception) {}
    }

    private fun requestWriteSettingsPermission() {
        if (Settings.System.canWrite(this)) {
            writeSettingsState.value = true
            return
        }
        runCatching {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    private fun sendDuckForTts() {
        try {
            val duck = Intent("com.vaishnava.alarm.DUCK_ALARM").apply { putExtra("duration", 5000) }
            sendBroadcast(duck)
        } catch (_: Exception) {}
    }

    // -------------------- Mission / timers --------------------
    private val REQUEST_CODE_OVERLAY_PERMISSION = 101
    private var requiredPassword: String? = null
    private val DEFAULT_GLOBAL_PASSWORD = "IfYouWantYouCanSleep"

    private enum class TimerState { StartupBlocked, InitialEntry, Blocked, Idle }

    private val STARTUP_BLOCKED_TIME_SECONDS = 90     // 90s (after Start Mission)
    private val INITIAL_PASSWORD_ENTRY_TIME_SECONDS = 30  // 30s entry window
    private val BLOCKED_TIME_SECONDS = 120            // 120s lockout

    private var timerState = TimerState.Idle
    private var alarmId = -1
    private var currentMissionId: String? = null
    @Volatile private var sequencerMissionUpdate: Boolean = false
    @Volatile private var isDismissed: Boolean = false
    @Volatile private var activityDismissed: Boolean = false
    @Volatile private var wakeCheckAcknowledged: Boolean = false
    @Volatile private var wakeCheckGuardActive: Boolean = false
    @Volatile private var samsungNoRestartGuard: Boolean = false
    private val isWakeCheckLaunchState = mutableStateOf(false)
    private var missionTapEnabled: Boolean = false
    private var isPreview: Boolean = false
    private var previewMission: String? = null
    private var isTestMode: Boolean = false
    private var isFromWakeCheck: Boolean = false

    private fun isSamsungProblemModel(): Boolean {
        val manu = Build.MANUFACTURER ?: ""
        val model = Build.MODEL ?: ""
        return manu.equals("samsung", ignoreCase = true) && model.uppercase().startsWith("SM-E156")
    }

    // -------------------- Screen receiver --------------------
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> { /* no-op */ }
                Intent.ACTION_USER_PRESENT -> { /* no-op */ }
            }
        }
    }

    private fun acknowledgeWakeCheck(alarmId: Int) {
        try {
            // Fast path: if this alarm has already been acknowledged within the
            // recent window, treat this as a no-op except for ensuring that any
            // lingering service/audio is stopped and the UI is closed. This
            // makes the handler fully idempotent against rapid double-taps.
            val alreadyAcked = WakeCheckStore.isAcked(this, alarmId, 10 * 60_000L)
            activityDismissed = true
            this.wakeCheckAcknowledged = true
            wakeCheckGuardActive = false

            // Persist authoritative acknowledgement + clear pending / gate
            // flags in device-protected storage so receivers/services see
            // the state immediately.
            val now = System.currentTimeMillis()
            WakeCheckStore.markAck(this, alarmId, now)
            runCatching {
                val dps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) createDeviceProtectedStorageContext() else this
                val prefs = dps.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                prefs.edit()
                    .putLong("dismiss_ts_$alarmId", now)
                    .putBoolean("wakecheck_pending_$alarmId", false)
                    .putBoolean("wakecheck_finalized_$alarmId", true)
                    .putBoolean("wakecheck_gate_active_$alarmId", false)
                    .apply()
                if (isSamsungProblemModel()) {
                    samsungNoRestartGuard = true
                    prefs.edit()
                        .putLong("samsung_no_restart_guard_ts_$alarmId", now)
                        .apply()
                }
            }

            // Cancel any pending wake-up check follow-up alarm using the same
            // deterministic PendingIntent parameters as scheduling.
            Log.d(TAG, "acknowledgeWakeCheck: cancel follow-up for $alarmId (alreadyAcked=$alreadyAcked)")
            cancelWakeCheckFollowUp(this, alarmId)

            // Stop TTS immediately
            // DISABLED: ttsRunnable cleanup - legacy TTS repeater removed
            try { ttsHandler.removeCallbacksAndMessages(null) } catch (_: Exception) {}
            try {
                tts?.apply {
                    stop()
                    shutdown()
                }
            } catch (_: Exception) {}

            // Disable only true one-shot alarms; repeating alarms stay enabled for next wake-up checks
            try {
                val storage = AlarmStorage(applicationContext)
                val alarm = storage.getAlarm(alarmId)
                if (alarm != null) {
                    val isOneShot = (alarm.days.isNullOrEmpty() && !alarm.repeatDaily)
                    if (isOneShot) {
                        try {
                            storage.disableAlarm(alarmId)
                        } catch (_: Exception) {
                            try {
                                storage.updateAlarm(alarm.copy(isEnabled = false))
                            } catch (_: Exception) {}
                        }
                    }
                }
            } catch (_: Exception) {}

            // Stop the foreground service (stop ringing/vibration) without running full dismissal logic
            val serviceIntent = Intent(this@AlarmActivity, AlarmForegroundService::class.java).apply {
                action = Constants.ACTION_STOP_FOREGROUND_SERVICE
                putExtra(AlarmReceiver.ALARM_ID, alarmId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            // Navigate back to main alarm list, mirroring dismissAlarm behaviour
            Log.d(TAG, "acknowledgeWakeCheck: navigating to MainActivity and finishing for $alarmId")
            val homeIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("from_alarm_dismiss", true)
            }
            startActivity(homeIntent)
            finish()
        } catch (_: Exception) {}
    }

    private fun bringToFront() {
        try {
            if (activityDismissed) return

            // First try to move the current task to front
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val activityManager = getSystemService(ACTIVITY_SERVICE) as? android.app.ActivityManager
                    val tasks = activityManager?.getRunningTasks(1)
                    if (!tasks.isNullOrEmpty() && tasks[0].id == taskId) {
                        activityManager?.moveTaskToFront(taskId, android.app.ActivityManager.MOVE_TASK_WITH_HOME)
                    }
                }
            } catch (_: Exception) { }

            // Then also try the intent approach as backup with enhanced flags
            val intent = Intent(this@AlarmActivity, AlarmActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT or
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_NO_HISTORY or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
                putExtra(AlarmReceiver.ALARM_ID, alarmId)
                if (isPreview) {
                    putExtra("is_preview", true)
                }
                if (isTestMode) {
                    putExtra("is_test_mode", true)
                }
                if (isFromWakeCheck) {
                    putExtra("from_wake_check", true)
                }
            }
            startActivity(intent)

            // Also ensure window flags are set properly
            try {
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                            WindowManager.LayoutParams.FLAG_FULLSCREEN
                )
            } catch (_: Exception) { }
        } catch (_: Exception) { }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        writeSettingsState.value = Settings.System.canWrite(this)

        // Show over lockscreen / wake - MUST be called before any UI setup
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        // Extra flags for ensuring visibility
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        // Register receiver
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenReceiver, filter)
            }
        } catch (_: Exception) {}

        alarmId = intent.getIntExtra(AlarmReceiver.ALARM_ID, -1)
        currentMissionId = intent.getStringExtra("mission_id")
        isWakeCheckLaunchState.value = intent.getBooleanExtra("from_wake_check", false)
        isSequencerMission = intent.getBooleanExtra(com.vaishnava.alarm.sequencer.MissionSequencer.EXTRA_FROM_SEQUENCER, false)
        sequencerContext = intent.getStringExtra("sequencer_context") ?: ""
        Log.d(TAG, "onCreate: alarmId=$alarmId missionId=$currentMissionId isSequencerMission=$isSequencerMission sequencerContext=$sequencerContext fromWakeCheck=${isWakeCheckLaunchState.value}")
        isPreview = intent.getBooleanExtra("preview", false)
        isTestMode = intent.getBooleanExtra("is_test_mode", false)
        isFromWakeCheck = intent.getBooleanExtra("from_wake_check", false)
        Log.d(TAG, "onCreateFlags: isPreview=$isPreview isTestMode=$isTestMode isFromWakeCheck=$isFromWakeCheck")

        // Debug: Show all intent extras including mission type
        Log.d(TAG, "=== INTENT DEBUG ===")
        val missionTypeFromIntent = intent.getStringExtra("mission_type")
        Log.d(TAG, "alarmId=$alarmId missionId=$currentMissionId missionType=$missionTypeFromIntent from_wake_check=${isWakeCheckLaunchState.value} isSequencerMission=$isSequencerMission sequencerContext=$sequencerContext")
        Log.d(TAG, "Intent extras: ${intent.extras?.keySet()?.joinToString(", ")}")
        intent.extras?.keySet()?.forEach { key ->
            Log.d(TAG, "  $key = ${intent.extras?.get(key)}")
        }
        Log.d(TAG, "=== END INTENT DEBUG ===")

        Log.d("WakeCheckDebug", "AlarmActivity.onCreate alarmId=$alarmId from_wake_check=${isWakeCheckLaunchState.value}")
        if (isWakeCheckLaunchState.value) {
            // Wake-up check follow-up launch; no user-facing toast needed.
        }

        // Load config & TTS (only for initial alarm launches, not wake-check follow-ups)
        val isWakeCheckAlarm: Boolean = try {
            val storage = AlarmStorage(applicationContext)
            val alarm = storage.getAlarms().find { it.id == alarmId }
            Log.d(TAG, "CONFIG_LOAD_START: alarmId=$alarmId isSequencerMission=$isSequencerMission sequencerContext=$sequencerContext hasAlarm=${alarm != null}")

            // Persisted missionType from storage is the single source of truth for alarm missions.
            // For sequencer missions, use the mission type from the intent instead
            val persistedMissionType = if (isSequencerMission) {
                val missionType = intent?.getStringExtra("mission_type")
                Log.d(TAG, "SEQUENCER_MISSION_TYPE: alarmId=$alarmId missionType=$missionType missionId=${intent?.getStringExtra("mission_id")}")
                // Never default to "none" for sequencer missions - use mission_id as fallback
                missionType ?: intent?.getStringExtra("mission_id") ?: "unknown"
            } else {
                alarm?.missionType ?: "none"
            }
            missionTapEnabled = (persistedMissionType == "tap")
            requiredPassword = when {
                missionTapEnabled -> null
                persistedMissionType == "password" ->
                    alarm?.missionPassword?.takeIf { it.isNotBlank() } ?: DEFAULT_GLOBAL_PASSWORD
                alarm?.missionPassword?.isNotBlank() == true ->
                    alarm.missionPassword
                else -> null
            }

            Log.d(TAG, "MISSION_CONFIG: alarmId=$alarmId isSequencer=$isSequencerMission missionType=$persistedMissionType tapEnabled=$missionTapEnabled hasPassword=${requiredPassword != null}")

            // Set currentMissionId for sequencer missions to prevent fallback issues
            if (isSequencerMission) {
                currentMissionId = intent?.getStringExtra("mission_id")
                    ?: "sequencer_mission_${alarmId}"
            }

            if (isPreview) {
                val pm = intent.getStringExtra("preview_mission")
                previewMission = pm
                missionTapEnabled = (pm == "tap")
                requiredPassword = if (pm == "password") "1234" else null // Use simple password for preview
                isWakeCheckLaunchState.value = false
            }

            if (!isWakeCheckLaunchState.value) {
                val nowStr: String = try {
                    val fmt = java.text.SimpleDateFormat("H:mm", java.util.Locale.US)
                    val time = fmt.format(java.util.Date())
                    val (hour, minute) = time.split(":").map { it.toInt() }
                    val period = if (hour < 12) "AM" else "PM"
                    val displayHour = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
                    val minuteStr = if (minute < 10) "zero $minute" else minute.toString()
                    "$displayHour $minuteStr $period"
                } catch (_: Exception) { 
                    // Always try to get time, even if exception occurs
                    try {
                        val cal = java.util.Calendar.getInstance()
                        val hour = cal.get(java.util.Calendar.HOUR)
                        val minute = cal.get(java.util.Calendar.MINUTE)
                        val ampm = if (cal.get(java.util.Calendar.AM_PM) == java.util.Calendar.AM) "AM" else "PM"
                        val displayHour = if (hour == 0) 12 else hour
                        val minuteStr = if (minute < 10) "zero $minute" else minute.toString()
                        "$displayHour $minuteStr $ampm"
                    } catch (_: Exception) { 
                        // Final fallback - use system time directly
                        java.text.SimpleDateFormat("h:mm a").format(java.util.Date())
                    }
                }
                ttsMessage = "The time is $nowStr. Time to wake up."

                // Check if silence_no_sound is selected - if so, don't use TTS
                val isSilenceRingtone = try {
                    val storage = AlarmStorage(applicationContext)
                    val alarm = storage.getAlarms().find { it.id == alarmId }
                    val ringtoneUri = alarm?.ringtoneUri?.toString()
                    Log.d(TAG, "TTS_CHECK: alarmId=$alarmId ringtoneUri=$ringtoneUri")

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

                    Log.d(TAG, "TTS_CHECK: containsSilence=$containsSilence")

                    // Write to persistent log file
                    writePersistentLog("TTS_CHECK: alarmId=$alarmId ringtoneUri=$ringtoneUri containsSilence=$containsSilence")

                    // Add to app's sequencer log system
                    MainActivity.getInstance()?.addSequencerLog("TTS_CHECK: alarmId=$alarmId ringtoneUri=$ringtoneUri containsSilence=$containsSilence")

                    containsSilence
                } catch (_: Exception) {
                    Log.d(TAG, "TTS_CHECK: Exception checking ringtone")
                    writePersistentLog("TTS_CHECK: Exception checking ringtone for alarmId=$alarmId")
                    MainActivity.getInstance()?.addSequencerLog("TTS_CHECK: Exception checking ringtone for alarmId=$alarmId")
                    false
                }

                if (!isSilenceRingtone) {
                    tts = android.speech.tts.TextToSpeech(this) { status ->
                        if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                            tts?.language = Locale.getDefault()
                            try {
                                val attrs = AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build()
                                tts?.setAudioAttributes(attrs)
                            } catch (_: Exception) {}
                            tts?.speak(ttsMessage, android.speech.tts.TextToSpeech.QUEUE_ADD, null, "WAKE_MSG")
                            lastSpokenTime = nowStr
                            // DISABLED: Legacy TTS repeater removed - service handles periodic TTS
                            Log.d(TAG, "Initial TTS spoken - periodic TTS handled by service")
                        }
                    }
                } else {
                    Log.d(TAG, "Silence ringtone detected - TTS disabled")
                }
            }
            Log.d(TAG, "CONFIG_LOAD_DONE: alarmId=$alarmId missionTapEnabled=$missionTapEnabled requiredPasswordSet=${requiredPassword != null} isSequencerMission=$isSequencerMission sequencerContext=$sequencerContext")
            true
        } catch (_: Exception) {
            requiredPassword = null
            Log.w(TAG, "CONFIG_LOAD_ERROR: alarmId=$alarmId; falling back to no mission/password")
            false
        }

        Handler(Looper.getMainLooper()).postDelayed({ checkOverlayPermission() }, 5000)

        setContent {
            AlarmTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0)
                ) { _ ->
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                        // Pure black background for eye protection

                        AlarmUI(
                            alarmId = alarmId,
                            requiredPassword = requiredPassword,
                            isWakeCheck = isWakeCheckAlarm && isWakeCheckLaunchState.value,
                            writeSettingsGranted = writeSettingsState.value,
                            onOpenWriteSettings = { requestWriteSettingsPermission() }
                        )
                    }
                }
            }
        }

        turnScreenOn()
    }

    override fun onResume() {
        super.onResume()
        writeSettingsState.value = Settings.System.canWrite(this)
    }

    private fun turnScreenOn() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                "AlarmApp::TurnScreenOn"
            )
            wakeLock.acquire(5000)
            wakeLock.release()
        } catch (e: Exception) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    setShowWhenLocked(true)
                    setTurnScreenOn(true)
                } else {
                    window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
                }
            } catch (_: Exception) {}
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Check if this is a protected alarm and block volume button dismissal
        val alarmStorage = AlarmStorage(applicationContext)
        val alarm = alarmStorage.getAlarms().find { it.id == alarmId }

        if (alarm?.isProtected == true && (
            keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        )) {
            Log.w(TAG, "Volume button press blocked for protected alarm $alarmId")
            return true
        }

        return if (
            keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_POWER
        ) {
            true
        } else super.onKeyDown(keyCode, event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        try {
            // If the wake-check gate is currently active, ignore any incoming
            // normal (non-wwake-check) intents so the "I'm awake" page is not
            // instantly replaced by the normal alarm UI.
            val incomingIsWakeCheck = intent.getBooleanExtra("from_wake_check", false)
            if (wakeCheckGuardActive && !incomingIsWakeCheck) {
                Log.d(TAG, "onNewIntent: ignoring normal intent while wake-check gate is active for alarmId=$alarmId")
                return
            }

            alarmId = intent.getIntExtra(AlarmReceiver.ALARM_ID, alarmId)

            // Handle sequencer mission updates
            val fromSequencer = intent.getBooleanExtra(MissionSequencer.EXTRA_FROM_SEQUENCER, false)
            val sequencerComplete = intent.getBooleanExtra("sequencer_complete", false)

            Log.d(TAG, "onNewIntent: alarmId=$alarmId fromSequencer=$fromSequencer sequencerComplete=$sequencerComplete")

            if (sequencerComplete) {
                Log.d(TAG, "SEQUENCER_COMPLETE: Multi-mission sequence completed, dismissing alarm")
                dismissAlarm(alarmId)
                return
            }

            if (fromSequencer) {
                currentMissionId = intent.getStringExtra("mission_id")
                Log.d(TAG, "SEQUENCER_MISSION_UPDATE: missionId=$currentMissionId missionType=${intent.getStringExtra("mission_type")}")
                // Flag that we need to reset mission state in the Composable
                sequencerMissionUpdate = true
            }

            // Always refresh wake-check launch state from the new intent so that
            // a restart-as-normal-alarm (no from_wake_check extra) correctly
            // switches the UI back to normal mode with dismiss/mission visible.
            isWakeCheckLaunchState.value = incomingIsWakeCheck
            if (isPreview) {
                // Enforce preview mode on any incoming intents
                isWakeCheckLaunchState.value = false
            }
            Log.d(TAG, "onNewIntent: alarmId=$alarmId from_wake_check=${isWakeCheckLaunchState.value}")
            if (isWakeCheckLaunchState.value) {
                // Wake-up check follow-up via new intent; no user-facing toast needed.
                // Only suppress this wake-check Activity if the same alarm was
                // already acknowledged very recently; do not mark the alarm as
                // acknowledged for normal flows so that future dismisses can
                // still schedule wake-check follow-ups.
                runCatching {
                    val dps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) createDeviceProtectedStorageContext() else this
                    val ts = dps.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                        .getLong("wakecheck_ack_ts_$alarmId", 0L)
                    if (ts > 0 && System.currentTimeMillis() - ts < 10 * 60_000L) {
                        finish(); return
                    }
                }
            }
        } catch (_: Exception) {}
    }

    // ---------- UI COMPOSABLE ----------
    @Composable
    private fun BoxScope.AlarmUI(
        modifier: Modifier = Modifier,
        alarmId: Int,
        requiredPassword: String?,
        isWakeCheck: Boolean,
        writeSettingsGranted: Boolean,
        onOpenWriteSettings: () -> Unit
    ) {
        // State
        var missionStarted by remember { mutableStateOf(false) }
        var timerState by remember { mutableStateOf(TimerState.Idle) }
        var remainingTime by remember { mutableStateOf(0) }
        var passwordInput by remember { mutableStateOf("") }
        var showPasswordError by remember { mutableStateOf(false) }
        var showPasswordVisible by remember { mutableStateOf(false) }
        var isDismissed by remember { mutableStateOf(false) }
        var tapCount by remember { mutableStateOf(0) }
        var tapSeconds by remember { mutableStateOf(105) }

        // Create Composable state for mission tap enabled to ensure proper recomposition
        var missionTapEnabledState by remember { mutableStateOf(false) }

        // Sync mission tap enabled state with class-level variable
        LaunchedEffect(Unit) {
            missionTapEnabledState = this@AlarmActivity.missionTapEnabled
        }

        // Reset state for new sequencer missions
        LaunchedEffect(isSequencerMission, currentMissionId) {
            if (isSequencerMission) {
                missionStarted = false
                timerState = TimerState.Idle
                passwordInput = ""
                showPasswordError = false
                tapCount = 0
                tapSeconds = 105
            }
        }

        // Handle sequencer mission updates from onNewIntent
        LaunchedEffect(sequencerMissionUpdate) {
            if (sequencerMissionUpdate && isSequencerMission) {
                Log.d(TAG, "SEQUENCER_STATE_RESET: Resetting mission state for next mission")
                missionStarted = false
                timerState = TimerState.Idle
                passwordInput = ""
                showPasswordError = false
                tapCount = 0
                tapSeconds = 105
                sequencerMissionUpdate = false // Reset the flag
            }
        }

        // NOTE: Do not auto-start missions; they must begin only when user taps Start Mission (including previews).

        // Ensure mission state is properly initialized for normal alarms
        LaunchedEffect(Unit) {
            if (!isPreview && !missionStarted) {
                // Small delay to ensure all states are loaded
                delay(100)
                // Force UI recomposition if mission is detected but not started
                if (missionTapEnabled || requiredPassword != null) {
                    // This ensures the "Start Mission" button appears
                    Log.d("AlarmActivity", "Mission detected but not started - ensuring UI state")
                }
            }
        }

        if (this@AlarmActivity.activityDismissed || isDismissed) {
            Box(modifier = Modifier.fillMaxSize()) {}
            return
        }

        val focusRequester = remember { FocusRequester() }
        val keyboardController = LocalSoftwareKeyboardController.current

        // Title time + subtitle
        val ringtoneTitle = remember(alarmId, isPreview) {
            try {
                if (this@AlarmActivity.isPreview) {
                    "Preview"
                } else {
                    val storage = AlarmStorage(applicationContext)
                    val alarm = storage.getAlarms().find { it.id == alarmId }
                    resolveRingtoneTitle(this@AlarmActivity, alarm?.ringtoneUri)
                }
            } catch (_: Exception) { if (this@AlarmActivity.isPreview) "Preview" else "Unknown Ringtone" }
        }

        // Get current alarm data for protected status check
        val currentAlarm = remember(alarmId, isPreview) {
            try {
                if (this@AlarmActivity.isPreview) {
                    null
                } else {
                    val storage = AlarmStorage(applicationContext)
                    storage.getAlarms().find { it.id == alarmId }
                }
            } catch (_: Exception) { null }
        }
        val uiNowStr = remember(alarmId) {
            try {
                val fmt = java.text.SimpleDateFormat("H:mm", java.util.Locale.US)
                val time = fmt.format(java.util.Date())
                val (hour, minute) = time.split(":").map { it.toInt() }
                val period = if (hour < 12) "AM" else "PM"
                val displayHour = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
                "$displayHour:${minute.toString().padStart(2, '0')} $period"
            } catch (_: Exception) { "--:--" }
        }

        // Wake-up Check prompt only on follow-up launches
        val wakeCheckConfig = remember(alarmId) {
            try {
                val storage = AlarmStorage(applicationContext)
                storage.getAlarms().find { it.id == alarmId }?.let { it.wakeCheckEnabled to it.wakeCheckMinutes }
                    ?: (false to 5)
            } catch (_: Exception) { false to 5 }
        }
        var gateActive by remember(isWakeCheck) { mutableStateOf(isWakeCheck && wakeCheckConfig.first) }
        var gateSeconds by remember(isWakeCheck) { mutableStateOf(20) }

        // When wake-up-check gate is visible, duck alarm audio so only the UI is prominent
        LaunchedEffect(gateActive) {
            if (gateActive) {
                // Mark that the wake-check gate is active so onNewIntent can
                // ignore stray normal alarm intents while the "I'm awake" UI
                // is on screen.
                wakeCheckGuardActive = true
                Log.d("WakeCheckDebug", "AlarmActivity.WakeCheck gateActive=true isWakeCheck=$isWakeCheck enabled=${wakeCheckConfig.first} alarmId=$alarmId")
                // Persist that the wake-check gate is currently active for this alarm
                runCatching {
                    val dps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) createDeviceProtectedStorageContext() else this@AlarmActivity
                    val prefs = dps.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                    prefs.edit().putBoolean("wakecheck_gate_active_$alarmId", true).apply()
                }
                runCatching {
                    val duck = Intent("com.vaishnava.alarm.DUCK_ALARM").apply {
                        putExtra("factor", 0f)
                        putExtra("duration", 20_000L)
                    }
                    this@AlarmActivity.sendBroadcast(duck)
                }
                // Ensure no alarm audio/vibration continues while wake-up-check UI is shown
                // Playback suppression is already handled by AlarmForegroundService when launched
                // as a wake-check follow-up; avoid stopping the service here to prevent restart races.
            } else {
                // When gate becomes inactive, stop the ducking effect
                runCatching {
                    val duck = Intent("com.vaishnava.alarm.DUCK_ALARM").apply {
                        putExtra("factor", 1f)
                        putExtra("duration", 0L)
                    }
                    this@AlarmActivity.sendBroadcast(duck)
                }
            }
        }
        if (gateActive) {
            LaunchedEffect(gateActive) {
                if (gateActive) {
                    // Reset countdown whenever the gate is (re)activated
                    gateSeconds = 20
                    while (gateSeconds > 0 && gateActive && !activityDismissed && !wakeCheckAcknowledged) {
                        delay(1000)
                        gateSeconds -= 1
                    }
                    
                    // CRITICAL: Only restart if NOT acknowledged and NOT dismissed
                    if (!wakeCheckAcknowledged && !activityDismissed) {
                        // CRITICAL: Force UI state update when timer expires
                        gateActive = false
                        wakeCheckGuardActive = false
                        
                        // CRITICAL: Force activity to finish immediately
                        try {
                            Log.d("WakeCheckDebug", "AlarmActivity: Force finishing activity due to timer timeout for alarmId=$alarmId")
                            activityDismissed = true
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                finishAndRemoveTask()
                            } else {
                                finish()
                            }
                            overridePendingTransition(0, 0)
                        } catch (_: Exception) { }
                        
                        // CRITICAL: Restart service WITHOUT skip_activity_launch to ensure normal alarm behavior
                        try {
                            Log.d("WakeCheckDebug", "AlarmActivity: Restarting AlarmForegroundService as NORMAL alarm for alarmId=$alarmId")
                            val serviceIntent = Intent(this@AlarmActivity, AlarmForegroundService::class.java).apply {
                                putExtra(AlarmReceiver.ALARM_ID, alarmId)
                                // CRITICAL: DO NOT skip activity launch - we want normal alarm UI
                                // putExtra("skip_activity_launch", true)  // REMOVED
                                // CRITICAL: Ensure this is NOT treated as a wake-up check launch
                                putExtra("from_wake_check", false)
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                startForegroundService(serviceIntent)
                            } else {
                                startService(serviceIntent)
                            }
                        } catch (_: Exception) { }
                        
                        // Clear state and exit
                        runCatching {
                            this@AlarmActivity.isWakeCheckLaunchState.value = false
                        }
                        runCatching {
                            val dps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) createDeviceProtectedStorageContext() else this@AlarmActivity
                            val prefs = dps.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                            prefs.edit().putBoolean("wakecheck_gate_active_$alarmId", false).apply()
                        }
                    } else {
                        Log.d("WakeCheckDebug", "AlarmActivity: Timer completed but alarm was acknowledged or dismissed, not restarting for alarmId=$alarmId")
                    }
                    
                    // CRITICAL: Exit the LaunchedEffect immediately
                    return@LaunchedEffect
                }
            }// Floating wake-up check gate UI: compact, rounded card near top-center with circular countdown and button
            AnimatedVisibility(
                visible = gateActive && !activityDismissed,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 32.dp)
            ) {
                Card(
                    modifier = Modifier,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 24.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Circular countdown indicator (20s gate)
                        val progress = gateSeconds / 20f
                        Box(contentAlignment = Alignment.Center) {
                            // Slightly smaller ring so the visual round icon on
                            // the wake-up check screen is more compact.
                            CircularProgressIndicator(
                                progress = progress.coerceIn(0f, 1f),
                                modifier = Modifier.size(48.dp),
                                strokeWidth = 4.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            // Smaller solid circular chip behind the countdown
                            // text so it stays readable but takes less space.
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .background(
                                        color = Color.Black.copy(alpha = 0.7f),
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${gateSeconds}",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }

                        Text(
                            text = "I'm awake?",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )

                        Button(
                            onClick = {
                                // Immediately finalize wake-up check and finish the
                                // alarm flow so no intermediate mission UI frame is
                                // rendered after tapping "I'm awake".
                                if (gateSeconds > 0) {
                                    // Only allow acknowledgement if timer is still running
                                    wakeCheckGuardActive = false
                                    gateActive = false
                                    acknowledgeWakeCheck(alarmId)
                                }
                                // If timer has expired, button is disabled by UI state
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = gateSeconds > 0 && !activityDismissed  // Disable button after timer expires or when activity dismissed
                        ) {
                            Text("I'm awake")
                        }
                    }
                }
            }
            }
        }

        AnimatedVisibility(
            visible = !writeSettingsGranted,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "System volume control requires enabling 'Modify system settings'.",
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(
                    onClick = onOpenWriteSettings,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .fillMaxWidth(0.85f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) {
                    Text("Open Settings", fontWeight = FontWeight.Bold)
                }
            }
        }

        // ---- TIMER ENGINE ----
        // Start Mission -> 90s StartupBlocked -> 30s InitialEntry -> 120s Blocked -> loop
        LaunchedEffect(missionStarted, timerState) {
            if (!missionStarted || activityDismissed) return@LaunchedEffect

            when (timerState) {
                TimerState.Idle, TimerState.StartupBlocked -> {
                    // Begin 90s startup block (no password visible)
                    timerState = TimerState.StartupBlocked
                    remainingTime = STARTUP_BLOCKED_TIME_SECONDS
                    while (remainingTime > 0 && timerState == TimerState.StartupBlocked) {
                        delay(1000)
                        remainingTime--
                    }
                    if (timerState == TimerState.StartupBlocked) {
                        timerState = TimerState.InitialEntry
                    }
                }
                TimerState.InitialEntry -> {
                    // Allow password entry for 30s (show keyboard)
                    remainingTime = INITIAL_PASSWORD_ENTRY_TIME_SECONDS
                    delay(120) // slight delay to ensure field is on screen
                    focusRequester.requestFocus()
                    keyboardController?.show()

                    while (remainingTime > 0 && timerState == TimerState.InitialEntry) {
                        delay(1000)
                        remainingTime--
                    }
                    if (timerState == TimerState.InitialEntry) {
                        // Time up without success -> Blocked
                        keyboardController?.hide()
                        timerState = TimerState.Blocked
                    }
                }
                TimerState.Blocked -> {
                    // 120s lockout (no input)
                    remainingTime = BLOCKED_TIME_SECONDS
                    keyboardController?.hide()
                    while (remainingTime > 0 && timerState == TimerState.Blocked) {
                        delay(1000)
                        remainingTime--
                    }
                    if (timerState == TimerState.Blocked) {
                        // Next attempt window
                        timerState = TimerState.InitialEntry
                    }
                }
            }
        }

        // Auto safety dismiss after 10 minutes
        LaunchedEffect(Unit) {
            delay(10 * 60 * 1000)
            if (!isDismissed && !activityDismissed) this@AlarmActivity.dismissAlarm(alarmId)
        }

        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (isPreview) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    val noRipple = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    Text(
                        text = "exit preview",
                        color = Color(0xFF90A4AE),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .clickable(indication = null, interactionSource = noRipple) { exitPreview() }
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            // Clock title + subtitle only when not in the wake-check gate
            if (!gateActive) {
                Text(
                    text = uiNowStr,
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Text(
                    text = ringtoneTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
            }

            // ===== TAP CHALLENGE (only after Start Mission; only on initial alarm) =====
            if (!isWakeCheck && missionTapEnabledState && missionStarted && !gateActive && !isDismissed) {
                LaunchedEffect(missionStarted) {
                    tapSeconds = 105
                    while (tapSeconds > 0 && !isDismissed && missionTapEnabledState && missionStarted && !gateActive && !activityDismissed) {
                        delay(1000)
                        tapSeconds -= 1
                    }
                    if (!isDismissed && missionTapEnabledState && missionStarted && tapCount < 100 && !activityDismissed) {
                        // Timeout -> treat as failed mission and fully dismiss alarm
                        dismissAlarm(alarmId)
                    }
                }

                // Moving round button implementation
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Tap Challenge",
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "Tap 108 times in ${tapSeconds}s",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Stationary centered round button
                    val density = LocalDensity.current
                    var containerW by remember { mutableStateOf(0) }
                    var containerH by remember { mutableStateOf(0) }
                    var btnX by remember { mutableStateOf(0.dp) }
                    var btnY by remember { mutableStateOf(0.dp) }
                    val safePad = 16.dp

                    // Compute current button size based on tapCount
                    val currentBtnSize = when {
                        tapCount >= 96 -> 40.dp
                        tapCount >= 72 -> 48.dp
                        tapCount >= 36 -> 56.dp
                        else -> 64.dp
                    }

                    // Clamp and optionally randomize initial placement within bounds
                    LaunchedEffect(containerW, containerH, currentBtnSize) {
                        if (containerW > 0 && containerH > 0 && !activityDismissed) {
                            val padPx = with(density) { safePad.toPx() }
                            val usableW = (containerW - 2 * padPx).coerceAtLeast(0f)
                            val usableH = (containerH - 2 * padPx).coerceAtLeast(0f)
                            val btnPx = with(density) { currentBtnSize.toPx() }
                            val maxX = (usableW - btnPx).toInt().coerceAtLeast(0)
                            val maxY = (usableH - btnPx).toInt().coerceAtLeast(0)

                            if (tapCount == 0) {
                                val rx = if (maxX > 0) Random.nextInt(0, maxX + 1) else 0
                                val ry = if (maxY > 0) Random.nextInt(0, maxY + 1) else 0
                                btnX = with(density) { rx.toDp() }
                                btnY = with(density) { ry.toDp() }
                            } else {
                                // Clamp existing position if size/container changed
                                val xPx = with(density) { btnX.toPx() }.coerceIn(0f, maxX.toFloat())
                                val yPx = with(density) { btnY.toPx() }.coerceIn(0f, maxY.toFloat())
                                btnX = with(density) { xPx.toDp() }
                                btnY = with(density) { yPx.toDp() }
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .onSizeChanged { sz ->
                                containerW = sz.width
                                containerH = sz.height
                            }
                    ) {
                        // Per-tap letter label from mantra text (letters only; skip spaces/newlines)
                        val mantraText = """
                            Hare Krishna Hare Krishna
                            Krishna Krishna Hare Hare
                            Hare Rama Hare Rama
                            Rama Rama Hare Hare
                            Om Shri Raghavendraya Namah
                        """.trimIndent()
                        val mantraLetters = remember { mantraText.filter { it.isLetter() } }
                        val labelChar = mantraLetters.getOrNull(tapCount)?.toString() ?: "✓"

                        Button(
                            onClick = {
                                if (!isDismissed && tapSeconds > 0) {
                                    tapCount += 1

                                    // Move to a new random position within bounds on every click
                                    val padPx = with(density) { safePad.toPx() }
                                    val btnPx = with(density) { currentBtnSize.toPx() }
                                    val usableW = (containerW - 2 * padPx).coerceAtLeast(0f)
                                    val usableH = (containerH - 2 * padPx).coerceAtLeast(0f)
                                    val maxX = (usableW - btnPx).toInt().coerceAtLeast(0)
                                    val maxY = (usableH - btnPx).toInt().coerceAtLeast(0)
                                    val rx = if (maxX > 0) Random.nextInt(0, maxX + 1) else 0
                                    val ry = if (maxY > 0) Random.nextInt(0, maxY + 1) else 0
                                    btnX = with(density) { rx.toDp() }
                                    btnY = with(density) { ry.toDp() }

                                    if (tapCount >= 108) {
                                        // Mission success: handle completion appropriately
                                        if (isSequencerMission) {
                                            // For sequencer missions, don't dismiss - advance to next mission
                                            this@AlarmActivity.onTapMissionSuccess(alarmId)
                                        } else {
                                            // For normal missions, dismiss as before
                                            isDismissed = true
                                            this@AlarmActivity.onTapMissionSuccess(alarmId)
                                        }
                                    }
                                }
                            },
                            shape = CircleShape,
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier
                                .offset(x = btnX + safePad, y = btnY + safePad)
                                .size(currentBtnSize)
                        ) {
                            Text(labelChar, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Progress text kept always visible below the moving area
                    Text(
                        text = "$tapCount of 108",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }

            // ===== BEFORE MISSION STARTS (unified for any mission except NONE) =====
            run {
                // For protected alarms, rely on the persisted missionType to decide if a mission exists
                val hasMission = if (currentAlarm?.isProtected == true) {
                    currentAlarm.missionType == "tap" || currentAlarm.missionType == "password"
                } else {
                    (missionTapEnabled || requiredPassword != null)
                }
                if (!isWakeCheck && hasMission) {
                    AnimatedVisibility(
                        visible = !missionStarted && !gateActive && !isDismissed,
                        enter = fadeIn() + slideInVertically(),
                        exit = fadeOut() + slideOutVertically()
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Button(
                                onClick = {
                                    missionStarted = true
                                    if (requiredPassword != null) {
                                        timerState = TimerState.StartupBlocked
                                        passwordInput = ""
                                        showPasswordError = false
                                    } else if (missionTapEnabled) {
                                        // Tap mission started
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(0.8f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFAB91))
                            ) {
                                AutoSizeText(text = "Start Mission", maxLines = 1)
                            }
                        }
                    }
                }
            }

            // ===== DURING MISSION: STATUS STRIP (only on initial alarm) =====
            if (!isWakeCheck && !missionTapEnabled && missionStarted && !gateActive && !isDismissed) {
                Spacer(Modifier.height(8.dp))
                val statusText = when (timerState) {
                    TimerState.StartupBlocked -> "Startup blocked. Wait $remainingTime sec."
                    TimerState.InitialEntry   -> "Enter password. Remaining time: $remainingTime sec."
                    TimerState.Blocked        -> "Input blocked. Try again in $remainingTime sec."
                    else                      -> ""
                }
                if (statusText.isNotEmpty()) {
                    Text(
                        text = statusText,
                        color = if (timerState == TimerState.Blocked || timerState == TimerState.StartupBlocked) Color.Red else Color.White,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ===== PASSWORD UI: ONLY DURING InitialEntry AFTER 90s (only on initial alarm) =====
            val passwordUiVisible = !isWakeCheck && !missionTapEnabled && missionStarted && timerState == TimerState.InitialEntry && requiredPassword != null && !gateActive && !isDismissed

            AnimatedVisibility(
                visible = passwordUiVisible,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Enter password to dismiss",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = {
                            passwordInput = it
                            showPasswordError = false
                        },
                        placeholder = { Text(if (isPreview) "1234" else (requiredPassword ?: DEFAULT_GLOBAL_PASSWORD), color = Color.White.copy(alpha = 0.6f)) },
                        visualTransformation = if (showPasswordVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            autoCorrect = false,
                            keyboardType = KeyboardType.Password
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        enabled = (timerState == TimerState.InitialEntry),
                        singleLine = true,
                        shape = RoundedCornerShape(0.dp),
                        colors = androidx.compose.material3.TextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedIndicatorColor = Color.White,
                            unfocusedIndicatorColor = Color.White.copy(alpha = 0.6f),
                            cursorColor = Color.White
                        ),
                        trailingIcon = {
                            val label = if (showPasswordVisible) "Hide" else "Show"
                            androidx.compose.material3.TextButton(onClick = { showPasswordVisible = !showPasswordVisible }) {
                                Text(label, color = Color.White)
                            }
                        }
                    )

                    if (showPasswordError) {
                        val errorMessage = if (requiredPassword.isNullOrBlank()) {
                            "No password configured - please set a password in alarm settings"
                        } else {
                            "Incorrect password"
                        }
                        Text(
                            text = errorMessage,
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Red.copy(alpha = 0.2f))
                                .padding(8.dp),
                            textAlign = TextAlign.Center
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .background(if (timerState == TimerState.InitialEntry) Color(0xFFFFAB91) else Color(0x66FFAB91))
                            .clickable(timerState == TimerState.InitialEntry) {
                                val actualPassword = requiredPassword
                                if (actualPassword.isNullOrBlank()) {
                                    // No password configured - show error and don't allow completion
                                    showPasswordError = true
                                    passwordInput = ""
                                    focusRequester.requestFocus()
                                } else if (passwordInput == actualPassword) {
                                    // For normal password missions, just dismiss the alarm directly
                                    dismissAlarm(alarmId)
                                } else {
                                    showPasswordError = true
                                    passwordInput = ""
                                    focusRequester.requestFocus()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Confirm",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            color = Color.Black,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Dismiss button visibility logic:
            // Show dismiss button when:
            // 1. For protected alarms: only when mission type is "none"
            // 2. For regular alarms: when no mission is required (none mission type)
            // 3. Always: must not be already dismissed, gate inactive, and tap mission not enabled
            // 4. NEVER show dismiss button during wake-up check
            val isProtectedAlarm = currentAlarm?.isProtected == true
            val missionTypeIsNone = currentAlarm?.missionType == "none"
            val hasNoPassword = requiredPassword == null
            val noMissionRequired = !missionTapEnabled && hasNoPassword

            // Simplified logic: For "none" mission type, always show dismiss button (unless already dismissed or in sequencer mode)
            // BUT NEVER during wake-up check
            val dismissButtonVisible = when {
                // Never show dismiss button during wake-up check
                isWakeCheckLaunchState.value -> false
                // Never show dismiss button in sequencer mode (multi-mission)
                isSequencerMission -> false
                // If mission type is explicitly "none", show dismiss button
                missionTypeIsNone && !isDismissed -> true
                // Protected alarms with "none" mission type
                isProtectedAlarm && missionTypeIsNone && !gateActive && !isDismissed -> true
                // Regular alarms with no mission required
                !isProtectedAlarm && noMissionRequired && !gateActive && !isDismissed -> true
                else -> false
            }

            AnimatedVisibility(
                visible = dismissButtonVisible,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
                Button(
                    onClick = {
                        isDismissed = true
                        // For all normal alarms, just dismiss directly without mission completion logic
                        dismissAlarm(alarmId)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4CAF50),
                        contentColor = Color.Black
                    )
                ) {
                    Text(
                        text = "Dismiss",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
            }
        }
    }

    // -------------------- Dismiss / cleanup --------------------
    private fun onTapMissionSuccess(alarmId: Int) {
        try {
            Log.d(TAG, "TAP_MISSION_SUCCESS: alarmId=$alarmId isSequencer=$isSequencerMission")

            // For sequencer missions, call onMissionCompleted to advance to next mission
            if (isSequencerMission) {
                onMissionCompleted(alarmId)
                return
            }

            // For normal missions, dismiss as before
            isDismissed = true
            activityDismissed = true
            missionTapEnabled = false
            try { this@AlarmActivity.runOnUiThread { } } catch (_: Exception) {}
            // DISABLED: ttsRunnable cleanup - legacy TTS repeater removed
            try { ttsHandler.removeCallbacksAndMessages(null) } catch (_: Exception) {}
            try {
                tts?.apply {
                    stop()
                    shutdown()
                }
            } catch (_: Exception) {}

            // Stop the foreground service (alarm audio/vibration)
            try {
                val serviceIntent = Intent(this@AlarmActivity, AlarmForegroundService::class.java).apply {
                    action = Constants.ACTION_STOP_FOREGROUND_SERVICE
                    putExtra(AlarmReceiver.ALARM_ID, alarmId)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } catch (_: Exception) {}

            // For preview mode, just finish normally
            if (isPreview) {
                finish()
                overridePendingTransition(0, 0)
                return
            }

            // For real alarms, we need to handle the alarm state properly
            // Check if this is a protected alarm
            val alarmStorage = AlarmStorage(applicationContext)
            val alarm = alarmStorage.getAlarms().find { it.id == alarmId }

            if (alarm?.isProtected == true) {
                Log.w(TAG, "Protected alarm $alarmId completed tap mission - mission completed, allowing dismissal")
                // Mission completed successfully, allow the activity to finish
                // The alarm remains active but the mission screen can close
            }

            // If wake-up check is enabled for this alarm, schedule the follow-up now (same as dismiss flow)
            runCatching {
                if (alarm?.wakeCheckEnabled == true && !wakeCheckAcknowledged) {
                    Log.d(TAG, "onTapMissionSuccess: scheduling wake-check follow-up in ${alarm.wakeCheckMinutes}m for $alarmId")
                    // Mark wake-check as pending and clear previous ack/finalize flags
                    val dps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) createDeviceProtectedStorageContext() else this
                    val prefs = dps.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putBoolean("wakecheck_pending_$alarmId", true)
                        .putLong("wakecheck_ack_ts_$alarmId", 0L)
                        .putBoolean("wakecheck_finalized_$alarmId", false)
                        .apply()
                    scheduleWakeCheckFollowUp(this, alarmId, alarm.wakeCheckMinutes)
                }
            }

            // For non-protected real alarms, finish the activity
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                finishAndRemoveTask()
            } else {
                finish()
            }
            overridePendingTransition(0, 0)
        } catch (_: Exception) {}
    }

    private fun onMissionCompleted(alarmId: Int) {
        try {
            Log.d(TAG, "MISSION_COMPLETED: alarmId=$alarmId isSequencer=$isSequencerMission currentMissionId=$currentMissionId")

            // For normal alarms (non-sequencer), just dismiss directly without mission completion logic
            if (!isSequencerMission) {
                dismissAlarm(alarmId)
                return
            }

            // Only send mission completion broadcast for sequencer missions
            val missionId = intent?.getStringExtra("mission_id")
                ?: currentMissionId
                ?: intent?.getStringExtra("mission_type")
                ?: takeIf { intent?.getStringExtra("mission_type") != "none" }?.let { intent?.getStringExtra("mission_type") }
                ?: "sequencer_mission_${alarmId}"

            Log.d(TAG, "MISSION_COMPLETION_BROADCAST: alarmId=$alarmId missionId=$missionId intentMissionId=${intent?.getStringExtra("mission_id")}")

            val completionIntent = Intent(MissionSequencer.ACTION_MISSION_COMPLETED).apply {
                putExtra(MissionSequencer.EXTRA_MISSION_ID, missionId)
                putExtra(MissionSequencer.EXTRA_MISSION_SUCCESS, true)
                putExtra(MissionSequencer.EXTRA_FROM_SEQUENCER, true)
            }
            sendBroadcast(completionIntent)

            // For sequencer missions, don't dismiss the activity - wait for next mission
            if (!isSequencerMission) {
                isDismissed = true
                activityDismissed = true
                missionTapEnabled = false
                try { this@AlarmActivity.runOnUiThread { } } catch (_: Exception) {}
                // DISABLED: ttsRunnable cleanup - legacy TTS repeater removed
                try { ttsHandler.removeCallbacksAndMessages(null) } catch (_: Exception) {}
                try {
                    tts?.apply {
                        stop()
                        shutdown()
                    }
                } catch (_: Exception) {}
            }

            // For preview mode, just finish immediately without complex dismissal logic
            if (isPreview) {
                finish()
                overridePendingTransition(0, 0)
                return
            }

            // Schedule wake-up check follow-up even for protected alarms after mission completion
            runCatching {
                val alarmStorage = AlarmStorage(applicationContext)
                val alarm = alarmStorage.getAlarms().find { it.id == alarmId }
                if (alarm?.wakeCheckEnabled == true && !wakeCheckAcknowledged) {
                    Log.d(TAG, "onMissionCompleted: scheduling wake-check follow-up in ${alarm.wakeCheckMinutes}m for $alarmId")
                    val dps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) createDeviceProtectedStorageContext() else this
                    val prefs = dps.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putBoolean("wakecheck_pending_$alarmId", true)
                        .putLong("wakecheck_ack_ts_$alarmId", 0L)
                        .putBoolean("wakecheck_finalized_$alarmId", false)
                        .apply()
                    scheduleWakeCheckFollowUp(this, alarmId, alarm.wakeCheckMinutes)
                }
            }

            // For real alarms, use the full dismiss logic
            dismissAlarm(alarmId)

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    finishAndRemoveTask()
                } else {
                    finish()
                }
                overridePendingTransition(0, 0)
            } catch (_: Exception) {}
        } catch (_: Exception) {}
    }

    private fun dismissAlarm(alarmId: Int) {
        try {
            // Check if this is a protected alarm
            val alarmStorage = AlarmStorage(applicationContext)
            val alarm = alarmStorage.getAlarms().find { it.id == alarmId }

            // Only block dismissal for protected alarms with missions (password/tap)
            // Allow dismissal for protected alarms with "none" mission type
            if (alarm?.isProtected == true && alarm.missionType != "none") {
                Log.w(TAG, "Attempted to dismiss protected alarm $alarmId with mission ${alarm.missionType} - blocking dismissal")
                // Show a message that this alarm cannot be dismissed
                try {
                    android.widget.Toast.makeText(
                        this,
                        "Protected alarm — cannot dismiss.",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } catch (_: Exception) {}
                return // Don't allow dismissal
            }

            activityDismissed = true

            try {
                val dpsContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    createDeviceProtectedStorageContext()
                } else {
                    this
                }
                val prefs = dpsContext.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                // Clear any lingering wake-check gate/pending flags so future daily fires are not suppressed
                try {
                    prefs.edit()
                        .putBoolean("wakecheck_gate_active_$alarmId", false)
                        .putBoolean("wakecheck_pending_$alarmId", false)
                        .putBoolean("wakecheck_finalized_$alarmId", false)
                        .apply()
                } catch (_: Exception) { }
                prefs.edit()
                    .putLong("dismiss_ts_$alarmId", System.currentTimeMillis())
                    .apply()
            } catch (_: Exception) {}

            // Only cancel wake-check loop if user acknowledged via "I'm awake"
            if (wakeCheckAcknowledged) {
                runCatching {
                    cancelWakeCheckFollowUp(this, alarmId)
                }
            }

            // Stop TTS
            // DISABLED: ttsRunnable cleanup - legacy TTS repeater removed
            try { ttsHandler.removeCallbacksAndMessages(null) } catch (_: Exception) {}
            try {
                tts?.apply {
                    stop()
                    shutdown()
                }
            } catch (e: Exception) {
                // Error stopping TTS
            }

            // Stop the foreground service
            val serviceIntent = Intent(this@AlarmActivity, AlarmForegroundService::class.java).apply {
                action = Constants.ACTION_STOP_FOREGROUND_SERVICE
                putExtra(AlarmReceiver.ALARM_ID, alarmId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            // Handle the alarm state - use existing alarm variable from above
            // If wake-check is enabled and user did NOT press "I'm awake", schedule (first or next) follow-up from now.
            runCatching {
                if (alarm?.wakeCheckEnabled == true && !wakeCheckAcknowledged) {
                    Log.d(TAG, "dismissAlarm: no acknowledge; schedule wake-check follow-up in ${alarm.wakeCheckMinutes}m for $alarmId")
                    // Mark wake-up-check as pending so UI cannot delete/toggle this alarm until flow completes.
                    // Also clear any previous wake-up-check acknowledgement/finalization so a prior "I'm awake"
                    // does not suppress this new cycle's WAKE_UP follow-up for the same alarmId.
                    runCatching {
                        val dps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) createDeviceProtectedStorageContext() else this
                        val prefs = dps.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                        prefs.edit()
                            .putBoolean("wakecheck_pending_$alarmId", true)
                            .putLong("wakecheck_ack_ts_$alarmId", 0L)
                            .putBoolean("wakecheck_finalized_$alarmId", false)
                            .apply()
                    }
                    scheduleWakeCheckFollowUp(this, alarmId, alarm.wakeCheckMinutes)
                }
            }

            // Cancel all possible alarm intents to prevent any re-trigger
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager

            try {
                // 1) Cancel the actual scheduled fire PendingIntent (DIRECT_BOOT_ALARM with requestCode=alarmId)
                val fireIntent = Intent(this, AlarmReceiver::class.java).apply {
                    action = "com.vaishnava.alarm.DIRECT_BOOT_ALARM"
                    data = android.net.Uri.parse("alarm://$alarmId")
                    putExtra(AlarmReceiver.ALARM_ID, alarmId)
                }
                PendingIntent.getBroadcast(
                    this,
                    alarmId,
                    fireIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                ).let { pi ->
                    alarmManager.cancel(pi)
                    pi.cancel()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error cancelling DIRECT_BOOT_ALARM PI: ${e.message}")
            }

            try {
                // 2) Cancel WAKE_UP backup broadcasts (used in restore/backup paths)
                val wakeIntent1 = Intent(this, AlarmReceiver::class.java).apply {
                    action = "com.vaishnava.alarm.WAKE_UP"
                    data = android.net.Uri.parse("alarm-wakeup://$alarmId")
                    putExtra(AlarmReceiver.ALARM_ID, alarmId)
                }
                val wakeIntent2 = Intent(this, AlarmReceiver::class.java).apply {
                    action = "com.vaishnava.alarm.WAKE_UP"
                    data = android.net.Uri.parse("alarm-wakeup2://$alarmId")
                    putExtra(AlarmReceiver.ALARM_ID, alarmId)
                }
                val codes = listOf(alarmId xor 0x4A11, alarmId xor 0x6B7F)
                val intents = listOf(wakeIntent1, wakeIntent2)
                codes.zip(intents).forEach { (code, intent) ->
                    val pi = PendingIntent.getBroadcast(
                        this,
                        code,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    try { alarmManager.cancel(pi); pi.cancel() } catch (_: Exception) {}
                }
                // Also cancel wake-up check follow-up (our loop) only if acknowledged
                if (wakeCheckAcknowledged) {
                    runCatching {
                        val wakeCheckIntent = Intent(this, AlarmReceiver::class.java).apply {
                            action = "com.vaishnava.alarm.WAKE_UP"
                            data = android.net.Uri.parse("alarm-wakecheck://$alarmId")
                            putExtra(AlarmReceiver.ALARM_ID, alarmId)
                        }
                        val pi = PendingIntent.getBroadcast(
                            this,
                            alarmId xor 0x9C0A,
                            wakeCheckIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        alarmManager.cancel(pi); pi.cancel()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error cancelling WAKE_UP backup PIs: ${e.message}")
            }

            try {
                // 3) Cancel the AlarmClock show intent (getActivity with requestCode=alarmId)
                val showIntent = Intent(this, AlarmActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra(AlarmReceiver.ALARM_ID, alarmId)
                }
                val showPi = PendingIntent.getActivity(
                    this,
                    alarmId,
                    showIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.cancel(showPi)
                showPi.cancel()
            } catch (e: Exception) {
                Log.e(TAG, "Error cancelling AlarmClock show PI: ${e.message}")
            }

            // Clear any existing notifications
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(alarmId)
            notificationManager.cancel(Constants.NOTIFICATION_ID_UPCOMING_ALARM)

            // Update alarm state
            if (alarm != null) {
                val isOneShot = alarm.days.isNullOrEmpty() && !alarm.repeatDaily
                if (isOneShot) {
                    // For one-shot alarms with wake-up check enabled, keep the alarm
                    // logically pending until the user explicitly acknowledges via
                    // "I'm awake". Only disable immediately when either wake-check
                    // is not enabled, or it has already been acknowledged.
                    val shouldDisableNow = !alarm.wakeCheckEnabled || wakeCheckAcknowledged
                    if (shouldDisableNow) {
                        val updatedAlarm = alarm.copy(isEnabled = false)
                        alarmStorage.updateAlarm(updatedAlarm)
                    }
                } else {
                    // Repeating alarm: keep enabled and reschedule next occurrence
                    val alarmScheduler = AndroidAlarmScheduler(applicationContext)
                    alarmScheduler.cancel(alarm)
                    alarmScheduler.schedule(alarm)
                }
            }

            // Clear device-protected storage flags
            try {
                val dpsContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    createDeviceProtectedStorageContext()
                } else {
                    this
                }
                val prefs = dpsContext.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                prefs.edit()
                    .remove("was_ringing")
                    .remove("ring_alarm_id")
                    .apply()
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing alarm_dps preferences: ${e.message}", e)
            }

            // Bring user back to the app home page after dismiss
            val homeIntent = Intent(this@AlarmActivity, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("dismiss_alarm", true)
            }
            startActivity(homeIntent)
        } catch (e: Exception) {
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
        }
    }

    @Deprecated("Back press is disabled for this screen")
    override fun onBackPressed() {
        // disable back
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Bring the alarm UI back if the user navigates away (until dismissed)
        if (!activityDismissed) {
            Handler(Looper.getMainLooper()).postDelayed({ bringToFront() }, 300)
        }
    }

    override fun onPause() {
        super.onPause()
        if (!activityDismissed) {
            Handler(Looper.getMainLooper()).postDelayed({ bringToFront() }, 500)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(screenReceiver) } catch (e: Exception) {
            // Error unregistering receiver
        }
        try {
            // DISABLED: ttsRunnable cleanup - legacy TTS repeater removed
            try { ttsHandler.removeCallbacksAndMessages(null) } catch (_: Exception) {}
            tts?.apply { stop(); shutdown() }
            tts = null
        } catch (e: Exception) {
            // Error cleaning up TTS resources
        }
    }

    @SuppressLint("ScheduleExactAlarm")
    private fun scheduleWakeCheckFollowUp(context: Context, alarmId: Int, minutes: Int) {
        try {
            // CRITICAL: Cancel any existing wake-up check for this alarm first to prevent duplicates
            cancelWakeCheckFollowUp(context, alarmId)
            
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val broadcast = Intent(context, AlarmReceiver::class.java).apply {
                action = "com.vaishnava.alarm.WAKE_UP"
                data = android.net.Uri.parse("alarm-wakecheck://$alarmId")
                putExtra(AlarmReceiver.ALARM_ID, alarmId)
            }
            val pi = PendingIntent.getBroadcast(
                context,
                alarmId xor 0x9C0A,
                broadcast,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val triggerAt = System.currentTimeMillis() + minutes * 60_000L
            Log.d(TAG, "scheduleWakeCheckFollowUp: alarm=$alarmId at=${triggerAt} (+${minutes}m) rc=${alarmId xor 0x9C0A}")
            // Provide a showIntent for setAlarmClock
            val showIntent = PendingIntent.getActivity(
                context,
                alarmId,
                Intent(context, AlarmActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra(AlarmReceiver.ALARM_ID, alarmId)
                    putExtra("from_wake_check", true)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            try {
                val info = AlarmManager.AlarmClockInfo(triggerAt, showIntent)
                am.setAlarmClock(info, pi)
            } catch (_: Exception) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (_: Exception) {}
    }

    private fun cancelWakeCheckFollowUp(context: Context, alarmId: Int) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val broadcast = Intent(context, AlarmReceiver::class.java).apply {
                action = "com.vaishnava.alarm.WAKE_UP"
                data = android.net.Uri.parse("alarm-wakecheck://$alarmId")
                putExtra(AlarmReceiver.ALARM_ID, alarmId)
            }
            val pi = PendingIntent.getBroadcast(
                context,
                alarmId xor 0x9C0A,
                broadcast,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(pi); pi.cancel()

            // Also cancel AlarmClock showIntent used when scheduling follow-ups
            runCatching {
                val showIntent = Intent(context, AlarmActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra(AlarmReceiver.ALARM_ID, alarmId)
                    putExtra("from_wake_check", true)
                }
                val showPi = PendingIntent.getActivity(
                    context,
                    alarmId,
                    showIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                am.cancel(showPi); showPi.cancel()
            }

            // Also cancel common variants defensively
            runCatching {
                val noData = Intent(context, AlarmReceiver::class.java).apply {
                    action = "com.vaishnava.alarm.WAKE_UP"
                    putExtra(AlarmReceiver.ALARM_ID, alarmId)
                }
                val pi2 = PendingIntent.getBroadcast(context, alarmId xor 0x9C0A, noData, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                am.cancel(pi2); pi2.cancel()
            }
            runCatching {
                val withData = Intent(context, AlarmReceiver::class.java).apply {
                    action = "com.vaishnava.alarm.WAKE_UP"
                    data = android.net.Uri.parse("alarm-wakecheck://$alarmId")
                    putExtra(AlarmReceiver.ALARM_ID, alarmId)
                }
                val pi3 = PendingIntent.getBroadcast(context, alarmId, withData, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                am.cancel(pi3); pi3.cancel()
            }
            runCatching {
                val withData = Intent(context, AlarmReceiver::class.java).apply {
                    action = "com.vaishnava.alarm.WAKE_UP"
                    data = android.net.Uri.parse("alarm-wakecheck://$alarmId")
                }
                val pi4 = PendingIntent.getBroadcast(context, 0, withData, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                am.cancel(pi4); pi4.cancel()
            }
        } catch (_: Exception) {}
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_CODE_OVERLAY_PERMISSION)
        }
    }
}
