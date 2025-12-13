@file:Suppress("DEPRECATION")

package com.vaishnava.alarm

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import android.widget.VideoView
import android.content.IntentFilter
import android.annotation.SuppressLint
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.vaishnava.alarm.sequencer.MissionLogger
import com.vaishnava.alarm.ui.AutoSizeText
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
        const val DEFAULT_GLOBAL_PASSWORD = "IfYouWantYouCanSleep"
        private const val PREFS_NAME = "alarm_volume_prefs"
        private const val KEY_PREV_VOLUME = "prev_alarm_volume"
        private const val MIN_PERCENT = 50

        
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

    // -------------------- Ultra-Minimal Final Fix --------------------
    private var mission2Completed = false

    // -------------------- Sequencer Context --------------------
    private var isSequencerMission: Boolean = false
    private var sequencerContext: String = ""
    private var secondMissionActive: Boolean = false
    private var isSequencerComplete: Boolean = false
    private var isMissedAlarm: Boolean = false

    // -------------------- TTS --------------------
    private var tts: android.speech.tts.TextToSpeech? = null
    private var ttsMessage: String = ""
    private val ttsHandler = Handler(Looper.getMainLooper())
    // DISABLED: ttsRunnable removed - legacy TTS repeater causing continuous repetition
    
    // --- activity-managed alarm audio (minimal) ---
    private var mediaPlayer: MediaPlayer? = null
    @Volatile private var mediaPlaybackStarted: Boolean = false
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

    // CRITICAL FIX: Handle new intents for both single missions and multi-mission sequences
    // Because we use FLAG_ACTIVITY_SINGLE_TOP + REORDER_TO_FRONT, the same AlarmActivity 
    // is reused for subsequent missions, but the intent needs to be updated.
    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
        
        if (mission2Completed || isSequencerComplete) {
            MissionLogger.log("MISSION_GUARD_FINAL: Blocking post-mission intent to prevent NONE.")
            finish()
            return
        }
        
        // ===================== NONE-MISSION FIX ======================
        // Prevent mission reset if sequencer is active and mission_type is missing
        val incomingMissionType = newIntent.getStringExtra("mission_type")
        val incomingMissionId = newIntent.getStringExtra("mission_id")

        if (isSequencerMission && incomingMissionType.isNullOrEmpty()) {
            MissionLogger.log("MISSION_GUARD: Ignored empty mission update (prevented NONE after mission 2).")
            return
        }
        // =============================================================
        
        // --- MISSION GUARD: Prevent NONE-machine after second mission ends ---
        if (mission2Completed) {
            MissionLogger.log("MISSION_GUARD: Ignoring stray intent after Mission 2. Preventing NONE-state.")
            finish()    // close activity fully
            return
        }
        
        // CRITICAL FIX: Prevent infinite loop with invalid intents
        val newAlarmId = newIntent.getIntExtra(AlarmReceiver.ALARM_ID, -1)
        val missionType = newIntent.getStringExtra("mission_type")
        val missionId = newIntent.getStringExtra("mission_id")
        
        // Ignore intents with invalid parameters to prevent infinite loops
        // Block: 1) No alarm ID and no mission type, OR 2) Valid alarm ID but null mission parameters
        if ((newAlarmId == -1 && missionType.isNullOrBlank()) || 
            (newAlarmId != -1 && missionId == null && missionType == null)) {
            MissionLogger.logWarning("NEW_INTENT_IGNORED: Ignoring intent with invalid parameters - alarmId=$newAlarmId missionType=$missionType missionId=$missionId")
            return
        }
        
        // CRITICAL DEBUG: Log all intent extras when onNewIntent is called
        MissionLogger.log("NEW_INTENT: onNewIntent called")
        newIntent.extras?.keySet()?.forEach { key ->
            MissionLogger.log("NEW_INTENT: Intent extra - $key = ${newIntent.extras?.get(key)}")
        }
        
        try {
            val missionType = newIntent.getStringExtra("mission_type") ?: ""
            val missionPassword = newIntent.getStringExtra("mission_password") ?: ""
            val isProtected = newIntent.getBooleanExtra("is_protected", false)
            sequencerContext = newIntent.getStringExtra("sequencer_context") ?: ""
            
            // Reset mission started flag to allow new mission to start (multi-mission fix)
            classMissionStarted = false
            
            // CRITICAL: Make AlarmActivity use NEW mission intent (fixes multi-mission)
            setIntent(newIntent)

            // Update mission-related state from new intent (multi-mission fix)
            currentMissionId = newIntent.getStringExtra("mission_id")
            isSequencerMission = newIntent.getBooleanExtra(com.vaishnava.alarm.sequencer.MissionSequencer.EXTRA_FROM_SEQUENCER, false)
            
            // Ensure activity uses new intent and starts audio if sequencer / mission provided uri
            val newUri = newIntent.getStringExtra("extra_ringtone_uri") ?: newIntent.getStringExtra(AlarmReceiver.EXTRA_RINGTONE_URI)
            
            // If no URI in intent, try to get from alarm storage
            val finalNewUri = if (newUri.isNullOrBlank()) {
                try {
                    val alarmStorage = AlarmStorage(applicationContext)
                    val alarm = alarmStorage.getAlarm(alarmId)
                    alarm?.ringtoneUri?.toString()
                } catch (e: Exception) {
                    null
                }
            } else {
                newUri
            }
            
            // Use RingtoneHelper to get valid URI - NO fallback
            val validNewUri = RingtoneHelper.parseUriSafe(finalNewUri)
            MissionLogger.log("ACT_AUDIO: Final URI for playback: $validNewUri")
            if (validNewUri != null) {
                startAudioIfNeeded(validNewUri.toString())
            } else {
                MissionLogger.log("ACT_AUDIO: No valid URI available, no audio will play")
            }
            
            // Update mission detection state for non-sequencer intents only
            val intentMissionType = newIntent.getStringExtra("mission_type")
            if (!intentMissionType.isNullOrEmpty()) {
                missionTapEnabled = (intentMissionType == "tap")
                requiredPassword = if (intentMissionType == "password") {
                    newIntent.getStringExtra("mission_password") ?: "IfYouWantYouCanSleep"
                } else {
                    null
                }
                Log.d(TAG, "onNewIntent: Updated non-sequencer mission state - missionType=$intentMissionType")
            }

            Log.d(TAG, "onNewIntent: Updated mission state - missionId=$currentMissionId missionType=$intentMissionType isSequencerMission=$isSequencerMission")
            Log.d(TAG, "onNewIntent: missionTapEnabled=$missionTapEnabled requiredPassword=${requiredPassword != null}")

            // CRITICAL: Force UI recomposition for sequencer missions only
            if (isSequencerMission) {
                sequencerMissionUpdate = true
                // CRITICAL FIX: Ensure screen turns on for second mission
                turnScreenOn()
            }

            // If the wake-check gate is currently active, ignore any incoming
            // normal (non-wake-check) intents so the "I'm awake" page is not
            // instantly replaced by the normal alarm UI.
            // CRITICAL FIX: Allow sequencer missions to pass through the wake-check gate
            val incomingIsWakeCheck = newIntent.getBooleanExtra("from_wake_check", false)
            if (wakeCheckGuardActive && !incomingIsWakeCheck && !isSequencerMission) {
                Log.d(TAG, "onNewIntent: ignoring normal intent while wake-check gate is active for alarmId=$newAlarmId")
                return
            }

            alarmId = newIntent.getIntExtra(AlarmReceiver.ALARM_ID, newAlarmId)

            // Handle sequencer mission updates
            val sequencerComplete = newIntent.getBooleanExtra("sequencer_complete", false)

            Log.d(TAG, "onNewIntent: alarmId=$alarmId isSequencerMission=$isSequencerMission sequencerComplete=$sequencerComplete")

            // CRITICAL FIX: Use Intent-only approach for normal missions (not from sequencer)
            if (!isSequencerMission && !sequencerComplete) {
                val intentMissionType = newIntent.getStringExtra("mission_type")
                val intentMissionPassword = newIntent.getStringExtra("mission_password") ?: ""
                
                Log.d(TAG, "INTENT_ONLY_ON_NEW_INTENT: alarmId=$alarmId missionType=$intentMissionType missionPassword=$intentMissionPassword")
                
                // Only update mission state if we have a valid mission type
                if (!intentMissionType.isNullOrEmpty()) {
                    // Use ONLY Intent extras - never access storage
                    missionTapEnabled = (intentMissionType == "tap")
                    requiredPassword = if (intentMissionPassword.isNotEmpty()) {
                        intentMissionPassword
                    } else if (intentMissionType == "password") {
                        "IfYouWantYouCanSleep"
                    } else {
                        null
                    }
                    Log.d(TAG, "INTENT_CONFIG_ON_NEW_INTENT: alarmId=$alarmId missionType=$intentMissionType tapEnabled=$missionTapEnabled hasPassword=${requiredPassword != null} requiredPassword=$requiredPassword")
                }
            }

            // CRITICAL FIX: Handle sequencer_complete by clearing mission state and finishing
            if (sequencerComplete) {
                Log.d(TAG, "SEQUENCER_COMPLETE_RECEIVED: Clearing mission state and finishing activity")
                
                // CRITICAL: Clear all mission state immediately
                missionTapEnabled = false
                requiredPassword = null
                currentMissionId = null
                isSequencerMission = false
                classMissionStarted = false
                
                // Set dismiss flags IMMEDIATELY to prevent any UI rendering
                activityDismissed = true
                isDismissed = true
                isSequencerComplete = true
                
                // CRITICAL: Force finish with finishAndRemoveTask to prevent any restart
                try {
                    Log.d(TAG, "SEQUENCER_COMPLETE: Finishing activity with finishAndRemoveTask")
                    // Stop activity-managed audio
                    stopAudioIfPlaying()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        finishAndRemoveTask()
                    } else {
                        finish()
                    }
                    // CRITICAL: Ensure activity is fully dismissed
                    finish()
                } catch (e: Exception) {
                    Log.e(TAG, "SEQUENCER_COMPLETE_FINISH_ERROR: ${e.message}")
                    // Fallback: try regular finish
                    try {
                        finish()
                    } catch (_: Exception) {}
                }
                
                return // Don't process anything else
            }
            
            val fromSequencer = newIntent.getBooleanExtra(com.vaishnava.alarm.sequencer.MissionSequencer.EXTRA_FROM_SEQUENCER, false)
            
            if ( fromSequencer) {
                currentMissionId = newIntent.getStringExtra("mission_id")
                val missionType = newIntent.getStringExtra("mission_type")
                val missionPassword = newIntent.getStringExtra("mission_password") ?: ""
                Log.d(TAG, "SEQUENCER_MISSION_UPDATE: missionId=$currentMissionId missionType=$missionType missionPassword=$missionPassword")
                
                // CRITICAL FIX: Use Intent extras for mission configuration
                missionTapEnabled = (missionType == "tap")
                this@AlarmActivity.requiredPassword = when {
                    missionType == "password" -> if (missionPassword.isNotEmpty()) missionPassword else "IfYouWantYouCanSleep"
                    else -> null // CRITICAL: Clear password for non-password missions
                }
                Log.d(TAG, "SEQUENCER_IMMEDIATE_CONFIG: missionType=$missionType missionTapEnabled=$missionTapEnabled requiredPassword=${this@AlarmActivity.requiredPassword != null}")
                
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
    
    // CRITICAL FIX: Class-level mission state for persistence
    // Mission state variables
    @Volatile
    var isSecondMissionLoading = false
    
    @Volatile
    private var classMissionStarted = false

    private fun isSamsungProblemModel(): Boolean {
        val manu = Build.MANUFACTURER ?: ""
        val model = Build.MODEL ?: ""
        return manu.equals("samsung", ignoreCase = true) && model.uppercase().startsWith("SM-E156")
    }

    // -------------------- Screen receiver --------------------
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "SCREEN_OFF: Screen turned off - mission state preserved")
                }
                Intent.ACTION_USER_PRESENT -> {
                    Log.d(TAG, "USER_PRESENT: Device unlocked - checking mission state")
                    // CRITICAL FIX: Do NOT reset mission state on unlock
                    // Mission state is preserved and restored in onResume()
                    if (classMissionStarted || missionTapEnabled || requiredPassword != null) {
                        Log.d(TAG, "USER_PRESENT: Active mission detected - preserving state")
                    } else {
                        Log.d(TAG, "USER_PRESENT: No active mission - keeping current state")
                    }
                }
            }
        }
    }

    // -------------------- Finish alarm receiver --------------------
    private val finishAlarmReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "FINISH_ALARM_BROADCAST_RECEIVED: ${intent?.action}")
            
            val sequencerComplete = intent?.getBooleanExtra("sequencer_complete", false) ?: false
            val finishDirectly = intent?.getBooleanExtra("finish_directly", false) ?: false
            
            Log.d(TAG, "FINISH_ALARM_PARAMS: sequencerComplete=$sequencerComplete finishDirectly=$finishDirectly")
            
            // CRITICAL FIX: Finish if sequencer is complete OR if finish_directly is requested
            // This allows proper dismissal after all missions in sequence are completed
            if (sequencerComplete || finishDirectly) {
                Log.d(TAG, "FINISH_ALARM_SEQUENCER_COMPLETE: Finishing activity due to sequencer completion")
                
                // Set dismiss flags IMMEDIATELY
                activityDismissed = true
                isDismissed = true
                isSequencerComplete = true
                
                // CRITICAL: Finish activity IMMEDIATELY without any delays or conditions
                try {
                    Log.d(TAG, "FINISH_ALARM: Force finishing activity immediately")
                    // Stop activity-managed audio
                    stopAudioIfPlaying()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        finishAndRemoveTask()
                    } else {
                        finish()
                    }
                    overridePendingTransition(0, 0)
                    return // Exit immediately
                } catch (e: Exception) {
                    Log.e(TAG, "FINISH_ALARM_ERROR: ${e.message}")
                    try {
                        finish()
                        overridePendingTransition(0, 0)
                    } catch (_: Exception) {}
                    return // Exit immediately
                }
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
        
        // ================= FINAL TERMINAL GUARD =================
        val incomingMissionType = intent.getStringExtra("mission_type")
        val incomingMissionId = intent.getStringExtra("mission_id")
        val fromSequencer = intent.getBooleanExtra(
            MissionSequencer.EXTRA_FROM_SEQUENCER,
            false
        )

        if (
            // Activity relaunched after sequencer completion
            isSequencerComplete ||

            // OR relaunched with empty mission (lockscreen / OEM relaunch)
            (!fromSequencer &&
             incomingMissionType.isNullOrEmpty() &&
             incomingMissionId.isNullOrEmpty())
        ) {
            MissionLogger.log(
                "FINAL_FIX: Killing AlarmActivity permanently to prevent NONE / duplicate mission"
            )

            stopAudioIfPlaying()
            finishAndRemoveTask()
            overridePendingTransition(0, 0)
            return
        }
        // ========================================================
        
        // CRITICAL DEBUG: Log all intent extras when AlarmActivity starts
        MissionLogger.log("ACTIVITY_START: AlarmActivity onCreate called")
        intent.extras?.keySet()?.forEach { key ->
            MissionLogger.log("ACTIVITY_START: Intent extra - $key = ${intent.extras?.get(key)}")
        }
        
        // CRITICAL DEBUG: Check if this is a proper alarm trigger
        val alarmId = intent.getIntExtra(AlarmReceiver.ALARM_ID, -1)
        val missionType = intent.getStringExtra("mission_type")
        val ringtoneUri = intent.getStringExtra(AlarmReceiver.EXTRA_RINGTONE_URI)
        
        // ===================== NONE-MISSION FIX ======================
        if (isSequencerMission && missionType.isNullOrEmpty()) {
            MissionLogger.log("MISSION_GUARD: Ignored null missionType on activity create (prevent NONE).")
            // Do NOT reset mission here
            // Do NOT disable taps
            // Do NOT clear password
        }
        // =============================================================
        
        MissionLogger.log("ACTIVITY_START: alarmId=$alarmId missionType=$missionType ringtoneUri=$ringtoneUri")
        
        if (alarmId == -1 && missionType == null) {
            MissionLogger.log("ACTIVITY_START: WARNING - AlarmActivity started without proper alarm parameters!")
        }
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

        // Register finish alarm receiver for sequencer completion
        try {
            val finishFilter = IntentFilter().apply {
                addAction("com.vaishnava.alarm.FINISH_ALARM")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(finishAlarmReceiver, finishFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(finishAlarmReceiver, finishFilter)
            }
        } catch (_: Exception) {}

        currentMissionId = intent.getStringExtra("mission_id")
        isWakeCheckLaunchState.value = intent.getBooleanExtra("from_wake_check", false)
        isSequencerMission = intent.getBooleanExtra(com.vaishnava.alarm.sequencer.MissionSequencer.EXTRA_FROM_SEQUENCER, false)
        sequencerContext = intent.getStringExtra("sequencer_context") ?: ""
        isMissedAlarm = intent.getBooleanExtra("is_missed_alarm", false)
        
        // CRITICAL FIX: Reset sequencer complete flag when starting new sequencer mission
        if (isSequencerMission) {
            isSequencerComplete = false
            Log.d(TAG, "SEQUENCER_RESET: Reset isSequencerComplete for new sequencer mission")
        }
        
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
            // SIMPLIFIED: Use Intent extras first, storage only if absolutely needed
            var alarm: com.vaishnava.alarm.data.Alarm? = null
            
            // Only try to get alarm from storage if Intent extras are missing
            val hasIntentExtras = intent?.getStringExtra("mission_type") != null
            if (!hasIntentExtras) {
                try {
                    val storage = AlarmStorage(applicationContext)
                    alarm = storage.getAlarm(alarmId)
                    Log.d(TAG, "STORAGE_FALLBACK: Got alarm from storage since Intent extras missing")
                } catch (e: Exception) {
                    Log.w(TAG, "STORAGE_FALLBACK: Failed to get alarm from storage", e)
                }
            } else {
                Log.d(TAG, "STORAGE_SKIP: Using Intent extras, no storage access needed")
            }
            
            // CRITICAL FIX: Detect sequencer missions by checking alarm missionType, not just intent extra
            // This ensures sequencer detection works during force restart when EXTRA_FROM_SEQUENCER is not set
            if (alarm?.missionType == "sequencer" && !isSequencerMission) {
                isSequencerMission = true
                Log.d(TAG, "SEQUENCER_DETECTION: Detected sequencer mission from alarm missionType during force restart")
            }
            
            Log.d(TAG, "CONFIG_LOAD_START: alarmId=$alarmId isSequencerMission=$isSequencerMission sequencerContext=$sequencerContext hasAlarm=${alarm != null}")

            // CRITICAL FIX: Use ONLY Intent extras - no storage access whatsoever
            val intentMissionType = intent?.getStringExtra("mission_type")
            val intentMissionPassword = intent?.getStringExtra("mission_password") ?: ""
            
            Log.d(TAG, "INTENT_ONLY: alarmId=$alarmId missionType=$intentMissionType missionPassword=$intentMissionPassword")
            
            // Only configure mission if we have a valid mission type
            if (!intentMissionType.isNullOrEmpty()) {
                // CRITICAL FIX: If MissionSequencer is active, don't trigger "none" mission logic
                val missionSequencer = MissionSequencer(this)
                if (missionSequencer.isMissionRunning() && intentMissionType == "none") {
                    Log.w(TAG, "SEQUENCER_ACTIVE: Skipping 'none' mission logic while sequencer is running")
                    return
                }
                
                // Use ONLY Intent extras - never access storage
                val persistedMissionType = intentMissionType
                missionTapEnabled = (persistedMissionType == "tap")
                requiredPassword = if (intentMissionPassword.isNotEmpty()) {
                    intentMissionPassword
                } else if (persistedMissionType == "password") {
                    DEFAULT_GLOBAL_PASSWORD
                } else {
                    null
                }
                
                Log.d(TAG, "MISSION_CONFIG: alarmId=$alarmId isSequencer=$isSequencerMission missionType=$persistedMissionType tapEnabled=$missionTapEnabled hasPassword=${requiredPassword != null} requiredPassword=$requiredPassword alarmMissionType=${alarm?.missionType} alarmMissionPassword=${alarm?.missionPassword}")
            } else {
                Log.w(TAG, "No valid mission type found in intent, skipping mission configuration")
            }

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
                    // CRITICAL FIX: Use safe storage approach for Direct Boot compatibility
                    val alarm = try {
                        val safeStorage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            AlarmStorage(createDeviceProtectedStorageContext())
                        } else {
                            AlarmStorage(applicationContext)
                        }
                        safeStorage.getAlarms().find { it.id == alarmId }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to get alarm from device-protected storage for TTS check, trying smart storage", e)
                        val regularStorage = AlarmStorage(applicationContext)
                        regularStorage.getAlarms().find { it.id == alarmId }
                    }
                    
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
            
            // Guarded audio start in onCreate (if present)
            val extraUri = intent.getStringExtra("extra_ringtone_uri") ?: intent.getStringExtra(AlarmReceiver.EXTRA_RINGTONE_URI)
            
            // If no URI in intent, try to get from alarm storage
            val finalUri = if (extraUri.isNullOrBlank()) {
                try {
                    val alarmStorage = AlarmStorage(applicationContext)
                    val alarm = alarmStorage.getAlarm(alarmId)
                    alarm?.ringtoneUri?.toString()
                } catch (e: Exception) {
                    null
                }
            } else {
                extraUri
            }
            
            // Use RingtoneHelper to get valid URI - NO fallback
            val validUri = RingtoneHelper.parseUriSafe(finalUri)
            MissionLogger.log("ACT_AUDIO: Final URI from storage for playback: $validUri")
            if (validUri != null) {
                startAudioIfNeeded(validUri.toString())
            } else {
                MissionLogger.log("ACT_AUDIO: No valid URI available from storage, no audio will play")
            }
            
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

                        // ----------------------------------------------------------------------
                        // FIX: Prevent NONE state after second mission or sequencer completion
                        // If the activity is reopened with an empty or invalid mission, close it
                        val mt = intent.getStringExtra("mission_type")
                        val mid = intent.getStringExtra("mission_id")

                        val shouldCloseActivity = mission2Completed ||
                            isSequencerMission &&
                            (mt.isNullOrEmpty() || mid.isNullOrEmpty())

                        if (shouldCloseActivity) {
                            MissionLogger.log("MISSION_GUARD_FINAL: Activity reopened with empty mission after completion. Finishing immediately.")
                            finish()
                        }
                        // ----------------------------------------------------------------------

                        if (!shouldCloseActivity) {
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
        }

        turnScreenOn()
    }

    override fun onResume() {
        super.onResume()
        
        // CRITICAL FIX: Only restore mission state for non-sequencer missions
        // For sequencer missions, use current intent data instead of saved state
        if (!isSequencerMission) {
            restoreMissionState()
        }
        
        // CRITICAL FIX: Only finish if sequencer is complete AND this is NOT an active sequencer mission
        // AND no active mission is in progress
        if (isSequencerComplete && !isSequencerMission && !classMissionStarted && (missionTapEnabled == false && requiredPassword == null)) {
            Log.d(TAG, "ON_RESUME_SEQUENCER_COMPLETE: Finishing activity immediately (no active mission)")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    finishAndRemoveTask()
                } else {
                    finish()
                }
                overridePendingTransition(0, 0)
                return
            } catch (e: Exception) {
                try { finish() } catch (_: Exception) {}
                return
            }
        }
        
        // CRITICAL FIX: Only reset mission if no valid mission is currently active
        // This prevents unlock events from clearing active missions
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val isKeyguardLocked = keyguardManager?.isKeyguardLocked ?: false
        
        if (!isKeyguardLocked) {
            // Device is unlocked - only reset if no active mission
            if (!classMissionStarted && !missionTapEnabled && requiredPassword == null) {
                Log.d(TAG, "ON_RESUME_UNLOCKED: No active mission - keeping current state")
            } else {
                Log.d(TAG, "ON_RESUME_UNLOCKED: Active mission detected - preserving state (started=$classMissionStarted, tap=$missionTapEnabled, password=${requiredPassword != null})")
            }
        }
        
        writeSettingsState.value = Settings.System.canWrite(this)
    }

    // Call this function when the second mission starts loading
    fun onSecondMissionLoading() {
        isSecondMissionLoading = true
        Log.d(TAG, "SECOND_MISSION_LOADING: Second mission loading started")
    }

    // Call this function once the second mission is fully ready
    fun onSecondMissionReady() {
        isSecondMissionLoading = false
        Log.d(TAG, "SECOND_MISSION_READY: Second mission is fully ready")
    }
    
    // --- activity-managed alarm audio (enhanced from AlarmForegroundService) ---
    private var ringtoneFallback: Ringtone? = null
    private var audioFocusRequest: android.media.AudioFocusRequest? = null
    private var playbackVolume: Float = 1.0f
    private var playbackStarted: Boolean = false
    private var audioCheckHandler: Handler? = null
    private var audioCheckRunnable: Runnable? = null
    private var awaitingResume: Boolean = false
    private var isWakeCheckLaunch: Boolean = false
    private var audioWakeLock: PowerManager.WakeLock? = null
    private var previousAlarmVolume: Int = -1
    
    private fun startAudioIfNeeded(uriString: String?) {
    MissionLogger.log("ACT_AUDIO: startAudioIfNeeded called with URI: $uriString")
    
    if (uriString.isNullOrBlank()) {
        MissionLogger.log("ACT_AUDIO: No ringtone URI, skipping start.")
        return
    }

    if (playbackStarted) {
        MissionLogger.log("ACT_AUDIO: Playback already started, skipping.")
        return
    }

    val finalRingtoneUri = Uri.parse(uriString)
    MissionLogger.log("ACT_AUDIO: Starting audio with URI: $uriString")

    try {
        // --- AUDIO MANAGER SETUP (ForegroundService style) ---
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        try { audioManager.mode = AudioManager.MODE_RINGTONE } catch (_: Exception) {}

        // CRITICAL: 50% volume logic from AlarmForegroundService
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
                    MissionLogger.log("ACT_AUDIO: ALARM stream below 50% ($currentAlarm < $targetAlarm). Raising...")
                    
                    val maxRetries = 3
                    var attempt = 0
                    while (attempt < maxRetries) {
                        try {
                            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, targetAlarm, 0)
                        } catch (e: Exception) {
                            MissionLogger.log("ACT_AUDIO: setStreamVolume attempt $attempt failed: ${e.message}")
                        }
                        
                        try { Thread.sleep(120) } catch (_: InterruptedException) {}
                        
                        currentAlarm = getCurrentAlarm()
                        if (currentAlarm >= targetAlarm) {
                            MissionLogger.log("ACT_AUDIO: ALARM raised to $currentAlarm on attempt ${attempt + 1}")
                            break
                        } else {
                            MissionLogger.log("ACT_AUDIO: ALARM still below 50% after attempt ${attempt + 1}: $currentAlarm < $targetAlarm")
                        }
                        attempt++
                    }
                    
                    if (currentAlarm < targetAlarm) {
                        MissionLogger.log("ACT_AUDIO: ALARM still below 50% after all retries: $currentAlarm < $targetAlarm")
                    }
                } else {
                    MissionLogger.log("ACT_AUDIO: ALARM stream already at or above 50%: $currentAlarm >= $targetAlarm")
                }
            } catch (e: Exception) {
                MissionLogger.log("ACT_AUDIO: Error while enforcing ALARM 50% floor: ${e.message}")
            }
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        // AUDIO FOCUS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            ).setAudioAttributes(audioAttributes).build()
            val focusResult = audioManager.requestAudioFocus(audioFocusRequest!!)
            MissionLogger.log("ACT_AUDIO: Audio focus request result: $focusResult")
        } else {
            @Suppress("DEPRECATION")
            val focusResult = audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_ALARM,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            )
            MissionLogger.log("ACT_AUDIO: Audio focus request result (legacy): $focusResult")
        }

        // --- MEDIA PLAYER SECTION (ForegroundService) ---
        try {
            MissionLogger.log("ACT_AUDIO: Attempting MediaPlayer setup...")
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val mp = MediaPlayer()
            mp.setAudioAttributes(attrs)
            mp.setDataSource(this, finalRingtoneUri)
            mp.isLooping = true

            mp.setVolume(playbackVolume, playbackVolume)

            mp.setOnCompletionListener { p ->
                try {
                    if (p.isPlaying) p.stop()
                    p.seekTo(0)
                    p.start()
                    p.setVolume(playbackVolume, playbackVolume)
                } catch (_: Exception) {}
            }

            mp.prepare()
            mp.start()

            mediaPlayer = mp
            playbackStarted = true

            ensurePlaybackSilencedIfAwaiting()

            MissionLogger.log("ACT_AUDIO: MediaPlayer started successfully with URI: $uriString")
            return
        } catch (mpFail: Exception) {
            MissionLogger.log("ACT_AUDIO: MediaPlayer failed, trying ringtone fallback: ${mpFail.message}")
        }

        // --- RINGTONE FALLBACK ---
        try {
            MissionLogger.log("ACT_AUDIO: Attempting Ringtone fallback...")
            val ring = RingtoneManager.getRingtone(this, finalRingtoneUri)
            if (ring != null) {
                ring.audioAttributes = audioAttributes
                ring.isLooping = true
                ring.play()

                ringtoneFallback = ring
                playbackStarted = true

                ensurePlaybackSilencedIfAwaiting()

                MissionLogger.log("ACT_AUDIO: Ringtone fallback started with URI: $uriString")
            } else {
                MissionLogger.log("ACT_AUDIO: Ringtone was null for URI: $uriString - no fallback will be used")
            }
        } catch (e: Exception) {
            MissionLogger.log("ACT_AUDIO: Ringtone fallback failed: ${e.message}")
        }

    } catch (e: Exception) {
        MissionLogger.log("ACT_AUDIO: ERROR ${e.message}")
    }
}
    
    private fun ensurePlaybackSilencedIfAwaiting() {
    if (!awaitingResume) {
        playbackStarted = true
        return
    }

    try { mediaPlayer?.setVolume(0f, 0f) } catch (_: Exception) {}
    try { mediaPlayer?.pause() } catch (_: Exception) {}
    try { ringtoneFallback?.stop() } catch (_: Exception) {}

    MissionLogger.log("ACT_AUDIO: Silenced due to awaitingResume.")
}
    
    private fun stopAudioIfPlaying() {
    try {
        mediaPlayer?.let { mp ->
            try {
                if (mp.isPlaying) mp.stop()
                mp.release()
            } catch (_: Exception) {}
        }

        ringtoneFallback?.let { ring ->
            try { ring.stop() } catch (_: Exception) {}
        }

        mediaPlayer = null
        ringtoneFallback = null
        playbackStarted = false

        MissionLogger.log("ACT_AUDIO: Audio fully stopped.")
    } catch (_: Exception) {}
}
    
    // ========= Volume save/restore helpers from AlarmForegroundService =========
    
    private fun savePreviousVolume(vol: Int) {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putInt(KEY_PREV_VOLUME, vol).apply()
        } catch (e: Exception) {}
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
                Log.d(TAG, "No previous volume to restore")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore previous volume: ${e.message}")
        }
    }
    
    private fun resetMissionUIBeforeNewSequencerMission() {
        try {
            // CRITICAL FIX: Stop both activity and service audio before starting new mission to prevent conflicts
            try { 
                mediaPlayer?.stop()
                mediaPlayer?.release()
                mediaPlayer = null
                playbackStarted = false
                MissionLogger.log("ACT_AUDIO: Stopped previous activity audio for new mission")
            } catch (_: Exception) {}
            
            // CRITICAL FIX: Stop AlarmForegroundService audio to prevent duplicate audio
            try {
                val serviceIntent = Intent(this, AlarmForegroundService::class.java).apply {
                    action = "ACTION_STOP_SERVICE_AUDIO"
                }
                startService(serviceIntent)
                MissionLogger.log("ACT_AUDIO: Sent stop audio broadcast to AlarmForegroundService")
            } catch (_: Exception) {}
            
            // stop any ongoing TTS / handlers
            try { tts?.stop(); tts?.shutdown(); tts = null } catch (_: Exception) {}
            try { ttsHandler.removeCallbacksAndMessages(null) } catch (_: Exception) {}

            // remove any pending bring-to-front / other runnables
            try { bringToFrontHandler.removeCallbacksAndMessages(null) } catch (_: Exception) {}
            // remove activity-specific bg callbacks / timers (example names from your file)
            try { 
                // if you use handlers for countdowns etc.
                // bgHandler.removeCallbacksAndMessages(null) // careful: remove only mission-specific ones
            } catch (_: Exception) {}

            // reset mission state flags that gate UI
            classMissionStarted = false
            isDismissed = false
            activityDismissed = false
            sequencerMissionUpdate = true

            // clear mission-specific UI state variables (password/tap)
            missionTapEnabled = false
            requiredPassword = null
            currentMissionId = null

            // stop any visual progress indicators / hide overlays (call your composable state resets)
            isWakeCheckLaunchState.value = false

            Log.d(TAG, "resetMissionUIBeforeNewSequencerMission: cleared previous mission state")
        } catch (e: Exception) {
            Log.e(TAG, "resetMissionUIBeforeNewSequencerMission_error: ${e.message}")
        }
    }
    
    private fun isSecondMissionReady(): Boolean {
        // Check if we have a valid second mission type from the sequencer
        val currentMissionType = intent?.getStringExtra("mission_type") ?: currentMissionId
        val isReady = !currentMissionType.isNullOrEmpty() && currentMissionType != "none"
        Log.d(TAG, "isSecondMissionReady: currentMissionType=$currentMissionType isReady=$isReady")
        return isReady
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
        // CRITICAL FIX: Only skip rendering if sequencer is complete AND this is NOT an active sequencer mission
        // AND there's no active mission (to prevent black screen for second missions)
        if (isSequencerComplete && !isSequencerMission && !classMissionStarted && (missionTapEnabled == false && requiredPassword == null)) {
            Log.d(TAG, "AlarmUI: Skipping render for completed sequencer (non-sequencer mission)")
            return
        }
        
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
        
        // CRITICAL FIX: Sync mission started state with class-level variable
        LaunchedEffect(Unit) {
            missionStarted = this@AlarmActivity.classMissionStarted
        }
        
        // CRITICAL FIX: Update class-level state when Composable state changes
        LaunchedEffect(missionStarted) {
            this@AlarmActivity.classMissionStarted = missionStarted
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
                
                // CRITICAL FIX: Set initial mission configuration for sequencer missions
                val missionType = intent?.getStringExtra("mission_type") ?: currentMissionId
                if (missionType.isNullOrEmpty()) {
                    Log.w(TAG, "No mission type available, skipping mission configuration")
                    return@LaunchedEffect
                }
                missionTapEnabled = (missionType == "tap")
                this@AlarmActivity.requiredPassword = when {
                    missionType == "password" -> DEFAULT_GLOBAL_PASSWORD
                    else -> null // CRITICAL: Clear password for non-password missions
                }
                Log.d(TAG, "INITIAL_SEQUENCER_CONFIG: missionType=$missionType missionTapEnabled=$missionTapEnabled requiredPassword=${this@AlarmActivity.requiredPassword != null}")
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
                
                // CRITICAL FIX: Refresh mission configuration for new mission
                val missionType = intent?.getStringExtra("mission_type") ?: currentMissionId
                if (missionType.isNullOrEmpty()) {
                    Log.w(TAG, "No mission type available for mission update, skipping configuration")
                    return@LaunchedEffect
                }
                Log.d(TAG, "SEQUENCER_MISSION_CONFIG: Updating mission config for missionType=$missionType")
                missionTapEnabled = (missionType == "tap")
                // CRITICAL FIX: Update Composable state to trigger UI recomposition
                missionTapEnabledState = (missionType == "tap")
                // Update requiredPassword through the class field (it's a var, not val)
                this@AlarmActivity.requiredPassword = when {
                    missionType == "password" -> DEFAULT_GLOBAL_PASSWORD
                    else -> null // CRITICAL: Clear password for non-password missions
                }
                Log.d(TAG, "SEQUENCER_MISSION_CONFIG_UPDATED: missionType=$missionType missionTapEnabled=$missionTapEnabled requiredPassword=${this@AlarmActivity.requiredPassword != null}")
                Log.d(TAG, "SEQUENCER_MISSION_CONFIG_UPDATED: missionTapEnabled=$missionTapEnabled requiredPassword=${this@AlarmActivity.requiredPassword != null}")
                
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
                    // CRITICAL FIX: Only show "Start Mission" for valid mission types
                    val currentMissionType = intent?.getStringExtra("mission_type") ?: currentMissionId
                    if (!currentMissionType.isNullOrEmpty() && currentMissionType != "none") {
                        // CRITICAL FIX: Auto-start only tap missions for sequencer, show button for password missions
                        if (isSequencerMission && currentMissionType == "tap") {
                            Log.d("AlarmActivity", "Auto-starting sequencer tap mission: $currentMissionType")
                            classMissionStarted = true
                            missionStarted = true
                        } else if (isSequencerMission && currentMissionType == "password") {
                            Log.d("AlarmActivity", "Password sequencer mission detected - showing Start Mission button")
                            // Don't auto-start password missions - let user click Start Mission
                        } else {
                            Log.d("AlarmActivity", "Mission detected but not started - ensuring UI state")
                        }
                    } else {
                        Log.w("AlarmActivity", "Invalid mission type detected, not showing Start Mission: $currentMissionType")
                    }
                }
            }
        }

        // CRITICAL FIX: Use MainActivity's singleton MissionSequencer to prevent leaks
        val missionSequencer = MainActivity.getInstance()?.missionSequencer
        if (missionSequencer == null) {
            Log.w(TAG, "MissionSequencer not available, skipping render")
            return
        }
        
        if (missionSequencer.isMissionRunning() && !isSecondMissionReady()) {
            // Keep waiting, do not show any placeholder or intermediate screen
            Log.d(TAG, "WAITING_FOR_SECOND_MISSION: Sequencer active, second mission not ready yet")
            return
        }
        
        if (this@AlarmActivity.activityDismissed || isDismissed) {
            Box(modifier = Modifier.fillMaxSize()) {}
            return
        }

        val focusRequester = remember { FocusRequester() }
        val keyboardController = LocalSoftwareKeyboardController.current

      
        // Get current alarm data for protected status check
        val currentAlarm = remember(alarmId, isPreview) {
            try {
                if (this@AlarmActivity.isPreview) {
                    null
                } else {
                    val storage = AlarmStorage(applicationContext)
                    val foundAlarm = storage.getAlarms().find { it.id == alarmId }
                    Log.d(TAG, "ALARM_LOAD_DEBUG: alarmId=$alarmId foundAlarm=$foundAlarm ringtoneUri=${foundAlarm?.ringtoneUri}")
                    foundAlarm
                }
            } catch (_: Exception) { null }
        }

        // Title time + subtitle
        val ringtoneTitle = remember(alarmId, isPreview) {
            try {
                if (this@AlarmActivity.isPreview) {
                    "Preview"
                } else {
                    // CRITICAL FIX: Use same approach as MainActivity but with fallback for sequencer
                    val alarmRingtoneUri = currentAlarm?.ringtoneUri
                    
                    if (alarmRingtoneUri != null) {
                        // Use same approach as MainActivity
                        resolveRingtoneTitle(this@AlarmActivity, alarmRingtoneUri)
                    } else {
                        // Fallback for sequencer missions - get ringtone from latest alarm
                        if (isSequencerMission) {
                            try {
                                val alarmStorage = AlarmStorage(applicationContext)
                                val alarmsWithRingtone = alarmStorage.getAlarms().filter { it.ringtoneUri != null }
                                val latestAlarmWithRingtone = alarmsWithRingtone.maxByOrNull { it.createdTime }
                                latestAlarmWithRingtone?.ringtoneUri?.let { uri ->
                                    resolveRingtoneTitle(this@AlarmActivity, uri)
                                } ?: "None selected"
                            } catch (e: Exception) {
                                "None selected"
                            }
                        } else {
                            "None selected"
                        }
                    }
                }
            } catch (e: Exception) { 
                if (this@AlarmActivity.isPreview) "Preview" else "Unknown Ringtone" 
            }
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
                    // CRITICAL FIX: Ensure tap missions always start with full 105 seconds in both single and multi-mission
                    // CRITICAL SEQUENCER FIX: For sequencer missions, ignore timer state and use full time
                    tapSeconds = 105
                    
                    // CRITICAL FIX: For sequencer missions, disable timer state to prevent interference with tap timer
                    if (isSequencerMission) {
                        timerState = TimerState.Idle
                    }
                    
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
                // For protected alarms,// CRITICAL FIX: Use intent-based mission detection for screen-off reliability
                val hasMission = if (currentAlarm?.isProtected == true) {
                    // For protected alarms, check intent extras first, convert "none" to empty
                    val missionType = intent?.getStringExtra("mission_type")?.let { if (it == "none") "" else it } 
                        ?: currentAlarm?.missionType?.let { if (it == "none") "" else it } ?: ""
                    // CRITICAL FIX: Only show missions for valid mission types
                    val isValidMissionType = missionType == "tap" || missionType == "password"
                    isValidMissionType
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
                                    // CRITICAL FIX: For sequencer missions, call onMissionCompleted to advance to next mission
                                    if (isSequencerMission) {
                                        onMissionCompleted(alarmId)
                                    } else {
                                        // For normal password missions, just dismiss the alarm directly
                                        dismissAlarm(alarmId)
                                    }
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

            // Dismiss button logic: Never show dismiss button - user must complete the mission
            val dismissButtonVisible = when {
                // Never show dismiss button during wake-up check
                isWakeCheckLaunchState.value -> false
                // Never show dismiss button in sequencer mode
                isSequencerMission || isSequencerComplete -> false
                // Never show dismiss button - user must complete mission
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
    // Helper function to determine actual password from mission data
    private fun getActualPassword(alarm: Alarm?, missionType: String?): String? {
        // If mission type is tap, no password needed
        if (missionType == "tap") {
            return null
        }
        
        // CRITICAL FIX: For Direct Boot compatibility, try to get fresh mission data if alarm is null
        val missionPassword = if (alarm?.missionPassword == null && missionType != null) {
            try {
                // Use default password for sequencer missions
                if (missionType.contains("+")) {
                    DEFAULT_GLOBAL_PASSWORD
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get fresh mission data for password retrieval", e)
                null
            }
        } else {
            alarm?.missionPassword
        }
        
        // CRITICAL FIX: If mission password contains "+", it's ANY sequence (tap+password, password+tap, etc.)
        // Never use the sequence string as the actual password
        if (missionPassword?.contains("+") == true) {
            return DEFAULT_GLOBAL_PASSWORD // Use default password for any sequencer missions
        }
        
        // For non-sequencer missions, use the mission password if it's a password mission
        return if (missionType == "password" && alarm?.missionType != "sequencer") {
            missionPassword?.takeIf { it.isNotBlank() } ?: DEFAULT_GLOBAL_PASSWORD
        } else null
    }

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
                ?: intent?.getStringExtra("mission_type")?.takeIf { it != "none" && it.isNotEmpty() }
                ?: "sequencer_mission_${alarmId}"

            Log.d(TAG, "MISSION_COMPLETION_BROADCAST: alarmId=$alarmId missionId=$missionId intentMissionId=${intent?.getStringExtra("mission_id")}")

            val completionIntent = Intent(MissionSequencer.ACTION_MISSION_COMPLETED).apply {
                putExtra(MissionSequencer.EXTRA_MISSION_ID, missionId)
                putExtra(MissionSequencer.EXTRA_MISSION_SUCCESS, true)
                putExtra(MissionSequencer.EXTRA_FROM_SEQUENCER, true)
            }
            sendBroadcast(completionIntent)

            // MISSION GUARD: Removed guard activation causing UI freeze and dual audio
            // The existing null/none prevention in restoreMissionState() handles the issue

            // CRITICAL FIX: Use sequencer complete status to determine if this is the last mission
            val missionSequencer = MainActivity.getInstance()?.missionSequencer
            val isSequencerComplete = missionSequencer?.getSequencerCompleteStatus() ?: false
            
            if (isSequencerComplete) {
                // ===== FINALIZE SEQUENCER =====
                mission2Completed = true
                MissionLogger.log("FINAL_FIX: Second mission completed. Closing AlarmActivity permanently.")

                stopAudioIfPlaying()
                finishAndRemoveTask()
                overridePendingTransition(0, 0)
                return
                // =============================
            }
            
            // CRITICAL FIX: For sequencer missions with more missions, keep activity open for next mission
            // The activity should remain open for the next mission in the sequence
            Log.d(TAG, "MISSION_COMPLETED_SEQUENCER: Keeping activity open for next mission in sequence")

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

            // CRITICAL FIX: For sequencer missions, dismiss directly without blocking protection
            // The sequencer handles mission completion, so we should finish the activity
            if (isSequencerMission) {
                Log.d(TAG, "MISSION_COMPLETED_SEQUENCER: Dismiss alarm directly for sequencer mission")
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        finishAndRemoveTask()
                    } else {
                        finish()
                    }
                    overridePendingTransition(0, 0)
                } catch (_: Exception) {}
                return
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
            // Convert "none" to empty string for comparison
            val missionType = alarm?.missionType?.let { if (it == "none") "" else it } ?: ""
            if (alarm?.isProtected == true && missionType.isNotEmpty()) {
                Log.w(TAG, "Attempted to dismiss protected alarm $alarmId with mission $missionType - blocking dismissal")
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
        
        // CRITICAL FIX: Only save mission state if sequencer is NOT complete
        // This prevents saving null/none mission state after sequencer completion
        if (!isSequencerComplete) {
            saveMissionState()
        } else {
            Log.d(TAG, "ON_PAUSE_SEQUENCER_COMPLETE: Not saving mission state - sequencer is complete")
        }
        
        if (!activityDismissed) {
            Handler(Looper.getMainLooper()).postDelayed({ bringToFront() }, 500)
        }
    }

    override fun onDestroy() {
        stopAudioIfPlaying()
        super.onDestroy()
        
        // CRITICAL FIX: Clear mission state when activity is destroyed
        clearMissionState()
        
        try { unregisterReceiver(screenReceiver) } catch (e: Exception) {
            // Error unregistering receiver
        }
        try { unregisterReceiver(finishAlarmReceiver) } catch (e: Exception) {
            // Error unregistering finish alarm receiver
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
    
    // -------------------- Mission State Persistence --------------------
    
    private var lastStateSaveTime = 0L
    private val STATE_SAVE_THROTTLE_MS = 500L // Throttle state saves to every 500ms
    
    private fun saveMissionState() {
        try {
            // CRITICAL FIX: Don't save state if alarmId is invalid (-1) or missionId is null
            // This prevents infinite loop of saving null state
            if (alarmId == -1 || currentMissionId == null) {
                Log.d(TAG, "MISSION_STATE_SKIP: Invalid state (alarmId=$alarmId missionId=$currentMissionId) - skipping save")
                return
            }
            
            // CRITICAL FIX: Throttle excessive state saves during active missions
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastStateSaveTime < STATE_SAVE_THROTTLE_MS && !isSequencerComplete) {
                // Skip saving if too recent and sequencer is not complete
                return
            }
            lastStateSaveTime = currentTime
            
            val prefs = getSharedPreferences("mission_state", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putInt("alarm_id", alarmId)
                putString("current_mission_id", currentMissionId)
                putBoolean("mission_tap_enabled", missionTapEnabled)
                putString("required_password", requiredPassword)
                putBoolean("is_sequencer_mission", isSequencerMission)
                putBoolean("is_sequencer_complete", isSequencerComplete)
                putString("sequencer_context", sequencerContext)
                putBoolean("mission_started", classMissionStarted)
                putLong("save_timestamp", System.currentTimeMillis())
                apply()
            }
            Log.d(TAG, "MISSION_STATE_SAVED: alarmId=$alarmId missionId=$currentMissionId tapEnabled=$missionTapEnabled hasPassword=${requiredPassword != null}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save mission state: ${e.message}")
        }
    }
    
    private fun restoreMissionState() {
        try {
            val prefs = getSharedPreferences("mission_state", Context.MODE_PRIVATE)
            val savedAlarmId = prefs.getInt("alarm_id", -1)
            val saveTimestamp = prefs.getLong("save_timestamp", 0)
            val now = System.currentTimeMillis()
            
            // Only restore if it's the same alarm and saved within last 5 minutes
            // CRITICAL FIX: Also block invalid alarmId (-1) and null missionId states
            if (savedAlarmId == alarmId && savedAlarmId != -1 && (now - saveTimestamp) < 5 * 60 * 1000) {
                currentMissionId = prefs.getString("current_mission_id", null)
                
                // CRITICAL FIX: Block "none" missions and null missionId from being restored from saved state
                if (currentMissionId == "none" || currentMissionId?.contains("none") == true || currentMissionId == null) {
                    Log.w(TAG, "RESTORE_BLOCKED: Blocking invalid mission from being restored - missionId=$currentMissionId")
                    currentMissionId = null
                    missionTapEnabled = false
                    requiredPassword = null
                    classMissionStarted = false
                    return
                }
                
                missionTapEnabled = prefs.getBoolean("mission_tap_enabled", false)
                requiredPassword = prefs.getString("required_password", null)
                isSequencerMission = prefs.getBoolean("is_sequencer_mission", false)
                isSequencerComplete = prefs.getBoolean("is_sequencer_complete", false)
                sequencerContext = prefs.getString("sequencer_context", "") ?: ""
                classMissionStarted = prefs.getBoolean("mission_started", false)
                
                Log.d(TAG, "MISSION_STATE_RESTORED: alarmId=$alarmId missionId=$currentMissionId tapEnabled=$missionTapEnabled hasPassword=${requiredPassword != null}")
            } else {
                Log.d(TAG, "MISSION_STATE_NOT_RESTORED: alarmId mismatch or too old (saved=$savedAlarmId current=$alarmId, age=${(now - saveTimestamp) / 1000}s)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore mission state: ${e.message}")
        }
    }
    
    private fun clearMissionState() {
        try {
            val prefs = getSharedPreferences("mission_state", Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
            Log.d(TAG, "MISSION_STATE_CLEARED")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear mission state: ${e.message}")
        }
    }
    
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        
        // CRITICAL FIX: Save mission state to instance state bundle
        outState.putInt("alarm_id", alarmId)
        outState.putString("current_mission_id", currentMissionId)
        outState.putBoolean("mission_tap_enabled", missionTapEnabled)
        outState.putString("required_password", requiredPassword)
        outState.putBoolean("is_sequencer_mission", isSequencerMission)
        outState.putBoolean("is_sequencer_complete", isSequencerComplete)
        outState.putString("sequencer_context", sequencerContext)
        outState.putBoolean("mission_started", classMissionStarted)
        
        Log.d(TAG, "MISSION_STATE_SAVED_TO_INSTANCE: alarmId=$alarmId missionId=$currentMissionId tapEnabled=$missionTapEnabled")
    }
    
    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        
        // CRITICAL FIX: Restore mission state from instance state bundle
        try {
            alarmId = savedInstanceState.getInt("alarm_id", -1)
            currentMissionId = savedInstanceState.getString("current_mission_id")
            missionTapEnabled = savedInstanceState.getBoolean("mission_tap_enabled", false)
            requiredPassword = savedInstanceState.getString("required_password")
            isSequencerMission = savedInstanceState.getBoolean("is_sequencer_mission", false)
            isSequencerComplete = savedInstanceState.getBoolean("is_sequencer_complete", false)
            sequencerContext = savedInstanceState.getString("sequencer_context", "")
            classMissionStarted = savedInstanceState.getBoolean("mission_started", false)
            
            Log.d(TAG, "MISSION_STATE_RESTORED_FROM_INSTANCE: alarmId=$alarmId missionId=$currentMissionId tapEnabled=$missionTapEnabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore mission state from instance: ${e.message}")
        }
    }
}
