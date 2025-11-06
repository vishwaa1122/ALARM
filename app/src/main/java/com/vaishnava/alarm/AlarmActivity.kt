package com.vaishnava.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.RingtoneManager
import android.speech.tts.TextToSpeech
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Vibrator
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.VideoView
import coil.compose.AsyncImage
import com.vaishnava.alarm.data.Alarm
import com.vaishnava.alarm.ui.theme.AlarmTheme
import kotlinx.coroutines.delay
import java.util.Locale

class AlarmActivity : ComponentActivity() {

    companion object { private const val TAG = "AlarmActivity" } // PATCHED_BY_AUTOFIXER

    private val REQUEST_CODE_OVERLAY_PERMISSION = 101
    private var requiredPassword: String? = null
    private val DEFAULT_GLOBAL_PASSWORD = "IfYouWantYouCanSleep"
    private var tts: TextToSpeech? = null
    private var ttsMessage: String = ""
    private val ttsHandler = Handler(Looper.getMainLooper())
    private var ttsRunnable: Runnable? = null
    private var lastSpokenTime: String = ""

    private val INITIAL_PASSWORD_ENTRY_TIME_SECONDS = 30 // Reverted to 30 seconds
    private val BLOCKED_TIME_SECONDS = 120
    private val STARTUP_BLOCKED_TIME_SECONDS = 90

