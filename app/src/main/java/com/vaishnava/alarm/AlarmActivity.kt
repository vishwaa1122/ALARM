package com.vaishnava.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.vaishnava.alarm.ui.theme.AlarmTheme
import kotlinx.coroutines.delay

class AlarmActivity : ComponentActivity() {

    companion object { private const val TAG = "AlarmActivity" } // PATCHED_BY_AUTOFIXER

    private val REQUEST_CODE_OVERLAY_PERMISSION = 101
    private val CORRECT_PASSWORD = "IfYouWantYouCanSleep"
    private val INITIAL_PASSWORD_ENTRY_TIME_SECONDS = 30 // Reverted to 30 seconds
    private val BLOCKED_TIME_SECONDS = 120
    private val STARTUP_BLOCKED_TIME_SECONDS = 90

    private enum class TimerState { StartupBlocked, InitialEntry, Blocked, Idle }
    private var timerState = TimerState.StartupBlocked
    private var alarmId = -1

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
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        )

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

        timerState = TimerState.StartupBlocked

        Handler(Looper.getMainLooper()).postDelayed({
            checkOverlayPermission()
        }, 5000)

        setContent {
            AlarmTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { paddingValues ->
                    AlarmUI(modifier = Modifier.padding(paddingValues), alarmId)
                }
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

        val focusRequester = remember { FocusRequester() }
        val keyboardController = LocalSoftwareKeyboardController.current
        val isPasswordInputEnabled = timerState == TimerState.InitialEntry

        LaunchedEffect(timerState) {
            when (timerState) {
                TimerState.StartupBlocked -> {
                    remainingTime = STARTUP_BLOCKED_TIME_SECONDS
                    while (remainingTime > 0 && timerState == TimerState.StartupBlocked) {
                        delay(1000)
                        remainingTime--
                    }
                    if (timerState == TimerState.StartupBlocked) timerState = TimerState.InitialEntry
                }
                TimerState.InitialEntry -> {
                    remainingTime = INITIAL_PASSWORD_ENTRY_TIME_SECONDS
                    while (remainingTime > 0 && timerState == TimerState.InitialEntry) {
                        delay(1000)
                        remainingTime--
                    }
                    if (timerState == TimerState.InitialEntry && passwordInput != CORRECT_PASSWORD)
                        timerState = TimerState.Blocked
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

        Column(
            modifier = modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("⏰ Alarm Ringing!")
            Spacer(Modifier.height(16.dp))
            Text("Password Hint: $CORRECT_PASSWORD", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = passwordInput,
                onValueChange = {
                    if (isPasswordInputEnabled) {
                        passwordInput = it
                        showPasswordError = false
                    }
                },
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(autoCorrect = false, keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                enabled = isPasswordInputEnabled
            )

            when {
                showPasswordError -> Text("Incorrect password", color = Color.Red)
                timerState == TimerState.Blocked || timerState == TimerState.StartupBlocked -> Text(
                    text = if (timerState == TimerState.StartupBlocked)
                        "Startup blocked. Wait $remainingTime sec."
                    else
                        "Input blocked. Try again in $remainingTime sec.",
                    color = Color.Red
                )
                timerState == TimerState.InitialEntry -> Text("Enter password. Remaining time: $remainingTime sec.")
            }

            Spacer(Modifier.height(16.dp))
            Button(onClick = {
                if (passwordInput == CORRECT_PASSWORD) {
                    try {
                        timerState = TimerState.Idle
                        val serviceIntent = Intent(this@AlarmActivity, AlarmForegroundService::class.java).apply {
                            action = Constants.ACTION_STOP_FOREGROUND_SERVICE
                            putExtra(AlarmReceiver.ALARM_ID, alarmId)
                        }
                        startService(serviceIntent)

                        val alarmStorage = AlarmStorage(applicationContext)
                        val alarms = alarmStorage.getAlarms()
                        val alarm = alarms.find { it.id == alarmId }

                        if (alarm != null && alarm.days.isNullOrEmpty()) {
                            val updatedAlarm = alarm.copy(isEnabled = false)
                            alarmStorage.updateAlarm(updatedAlarm)
                            val alarmScheduler = AndroidAlarmScheduler(applicationContext)
                            alarmScheduler.cancel(updatedAlarm)
                            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
                        }

                        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 100)
                    } catch (e: Exception) {
                        // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
                        showPasswordError = true
                    }
                } else showPasswordError = true
            }, enabled = timerState == TimerState.InitialEntry) {
                Text("Dismiss")
            }
        }
    }

    override fun onBackPressed() {
        // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        relaunchSelf()
    }

    override fun onPause() {
        super.onPause()
        if (!isFinishing) {
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
            relaunchSelf()
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
    }

    private fun relaunchSelf() {
        try {
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
            val intent = Intent(this, AlarmActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                putExtra(AlarmReceiver.ALARM_ID, alarmId)
            }
            startActivity(intent)
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
        } catch (e: Exception) {
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
        }
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
