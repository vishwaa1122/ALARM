package com.vaishnava.alarm
import com.vaishnava.alarm.CloudBackupControls
import android.Manifest
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
import android.content.ComponentName
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AlarmAdd
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Task
import com.vaishnava.alarm.data.Alarm
import com.vaishnava.alarm.ui.theme.AlarmTheme
import java.util.Calendar

class MainActivity : ComponentActivity() {

    private lateinit var alarmStorage: AlarmStorage
    private lateinit var alarmScheduler: AlarmScheduler
    private val alarmPermissionRequestCode = 1001
    private val batteryOptimizationRequestCode = 1002
    private val scheduleExactAlarmRequestCode = 1003
    private val RC_SIGN_IN = 9001

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

        // Request necessary permissions
        requestNecessaryPermissions()
        // Prompt for exact alarm capability and battery optimization exceptions when applicable
        promptExactAlarmIfNeeded()
        promptIgnoreBatteryOptimizationsIfNeeded()
        promptAutoStartIfNeeded()

        // Start a background task to check for upcoming alarms
        startAlarmNotificationChecker()

        // Register broadcast receiver for clearing notifications
        val filter = IntentFilter("com.vaishnava.alarm.CLEAR_NOTIFICATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(clearNotificationReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(clearNotificationReceiver, filter)
        }

        setContent {
            AlarmTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AlarmScreenContent(
                        alarmStorage = alarmStorage,
                        alarmScheduler = alarmScheduler,
                        googleSignInClient = mGoogleSignInClient,
                        scheduleAlarmCallback = { hour, minute, ringtoneUri, days, missionType, missionPassword ->
                            val alarmId = alarmStorage.getNextAlarmId()
                            Log.d("AlarmApp", "Creating alarm with ID: $alarmId")
                            Log.d("AlarmApp", "Alarm details - Hour: $hour, Minute: $minute, Ringtone: $ringtoneUri, Days: $days")

                            val finalDays = if (days.isEmpty()) null else days.toList()

                            val alarm = com.vaishnava.alarm.data.Alarm(
                                id = alarmId,
                                hour = hour,
                                minute = minute,
                                isEnabled = true,
                                ringtoneUri = ringtoneUri,
                                days = finalDays,
                                isProtected = false,
                                missionType = missionType,
                                missionPassword = missionPassword
                            )
                            Log.d("AlarmApp", "Created alarm object with ID: ${alarm.id}")
                            alarmStorage.addAlarm(alarm)
                            alarmScheduler.schedule(alarm)
                            
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
                        }
                    )
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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

    private fun clearNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.cancel(2)
    }