    private enum class TimerState { StartupBlocked, InitialEntry, Blocked, Idle }
    private var timerState = TimerState.StartupBlocked
    private var alarmId = -1
    @Volatile private var activityDismissed: Boolean = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.track call
                }
                Intent.ACTION_USER_PRESENT -> {
                    // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.track call
                }
            }
        }
    }

    private fun bringToFront() {
        try {
            val intent = Intent(this@AlarmActivity, AlarmActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
                putExtra(AlarmReceiver.ALARM_ID, alarmId)
            }
            startActivity(intent)
        } catch (_: Exception) { }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Hardened: Wake activity even under lock or sleep
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

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )

        // Additional flags to ensure the screen turns on and shows over the lock screen
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Allow overlay type safely across Android versions
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            } else {
                @Suppress("DEPRECATION")
                window.setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT)
            }
            } catch (e: Exception) {
                // PATCHED_BY_AUTO-FIXER: Removed UnifiedLogger.e call
        }

        // Register broadcast receiver for screen events
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(screenReceiver, filter)
            }
        } catch (e: Exception) {
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.e call
        }

        alarmId = intent.getIntExtra(AlarmReceiver.ALARM_ID, -1)
        // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.d call

        // Load mission config from storage for this alarm
        try {
            val storage = AlarmStorage(applicationContext)
            val alarm = storage.getAlarms().find { it.id == alarmId }
            if (alarm?.missionType == "password") {
                requiredPassword = alarm.missionPassword?.takeIf { it.isNotBlank() } ?: DEFAULT_GLOBAL_PASSWORD
                timerState = TimerState.StartupBlocked
            } else {
                requiredPassword = null
                timerState = TimerState.Idle
            }
            // Initialize TTS and speak reminder
            // Build TTS message with current time (computed dynamically)
            val nowStr: String = try {
                val fmt = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                fmt.format(java.util.Date())
            } catch (_: Exception) { "now" }
            ttsMessage = "The time is $nowStr. It is time to wake up now."
            tts = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.getDefault()
                    // Prefer guidance usage so it can mix with alarm sound on some devices
                    try {
                        val attrs = android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                        tts?.setAudioAttributes(attrs)
                    } catch (_: Exception) { }
                    // Speak immediately (compute current time fresh), then repeat every 15 seconds until dismissed
                    val firstNow = try {
                        val fmt = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                        fmt.format(java.util.Date())
                    } catch (_: Exception) { "now" }
                    val firstMsg = "The time is $firstNow. It is time to wake up now."
                    ttsMessage = firstMsg
                    sendDuckForTts(firstMsg)
                    tts?.speak(firstMsg, TextToSpeech.QUEUE_ADD, null, "WAKE_MSG")
                    lastSpokenTime = firstNow
                    startTtsRepeater()
                }
            }
        } catch (_: Exception) {
            requiredPassword = null
            timerState = TimerState.Idle
        }

        Handler(Looper.getMainLooper()).postDelayed({
            checkOverlayPermission()
        }, 5000)

        setContent {
            AlarmTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0)
                ) { paddingValues ->
                    Box(modifier = Modifier.fillMaxSize()) {
                        // 0) Try network image background (Coil)
                        AsyncImage(
                            model = "https://www.google.com/url?sa=i&url=https%3A%2F%2Fwww.vectorstock.com%2Froyalty-free-vector%2Falarm-clock-wake-up-background-vector-27046893&psig=AOvVaw3cJGLBs0lEkGqS_WCKRrIi&ust=1761984773784000&source=images&cd=vfe&opi=89978449&ved=0CBUQjRxqFwoTCOj9yvf-zZADFQAAAAAdAAAAABAL",
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )

                        // 1) Try video background: raw/alarm_bg
                        val videoResId = try { resources.getIdentifier("alarm_bg", "raw", packageName) } catch (_: Exception) { 0 }
                        val imageResId = try { resources.getIdentifier("alarm_bg", "drawable", packageName) } catch (_: Exception) { 0 }

                        if (videoResId != 0) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { ctx ->
                                    VideoView(ctx).apply {
                                        val uri = android.net.Uri.parse("android.resource://" + packageName + "/" + videoResId)
                                        setVideoURI(uri)
                                        setOnPreparedListener { mp ->
                                            mp.isLooping = true
                                            mp.setVolume(0f, 0f)
                                            start()
                                        }
                                    }
                                }
                            )
                        } else if (imageResId != 0) {
                            Image(
                                painter = painterResource(id = imageResId),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            // Fallback gradient
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            colors = listOf(Color(0xFF0F2027), Color(0xFF203A43), Color(0xFF2C5364))
                                        )
                                    )
                            )
                        }

                        // Scrim for readability
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.25f))
                        )

                        AlarmUI(modifier = Modifier, alarmId)
                    }
                }
            }
        }
        
        // Immediately turn on the screen when the activity starts
        turnScreenOn()
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    setShowWhenLocked(true)
                    setTurnScreenOn(true)
                } else {
                    window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
                }
            } catch (e2: Exception) {
                // If all else fails, we've done our best
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return if (keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_POWER
        ) {
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.d call
            true
        } else super.onKeyDown(keyCode, event)
    }

    @Composable
    fun AlarmUI(modifier: Modifier = Modifier, alarmId: Int) {
        var passwordInput by remember { mutableStateOf("") }
        var showPasswordError by remember { mutableStateOf(false) }
        var remainingTime by remember { mutableStateOf(STARTUP_BLOCKED_TIME_SECONDS) }
        var isDismissed by remember { mutableStateOf(false) }
        var showPasswordVisible by remember { mutableStateOf(false) }
        var missionStarted by remember { mutableStateOf(false) }

        val focusRequester = remember { FocusRequester() }
        val keyboardController = LocalSoftwareKeyboardController.current
        val isPasswordInputEnabled = timerState == TimerState.InitialEntry

        LaunchedEffect(timerState, missionStarted) {
            when (timerState) {
                TimerState.StartupBlocked -> {
                    // Only start the 90s countdown after Start Mission is pressed
                    if (missionStarted) {
                        remainingTime = STARTUP_BLOCKED_TIME_SECONDS
                        while (remainingTime > 0 && timerState == TimerState.StartupBlocked && missionStarted) {
                            delay(1000)
                            remainingTime--
                        }
                        if (timerState == TimerState.StartupBlocked && missionStarted) {
                            timerState = TimerState.InitialEntry
                        }
                    }
                }
                TimerState.InitialEntry -> {
                    remainingTime = INITIAL_PASSWORD_ENTRY_TIME_SECONDS
                    while (remainingTime > 0 && timerState == TimerState.InitialEntry) {
                        delay(1000)
                        remainingTime--
                    }
                    if (timerState == TimerState.InitialEntry && passwordInput != (requiredPassword ?: "")) {
                        timerState = TimerState.Blocked
                        // Boost alarm loudness beyond device max when user missed the 30s window
                        try {
                            val boost = Intent("com.vaishnava.alarm.BOOST_ALARM").apply {
                                putExtra("gainMb", 3000) // +30 dB (max)
                            }
                            sendBroadcast(boost)
                        } catch (_: Exception) { }
                    }
                }
                TimerState.Blocked -> {
                    remainingTime = BLOCKED_TIME_SECONDS
                    while (remainingTime > 0 && timerState == TimerState.Blocked) {
                        delay(1000)
                        remainingTime--
                    }
                    if (timerState == TimerState.Blocked) timerState = TimerState.InitialEntry
                }
                TimerState.Idle -> {}
            }
        }

        LaunchedEffect(isPasswordInputEnabled) {
            if (isPasswordInputEnabled) {
                focusRequester.requestFocus()
                keyboardController?.show()
            } else keyboardController?.hide()
        }

        // Auto-dismiss after a certain time to prevent force restart requirement
        LaunchedEffect(Unit) {
            delay(10 * 60 * 1000) // Auto-dismiss after 10 minutes
            if (!isDismissed) {
                dismissAlarm(alarmId)
            }
        }

        Box(
            modifier = modifier
                .fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
            // Prominent time block
            val context = this@AlarmActivity
            val ringtoneTitle = remember(alarmId) {
                try {
                    val storage = AlarmStorage(applicationContext)
                    val alarm = storage.getAlarms().find { it.id == alarmId }
                    val uriString = alarm?.ringtoneUri
                    if (uriString.isNullOrBlank()) {
                        "Unknown Ringtone"
                    } else {
                        val uri = android.net.Uri.parse(uriString)
                        val ringtone = RingtoneManager.getRingtone(context, uri)
                        ringtone?.getTitle(context) ?: "Unknown Ringtone"
                    }
                } catch (_: Exception) {
                    "Unknown Ringtone"
                }
            }
            val uiNowStr = remember(alarmId) {
                try {
                    val fmt = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                    fmt.format(java.util.Date())
                } catch (_: Exception) { "--:--" }
            }
            if (requiredPassword == null || !missionStarted) {
                Text(
                    text = uiNowStr,
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
            
            if (requiredPassword == null || !missionStarted) {
                Text(
                    "It is time to wake up now",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB0BEC5),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
            Spacer(Modifier.height(8.dp))
            if (requiredPassword != null) {
                if (timerState == TimerState.StartupBlocked && !missionStarted) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .background(Color(0xFFFFAB91))
                            .clickable { missionStarted = true },
                        contentAlignment = Alignment.Center
                    ) { Text("Start Mission", modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
                }
            }
            Spacer(Modifier.height(8.dp))
            AnimatedVisibility(visible = requiredPassword != null && missionStarted, enter = fadeIn() + slideInVertically(), exit = fadeOut() + slideOutVertically()) {
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
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        OutlinedTextField(
                            value = passwordInput,
                            onValueChange = {
                                if (isPasswordInputEnabled) {
                                    passwordInput = it
                                    showPasswordError = false
                                }
                            },
                            placeholder = { Text(DEFAULT_GLOBAL_PASSWORD) },
                            visualTransformation = if (showPasswordVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(autoCorrect = false, keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                            enabled = isPasswordInputEnabled,
                            singleLine = true,
                            shape = RectangleShape,
                            trailingIcon = {
                                val label = if (showPasswordVisible) "Hide" else "Show"
                                androidx.compose.material3.TextButton(onClick = { showPasswordVisible = !showPasswordVisible }) { Text(label) }
                            }
                        )
                        if (!showPasswordVisible && passwordInput.isNotEmpty()) {
                            val entered = "\u2022".repeat(passwordInput.length)
                            Text(
                                "Entering: $entered",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFB0BEC5),
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                }
            }

            when {
                showPasswordError -> Text("Incorrect password", color = Color.Red, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                timerState == TimerState.Blocked || (timerState == TimerState.StartupBlocked && missionStarted) -> Text(
                    text = if (timerState == TimerState.StartupBlocked)
                        "Startup blocked. Wait $remainingTime sec."
                    else
                        "Input blocked. Try again in $remainingTime sec.",
                    color = Color.Red,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                timerState == TimerState.InitialEntry && requiredPassword != null -> Text(
                    "Enter password. Remaining time: $remainingTime sec.",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }

            Spacer(Modifier.height(8.dp))
            if (requiredPassword != null && missionStarted) {
                val enabled = timerState == TimerState.InitialEntry
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .background(if (enabled) Color(0xFFFFAB91) else Color(0x66FFAB91))
                        .let { if (enabled) it.clickable {
                            if (passwordInput == (requiredPassword ?: "")) {
                                isDismissed = true
                                dismissAlarm(alarmId)
                            } else showPasswordError = true
                        } else it },
                    contentAlignment = Alignment.Center
                ) { Text("Complete Mission", modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
            } else if (requiredPassword == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .background(Color(0xFFFFAB91))
                        .clickable {
                            isDismissed = true
                            dismissAlarm(alarmId)
                        },
                    contentAlignment = Alignment.Center
                ) { Text("Dismiss", modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
            }
            
            // Remove the snooze and force dismiss buttons as they're not useful
        }
        }
    }

    private fun dismissAlarm(alarmId: Int) {
        try {
            activityDismissed = true
            timerState = TimerState.Idle
            try {
                ttsRunnable?.let { ttsHandler.removeCallbacks(it) }
                tts?.stop(); tts?.shutdown()
            } catch (_: Exception) { }
            
            // Stop the foreground service
            val serviceIntent = Intent(this@AlarmActivity, AlarmForegroundService::class.java).apply {
                action = Constants.ACTION_STOP_FOREGROUND_SERVICE
                putExtra(AlarmReceiver.ALARM_ID, alarmId)
            }
            startService(serviceIntent)

            // Get the alarm and scheduler
            val alarmStorage = AlarmStorage(applicationContext)
            val alarmScheduler = AndroidAlarmScheduler(applicationContext)
            val alarms = alarmStorage.getAlarms()
            val alarm = alarms.find { it.id == alarmId } ?: return
            
            // For all alarms (both one-time and repeating), cancel the pending alarm
            alarmScheduler.cancel(alarm)
            
            // For one-time alarms, disable them completely
            // For repeating alarms, keep them enabled but cancel the current instance
            if (alarm.days.isNullOrEmpty()) {
                // One-time alarm - disable it completely
                val updatedAlarm = alarm.copy(isEnabled = false)
                alarmStorage.updateAlarm(updatedAlarm)
            } else {
                // Repeating alarm - just cancel the current instance, keep it enabled
                // The alarm will fire again at the next scheduled time
            }

            // Clear the upcoming alarm notification
            val clearNotificationIntent = Intent("com.vaishnava.alarm.CLEAR_NOTIFICATION").apply {
                setPackage(packageName)
            }
            sendBroadcast(clearNotificationIntent)

            // Bring user back to the app home page after dismiss
            val homeIntent = Intent(this@AlarmActivity, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(homeIntent)

            Handler(Looper.getMainLooper()).postDelayed({ finish() }, 100)
        } catch (e: Exception) {
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
        }
    }

    override fun onBackPressed() {
        // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
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
        // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
        try {
            unregisterReceiver(screenReceiver)
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
        } catch (e: Exception) {
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
        }
        try {
            ttsRunnable?.let { ttsHandler.removeCallbacks(it) }
            tts?.stop(); tts?.shutdown()
        } catch (_: Exception) { }
    }

    private fun startTtsRepeater() {
        val r = object : Runnable {
            override fun run() {
                if (timerState != TimerState.Idle) {
                    try {
                        val nowStr = try {
                            val fmt = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                            fmt.format(java.util.Date())
                        } catch (_: Exception) { "now" }
                        if (nowStr != lastSpokenTime) {
                            val msg = "The time is $nowStr. It is time to wake up now."
                            ttsMessage = msg
                            lastSpokenTime = nowStr
                            sendDuckForTts(msg)
                            tts?.speak(msg, TextToSpeech.QUEUE_ADD, null, "WAKE_MSG_REPEAT")
                        }
                    } catch (_: Exception) { }
                    // Check frequently, but only speak on change
                    ttsHandler.postDelayed(this, 5000)
                }
            }
        }
        ttsRunnable = r
        ttsHandler.postDelayed(r, 5000)
    }

    private fun sendDuckForTts(message: String) {
        val words = message.trim().split("\\s+".toRegex()).size.coerceAtLeast(3)
        val durationMs = (words * 250).coerceIn(1500, 5000)
        try {
            val intent = Intent("com.vaishnava.alarm.DUCK_ALARM").apply {
                putExtra("factor", 0.25f)
                putExtra("duration", durationMs.toLong())
            }
            sendBroadcast(intent)
        } catch (_: Exception) { }
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
                } catch (e: Exception) {
                    // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
                }
            }
        }
    }
}