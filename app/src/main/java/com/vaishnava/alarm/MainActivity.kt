@file:Suppress(
    "DEPRECATION",              // GoogleSignIn, GoogleSignInClient
    "UNUSED_VARIABLE",          // sharedPrefs, ringtoneUriString, amPm, etc.
    "UNUSED_PARAMETER",         // cancelAlarmCallback
    "UNUSED_ANONYMOUS_PARAMETER",
    "NAME_SHADOWING",           // shadowed context/uriString/numeric
    "UNNECESSARY_SAFE_CALL",    // safe calls on non-null types
    "KotlinConstantConditions"   // Elvis operator always returns left operand
)

package com.vaishnava.alarm
import com.vaishnava.alarm.CloudBackupControls
import com.vaishnava.alarm.data.resolveRingtoneTitle
import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import com.vaishnava.alarm.DirectBootRestoreReceiver
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import android.content.ComponentName
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AlarmAdd
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import com.vaishnava.alarm.ui.AutoSizeText
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.FloatingActionButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color.Companion
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.whileSelect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Task
import com.vaishnava.alarm.data.Alarm
import com.vaishnava.alarm.ui.theme.AlarmTheme
import com.vaishnava.alarm.sequencer.MissionSequencer
import com.vaishnava.alarm.sequencer.MissionLogger
import com.vaishnava.alarm.sequencer.MissionSpec
import java.util.Calendar

class MainActivity : BaseActivity() {

    private lateinit var alarmStorage: AlarmStorage
    private lateinit var alarmScheduler: AlarmScheduler
    lateinit var missionSequencer: MissionSequencer
    private val alarmPermissionRequestCode = 1001
    private val batteryOptimizationRequestCode = 1002
    private val scheduleExactAlarmRequestCode = 1003
    private val RC_SIGN_IN = 9001
    
    // In-app logging state
    private val sequencerLogs = mutableStateListOf<String>()
    private val showSequencerLogs = mutableStateOf(false)
    
    // Alarm refresh trigger for UI recomposition
    private val alarmRefreshTrigger = mutableStateOf(0)
    