    private fun scheduleProtectedDefaultAlarm() {
        val defaultAlarmHour = 5
        val defaultAlarmMinute = 0
        val defaultRingtoneUri =
            Uri.parse("android.resource://${packageName}/raw/glassy_bell")
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

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(clearNotificationReceiver)
        } catch (e: Exception) {
            // Ignore if receiver was not registered
        }
    }

    private fun tryRestoreFromCloud() {
        try {
            val account = com.google.android.gms.auth.api.signin.GoogleSignIn.getLastSignedInAccount(this)
            if (account == null) return
            CloudAlarmStorage(this).loadAlarmsFromCloud { alarms ->
                if (alarms == null) return@loadAlarmsFromCloud
                if (alarms.isNotEmpty()) {
                    alarmStorage.saveAlarms(alarms)
                    runOnUiThread {
                        android.widget.Toast.makeText(this, "Alarms restored from cloud (${alarms.size})", android.widget.Toast.LENGTH_SHORT).show()
                        // No activity recreate here; UI will reflect on next open or via manual Restore button which updates list state
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore restore errors during startup
        }
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
    scheduleAlarmCallback: (Int, Int, Uri?, Set<Int>, String?, String?) -> Alarm,
    cancelAlarmCallback: (Int) -> Unit
) {
    val context = LocalContext.current
    val alarms = remember { mutableStateListOf<Alarm>() }
    val showAddDialog = remember { mutableStateOf(false) }
    val showDiagnosticsDialog = remember { mutableStateOf(false) }

    // We'll keep a simple state for hour/minute (same logic preserved)
    val timeHour = remember { mutableStateOf(7) }
    val timeMinute = remember { mutableStateOf(0) }

    val selectedDaysState: MutableState<Set<Int>> = remember { mutableStateOf(setOf<Int>()) }
    val selectedRingtoneUriState: MutableState<Uri?> = remember { mutableStateOf(null) }
    val mediaPlayerState: MutableState<MediaPlayer?> = remember { mutableStateOf(null) }
    val showRingtonePicker = remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
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
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0A0E21),
                        Color(0xFF1B1B3A),
                        Color(0xFF0A0E21)
                    )
                )
            )
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

                Spacer(Modifier.height(4.dp))

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
                    // Show signed-in state with cloud actions
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth(0.9f)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Signed in as ${currentAccount.email ?: currentAccount.displayName}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            // Open Cloud Backup page
                            OutlinedButton(
                                onClick = {
                                    try {
                                        val intent = Intent(context, GoogleSignInActivity::class.java)
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        android.widget.Toast.makeText(context, "Unable to open Cloud Backup: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.padding(bottom = 8.dp)
                            ) { Text("Manage Cloud Backup") }

                            // Cloud actions moved into GoogleSignInActivity
                            OutlinedButton(
                                onClick = {
                                    googleSignInClient.signOut().addOnCompleteListener {
                                        android.widget.Toast.makeText(context, "Signed out", android.widget.Toast.LENGTH_SHORT).show()
                                        (context as? android.app.Activity)?.recreate()
                                    }
                                }
                            ) {
                                Text("Sign out")
                            }
                        }
                    }
                }
            }

            // Alarms header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.List,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = "YOUR ALARMS",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Text(
                        text = "${alarms.size} items",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
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
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
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
                                        tint = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier
                                            .size(56.dp)
                                            .padding(bottom = 12.dp)
                                    )
                                    Text(
                                        text = "No alarms set",
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(bottom = 6.dp)
                                    )
                                    Text(
                                        text = "Tap the + button to create your first alarm",
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(bottom = 16.dp)
                                    )
                                    Button(
                                        onClick = { showAddDialog.value = true },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary
                                        )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Add,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier
                                                .size(18.dp)
                                                .padding(end = 8.dp)
                                        )
                                        Text(
                                            "Add Your First Alarm",
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    items(alarms.sortedBy { it.hour * 60 + it.minute }, key = { it.id }) { alarm ->
                        AlarmItem(
                            alarm = alarm,
                            onRemove = { alarmToRemove ->
                                alarmScheduler.cancel(alarmToRemove)
                                alarmStorage.deleteAlarm(alarmToRemove.id)
                                alarms.remove(alarmToRemove)
                            },
                            onToggleEnable = { alarmToToggle, isEnabled ->
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
                // Add 1 second to the current time to ensure the alarm is in the future
                now.add(Calendar.SECOND, 1)
                timeHour.value = now.get(Calendar.HOUR_OF_DAY)
                timeMinute.value = now.get(Calendar.MINUTE)
                showAddDialog.value = true
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .navigationBarsPadding(),
            containerColor = MaterialTheme.colorScheme.primary,
            elevation = FloatingActionButtonDefaults.elevation(6.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "Add Alarm",
                tint = MaterialTheme.colorScheme.onPrimary
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
            onDismissRequest = { showAddDialog.value = false },
            onConfirm = { hour, minute, ringtoneUri, days, missionType, missionPassword ->
                scheduleAlarmCallback(hour, minute, ringtoneUri, days, missionType, missionPassword)
                showAddDialog.value = false
                // Refresh the alarms list
                alarms.clear()
                alarms.addAll(alarmStorage.getAlarms())
            },
            mediaPlayerState = mediaPlayerState,
            showRingtonePicker = showRingtonePicker
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
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
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
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
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
    onToggleEnable: (Alarm, Boolean) -> Unit
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
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
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = formatDays(alarm.days),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
                if (alarm.isProtected && !(alarm.hour == 5 && alarm.minute == 0)) {
                    Text("Protected Alarm", color = Color.Red)
                }
                val ringtoneTitle = remember(alarm.ringtoneUri) {
                    try {
                        if (alarm.ringtoneUri != null) {
                            // Handle glassy_bell resource properly
                            if (alarm.ringtoneUri?.toString()?.contains("glassy_bell") == true) {
                                "🎵 glassy_bell"
                            } else if (alarm.ringtoneUri?.toString()?.startsWith("android.resource://") == true) {
                                // Handle other resource URIs
                                val uriString = alarm.ringtoneUri?.toString()
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
                                val uri = Uri.parse(alarm.ringtoneUri?.toString() ?: Settings.System.DEFAULT_ALARM_ALERT_URI.toString())
                                val ringtone = RingtoneManager.getRingtone(context, uri)
                                ringtone?.getTitle(context) ?: "Unknown Ringtone"
                            }
                        } else {
                            "Unknown Ringtone"
                        }
                    } catch (e: Exception) {
                        // Fallback for any errors
                        if (alarm.ringtoneUri?.toString()?.contains("glassy_bell") == true) {
                            "🎵 glassy_bell"
                        } else {
                            "🎵 Custom Ringtone"
                        }
                    }
                }
                Text(
                    text = ringtoneTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Switch(
                    checked = alarm.isEnabled,
                    onCheckedChange = { enabled -> onToggleEnable(alarm, enabled) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = { onRemove(alarm) },
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

        }
    }
}

@Composable
fun AddAlarmDialog(
    timeHour: MutableState<Int>,
    timeMinute: MutableState<Int>,
    selectedDaysState: MutableState<Set<Int>>,
    selectedRingtoneUriState: MutableState<Uri?>,
    onDismissRequest: () -> Unit,
    onConfirm: (Int, Int, Uri?, Set<Int>, String?, String?) -> Unit,
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
    // Mission state hoisted so all AlertDialog slots can access
    val missionTypeState = remember { mutableStateOf("none") }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Add New Alarm")
                Icon(
                    imageVector = Icons.Filled.AlarmAdd,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
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
                        Text(
                            text = String.format("%02d", displayHour),
                            fontSize = 44.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Colon separator
                    Text(
                        text = ":",
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
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
                                            timeMinute.value = (timeMinute.value + 1) % 60
                                        } else {
                                            // Swipe down - decrease minute
                                            timeMinute.value = if (timeMinute.value > 0) timeMinute.value - 1 else 59
                                        }
                                        hasTriggered = true
                                        totalDrag = 0f
                                    }
                                    change.consume()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = String.format("%02d", timeMinute.value),
                            fontSize = 44.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // AM/PM indicator - Make sure it updates correctly
                    Box(
                        modifier = Modifier
                            .width(60.dp)
                            .padding(start = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        // Nested Box to properly center the text
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primary)
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = amPm, // This should update correctly now
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }

                // Day selection
                Text(
                    text = "Repeat",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
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

                // Mission selection
                Text(
                    text = "Mission",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .align(Alignment.Start)
                        .padding(top = 16.dp, bottom = 8.dp)
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { missionTypeState.value = "none" },
                        colors = ButtonDefaults.outlinedButtonColors(),
                        border = BorderStroke(1.dp, if (missionTypeState.value == "none") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                    ) { Text("None") }
                    OutlinedButton(
                        onClick = { missionTypeState.value = "password" },
                        colors = ButtonDefaults.outlinedButtonColors(),
                        border = BorderStroke(1.dp, if (missionTypeState.value == "password") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                    ) { Text("Password") }
                }
                // No per-alarm password input; uses globally set password in AlarmActivity

                // Ringtone selection
                Text(
                    text = "Ringtone",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
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
                        containerColor = MaterialTheme.colorScheme.primaryContainer
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
                                        // Handle glassy_bell resource properly
                                        if (selectedRingtoneUriState.value?.toString()?.contains("glassy_bell") == true) {
                                            "🎵 glassy_bell"
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
                                        if (selectedRingtoneUriState.value?.toString()?.contains("glassy_bell") == true) {
                                            "🎵 glassy_bell"
                                        } else {
                                            "🎵 Custom Ringtone"
                                        }
                                    }
                                }
                            }
                            Text(
                                text = ringtoneName,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowUp,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        timeHour.value,
                        timeMinute.value,
                        selectedRingtoneUriState.value,
                        selectedDaysState.value,
                        missionTypeState.value,
                        null
                    )
                },
                enabled = selectedRingtoneUriState.value != null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "Create Alarm",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismissRequest,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "Cancel",
                    color = MaterialTheme.colorScheme.onSurface
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

        val resourceId = context.resources.getIdentifier("glassy_bell", "raw", context.packageName)
        if (resourceId != 0) {
            list.add("🎵 glassy_bell" to Uri.parse("android.resource://${context.packageName}/$resourceId"))
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
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Select Ringtone")
                Icon(
                    imageVector = Icons.Filled.Audiotrack,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.height(400.dp)
            ) {
                Text(
                    text = "Tap on a ringtone to preview it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                LazyColumn(
                    modifier = Modifier.weight(1f)
                ) {
                    items(ringtones) { pair ->
                        val name = pair.first
                        val uri = pair.second
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
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
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.surfaceVariant
                            ),
                            elevation = CardDefaults.cardElevation(
                                defaultElevation = if (selectedRingtone.value == uri) 4.dp else 2.dp
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
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
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        else
                                            MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier
                                            .size(20.dp)
                                            .padding(end = 8.dp)
                                    )
                                    Text(
                                        text = name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (selectedRingtone.value == uri)
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        else
                                            MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (selectedRingtone.value == uri) {
                                    Icon(
                                        imageVector = Icons.Filled.CheckCircle,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                Text("Select")
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            ) {
                Text("Cancel")
            }
        }
    )
}