    companion object {
        private var instance: MainActivity? = null

        fun getInstance(): MainActivity? = instance

        fun getMissionSequencer(): MissionSequencer? {
            return instance?.missionSequencer
        }
    }
    fun addSequencerLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        sequencerLogs.add("[$timestamp] $message")
        // Keep only last 50 logs
        if (sequencerLogs.size > 50) {
            sequencerLogs.removeAt(0)
        }
        // Also write to internal log file
        try {
            val fullTimestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            val logFile = java.io.File(getInstance()?.filesDir, "alarm_debug.log")
            val writer = java.io.FileWriter(logFile, true)
            writer.append("[$fullTimestamp] [UI] $message\n")
            writer.close()
        } catch (e: Exception) {
            // Silently fail if we can't write to storage
        }
    }

    private lateinit var mGoogleSignInClient: GoogleSignInClient

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }

        if (allGranted) {
            Log.d("AlarmApp", "All alarm permissions granted")
        } else {
            Log.e("AlarmApp", "Some alarm permissions denied")
        }
    }

    override fun onResume() {
        super.onResume()
        // Try to restore again in case sign-in just completed
        tryRestoreFromCloud()
        
        // Ensure sequencer is running and restored
        missionSequencer.let { sequencer ->
            if (sequencer.getQueueSize() > 0 && !sequencer.isMissionRunning()) {
                MissionLogger.log("MAIN_ACTIVITY_RESUME: queue exists but no mission running, ensuring continuation")
                sequencer.scope.launch {
                    delay(500) // Brief delay to allow any pending broadcasts
                    if (!sequencer.isMissionRunning() && sequencer.getQueueSize() > 0) {
                        MissionLogger.log("MAIN_ACTIVITY_FORCE_CONTINUE: forcing queue processing")
                        sequencer.processQueue()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Clear the force_restart_in_progress flag when the app fully starts
        val dpsContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            createDeviceProtectedStorageContext() ?: this
        } else this
        val sharedPrefs = dpsContext.getSharedPreferences(DirectBootRestoreReceiver.PREFS_NAME, Context.MODE_PRIVATE)
        try {
            dpsContext.deleteFile(DirectBootRestoreReceiver.FORCE_RESTART_FLAG_FILE)
            Log.d("AlarmApp", "MainActivity: FORCE_RESTART_FLAG_FILE deleted.")
        } catch (e: Exception) {
            Log.e("AlarmApp", "MainActivity: Failed to delete FORCE_RESTART_FLAG_FILE: ${e.message}")
        }

        alarmStorage = AlarmStorage(this)
        alarmScheduler = AndroidAlarmScheduler(this)
        missionSequencer = MissionSequencer(this)
        
        // Set static instance for AlarmActivity access
        instance = this
        
        // Start in-app log capture
        addSequencerLog("MissionSequencer initialized")
        addSequencerLog("Queue size: ${missionSequencer.getQueueSize()}")
        addSequencerLog("Is mission running: ${missionSequencer.isMissionRunning()}")

        // Log signing fingerprints in debug builds to help configure Firebase
        if (BuildConfig.DEBUG) {
            logSigningFingerprints()
        }

        // Attempt to restore alarms from Google Drive appData if user is signed in
        tryRestoreFromCloud()

        // Configure Google Sign In
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .build()

        mGoogleSignInClient = GoogleSignIn.getClient(this, gso)

        // Permissions will be handled manually by user through permission button
        // Removed automatic permission requests

        // Start a background task to check for upcoming alarms
        startAlarmNotificationChecker()

        // Register broadcast receiver for clearing notifications
        val clearFilter = IntentFilter("com.vaishnava.alarm.CLEAR_NOTIFICATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(clearNotificationReceiver, clearFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(clearNotificationReceiver, clearFilter)
        }

        // Register broadcast receiver for alarm refresh signals
        val refreshFilter = IntentFilter("com.vaishnava.alarm.REFRESH_ALARMS")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(alarmRefreshReceiver, refreshFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(alarmRefreshReceiver, refreshFilter)
        }

        setContent {
            AlarmTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    AlarmScreenContent(
                        alarmStorage = alarmStorage,
                        alarmScheduler = alarmScheduler,
                        googleSignInClient = mGoogleSignInClient,
                        scheduleAlarmCallback = { hour, minute, ringtoneUri, days, missionType, missionPassword, wakeCheckEnabled, wakeCheckMinutes, isProtected ->
                            val alarmId = alarmStorage.getNextAlarmId()
                            Log.d("AlarmApp", "Creating alarm with ID: $alarmId")
                            Log.d("AlarmApp", "Alarm details - Hour: $hour, Minute: $minute, Ringtone: $ringtoneUri, Days: $days, Protected: $isProtected")

                            val finalDays = if (days.isEmpty()) null else days.toList()

                            val alarm = com.vaishnava.alarm.data.Alarm(
                                id = alarmId,
                                hour = hour,
                                minute = minute,
                                isEnabled = true,
                                ringtoneUri = ringtoneUri,
                                days = finalDays,
                                isProtected = isProtected,
                                missionType = missionType,
                                missionPassword = missionPassword,
                                wakeCheckEnabled = wakeCheckEnabled,
                                wakeCheckMinutes = wakeCheckMinutes,
                                repeatDaily = finalDays != null && finalDays.size == 7,
                                timeChangeUsed = false, // Initialize as not used
                                createdTime = System.currentTimeMillis() // Track creation time
                            )
                            Log.d("AlarmApp", "Created alarm object with ID: ${alarm.id}")
                            
                            // CRITICAL: Check exact alarm permission before scheduling (Android 15+ fix)
                            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                            val canScheduleExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                try {
                                    alarmManager.canScheduleExactAlarms()
                                } catch (e: Exception) {
                                    Log.e("AlarmApp", "Error checking exact alarm permission", e)
                                    false
                                }
                            } else {
                                true
                            }
                            
                            if (!canScheduleExact) {
                                Log.e("AlarmApp", "❌ EXACT ALARM PERMISSION DENIED - Cannot schedule alarm ID: ${alarm.id}")
                                Toast.makeText(
                                    this@MainActivity,
                                    "❌ Alarm permission required! Go to Settings → Apps → Special app access → Alarms & Reminders",
                                    Toast.LENGTH_LONG
                                ).show()
                                
                                // Still save the alarm but don't schedule it
                                alarmStorage.addAlarm(alarm)
                                Log.w("AlarmApp", "Alarm saved but not scheduled due to missing permission")
                            } else {
                                // Permission granted - proceed with scheduling
                                alarmStorage.addAlarm(alarm)
                                try {
                                    alarmScheduler.schedule(alarm)
                                    Log.d("AlarmApp", "✅ Alarm scheduled successfully: ID ${alarm.id}")
                                } catch (e: SecurityException) {
                                    Log.e("AlarmApp", "❌ SecurityException scheduling alarm: ${e.message}", e)
                                    Toast.makeText(
                                        this@MainActivity,
                                        "❌ Alarm permission denied. Please enable Alarms & Reminders permission.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                } catch (e: Exception) {
                                    Log.e("AlarmApp", "❌ Failed to schedule alarm: ${e.message}", e)
                                    Toast.makeText(
                                        this@MainActivity,
                                        "❌ Failed to schedule alarm. Please try again.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }

                            // Remove the immediate notification when scheduling an alarm
                            // Notifications should only show in the last minute before the alarm triggers
                            // showNotification(
                            //     "Alarm Scheduled",
                            //     "Your alarm is set for ${formatTime12Hour(hour, minute)}"
                            // )

                            alarm
                        },
                        cancelAlarmCallback = { alarmId ->
                            val alarm = alarmStorage.getAlarms().find { it.id == alarmId }
                            alarm?.let {
                                alarmStorage.deleteAlarm(alarmId)
                                alarmScheduler.cancel(it)
                            }
                        },
                        alarmRefreshTrigger = alarmRefreshTrigger
                    )
                }

                // Permissions button is shown inside AlarmScreenContent where its state lives
            }
            
            // Floating debug button and log overlay
            Box(modifier = Modifier.fillMaxSize()) {
                // Debug log toggle button - moved to top
                FloatingActionButton(
                    onClick = {
                        val hasQueue = missionSequencer.getQueueSize() > 0
                        val hasLogs = sequencerLogs.isNotEmpty()
                        if (!hasQueue && !hasLogs) {
                            Toast.makeText(
                                this@MainActivity,
                                "No sequencer missions/logs yet",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            addSequencerLog("UI_LOG_TOGGLE: opened=${!showSequencerLogs.value} queueSize=${missionSequencer.getQueueSize()} running=${missionSequencer.isMissionRunning()} currentMissionId=${missionSequencer.getCurrentMission()?.id ?: "<none>"}")
                            showSequencerLogs.value = !showSequencerLogs.value
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .size(48.dp),
                    containerColor = Color(0xFF424242),
                    contentColor = Color.White
                ) {
                    Text("LOG", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                
                // Log overlay
                if (showSequencerLogs.value) {
                    Card(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth(0.9f)
                            .fillMaxHeight(0.7f),
                        colors = CardDefaults.cardColors(
                            containerColor = Color.Black.copy(alpha = 0.9f)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Sequencer Logs",
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            // Share only the currently recorded logs (in-memory list)
                                            val text = if (sequencerLogs.isEmpty()) {
                                                "No sequencer logs to share."
                                            } else {
                                                sequencerLogs.joinToString("\n")
                                            }
                                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_TEXT, text)
                                            }
                                            val shareIntent = Intent.createChooser(sendIntent, "Share Sequencer Logs")
                                            startActivity(shareIntent)
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFF424242)
                                        )
                                    ) {
                                        Text("Share", color = Color.White)
                                    }

                                    Button(
                                        onClick = { 
                                            showSequencerLogs.value = false
                                            sequencerLogs.clear()
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFF424242)
                                        )
                                    ) {
                                        Text("Clear", color = Color.White)
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            // Current status
                            Text(
                                "Queue Size: ${missionSequencer.getQueueSize()} | Running: ${missionSequencer.isMissionRunning()}",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            // Manual controls for debugging
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { 
                                        addSequencerLog("UI_START_QUEUE_CLICK: userRequestedStart queueSizeBefore=${missionSequencer.getQueueSize()} runningBefore=${missionSequencer.isMissionRunning()} currentMissionId=${missionSequencer.getCurrentMission()?.id ?: "<none>"}")
                                        missionSequencer.processQueue()
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color.Blue
                                    )
                                ) {
                                    Text("Start Queue", color = Color.White, fontSize = 10.sp)
                                }
                                
                                Button(
                                    onClick = { 
                                        val size = missionSequencer.getQueueSize()
                                        val running = missionSequencer.isMissionRunning()
                                        val currentId = missionSequencer.getCurrentMission()?.id ?: "<none>"
                                        addSequencerLog("UI_STATUS_CLICK: queueSize=$size running=$running currentMissionId=$currentId")
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF424242)
                                    )
                                ) {
                                    Text("Status", color = Color.White, fontSize = 10.sp)
                                }
                                
                                Button(
                                    onClick = { 
                                        addSequencerLog("UI_ADVANCE_CLICK: userRequestedAdvance queueSizeBefore=${missionSequencer.getQueueSize()} runningBefore=${missionSequencer.isMissionRunning()} currentMissionId=${missionSequencer.getCurrentMission()?.id ?: "<none>"}")
                                        missionSequencer.scope.launch {
                                            missionSequencer.processQueue()
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFFF9800) // Orange
                                    )
                                ) {
                                    Text("Advance", color = Color.White, fontSize = 10.sp)
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            // Logs
                            LazyColumn(
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(sequencerLogs) { log ->
                                    Text(
                                        log,
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            // Handle result from GoogleSignInActivity
            data?.let { intent ->
                if (intent.getBooleanExtra("alarms_restored", false)) {
                    val count = intent.getIntExtra("alarms_count", 0)
                    Toast.makeText(this, "Alarm list refreshed with $count restored alarms", Toast.LENGTH_SHORT).show()
                    
                    // Refresh the UI by triggering a recomposition
                    // The alarm list will automatically refresh since it reads from alarmStorage.getAlarms()
                }
            }
        }
    }

    private fun requestNecessaryPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WAKE_LOCK
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.WAKE_LOCK)
        }

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECEIVE_BOOT_COMPLETED
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.RECEIVE_BOOT_COMPLETED)
        }

        // For Android 13+, we need POST_NOTIFICATIONS permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun promptExactAlarmIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val am = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                if (!am.canScheduleExactAlarms()) {
                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
            } catch (_: Exception) { }
        }
    }

    private fun promptIgnoreBatteryOptimizationsIfNeeded() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        } catch (_: Exception) { }
    }

    private fun promptAutoStartIfNeeded() {
        // Many OEMs gate auto-start under their own settings. Attempt best-effort intents.
        try {
            val prefs = getSharedPreferences("oem_prompts", Context.MODE_PRIVATE)
            if (prefs.getBoolean("auto_start_prompted_once", false)) {
                return
            }
            val manu = Build.MANUFACTURER.lowercase()
            val tried = when {
                manu.contains("xiaomi") -> {
                    // Redmi 13C (MIUI) often needs SecurityCenter + PowerKeeper pages
                    tryStart(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")) ||
                    tryStart(ComponentName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")) ||
                    tryStart(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")).also { started ->
                        if (!started) {
                            // Try explicit Intent with extras for HiddenAppsConfigActivity
                            val i = Intent().apply {
                                component = ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                                putExtra("package_name", packageName)
                                putExtra("package_label", getString(R.string.app_name))
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            tryStartIntent(i)
                        }
                    } ||
                    tryStart(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.PowerHideModeActivity")) ||
                    tryStart(ComponentName("com.miui.securitycenter", "com.miui.appmanager.AppManagerMainActivity")) ||
                    tryStartIntent(Intent("miui.intent.action.POWER_HIDE_MODE_APP_LIST"))
                }
                manu.contains("oppo") -> tryStart(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"))
                    || tryStart(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"))
                manu.contains("vivo") -> tryStart(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"))
                    || tryStart(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"))
                manu.contains("huawei") -> tryStart(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))
                manu.contains("oneplus") -> tryStart(ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"))
                else -> false
            }
            if (!tried) {
                // Fallback to app details where user can enable autostart/battery settings
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
                prefs.edit().putBoolean("auto_start_prompted_once", true).apply()
                return
            }
            // Mark as prompted so we don't nag repeatedly across launches
            prefs.edit().putBoolean("auto_start_prompted_once", true).apply()
        } catch (_: Exception) { }
    }

    private fun tryStart(cn: android.content.ComponentName): Boolean {
        return try {
            val intent = Intent().apply {
                component = cn
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun tryStartIntent(intent: Intent): Boolean {
        return try {
            startActivity(intent)
            true
        } catch (_: Exception) { false }
    }

    private fun startAlarmNotificationChecker() {
        // Check more frequently to ensure we catch the 1-minute window
        Thread {
            while (true) {
                try {
                    Thread.sleep(2000) // Check every 2 seconds for even better precision
                    checkAndShowUpcomingAlarmNotification()

                    // Also check if we need to reschedule any daily alarms
                    rescheduleDailyAlarmsIfNeeded()
                } catch (e: InterruptedException) {
                    break
                }
            }
        }.start()
    }

    private fun rescheduleDailyAlarmsIfNeeded() {
        try {
            val now = Calendar.getInstance()
            val currentHour = now.get(Calendar.HOUR_OF_DAY)
            val currentMinute = now.get(Calendar.MINUTE)

            // If it's around midnight (between 12:00 AM and 12:05 AM), reschedule all daily alarms
            // This ensures daily alarms are properly set for the new day
            if (currentHour == 0 && currentMinute <= 5) {
                val alarms = alarmStorage.getAlarms().filter { it.isEnabled && !it.days.isNullOrEmpty() }
                alarms.forEach { alarm ->
                    // Reschedule the alarm to ensure it's set for the correct day
                    alarmScheduler.schedule(alarm)
                    Log.d("AlarmApp", "Rescheduled daily alarm ID: ${alarm.id} at ${alarm.hour}:${alarm.minute}")
                }
            }
        } catch (e: Exception) {
            Log.e("AlarmApp", "Error rescheduling daily alarms: ${e.message}", e)
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
    private fun checkAndShowUpcomingAlarmNotification() {
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
            Log.e("AlarmApp", "Error checking upcoming alarms: ${e.message}", e)
        }
    }

    // =====================================================
    // Helper (NEW) — compute millis until next occurrence
    // =====================================================
    private fun getMillisUntilNextAlarm(alarm: Alarm): Long {
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

    private fun getNextScheduledAlarm(): Alarm? {
        val alarms = alarmStorage.getAlarms().filter { it.isEnabled }
        if (alarms.isEmpty()) return null

        var nextAlarm: Alarm? = null
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

    private fun calculateNextAlarmTime(alarm: Alarm): Long {
        val now = Calendar.getInstance()
        val alarmCalendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, alarm.hour)
            set(Calendar.MINUTE, alarm.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (!alarm.days.isNullOrEmpty()) {
            val currentDay = now.get(Calendar.DAY_OF_WEEK)
            val currentHour = now.get(Calendar.HOUR_OF_DAY)
            val currentMinute = now.get(Calendar.MINUTE)

            // Check if today is a valid day and the alarm time hasn't passed yet
            if (alarm.days.contains(currentDay)) {
                if (alarm.hour > currentHour ||
                    (alarm.hour == currentHour && alarm.minute > currentMinute)) {
                    return alarmCalendar.timeInMillis
                }
            }

            // Look for the next valid day
            for (i in 1..7) {
                val checkDay = (currentDay + i - 1) % 7 + 1
                if (alarm.days.contains(checkDay)) {
                    alarmCalendar.add(Calendar.DAY_OF_YEAR, i)
                    return alarmCalendar.timeInMillis
                }
            }
        } else {
            // For one-time alarms (including the protected 5 AM alarm),
            // if the time has passed today, schedule for tomorrow
            if (alarmCalendar.timeInMillis <= now.timeInMillis) {
                alarmCalendar.add(Calendar.DAY_OF_YEAR, 1)
            }
            return alarmCalendar.timeInMillis
        }

        // Fallback - add one day
        alarmCalendar.add(Calendar.DAY_OF_YEAR, 1)
        return alarmCalendar.timeInMillis
    }

    private fun getTimeUntilNextAlarmInMinutes(alarm: Alarm): Double {
        val nextAlarmTime = calculateNextAlarmTime(alarm)
        val now = System.currentTimeMillis()
        val diffInMillis = nextAlarmTime - now
        return diffInMillis.toDouble() / (1000.0 * 60.0)
    }

    private fun showNotification(title: String, content: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

        // Use a fresh channel ID to avoid previously muted channel settings on the device
        val channelId = "next_alarm_conv_v2"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                "Next Alarm Alerts",
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Shows information about your next alarm"
                enableVibration(true)
                enableLights(true)
                setShowBadge(true)
                // Let users enable bubbles (Android 11+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try { setAllowBubbles(true) } catch (_: Exception) { }
                }
                // Explicitly set a default notification sound
                try {
                    val soundUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                    val attrs = android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    setSound(soundUri, attrs)
                } catch (_: Exception) { }
            }
            notificationManager.createNotificationChannel(channel)
        }

        // No full-screen intent for WhatsApp-like conversation behavior

        val now = System.currentTimeMillis()
        val me = androidx.core.app.Person.Builder()
            .setName("Alarm")
            .setImportant(true)
            .build()
        val messaging = androidx.core.app.NotificationCompat.MessagingStyle(me)
            .addMessage(content, now, me)
            .setConversationTitle("Next Alarm")
            .setGroupConversation(false)

        // Ensure a dynamic shortcut exists for this conversation id so the
        // system can rank it under Conversations like WhatsApp.
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N_MR1) {
                val sm = getSystemService(android.content.pm.ShortcutManager::class.java)
                val b = android.content.pm.ShortcutInfo.Builder(this, "next_alarm_conv")
                    .setShortLabel("Next Alarm")
                    .setLongLabel("Next Alarm")
                    .setIntent(android.content.Intent(this, MainActivity::class.java).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        putExtra("from_next_alarm_notice", true)
                    })
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    try { b.setLongLived(true) } catch (_: Exception) { }
                }
                val shortcut = b.build()
                try { sm?.addDynamicShortcuts(java.util.Collections.singletonList(shortcut)) } catch (_: Exception) { }
            }
        } catch (_: Exception) { }

        val notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(android.graphics.Color.parseColor("#1976D2"))
            .setContentTitle(title)
            .setContentText(content)
            .setWhen(now)
            .setShowWhen(true)
            .setStyle(messaging)
            .setShortcutId("next_alarm_conv")
            .setLocusId(androidx.core.content.LocusIdCompat("next_alarm_conv"))
            .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(androidx.core.app.NotificationCompat.DEFAULT_ALL)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()

        // Post as a standard conversation notification so MIUI places it in Conversations at the top.
        notificationManager.notify(2, notification)
    }

    private fun clearNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.cancel(2)
    }

    private fun scheduleProtectedDefaultAlarm() {
        val defaultAlarmHour = 5
        val defaultAlarmMinute = 0
        val glossyId = resources.getIdentifier("glassy_bell", "raw", packageName)
        val pyaroId = resources.getIdentifier("pyaro_vrindavan", "raw", packageName)

        val defaultRingtoneUri = when {
            pyaroId != 0 -> Uri.parse("android.resource://${packageName}/$pyaroId")
            glossyId != 0 -> Uri.parse("android.resource://${packageName}/$glossyId")
            else -> Uri.parse("android.resource://${packageName}/raw/glassy_bell")
        }

        val defaultDays = listOf(
            Calendar.SUNDAY,
            Calendar.MONDAY,
            Calendar.TUESDAY,
            Calendar.WEDNESDAY,
            Calendar.THURSDAY,
            Calendar.FRIDAY,
            Calendar.SATURDAY
        )

        val existingDefaultAlarm = alarmStorage.getAlarms().firstOrNull {
            it.hour == defaultAlarmHour &&
                    it.minute == defaultAlarmMinute &&
                    it.ringtoneUri == defaultRingtoneUri &&
                    it.days?.containsAll(defaultDays) == true &&
                    it.isProtected
        }

        if (existingDefaultAlarm == null) {
            val alarmId = alarmStorage.getNextAlarmId()
            val protectedDefaultAlarm = Alarm(
                id = alarmId,
                hour = defaultAlarmHour,
                minute = defaultAlarmMinute,
                isEnabled = true,
                ringtoneUri = defaultRingtoneUri,
                days = defaultDays,
                isHidden = false,
                isProtected = true
            )
            alarmStorage.addAlarm(protectedDefaultAlarm)
            alarmScheduler.schedule(protectedDefaultAlarm)
            markAlarmSet(true)
            Log.d("AlarmApp", "✅ Protected default 5 AM alarm scheduled")
        } else {
            Log.d("AlarmApp", "ℹ️ Protected default 5 AM alarm already exists.")
        }
    }

    private fun scheduleAndSaveAlarm(
        hour: Int,
        minute: Int,
        ringtoneUri: Uri?,
        days: Set<Int>
    ): Alarm {
        val alarmId = alarmStorage.getNextAlarmId()
        val ringtoneUriString = ringtoneUri?.toString()

        val alarm = Alarm(
            id = alarmId,
            hour = hour,
            minute = minute,
            isEnabled = true,
            ringtoneUri = ringtoneUri ?: Settings.System.DEFAULT_ALARM_ALERT_URI,
            days = days.toList(),
            isHidden = false,
            isProtected = false
        )
        alarmStorage.addAlarm(alarm)
        alarmScheduler.schedule(alarm)

        markAlarmSet(true)
        // Remove the notification update as we don't want to show notifications when alarms are set
        // updateNextAlarmNotification()
        return alarm
    }

    private fun cancelAlarm(alarmId: Int) {
        val alarm = alarmStorage.getAlarms().firstOrNull { it.id == alarmId }
        if (alarm != null && !alarm.isProtected) {
            alarmScheduler.cancel(alarm)
            alarmStorage.deleteAlarm(alarmId)
            markAlarmSet(false)
            Log.d("AlarmApp", "Alarm with ID $alarmId cancelled.")
            // Remove the notification update as we don't want to show notifications when alarms are set
            // updateNextAlarmNotification()
        }
    }

    public fun markAlarmSet(value: Boolean) {
        val prefs = getSharedPreferences("AlarmPrefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("alarm_set", value).apply()
    }
    private val clearNotificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.vaishnava.alarm.CLEAR_NOTIFICATION") {
                clearNotification()
            }
        }
    }

    private val alarmRefreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.vaishnava.alarm.REFRESH_ALARMS") {
                val restored = intent.getBooleanExtra("alarms_restored", false)
                val count = intent.getIntExtra("alarms_count", 0)
                
                if (restored) {
                    Toast.makeText(this@MainActivity, "Alarm list refreshed with $count restored alarms", Toast.LENGTH_SHORT).show()
                    
                    // Trigger UI refresh by incrementing the refresh trigger
                    // This will force recomposition of the alarm list
                    alarmRefreshTrigger.value++
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(clearNotificationReceiver)
        } catch (e: Exception) {
            // Ignore if receiver was not registered
        }
        try {
            unregisterReceiver(alarmRefreshReceiver)
        } catch (e: Exception) {
            // Ignore if receiver was not registered
        }
        
        // Cleanup mission sequencer
        if (::missionSequencer.isInitialized) {
            missionSequencer.destroy()
        }
        
        // Cleanup completed
    }

    private fun tryRestoreFromCloud() {
        // Auto-restore disabled: backups should only be restored from explicit user action
        // via the "Restore from Drive" button in CloudBackupControls.
        // Intentionally left empty to avoid modifying alarms silently on startup.
    }

    private fun logSigningFingerprints() {
        try {
            val pm = packageManager
            val pkg = packageName
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
                val current = info.signingInfo?.apkContentsSigners
                val history = info.signingInfo?.signingCertificateHistory
                val signatures = current ?: history
                signatures?.forEach { sig ->
                    val sha1 = java.security.MessageDigest.getInstance("SHA-1").digest(sig.toByteArray())
                    val sha256 = java.security.MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
                    fun ByteArray.hex() = joinToString(":") { "%02X".format(it) }
                    Log.i("AppSHA", "SHA-1: ${sha1.hex()}")
                    Log.i("AppSHA", "SHA-256: ${sha256.hex()}")
                } ?: run {
                    Log.w("AppSHA", "No signing signatures available")
                }
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                info.signatures?.forEach { sig ->
                    val sha1 = java.security.MessageDigest.getInstance("SHA-1").digest(sig.toByteArray())
                    val sha256 = java.security.MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
                    fun ByteArray.hex() = joinToString(":") { "%02X".format(it) }
                    Log.i("AppSHA", "SHA-1: ${sha1.hex()}")
                    Log.i("AppSHA", "SHA-256: ${sha256.hex()}")
                } ?: run {
                    Log.w("AppSHA", "No legacy signatures available")
                }
            }
        } catch (e: Exception) {
            Log.e("AppSHA", "Failed to compute fingerprints", e)
        }
    }
}

fun formatDays(days: List<Int>?): String {
    if (days.isNullOrEmpty()) return "No days selected"
    
    // Check if all 7 days are selected
    val allDays = setOf(Calendar.SUNDAY, Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY)
    if (days.toSet() == allDays) return "Everyday"
    
    val dayMap = mapOf(
        Calendar.SUNDAY to "Su",
        Calendar.MONDAY to "Mo",
        Calendar.TUESDAY to "Tu",
        Calendar.WEDNESDAY to "We",
        Calendar.THURSDAY to "Th",
        Calendar.FRIDAY to "Fr",
        Calendar.SATURDAY to "Sa"
    )
    return days.sorted().joinToString(", ") { dayMap[it] ?: "" }
}

fun formatTime12Hour(hour: Int, minute: Int): String {
    val period = if (hour >= 12) "PM" else "AM"
    val formattedHour = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    return String.format("%d:%02d %s", formattedHour, minute, period)
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AlarmScreenContent(
    alarmStorage: AlarmStorage,
    alarmScheduler: AlarmScheduler,
    googleSignInClient: GoogleSignInClient,
    scheduleAlarmCallback: (Int, Int, Uri?, Set<Int>, String?, String?, Boolean, Int, Boolean) -> Alarm,
    cancelAlarmCallback: (Int) -> Unit,
    alarmRefreshTrigger: androidx.compose.runtime.State<Int>
) {
    val context = LocalContext.current
    val alarms = remember { mutableStateListOf<Alarm>() }
    val showAddDialog = remember { mutableStateOf(false) }
    val showDiagnosticsDialog = remember { mutableStateOf(false) }
    val showPermissionsDialog = remember { mutableStateOf(false) }
    val showQueueMissionsDialog = remember { mutableStateOf(false) }
    val showPermissionsButton = remember { mutableStateOf(true) } // Start visible, hide only after manual permission check

    fun recomputePermissionsVisibility() {
        try {
            val appCtx = context.applicationContext

            // Runtime permissions that are critical for alarms
            val wakeLockOk = ContextCompat.checkSelfPermission(appCtx, Manifest.permission.WAKE_LOCK) == PackageManager.PERMISSION_GRANTED
            val bootOk = ContextCompat.checkSelfPermission(appCtx, Manifest.permission.RECEIVE_BOOT_COMPLETED) == PackageManager.PERMISSION_GRANTED
            val postNotifOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(appCtx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }

            // Power / alarm capabilities
            val pm = appCtx.getSystemService(android.os.PowerManager::class.java)
            val am = appCtx.getSystemService(android.app.AlarmManager::class.java)
            val exactOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try { 
                    // Check if USE_EXACT_ALARM is available (granted automatically for alarm apps)
                    // or if SCHEDULE_EXACT_ALARM is granted
                    am?.canScheduleExactAlarms() == true
                } catch (_: Exception) { false }
            } else true
            val batteryOk = try { pm?.isIgnoringBatteryOptimizations(appCtx.packageName) == true } catch (_: Exception) { false }
            val notifOk = try { NotificationManagerCompat.from(appCtx).areNotificationsEnabled() } catch (_: Exception) { false }

            // Modify system settings (WRITE_SETTINGS) – used for system volume control
            val writeSettingsOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try { Settings.System.canWrite(appCtx) } catch (_: Exception) { false }
            } else {
                true
            }

            // Overlay permission (shown in dialog, treat as required for hiding the button)
            val overlayOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try { Settings.canDrawOverlays(appCtx) } catch (_: Exception) { false }
            } else {
                true
            }

            // Check if device admin is actually granted
            val deviceAdminComponent = android.content.ComponentName(appCtx, MyDeviceAdminReceiver::class.java)
            val devicePolicyManager = appCtx.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val deviceAdminOk = devicePolicyManager.isAdminActive(deviceAdminComponent)

            // Hide button only when all permissions are granted (but only checked manually)
            val allOk = wakeLockOk && bootOk && postNotifOk && exactOk && batteryOk && notifOk && overlayOk && deviceAdminOk && writeSettingsOk
            showPermissionsButton.value = !allOk
        } catch (_: Exception) {
            // On any unexpected error, keep the button visible so the user can manually review.
            showPermissionsButton.value = true
        }
    }

    // Check permissions automatically on app start and resume (but don't auto-request)
    LaunchedEffect(Unit) { 
        recomputePermissionsVisibility() 
    }
    
    // Also check when app resumes
    val lifecycleOwner2 = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner2) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) recomputePermissionsVisibility()
        }
        lifecycleOwner2.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner2.lifecycle.removeObserver(obs) }
    }

    // We'll keep a simple state for hour/minute (same logic preserved)
    val timeHour = remember { mutableStateOf(7) }
    val timeMinute = remember { mutableStateOf(0) }

    val selectedDaysState: MutableState<Set<Int>> = remember { mutableStateOf(setOf<Int>()) }
    val selectedRingtoneUriState: MutableState<Uri?> = remember { mutableStateOf(null) }
    val mediaPlayerState: MutableState<MediaPlayer?> = remember { mutableStateOf(null) }
    val showRingtonePicker = remember { mutableStateOf(false) }
    // Wake-up check (per-alarm)
    val wakeCheckEnabledState = remember { mutableStateOf(false) }
    val wakeCheckMinutesState = remember { mutableStateOf(5) }
    // Protected alarm state
    val protectedAlarmState = remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        alarms.clear()
        alarms.addAll(alarmStorage.getAlarms())
    }

    // Reload list when refresh trigger changes (e.g., after cloud restore)
    LaunchedEffect(alarmRefreshTrigger.value) {
        alarms.clear()
        alarms.addAll(alarmStorage.getAlarms())
    }

    // Reload list when activity resumes (e.g., after granting Drive consent and auto-restore)
    val listLifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(listLifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                alarms.clear()
                alarms.addAll(alarmStorage.getAlarms())
            }
        }
        listLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { listLifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(Unit) {
        onDispose {
            mediaPlayerState.value?.stop()
            mediaPlayerState.value?.release()
            mediaPlayerState.value = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Replace the alarm icon with the app logo using Image composable
                // Use the webp file directly from mipmap
                Image(
                    painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                    contentDescription = "App Logo",
                    modifier = Modifier.size(72.dp)
                )

                Spacer(Modifier.height(12.dp))

                Text(
                    text = "CHRONA",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "Precision Time Management",
                    fontSize = 14.sp,
                    color = Color(0xFF64FFDA),
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(12.dp))

                // Google Sign In Button
                val accountState = remember { mutableStateOf(com.google.android.gms.auth.api.signin.GoogleSignIn.getLastSignedInAccount(context)) }
                LaunchedEffect(Unit) {
                    accountState.value = com.google.android.gms.auth.api.signin.GoogleSignIn.getLastSignedInAccount(context)
                }
                // Auto-sync every 2 seconds for instant updates
                LaunchedEffect(Unit) {
                    while (true) {
                        kotlinx.coroutines.delay(2000)
                        val currentAccount = com.google.android.gms.auth.api.signin.GoogleSignIn.getLastSignedInAccount(context)
                        if (accountState.value?.email != currentAccount?.email) {
                            accountState.value = currentAccount
                        }
                    }
                }
                val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                        if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                            accountState.value = com.google.android.gms.auth.api.signin.GoogleSignIn.getLastSignedInAccount(context)
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }
                val currentAccount = accountState.value
                if (currentAccount == null) {
                    Button(
                        onClick = {
                            try {
                                val intent = Intent(context, GoogleSignInActivity::class.java)
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(context, "Unable to open Google Sign-In: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF4285F4),
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .fillMaxWidth(0.8f)
                            .height(48.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color.White)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.googleg_standard_color_18),
                                    contentDescription = "Google Logo",
                                    tint = Color.Unspecified,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .align(Alignment.Center)
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Sign in with Google",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    // Show signed-in state
                    Column(
                        modifier = Modifier.fillMaxWidth(0.9f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Button(
                            onClick = {
                                try {
                                    val intent = Intent(context, GoogleSignInActivity::class.java)
                                    (context as? Activity)?.startActivityForResult(intent, 1001)
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(context, "Unable to open Cloud Backup: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF424242),
                                contentColor = Color.White
                            ),
                            modifier = Modifier
                                .fillMaxWidth(0.8f)
                                .height(48.dp)
                        ) {
                            Text(
                                text = "Signed in",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                if (showPermissionsButton.value) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { showPermissionsDialog.value = true },
                        modifier = Modifier.fillMaxWidth(0.9f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color.White
                        ),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.6f))
                    ) { Text("Permissions & Power Settings", color = Color.White) }
                }
            }

            // Alarms header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.List,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = "YOUR ALARMS",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Spacer(Modifier.weight(1f))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF1E1E1E)
                    )
                ) {
                    Text(
                        text = "${alarms.size} items",
                        fontSize = 12.sp,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            // alarms list
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (alarms.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = Color(0xFF1E1E1E)
                                ),
                                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(28.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.AlarmOff,
                                        contentDescription = "No Alarms",
                                        tint = Color.White,
                                        modifier = Modifier
                                            .size(56.dp)
                                            .padding(bottom = 12.dp)
                                    )
                                    Text(
                                        text = "No alarms set",
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        modifier = Modifier.padding(bottom = 6.dp)
                                    )
                                    Text(
                                        text = "Tap the + button to create your first alarm",
                                        fontSize = 14.sp,
                                        color = Color.White,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(bottom = 16.dp)
                                    )
                                    Button(
                                        onClick = {
                                            // Reset dialog states for new alarm
                                            selectedDaysState.value = emptySet()
                                            selectedRingtoneUriState.value = null
                                            wakeCheckEnabledState.value = false
                                            wakeCheckMinutesState.value = 5
                                            protectedAlarmState.value = false
                                            
                                            showAddDialog.value = true
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFF4285F4)
                                        )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Add,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier
                                                .size(18.dp)
                                                .padding(end = 8.dp)
                                        )
                                        Text(
                                            "Add Your First Alarm",
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    items(alarms.sortedBy { it.hour * 60 + it.minute }, key = { it.id }) { alarm ->
                        val context = LocalContext.current
                        AlarmItem(
                            alarm = alarm,
                            onRemove = { alarmToRemove ->
                                alarmScheduler.cancel(alarmToRemove)
                                alarmStorage.deleteAlarm(alarmToRemove.id)
                                alarms.remove(alarmToRemove)
                            },
                            onToggleEnable = { alarmToToggle, isEnabled ->
                                // Do not allow toggle while wake-up-check gate is active for this alarm
                                val dpsContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                    context.createDeviceProtectedStorageContext() ?: context
                                } else context
                                val prefs = dpsContext.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                                val gateActive = prefs.getBoolean("wakecheck_gate_active_${alarmToToggle.id}", false)
                                val pending = prefs.getBoolean("wakecheck_pending_${alarmToToggle.id}", false)
                                if (gateActive || pending) {
                                    return@AlarmItem
                                }

                                val updatedAlarm = alarmToToggle.copy(isEnabled = isEnabled)
                                alarmStorage.updateAlarm(updatedAlarm)
                                if (isEnabled) {
                                    alarmScheduler.schedule(updatedAlarm)
                                } else {
                                    alarmScheduler.cancel(updatedAlarm)
                                }
                                val index = alarms.indexOfFirst { it.id == updatedAlarm.id }
                                if (index != -1) {
                                    alarms[index] = updatedAlarm
                                }
                            },
                            onTimeEdit = { alarmToEdit, newHour, newMinute, newRingtoneUri, newDays, newMissionType, newMissionPassword, newIsProtected, newWakeCheckEnabled, newWakeCheckMinutes ->
                                // Update the alarm with all new values and mark time change as used
                                val updatedAlarm = alarmToEdit.copy(
                                    hour = newHour,
                                    minute = newMinute,
                                    ringtoneUri = newRingtoneUri,
                                    days = newDays?.toList(),
                                    missionType = newMissionType,
                                    missionPassword = newMissionPassword,
                                    isProtected = newIsProtected,
                                    wakeCheckEnabled = newWakeCheckEnabled,
                                    wakeCheckMinutes = newWakeCheckMinutes,
                                    repeatDaily = newDays?.size == 7,
                                    timeChangeUsed = true // Mark that time change was used
                                )
                                alarmStorage.updateAlarm(updatedAlarm)
                                alarmScheduler.schedule(updatedAlarm)
                                
                                // Update the alarm in the list
                                val index = alarms.indexOfFirst { it.id == updatedAlarm.id }
                                if (index != -1) {
                                    alarms[index] = updatedAlarm
                                }
                                
                                Toast.makeText(context, "Saved. Final", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                    item {
                        Spacer(modifier = Modifier.height(80.dp))
                    }
                }
            }
        }

        // FAB
        FloatingActionButton(
            onClick = {
                // Initialize dialog time from system state or defaults
                val now = Calendar.getInstance()
                // Set default to current time + 1 minute
                now.add(Calendar.MINUTE, 1)
                timeHour.value = now.get(Calendar.HOUR_OF_DAY)
                timeMinute.value = now.get(Calendar.MINUTE)
                
                // Reset dialog states for new alarm
                selectedDaysState.value = emptySet()
                selectedRingtoneUriState.value = null
                wakeCheckEnabledState.value = false
                wakeCheckMinutesState.value = 5
                protectedAlarmState.value = false
                
                showAddDialog.value = true
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .navigationBarsPadding(),
            containerColor = Color(0xFF424242),
            elevation = FloatingActionButtonDefaults.elevation(6.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "Add Alarm",
                tint = Color.White
            )
        }
    }

    // Add Alarm Dialog
    if (showAddDialog.value) {
        AddAlarmDialog(
            timeHour = timeHour,
            timeMinute = timeMinute,
            selectedDaysState = selectedDaysState,
            selectedRingtoneUriState = selectedRingtoneUriState,
            wakeCheckEnabledState = wakeCheckEnabledState,
            wakeCheckMinutesState = wakeCheckMinutesState,
            protectedAlarmState = protectedAlarmState,
            alarms = alarms,
            onDismissRequest = { showAddDialog.value = false },
            onConfirm = { hour, minute, ringtoneUri, days, missionType, missionPassword, isProtected ->
                scheduleAlarmCallback(
                    hour,
                    minute,
                    ringtoneUri,
                    days,
                    missionType,
                    missionPassword,
                    wakeCheckEnabledState.value,
                    wakeCheckMinutesState.value,
                    isProtected
                )
                showAddDialog.value = false
                // Refresh the alarms list
                alarms.clear()
                alarms.addAll(alarmStorage.getAlarms())
            },
            mediaPlayerState = mediaPlayerState,
            showRingtonePicker = showRingtonePicker
        )
    }

    if (showPermissionsDialog.value) {
        PermissionsCenterDialog(
            onDismiss = { showPermissionsDialog.value = false; recomputePermissionsVisibility() }
        )
    }

    if (showQueueMissionsDialog.value) {
        QueueMissionsDialog(
            onDismiss = { showQueueMissionsDialog.value = false },
            onEnqueue = { missionIds ->
                val mainActivity = context as MainActivity
                val specs = missionIds.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { missionId ->
                    MissionSpec(
                        id = missionId,
                        params = mapOf("mission_type" to missionId),
                        timeoutMs = 30000L,
                        retryCount = 3,
                        sticky = true,
                        retryDelayMs = 1000L
                    )
                }
                mainActivity.missionSequencer.enqueueAll(specs)
                // Immediate overlay-friendly logging after missions are added
                val ids = specs.joinToString(" , ") { it.id }
                val queueSize = mainActivity.missionSequencer.getQueueSize()
                val currentId = mainActivity.missionSequencer.getCurrentMission()?.id ?: "<none>"
                mainActivity.addSequencerLog("ENQUEUED_MISSIONS: [$ids] queueSize=$queueSize currentMissionId=$currentId")
                Toast.makeText(context, "Queued ${specs.size} missions", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
fun DayToggleButtons(selectedDays: Set<Int>, onDayToggle: (Int) -> Unit) {
    val days = listOf(
        Calendar.SUNDAY to "Su",
        Calendar.MONDAY to "Mo",
        Calendar.TUESDAY to "Tu",
        Calendar.WEDNESDAY to "We",
        Calendar.THURSDAY to "Th",
        Calendar.FRIDAY to "Fr",
        Calendar.SATURDAY to "Sa"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        days.forEach { (day, label) ->
            Card(
                modifier = Modifier
                    .size(36.dp)
                    .clickable { onDayToggle(day) },
                shape = CircleShape,
                colors = CardDefaults.cardColors(
                    containerColor = if (selectedDays.contains(day)) {
                        Color(0xFF424242)
                    } else {
                        Color(0xFF1E1E1E)
                    }
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (selectedDays.contains(day)) {
                            Color.White
                        } else {
                            Color.White
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun AlarmItem(
    alarm: Alarm,
    onRemove: (Alarm) -> Unit,
    onToggleEnable: (Alarm, Boolean) -> Unit,
    onTimeEdit: (Alarm, Int, Int, Uri?, Set<Int>?, String?, String?, Boolean, Boolean, Int) -> Unit = { _, _, _, _, _, _, _, _, _, _ -> }
) {
    val context = LocalContext.current
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }
    
    // Update current time every second to check edit button visibility
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000) // Update every second
            currentTime = System.currentTimeMillis()
        }
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = formatTime12Hour(alarm.hour, alarm.minute),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White
                )
                Text(
                    text = formatDays(alarm.days),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White
                )
                val indicator = remember(alarm.id, alarm.missionType, alarm.missionPassword) {
                    when {
                        alarm.missionType == "tap" -> "Tap"
                        alarm.missionType == "password" || (alarm.missionPassword?.isNotBlank() == true) -> "Pwd"
                        else -> null
                    }
                }
                if (indicator != null) {
                    Text(
                        text = indicator,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White
                    )
                }
                if (alarm.isProtected) {
                    Text(
                        text = "🛡️ Protected", 
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        fontWeight = FontWeight.Medium
                    )
                }
                
                // Check and show permission status for enabled alarms
                if (alarm.isEnabled) {
                    val hasPermission = remember {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                            try {
                                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                                alarmManager.canScheduleExactAlarms()
                            } catch (e: Exception) {
                                false
                            }
                        } else {
                            true
                        }
                    }
                    
                    if (!hasPermission) {
                        Text(
                            text = "⚠️ Permission Required", 
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFF9800), // Orange warning color
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                val ringtoneTitle = remember(alarm.ringtoneUri) {
                    resolveRingtoneTitle(context, alarm.ringtoneUri)
                }
                Text(
                    text = ringtoneTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Check permission status for switch styling
                val hasPermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    try {
                        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                        alarmManager.canScheduleExactAlarms()
                    } catch (e: Exception) {
                        false
                    }
                } else {
                    true
                }
                
                Switch(
                    checked = alarm.isEnabled,
                    onCheckedChange = { enabled -> 
                        if (alarm.isProtected && !enabled) {
                            // Prevent turning off protected alarm
                            android.widget.Toast.makeText(
                                context,
                                "Cannot turn off protected alarm",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            return@Switch
                        }
                        
                        // If enabling without permission, show guidance
                        if (enabled && !hasPermission) {
                            android.widget.Toast.makeText(
                                context,
                                "⚠️ Enable alarm permission first! Settings → Apps → Special app access → Alarms & Reminders",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                        
                        onToggleEnable(alarm, enabled) 
                    },
                    enabled = !alarm.isProtected || alarm.isEnabled, // Allow turning ON but not OFF for protected alarms
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = if (hasPermission) MaterialTheme.colorScheme.primary else Color(0xFFFF9800),
                        checkedTrackColor = if (hasPermission) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else Color(0xFFFF9800).copy(alpha = 0.5f),
                        uncheckedThumbColor = Color.Gray,
                        uncheckedTrackColor = Color.Gray.copy(alpha = 0.5f)
                    )
                )
                Spacer(Modifier.width(8.dp))
                
                // Edit button for protected alarms (one-time time change)
                if (alarm.isProtected && !alarm.timeChangeUsed) {
                    val timeSinceCreation = currentTime - alarm.createdTime
                    val timeWindowInMs = 5 * 60 * 1000L // 5 minutes for production use
                    val timeRemaining = (timeWindowInMs - timeSinceCreation) / 1000 // seconds remaining
                    
                    // Only show edit button within the time window of creation
                    if (timeSinceCreation < timeWindowInMs) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    // Open the full alarm dialog for editing
                                    showEditDialog = true
                                },
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(
                                        Color(0xFF424242),
                                        CircleShape
                                    )
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Edit,
                                    contentDescription = "Edit Time (One-time)",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            
                            // Show countdown timer
                            Text(
                                text = "${timeRemaining}s",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                            
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                }
                
                IconButton(
                    onClick = {
                        // Block delete if alarm is protected or wake-up-check is active
                        if (alarm.isProtected) {
                            android.widget.Toast.makeText(
                                context,
                                "Cannot delete protected alarm",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            return@IconButton
                        }
                        
                        // Block delete immediately when wake-up-check gate is active or pending
                        val dpsContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            context.createDeviceProtectedStorageContext() ?: context
                        } else context
                        val prefs = dpsContext.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                        val gateActive = prefs.getBoolean("wakecheck_gate_active_${alarm.id}", false)
                        val pending = prefs.getBoolean("wakecheck_pending_${alarm.id}", false)
                        if (gateActive || pending) {
                            android.widget.Toast.makeText(
                                context,
                                "Wake-up check active",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            showDeleteConfirm = true
                        }
                    },
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            MaterialTheme.colorScheme.errorContainer,
                            CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Delete Alarm",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (showDeleteConfirm) {
                AlertDialog(
                    onDismissRequest = { showDeleteConfirm = false },
                    title = { Text(text = "Delete alarm?", color = Color.White) },
                    text = { Text(text = "Confirm?", color = Color.White) },
                    confirmButton = {
                        TextButton(onClick = {
                            showDeleteConfirm = false
                            onRemove(alarm)
                        }) {
                            Text("Delete", color = Color.White)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteConfirm = false }) {
                            Text("Cancel", color = Color.White)
                        }
                    }
                )
            }

            if (showEditDialog) {
            AddAlarmDialog(
                timeHour = remember { mutableStateOf(alarm.hour) },
                timeMinute = remember { mutableStateOf(alarm.minute) },
                selectedDaysState = remember { mutableStateOf(alarm.days?.toSet() ?: emptySet()) },
                selectedRingtoneUriState = remember { mutableStateOf(alarm.ringtoneUri) },
                wakeCheckEnabledState = remember { mutableStateOf(alarm.wakeCheckEnabled) },
                wakeCheckMinutesState = remember { mutableStateOf(alarm.wakeCheckMinutes) },
                protectedAlarmState = remember { mutableStateOf(alarm.isProtected) },
                alarms = emptyList(), // Not needed for edit mode
                onDismissRequest = { showEditDialog = false },
                onConfirm = { hour, minute, ringtoneUri, days, missionType, missionPassword, isProtected ->
                    showEditDialog = false
                    onTimeEdit(alarm, hour, minute, ringtoneUri, days, missionType, missionPassword, isProtected, alarm.wakeCheckEnabled, alarm.wakeCheckMinutes)
                },
                mediaPlayerState = remember { mutableStateOf(null) },
                showRingtonePicker = remember { mutableStateOf(false) }
            )
        }

        }
    }
}

@Composable
fun AddAlarmDialog(
    timeHour: MutableState<Int>,
    timeMinute: MutableState<Int>,
    selectedDaysState: MutableState<Set<Int>>,
    selectedRingtoneUriState: MutableState<Uri?>,
    wakeCheckEnabledState: MutableState<Boolean>,
    wakeCheckMinutesState: MutableState<Int>,
    protectedAlarmState: MutableState<Boolean>,
    alarms: List<Alarm>,
    onDismissRequest: () -> Unit,
    onConfirm: (Int, Int, Uri?, Set<Int>, String?, String?, Boolean) -> Unit,
    mediaPlayerState: MutableState<MediaPlayer?>,
    showRingtonePicker: MutableState<Boolean>
) {
    val context = LocalContext.current
    // Move these calculations inside the Composable so they update correctly
    val displayHour = remember(timeHour.value) {
        if (timeHour.value == 0) 12 else if (timeHour.value > 12) timeHour.value - 12 else timeHour.value
    }
    val amPm = remember(timeHour.value) {
        if (timeHour.value >= 12) "PM" else "AM"
    }
    val isPm = remember { mutableStateOf(timeHour.value >= 12) }
    LaunchedEffect(timeHour.value) {
        isPm.value = timeHour.value >= 12
    }
    // Mission state hoisted so all AlertDialog slots can access
    val missionTypeState = remember { mutableStateOf("none") }
    val isQueueMode = remember { mutableStateOf(false) }
    val queuedMissions = remember { mutableStateListOf<String>() }
    
    // Available missions for selection
    val availableMissions = listOf("password", "tap")
    var hourInput by remember { mutableStateOf(String.format("%02d", displayHour)) }
    var minuteInput by remember { mutableStateOf(String.format("%02d", timeMinute.value)) }
    var hourFieldFocused by remember { mutableStateOf(false) }
    var minuteFieldFocused by remember { mutableStateOf(false) }

    fun normalizeHour(value: Int): Int = ((value % 24) + 24) % 24

    fun syncHourInput() {
        if (!hourFieldFocused) {
            hourInput = String.format("%02d", displayHour)
        }
    }

    fun syncMinuteInput() {
        if (!minuteFieldFocused) {
            minuteInput = String.format("%02d", timeMinute.value)
        }
    }
    
    fun adjustHour(delta: Int) {
        timeHour.value = normalizeHour(timeHour.value + delta)
        syncHourInput()
    }

    fun adjustMinute(delta: Int) {
        val newMinute = timeMinute.value + delta
        if (newMinute >= 60) {
            timeMinute.value = newMinute % 60
            adjustHour(newMinute / 60)
        } else if (newMinute < 0) {
            val deficit = (kotlin.math.abs(newMinute) + 59) / 60
            adjustHour(-deficit)
            timeMinute.value = (newMinute % 60 + 60) % 60
        } else {
            timeMinute.value = newMinute
        }
        syncMinuteInput()
    }

    LaunchedEffect(displayHour, isPm.value) { syncHourInput() }
    LaunchedEffect(timeMinute.value) { syncMinuteInput() }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        containerColor = Color.Black,
        titleContentColor = Color.White,
        textContentColor = Color.White,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Add New Alarm", color = Color.White)
                Icon(
                    imageVector = Icons.Filled.AlarmAdd,
                    contentDescription = null,
                    tint = Color.White
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black)
                    .padding(8.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Time selection with swipe controls
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.Bottom
                ) {
                    // Hour selector with swipe up/down
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .pointerInput(Unit) {
                                var hasTriggered = false
                                var totalDrag = 0f

                                detectVerticalDragGestures(
                                    onDragStart = {
                                        hasTriggered = false
                                        totalDrag = 0f
                                    },
                                    onDragEnd = {
                                        hasTriggered = false
                                        totalDrag = 0f
                                    }
                                ) { change, dragAmount ->
                                    totalDrag += dragAmount

                                    // Only trigger once per drag gesture
                                    if (!hasTriggered && kotlin.math.abs(totalDrag) > 30f) {
                                        if (totalDrag < 0) {
                                            // Swipe up - increase hour
                                            timeHour.value = (timeHour.value + 1) % 24
                                        } else {
                                            // Swipe down - decrease hour
                                            timeHour.value = if (timeHour.value > 0) timeHour.value - 1 else 23
                                        }
                                        hasTriggered = true
                                        totalDrag = 0f
                                    }
                                    change.consume()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        BasicTextField(
                            value = hourInput,
                            onValueChange = { newValue ->
                                val sanitized = newValue.filter { it.isDigit() }.take(2)
                                hourInput = sanitized
                                if (sanitized.isNotEmpty()) {
                                    val numeric = sanitized.toInt().coerceIn(1, 12)
                                    val base = if (numeric == 12) 0 else numeric
                                    hourFieldFocused = true
                                    timeHour.value = if (isPm.value) {
                                        if (numeric == 12) 12 else base + 12
                                    } else {
                                        base
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusable()
                                .onFocusChanged { focusState ->
                                    hourFieldFocused = focusState.isFocused
                                    if (!focusState.isFocused) {
                                        hourInput = String.format("%02d", displayHour)
                                    }
                                },
                            textStyle = MaterialTheme.typography.displayMedium.copy(
                                fontSize = 44.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                                textAlign = TextAlign.Center
                            ),
                            keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number)
                        )
                    }

                    // Colon separator
                    Text(
                        text = ":",
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .align(Alignment.Bottom)
                    )

                    // Minute selector with swipe up/down
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .pointerInput(Unit) {
                                var hasTriggered = false
                                var totalDrag = 0f

                                detectVerticalDragGestures(
                                    onDragStart = {
                                        hasTriggered = false
                                        totalDrag = 0f
                                    },
                                    onDragEnd = {
                                        hasTriggered = false
                                        totalDrag = 0f
                                    }
                                ) { change, dragAmount ->
                                    totalDrag += dragAmount

                                    // Only trigger once per drag gesture
                                    if (!hasTriggered && kotlin.math.abs(totalDrag) > 30f) {
                                        if (totalDrag < 0) {
                                            // Swipe up - increase minute
                                            adjustMinute(1)
                                        } else {
                                            adjustMinute(-1)
                                        }
                                        hasTriggered = true
                                        totalDrag = 0f
                                    }
                                    change.consume()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        BasicTextField(
                            value = minuteInput,
                            onValueChange = { newValue ->
                                val sanitized = newValue.filter { it.isDigit() }.take(2)
                                minuteInput = sanitized
                                val numeric = sanitized.toIntOrNull()
                                if (numeric != null) {
                                    val numeric = sanitized.toInt()
                                    val clamped = numeric.coerceIn(0, 59)
                                    timeMinute.value = clamped
                                    if (numeric != clamped) {
                                        adjustMinute(numeric - clamped)
                                    } else {
                                        syncMinuteInput()
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusable()
                                .onFocusChanged { focusState ->
                                    minuteFieldFocused = focusState.isFocused
                                    if (!focusState.isFocused) {
                                        syncMinuteInput()
                                    }
                                },
                            textStyle = MaterialTheme.typography.displayMedium.copy(
                                fontSize = 44.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                                textAlign = TextAlign.Center
                            ),
                            keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number)
                        )
                    }

                    Row(
                        modifier = Modifier
                            .width(120.dp)
                            .height(40.dp)
                            .padding(start = 8.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF1E1E1E)),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(if (!isPm.value) Color(0xFF424242) else Color.Transparent)
                                .clickable {
                                    if (isPm.value) {
                                        if (timeHour.value >= 12) timeHour.value = (timeHour.value - 12).coerceIn(0, 23)
                                        isPm.value = false
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "AM",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (!isPm.value) Color.White else Color.White
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(if (isPm.value) Color(0xFF424242) else Color.Transparent)
                                .clickable {
                                    if (!isPm.value) {
                                        if (timeHour.value < 12) timeHour.value = (timeHour.value + 12).coerceIn(0, 23)
                                        isPm.value = true
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "PM",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isPm.value) Color.White else Color.White
                            )
                        }
                    }
                }

                // Day selection
                Text(
                    text = "Repeat",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.Start)
                        .padding(top = 16.dp, bottom = 8.dp)
                )
                DayToggleButtons(
                    selectedDays = selectedDaysState.value,
                    onDayToggle = { day ->
                        selectedDaysState.value = if (selectedDaysState.value.contains(day)) {
                            selectedDaysState.value - day
                        } else {
                            selectedDaysState.value + day
                        }
                    }
                )

                // Mission selection with queue mode toggle and preview
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Mission",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Preview button
                        val previewContext = LocalContext.current
                        val currentMissionForPreview = if (isQueueMode.value) {
                            queuedMissions.firstOrNull()
                        } else {
                            missionTypeState.value
                        }
                        
                        Text(
                            text = "preview",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                                ) {
                                    val pm = when (currentMissionForPreview) {
                                        "tap" -> "tap"
                                        "password" -> "password"
                                        else -> null
                                    }
                                    if (pm == null) {
                                        return@clickable
                                    }
                                    try {
                                        val intent = Intent(previewContext, AlarmActivity::class.java).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                            putExtra("preview", true)
                                            putExtra("preview_mission", pm)
                                            putExtra(AlarmReceiver.ALARM_ID, 9100)
                                        }
                                        previewContext.startActivity(intent)
                                    } catch (_: Exception) {}
                                }
                        )
                        
                        Text(
                            text = "Queue Mode",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Switch(
                            checked = isQueueMode.value,
                            onCheckedChange = { isQueueMode.value = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                uncheckedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF424242),
                                uncheckedTrackColor = Color(0xFF616161)
                            )
                        )
                    }
                }

                if (isQueueMode.value) {
                    // Multi-mission queue with button selection
                    Text(
                        text = "Build your mission sequence by pressing buttons below:",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    // Show queued missions
                    if (queuedMissions.isNotEmpty()) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp)
                            ) {
                                Text(
                                    text = "Mission Queue (${queuedMissions.size})",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                                queuedMissions.forEachIndexed { index, mission ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${index + 1}. ${mission.replaceFirstChar { it.uppercase() }}",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                        IconButton(
                                            onClick = { 
                                                if (index < queuedMissions.size) {
                                                    queuedMissions.removeAt(index)
                                                }
                                            },
                                            modifier = Modifier.size(20.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Close,
                                                contentDescription = "Remove",
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.size(12.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                    // Mission selection buttons for queue
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Password mission
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { 
                                queuedMissions.add("password")
                            },
                            colors = ButtonDefaults.outlinedButtonColors(),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                        ) { 
                            Text("Add Password", fontSize = 10.sp, maxLines = 1) 
                        }
                        
                        // Tap mission
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { 
                                queuedMissions.add("tap")
                            },
                            colors = ButtonDefaults.outlinedButtonColors(),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                        ) { 
                            Text("Add Tap", fontSize = 10.sp, maxLines = 1) 
                        }
                    }
                    
                    // Clear queue button
                    if (queuedMissions.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { queuedMissions.clear() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color.White
                            )
                        ) {
                            Text("Clear Queue", fontSize = 12.sp, color = Color.White)
                        }
                    }
                } else {
                    // Single mission selection
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // None mission
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { missionTypeState.value = "none" },
                            colors = ButtonDefaults.outlinedButtonColors(),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                            border = BorderStroke(1.dp, if (missionTypeState.value == "none") Color.White else Color.White.copy(alpha = 0.6f))
                        ) { 
                            AutoSizeText(
                                text = "None", 
                                maxLines = 2, 
                                modifier = Modifier.fillMaxWidth(), 
                                minFontSize = 8.sp,
                                style = MaterialTheme.typography.bodySmall.copy(color = Color.White)
                            )
                        }
                        
                        // Password mission
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { missionTypeState.value = "password" },
                            colors = ButtonDefaults.outlinedButtonColors(),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                            border = BorderStroke(1.dp, if (missionTypeState.value == "password") Color.White else Color.White.copy(alpha = 0.6f))
                        ) { 
                            AutoSizeText(
                                text = "Password", 
                                maxLines = 2, 
                                modifier = Modifier.fillMaxWidth(), 
                                minFontSize = 8.sp,
                                style = MaterialTheme.typography.bodySmall.copy(color = Color.White)
                            )
                        }
                        
                        // Tap mission
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { missionTypeState.value = "tap" },
                            colors = ButtonDefaults.outlinedButtonColors(),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                            border = BorderStroke(1.dp, if (missionTypeState.value == "tap") Color.White else Color.White.copy(alpha = 0.6f))
                        ) { 
                            AutoSizeText(
                                text = "Tap Challenge", 
                                maxLines = 2, 
                                modifier = Modifier.fillMaxWidth(), 
                                minFontSize = 8.sp,
                                style = MaterialTheme.typography.bodySmall.copy(color = Color.White)
                            )
                        }
                    }
                    
                    // Password hint for hardcoded password
                    if (missionTypeState.value == "password") {
                        Text(
                            text = "Default password: IfYouWantYouCanSleep",
                            fontSize = 12.sp,
                            color = Color.White,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
                // No per-alarm password input; uses globally set password in AlarmActivity

                // (no separate preview row; link is on the Mission header)

                // Wake-up Check (per-alarm)
                Text(
                    text = "Wake-up check",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.Start)
                        .padding(top = 16.dp, bottom = 8.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { wakeCheckEnabledState.value = !wakeCheckEnabledState.value },
                        border = BorderStroke(1.dp, if (wakeCheckEnabledState.value) Color.White else Color.White.copy(alpha = 0.6f))
                    ) { Text(if (wakeCheckEnabledState.value) "Enabled" else "Disabled", color = Color.White) }

                    if (wakeCheckEnabledState.value) {
                        val options = listOf(1, 3, 5, 7, 10)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            options.forEach { m ->
                                OutlinedButton(
                                    onClick = { wakeCheckMinutesState.value = m },
                                    border = BorderStroke(1.dp, if (wakeCheckMinutesState.value == m) Color.White else Color.White.copy(alpha = 0.6f))
                                ) { Text("${m}m", color = Color.White) }
                            }
                        }
                    }
                }

                // Protected Alarm (only show if no existing protected alarm)
                val hasExistingProtectedAlarm = alarms.any { it.isProtected && it.isEnabled }
                if (!hasExistingProtectedAlarm) {
                    Text(
                        text = "Protected Alarm",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.Start)
                            .padding(top = 16.dp, bottom = 8.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Check if this should be enabled (only for daily alarms)
                        val isDailyAlarm = selectedDaysState.value.size == 7 // All days selected = daily
                        val canProtect = isDailyAlarm // No need to check for existing protected alarm since section is hidden
                        
                        Checkbox(
                            checked = protectedAlarmState.value,
                            onCheckedChange = { 
                                if (canProtect) {
                                    protectedAlarmState.value = it
                                }
                            },
                            enabled = canProtect,
                            modifier = Modifier.size(24.dp),
                            colors = CheckboxDefaults.colors(
                                checkedColor = Color(0xFF424242),
                                uncheckedColor = Color(0xFF616161),
                                checkmarkColor = Color.White
                            )
                        )
                        
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Protect this alarm",
                                fontSize = 14.sp,
                                color = if (canProtect) Color.White else Color.White.copy(alpha = 0.6f)
                            )
                            if (!isDailyAlarm) {
                                Text(
                                    text = "Daily alarms only",
                                    fontSize = 12.sp,
                                    color = Color.White.copy(alpha = 0.7f)
                                )
                            } else {
                                Text(
                                    text = "Prevents accidental dismiss",
                                    fontSize = 12.sp,
                                    color = Color.White.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }

                // Ringtone selection
                Text(
                    text = "Ringtone",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.Start)
                        .padding(top = 16.dp, bottom = 8.dp)
                )
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { showRingtonePicker.value = true },
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF1E1E1E)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Audiotrack,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier
                                .size(24.dp)
                                .padding(end = 12.dp)
                        )
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "Select Ringtone",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            val ringtoneName = remember(selectedRingtoneUriState.value) {
                                if (selectedRingtoneUriState.value == null) {
                                    "None selected"
                                } else {
                                    try {
                                        // Handle bundled raw resources properly
                                        val uriString = selectedRingtoneUriState.value?.toString()
                                        val isGlassy = uriString?.contains("glassy_bell") == true
                                        val isPyaro = uriString?.contains("pyaro_vrindavan") == true
                                        val isHareKrishna = uriString?.contains("harekrishna_chant") == true
                                        val isSilenceNoSound = uriString?.contains("silence_no_sound") == true
                                        if (isSilenceNoSound) {
                                            "🔇 Silence No Sound"
                                        } else if (isGlassy) {
                                            "🎵 glassy_bell"
                                        } else if (isPyaro) {
                                            "🎵 pyaro_vrindavan"
                                        } else if (isHareKrishna) {
                                            "🎵 harekrishna_chant"
                                        } else if (selectedRingtoneUriState.value?.toString()?.startsWith("android.resource://") == true) {
                                            // Handle other resource URIs
                                            val uriString = selectedRingtoneUriState.value?.toString()
                                            if (uriString?.contains("/raw/") == true) {
                                                // Extract resource name from path format: android.resource://package/raw/resourceName
                                                val segments = uriString?.split("/") ?: emptyList()
                                                if (segments.size > 3) {
                                                    "🎵 ${segments[3]}"
                                                } else {
                                                    "🎵 Custom Ringtone"
                                                }
                                            } else {
                                                // Extract resource name from ID format: android.resource://package/resourceId
                                                val segments = uriString?.split("/") ?: emptyList()
                                                if (segments.size > 3) {
                                                    try {
                                                        val resourceId = segments[3].toInt()
                                                        // Try to get resource name, fallback to "🎵 Custom Ringtone" if fails
                                                        try {
                                                            context.resources.getResourceEntryName(resourceId)
                                                        } catch (e: Exception) {
                                                            "🎵 Custom Ringtone"
                                                        }
                                                    } catch (e: NumberFormatException) {
                                                        "🎵 Custom Ringtone"
                                                    }
                                                } else {
                                                    "🎵 Custom Ringtone"
                                                }
                                            }
                                        } else {
                                            // Handle system ringtones
                                            val ringtone = RingtoneManager.getRingtone(context, selectedRingtoneUriState.value ?: Settings.System.DEFAULT_ALARM_ALERT_URI)
                                            ringtone?.getTitle(context) ?: "Unknown Ringtone"
                                        }
                                    } catch (e: Exception) {
                                        // Fallback for any errors
                                        val uriString = selectedRingtoneUriState.value?.toString()
                                        when {
                                            uriString?.contains("silence_no_sound") == true -> "🔇 Silence No Sound"
                                            uriString?.contains("glassy_bell") == true -> "🎵 glassy_bell"
                                            uriString?.contains("pyaro_vrindavan") == true -> "🎵 pyaro_vrindavan"
                                            uriString?.contains("harekrishna_chant") == true -> "🎵 harekrishna_chant"
                                            else -> "🎵 Custom Ringtone"
                                        }
                                    }
                                }
                            }
                            Text(
                                text = ringtoneName,
                                fontSize = 14.sp,
                                color = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowUp,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isQueueMode.value && queuedMissions.isNotEmpty()) {
                        // Handle queue mode - queue missions and create alarm with first mission
                        val mainActivity = context as MainActivity
                        Log.d("MainActivity", "QUEUE_MODE_ALARM_CREATE: queuedMissions=${queuedMissions.joinToString(",")}")
                        
                        val specs = queuedMissions.map { missionId ->
                            Log.d("MainActivity", "CREATING_MISSION_SPEC: missionId=$missionId")
                            MissionSpec(
                                id = missionId,
                                params = mapOf("mission_type" to missionId),
                                timeoutMs = 30000L,
                                retryCount = 3,
                                sticky = true,
                                retryDelayMs = 1000L
                            )
                        }
                        Log.d("MainActivity", "CALLING_ENQUEUE_ALL: specs=${specs.size}")
                        mainActivity.missionSequencer.enqueueAll(specs)
                        
                        // Create alarm with first mission as the primary mission
                        onConfirm(
                            timeHour.value,
                            timeMinute.value,
                            selectedRingtoneUriState.value,
                            selectedDaysState.value,
                            queuedMissions.first(), // First mission as primary
                            null,
                            protectedAlarmState.value
                        )
                    } else {
                        // Handle single mission mode
                        onConfirm(
                            timeHour.value,
                            timeMinute.value,
                            selectedRingtoneUriState.value,
                            selectedDaysState.value,
                            missionTypeState.value,
                            null, // Use hardcoded default password
                            protectedAlarmState.value
                        )
                    }
                },
                enabled = selectedRingtoneUriState.value != null && (!isQueueMode.value || queuedMissions.isNotEmpty()),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF424242)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = if (isQueueMode.value) "Create Alarm & Queue ${queuedMissions.size} Missions" else "Create Alarm",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismissRequest,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Cancel",
                    color = Color.White
                )
            }
        }
    )

    // Ringtone picker dialog
    if (showRingtonePicker.value) {
        CustomRingtonePicker(
            context = context,
            onRingtoneSelected = { uri ->
                selectedRingtoneUriState.value = uri
                showRingtonePicker.value = false
            },
            onDismiss = {
                mediaPlayerState.value?.stop()
                mediaPlayerState.value?.release()
                mediaPlayerState.value = null
                showRingtonePicker.value = false
            },
            mediaPlayerState = mediaPlayerState
        )
    }
}

@Composable
fun PermissionsCenterDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Permissions & Power Settings") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Exact Alarms", fontWeight = FontWeight.SemiBold, color = Color.White)
                            Text("Allow the app to schedule precise alarms.", color = Color.White)
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(onClick = {
                                try {
                                    // Prefer the official exact-alarm settings screen (Alarms & Reminders)
                                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    // Fallback: App Info screen
                                    try {
                                        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                        }
                                        context.startActivity(fallback)
                                    } catch (_: Exception) { }
                                }
                            }) {
                                Text("Open Alarms & Reminders")
                            }
                        }
                    }
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Preview", fontWeight = FontWeight.SemiBold, color = Color.White)
                        Text("Quickly test the alarm screen without scheduling.", color = Color.White)
                        Spacer(Modifier.height(6.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = {
                                try {
                                    val intent = Intent(context, AlarmActivity::class.java).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                        putExtra("preview", true)
                                        putExtra("preview_mission", "tap")
                                        putExtra(AlarmReceiver.ALARM_ID, 9001)
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {}
                            }, modifier = Modifier.weight(1f)) { Text("Preview Tap") }

                            Button(onClick = {
                                try {
                                    val intent = Intent(context, AlarmActivity::class.java).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                        putExtra("preview", true)
                                        putExtra("preview_mission", "password")
                                        putExtra(AlarmReceiver.ALARM_ID, 9002)
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {}
                            }, modifier = Modifier.weight(1f)) { Text("Preview Password") }
                        }
                    }
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Battery Optimization", fontWeight = FontWeight.SemiBold, color = Color.White)
                        Text("Disable optimization so alarms can ring reliably.", color = Color.White)
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = {
                            try {
                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        }) { Text("Allow 'Don't optimize'") }
                    }
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Notifications", fontWeight = FontWeight.SemiBold, color = Color.White)
                        Text("Permit notifications for foreground alarm service.", color = Color.White)
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = {
                            try {
                                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        }) { Text("Open Notification Settings") }
                    }
                }

                // Modify system settings (WRITE_SETTINGS)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Modify system settings", fontWeight = FontWeight.SemiBold, color = Color.White)
                            Text(
                                "Allow Chrona to change system settings such as alarm volume.",
                                color = Color.White
                            )
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(onClick = {
                                try {
                                    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {}
                            }) { Text("Open Modify System Settings") }
                        }
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Display over other apps", fontWeight = FontWeight.SemiBold, color = Color.White)
                            Text("Helps show the alarm screen above other apps.", color = Color.White)
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(onClick = {
                                try {
                                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                                    context.startActivity(intent)
                                } catch (_: Exception) {}
                            }) { Text("Open Overlay Permission") }
                        }
                    }
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Device admin", fontWeight = FontWeight.SemiBold, color = Color.White)
                        Text("Open Device admin apps section to activate device permissions.", color = Color.White)
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = {
                            try {
                                // Try to request device admin activation for this app directly
                                val deviceAdminComponent = android.content.ComponentName(context, MyDeviceAdminReceiver::class.java)
                                val intent = Intent("android.app.action.ADD_DEVICE_ADMIN").apply {
                                    putExtra("android.app.extra.DEVICE_ADMIN", deviceAdminComponent)
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                try {
                                    // Alternative: Try to open device admin apps using direct approach
                                    val intent = Intent("android.settings.DEVICE_ADMIN_SETTINGS")
                                    context.startActivity(intent)
                                } catch (e2: Exception) {
                                    try {
                                        // Another alternative: Use the device admin policy screen
                                        val intent = Intent("android.app.action.DEVICE_ADMIN_SETTINGS")
                                        context.startActivity(intent)
                                    } catch (e3: Exception) {
                                        android.widget.Toast.makeText(context, "Please go to Settings > Security > Device admin apps", android.widget.Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }) {
                            Text("Open device admin settings")
                        }
                    }
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Auto-start & OEM power", fontWeight = FontWeight.SemiBold, color = Color.White)
                        Text("Open OEM pages to allow background start and no restrictions.", color = Color.White)
                        Spacer(Modifier.height(6.dp))
                        Button(onClick = {
                            try {
                                val manu = Build.MANUFACTURER.lowercase()
                                fun tryStart(context: Context, cn: ComponentName): Boolean = try {
                                    val i = Intent().apply { component = cn; addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                    context.startActivity(i); true
                                } catch (_: Exception) { false }

                                val tried = when {
                                    manu.contains("xiaomi") -> tryStart(context, ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")) ||
                                        tryStart(context, ComponentName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")) ||
                                        tryStart(context, ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")) ||
                                        tryStart(context, ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.PowerHideModeActivity"))
                                    manu.contains("oppo") -> tryStart(context, ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")) ||
                                        tryStart(context, ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"))
                                    manu.contains("vivo") -> tryStart(context, ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")) ||
                                        tryStart(context, ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"))
                                    manu.contains("huawei") -> tryStart(context, ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))
                                    manu.contains("oneplus") -> tryStart(context, ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"))
                                    else -> false
                                }
                                if (!tried) {
                                    try {
                                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                        }
                                        context.startActivity(intent)
                                    } catch (_: Exception) {}
                                }
                            } catch (_: Exception) {}
                        }) { Text("Open OEM Auto-start Settings") }
                    }
                }
            }
        }  ,
        confirmButton = { Button(onClick = onDismiss) { Text("Done") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
fun DayToggleButton(
    day: Int,
    dayString: String,
    selectedDays: Set<Int>,
    onDaySelected: (Int, Boolean) -> Unit
) {
    val isSelected = selectedDays.contains(day)
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp)
            )
            .border(
                width = 1.5.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable {
                onDaySelected(day, !isSelected)
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = dayString,
            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
fun CustomRingtonePicker(
    context: Context,
    onRingtoneSelected: (Uri) -> Unit,
    onDismiss: () -> Unit,
    mediaPlayerState: MutableState<MediaPlayer?>
) {
    val ringtones = remember(context) {
        val list = mutableListOf<Pair<String, Uri>>()

        // Add silence_no_sound first
        val silenceId = context.resources.getIdentifier("silence_no_sound", "raw", context.packageName)
        if (silenceId != 0) {
            list.add("🔇 Silence No Sound" to Uri.parse("android.resource://${context.packageName}/$silenceId"))
        }

        val glossyId = context.resources.getIdentifier("glassy_bell", "raw", context.packageName)
        if (glossyId != 0) {
            list.add("🎵 Glossy Bell" to Uri.parse("android.resource://${context.packageName}/$glossyId"))
        }

        val pyaroId = context.resources.getIdentifier("pyaro_vrindavan", "raw", context.packageName)
        if (pyaroId != 0) {
            list.add("🎵 Pyaro Vrindavan" to Uri.parse("android.resource://${context.packageName}/$pyaroId"))
        }

        // Add all new songs
        val agamId = context.resources.getIdentifier("agam_kaalbhairav_ashtakam", "raw", context.packageName)
        if (agamId != 0) {
            list.add("🎵 Kaalbhairav Ashtakam" to Uri.parse("android.resource://${context.packageName}/$agamId"))
        }

        val alaipayutheId = context.resources.getIdentifier("alaipayuthe", "raw", context.packageName)
        if (alaipayutheId != 0) {
            list.add("🎵 Alaipayuthe" to Uri.parse("android.resource://${context.packageName}/$alaipayutheId"))
        }

        val gentleOceanId = context.resources.getIdentifier("gentle_ocean_and_birdsong_24068", "raw", context.packageName)
        if (gentleOceanId != 0) {
            list.add("🎵 Gentle Ocean & Birdsong" to Uri.parse("android.resource://${context.packageName}/$gentleOceanId"))
        }

        val harekrishnaId = context.resources.getIdentifier("harekrishna_chant", "raw", context.packageName)
        if (harekrishnaId != 0) {
            list.add("🎵 Hare Krishna Chant" to Uri.parse("android.resource://${context.packageName}/$harekrishnaId"))
        }

        val krishnaFluteId = context.resources.getIdentifier("krishna_flute", "raw", context.packageName)
        if (krishnaFluteId != 0) {
            list.add("🎵 Krishna Flute" to Uri.parse("android.resource://${context.packageName}/$krishnaFluteId"))
        }

        val radhaKrishnaId = context.resources.getIdentifier("radha_krishna_flute", "raw", context.packageName)
        if (radhaKrishnaId != 0) {
            list.add("🎵 Radha Krishna Flute" to Uri.parse("android.resource://${context.packageName}/$radhaKrishnaId"))
        }

        try {
            val ringtoneManager = RingtoneManager(context)
            ringtoneManager.setType(RingtoneManager.TYPE_ALARM)
            val cursor: Cursor? = ringtoneManager.cursor
            cursor?.let {
                while (it.moveToNext()) {
                    val title = it.getString(RingtoneManager.TITLE_COLUMN_INDEX)
                    val uri = ringtoneManager.getRingtoneUri(it.position)
                    list.add("🔔 $title" to uri)
                }
                it.close()
            }
        } catch (e: Exception) {
            Log.e("AlarmApp", "Error loading alarm ringtones: ${e.message}", e)
        }

        if (list.isEmpty()) {
            try {
                val ringtoneManager = RingtoneManager(context)
                ringtoneManager.setType(RingtoneManager.TYPE_NOTIFICATION)
                val cursor: Cursor? = ringtoneManager.cursor
                cursor?.let {
                    while (it.moveToNext()) {
                        val title = it.getString(RingtoneManager.TITLE_COLUMN_INDEX)
                        val uri = ringtoneManager.getRingtoneUri(it.position)
                        list.add("📢 $title" to uri)
                    }
                    it.close()
                }
            } catch (e: Exception) {
                Log.e("AlarmApp", "Error loading notification ringtones: ${e.message}", e)
            }
        }

        if (list.isEmpty()) {
            list.add("⏰ Default Alarm" to RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            list.add("🔔 Default Notification" to RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
        }

        list
    }
    val selectedRingtone = remember { mutableStateOf<Uri?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.Black,
        titleContentColor = Color.White,
        textContentColor = Color.White,
        title = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Select Ringtone", color = Color.White)
                Icon(
                    imageVector = Icons.Filled.Audiotrack,
                    contentDescription = null,
                    tint = Color.White
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .height(400.dp)
                    .background(Color.Black)
            ) {
                Text(
                    text = "Tap on a ringtone to preview it",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black)
                        .padding(vertical = 8.dp, horizontal = 4.dp)
                )
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color.Black)
                ) {
                    items(ringtones) { pair ->
                        val name = pair.first
                        val uri = pair.second
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp, horizontal = 4.dp)
                                .border(1.dp, Color.DarkGray, RoundedCornerShape(8.dp))
                                .clickable {
                                    mediaPlayerState.value?.stop()
                                    mediaPlayerState.value?.release()
                                    mediaPlayerState.value = null

                                    try {
                                        mediaPlayerState.value = MediaPlayer().apply {
                                            val audioAttributes = AudioAttributes.Builder()
                                                .setUsage(AudioAttributes.USAGE_ALARM)
                                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                                .build()
                                            setAudioAttributes(audioAttributes)
                                            setDataSource(context, uri)
                                            prepare()
                                            start()
                                        }
                                        selectedRingtone.value = uri
                                    } catch (e: Exception) {
                                        Log.e("AlarmApp", "Error playing ringtone: ${e.message}", e)
                                        mediaPlayerState.value?.release()
                                        mediaPlayerState.value = null
                                    }
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = if (selectedRingtone.value == uri)
                                    Color(0xFF333333)
                                else
                                    Color(0xFF1A1A1A)
                            ),
                            elevation = CardDefaults.cardElevation(
                                defaultElevation = if (selectedRingtone.value == uri) 4.dp else 2.dp
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(if (selectedRingtone.value == uri) Color(0xFF333333) else Color(0xFF1A1A1A))
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Audiotrack,
                                        contentDescription = null,
                                        tint = if (selectedRingtone.value == uri)
                                            Color.White
                                        else
                                            Color.White.copy(alpha = 0.7f),
                                        modifier = Modifier
                                            .size(20.dp)
                                            .padding(end = 8.dp)
                                    )
                                    Text(
                                        text = name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (selectedRingtone.value == uri)
                                            Color.White
                                        else
                                            Color.White.copy(alpha = 0.7f),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (selectedRingtone.value == uri) {
                                    Icon(
                                        imageVector = Icons.Filled.CheckCircle,
                                        contentDescription = "Selected",
                                        tint = Color.White,
                                        modifier = Modifier.align(Alignment.CenterEnd)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    mediaPlayerState.value?.stop()
                    mediaPlayerState.value?.release()
                    mediaPlayerState.value = null
                    selectedRingtone.value?.let { onRingtoneSelected(it) }
                },
                enabled = selectedRingtone.value != null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF333333),
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFF1A1A1A),
                    disabledContentColor = Color.Gray
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                Text("Select", color = Color.White)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = {
                    mediaPlayerState.value?.stop()
                    mediaPlayerState.value?.release()
                    mediaPlayerState.value = null
                    onDismiss()
                },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color.White,
                    containerColor = Color.Transparent
                ),
                border = BorderStroke(1.dp, Color.Gray),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            ) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun QueueMissionsDialog(
    onDismiss: () -> Unit,
    onEnqueue: (String) -> Unit
) {
    val missionIds = remember { mutableStateOf("") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Queue Missions", color = Color.White)
        },
        text = {
            Column {
                Text(
                    text = "Enter mission IDs (comma-separated):",
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = missionIds.value,
                    onValueChange = { missionIds.value = it },
                    label = { Text("Mission IDs", color = Color.White) },
                    placeholder = { Text("mission1,mission2,mission3", color = Color.White.copy(alpha = 0.5f)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Example: math_challenge,meditation_timer,exercise_routine",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (missionIds.value.isNotBlank()) {
                        onEnqueue(missionIds.value)
                        onDismiss()
                    }
                },
                enabled = missionIds.value.isNotBlank()
            ) {
                Text("Queue Missions", color = Color.White)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel", color = Color.White)
            }
        }
    )
}
