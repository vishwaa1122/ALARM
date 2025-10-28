package com.vaishnava.alarm

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AlarmAdd
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.vaishnava.alarm.data.Alarm
import com.vaishnava.alarm.ui.theme.AlarmTheme
import com.vaishnava.alarm.util.PermissionUtils
import java.util.Calendar

class MainActivity : ComponentActivity() {

    private lateinit var alarmStorage: AlarmStorage
    private lateinit var alarmScheduler: AlarmScheduler
    private val alarmPermissionRequestCode = 1001
    private val batteryOptimizationRequestCode = 1002
    private val scheduleExactAlarmRequestCode = 1003

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        alarmStorage = AlarmStorage(this)
        alarmScheduler = AndroidAlarmScheduler(this)

        // Request necessary permissions
        requestNecessaryPermissions()

        // Start a background task to check for upcoming alarms
        startAlarmNotificationChecker()

        setContent {
            AlarmTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AlarmScreenContent(
                        alarmStorage = alarmStorage,
                        alarmScheduler = alarmScheduler,
                        scheduleAlarmCallback = { hour, minute, ringtoneUri, days ->
                            val alarmId = alarmStorage.getNextAlarmId()
                            Log.d("AlarmApp", "Creating alarm with ID: $alarmId")
                            Log.d("AlarmApp", "Alarm details - Hour: $hour, Minute: $minute, Ringtone: $ringtoneUri, Days: $days")

                            val finalDays = if (days.isEmpty()) null else days.toList()

                            val alarm = com.vaishnava.alarm.data.Alarm(
                                id = alarmId,
                                hour = hour,
                                minute = minute,
                                isEnabled = true,
                                ringtoneUri = ringtoneUri?.toString(),
                                days = finalDays
                            )
                            Log.d("AlarmApp", "Created alarm object with ID: ${alarm.id}")
                            alarmStorage.addAlarm(alarm)
                            alarmScheduler.schedule(alarm)
                            
                            // Show notification immediately after scheduling
                            showNotification(
                                "Alarm Scheduled",
                                "Your alarm is set for ${formatTime12Hour(hour, minute)}"
                            )
                            
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

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }

        if (!PermissionUtils.isIgnoringBatteryOptimizations(this)) {
            PermissionUtils.requestIgnoreBatteryOptimizations(this)
        }

        if (!PermissionUtils.canScheduleExactAlarms(this)) {
            PermissionUtils.requestScheduleExactAlarmsPermission(this)
        }
    }

    private fun startAlarmNotificationChecker() {
        // Check for upcoming alarms every minute
        val handler = android.os.Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                checkAndShowUpcomingAlarmNotification()
                // Schedule next check in 1 minute
                handler.postDelayed(this, 60 * 1000)
            }
        }
        handler.post(runnable)
    }

    private fun checkAndShowUpcomingAlarmNotification() {
        val nextAlarm = getNextScheduledAlarm()
        if (nextAlarm != null) {
            val timeUntilAlarm = getTimeUntilNextAlarmInMinutes(nextAlarm)
            // Show notification if alarm is within 1 minute
            if (timeUntilAlarm <= 1 && timeUntilAlarm >= 0) {
                showNotification(
                    "Next Alarm",
                    "Your next alarm is at ${formatTime12Hour(nextAlarm.hour, nextAlarm.minute)}"
                )
            }
        }
    }

    private fun getTimeUntilNextAlarmInMinutes(alarm: Alarm): Long {
        val nextAlarmTime = calculateNextAlarmTime(alarm)
        val now = System.currentTimeMillis()
        val diffInMillis = nextAlarmTime - now
        return diffInMillis / (1000 * 60)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            batteryOptimizationRequestCode -> {
                if (PermissionUtils.isIgnoringBatteryOptimizations(this)) {
                    Log.d("AlarmApp", "Battery optimization whitelist granted")
                } else {
                    Log.e("AlarmApp", "Battery optimization whitelist denied")
                }
            }
            scheduleExactAlarmRequestCode -> {
                if (PermissionUtils.canScheduleExactAlarms(this)) {
                    Log.d("AlarmApp", "Schedule exact alarm permission granted")
                } else {
                    Log.e("AlarmApp", "Schedule exact alarm permission denied")
                }
            }
        }
    }

    fun showNextAlarmNotification() {
        val nextAlarm = getNextScheduledAlarm()

        if (nextAlarm != null) {
            val timeUntilAlarm = getTimeUntilNextAlarm(nextAlarm)
            showNotification(
                "Next Alarm",
                "Your next alarm is at ${formatTime12Hour(nextAlarm.hour, nextAlarm.minute)} in $timeUntilAlarm"
            )
        } else {
            showNotification(
                "No Alarms",
                "You don't have any alarms scheduled"
            )
        }
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
                    alarmCalendar.add(Calendar.DAY_OF_YEAR, i)
                    return alarmCalendar.timeInMillis
                }
            }
        } else {
            if (alarmCalendar.timeInMillis <= now.timeInMillis) {
                alarmCalendar.add(Calendar.DAY_OF_YEAR, 1)
            }
            return alarmCalendar.timeInMillis
        }

        return alarmCalendar.timeInMillis
    }

    private fun getTimeUntilNextAlarm(alarm: Alarm): String {
        val nextAlarmTime = calculateNextAlarmTime(alarm)
        val now = System.currentTimeMillis()
        val diffInMillis = nextAlarmTime - now

        val minutes = diffInMillis / (1000 * 60)
        val hours = minutes / 60
        val remainingMinutes = minutes % 60

        return when {
            hours > 0 -> "${hours}h ${remainingMinutes}m"
            minutes > 0 -> "${minutes} minutes"
            else -> "less than a minute"
        }
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

    private fun scheduleAndSaveAlarm(
        hour: Int,
        minute: Int,
        ringtoneUri: Uri?,
        days: Set<Int>
    ): Alarm {
        val alarmId = alarmStorage.getNextAlarmId()
        val ringtoneUriString =
            ringtoneUri?.toString() ?: Settings.System.DEFAULT_ALARM_ALERT_URI.toString()

        val alarm = Alarm(
            id = alarmId,
            hour = hour,
            minute = minute,
            isEnabled = true,
            ringtoneUri = ringtoneUriString,
            days = days.toList(),
            isHidden = false
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
}

fun formatDays(days: List<Int>?): String {
    if (days.isNullOrEmpty()) return "No days selected"
    val dayMap = mapOf(
        Calendar.SUNDAY to "Su",
        Calendar.MONDAY to "M",
        Calendar.TUESDAY to "Tu",
        Calendar.WEDNESDAY to "W",
        Calendar.THURSDAY to "Th",
        Calendar.FRIDAY to "F",
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
    scheduleAlarmCallback: (Int, Int, Uri?, Set<Int>) -> Alarm,
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
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(Color(0xFF6A11CB), Color(0xFF2575FC)),
                                center = androidx.compose.ui.geometry.Offset(36.dp.value, 36.dp.value),
                                radius = 36.dp.value
                            ),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Alarm,
                        contentDescription = "Alarm Icon",
                        tint = Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                }

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
            }

            // Alarms header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.List,
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(28.dp),
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
                timeHour.value = 7
                timeMinute.value = 0
                showAddDialog.value = true
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .navigationBarsPadding(),
            containerColor = MaterialTheme.colorScheme.primary,
            elevation = FloatingActionButtonDefaults.elevation(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "Add Alarm",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(28.dp)
            )
        }

        // --------------- Add Alarm Dialog ----------------
        if (showAddDialog.value) {
            // Use a scrollable AlertDialog; time UI here is robust and won't wrap AM/PM
            AlertDialog(
                onDismissRequest = { showAddDialog.value = false },
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Add New Alarm", fontWeight = FontWeight.Bold)
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
                            .verticalScroll(rememberScrollState())
                            .fillMaxWidth()
                    ) {
                        // Top Time Card - replicates original visual style but ensures AM/PM never wraps
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f)
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Set Time",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )

                                // Large time display + AM/PM pills arranged horizontally with guaranteed spacing
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    val hour = timeHour.value
                                    val minute = timeMinute.value
                                    val period = if (hour >= 12) "PM" else "AM"
                                    val displayHour = when {
                                        hour == 0 -> 12
                                        hour > 12 -> hour - 12
                                        else -> hour
                                    }

                                    // big numeric group
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        // Hour display with swipe gestures
                                        Box(
                                            modifier = Modifier
                                                .size(80.dp)
                                                .pointerInput(Unit) {
                                                    var hasTriggered = false
                                                    
                                                    detectVerticalDragGestures(
                                                        onDragStart = {
                                                            hasTriggered = false
                                                        },
                                                        onDragEnd = {
                                                            hasTriggered = false
                                                        },
                                                        onDragCancel = {
                                                            hasTriggered = false
                                                        }
                                                    ) { change, dragAmount ->
                                                        // Only trigger once per complete drag gesture
                                                        if (!hasTriggered && kotlin.math.abs(dragAmount) > 15f) {
                                                            if (dragAmount < 0) {
                                                                // Swipe up - increase hour by 1
                                                                timeHour.value = (timeHour.value + 1) % 24
                                                            } else {
                                                                // Swipe down - decrease hour by 1
                                                                timeHour.value = if (timeHour.value > 0) timeHour.value - 1 else 23
                                                            }
                                                            hasTriggered = true
                                                            change.consume()
                                                        }
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

                                        Text(
                                            text = ":",
                                            fontSize = 44.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(horizontal = 8.dp)
                                        )

                                        // Minute display with swipe gestures
                                        Box(
                                            modifier = Modifier
                                                .size(80.dp)
                                                .pointerInput(Unit) {
                                                    var hasTriggered = false
                                                    
                                                    detectVerticalDragGestures(
                                                        onDragStart = {
                                                            hasTriggered = false
                                                        },
                                                        onDragEnd = {
                                                            hasTriggered = false
                                                        },
                                                        onDragCancel = {
                                                            hasTriggered = false
                                                        }
                                                    ) { change, dragAmount ->
                                                        // Only trigger once per complete drag gesture
                                                        if (!hasTriggered && kotlin.math.abs(dragAmount) > 15f) {
                                                            if (dragAmount < 0) {
                                                                // Swipe up - increase minute by 1
                                                                timeMinute.value = (timeMinute.value + 1) % 60
                                                            } else {
                                                                // Swipe down - decrease minute by 1
                                                                timeMinute.value = if (timeMinute.value > 0) timeMinute.value - 1 else 59
                                                            }
                                                            hasTriggered = true
                                                            change.consume()
                                                        }
                                                    }
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = String.format("%02d", minute),
                                                fontSize = 44.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(14.dp))

                                    // AM/PM selection using Buttons for reliable text display
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        // AM button
                                        Button(
                                            onClick = {
                                                if (timeHour.value >= 12) timeHour.value = timeHour.value - 12
                                            },
                                            modifier = Modifier
                                                .padding(bottom = 6.dp)
                                                .size(60.dp, 40.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (period == "AM") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                            ),
                                            shape = RoundedCornerShape(12.dp),
                                            contentPadding = PaddingValues(0.dp)
                                        ) {
                                            Text(
                                                text = "AM",
                                                fontWeight = if (period == "AM") FontWeight.Bold else FontWeight.Normal,
                                                color = if (period == "AM") MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                                fontSize = 18.sp,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }

                                        // PM button
                                        Button(
                                            onClick = {
                                                if (timeHour.value < 12) timeHour.value = (timeHour.value + 12) % 24
                                            },
                                            modifier = Modifier
                                                .size(60.dp, 40.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (period == "PM") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                            ),
                                            shape = RoundedCornerShape(12.dp),
                                            contentPadding = PaddingValues(0.dp)
                                        ) {
                                            Text(
                                                text = "PM",
                                                fontWeight = if (period == "PM") FontWeight.Bold else FontWeight.Normal,
                                                color = if (period == "PM") MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                                fontSize = 18.sp,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // Note: preserved style and simple guidance text
                                Text(
                                    text = "Tap numbers to adjust hour and minute. Tap AM/PM to switch.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                        }

                        // Days selection card (same logic)
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f)
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "Select Days",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                                val daysOfWeek = listOf(
                                    Pair(Calendar.SUNDAY, "Su"),
                                    Pair(Calendar.MONDAY, "Mo"),
                                    Pair(Calendar.TUESDAY, "Tu"),
                                    Pair(Calendar.WEDNESDAY, "We"),
                                    Pair(Calendar.THURSDAY, "Th"),
                                    Pair(Calendar.FRIDAY, "Fr"),
                                    Pair(Calendar.SATURDAY, "Sa")
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    daysOfWeek.forEach { (dayInt, dayString) ->
                                        DayToggleButton(
                                            day = dayInt,
                                            dayString = dayString,
                                            selectedDays = selectedDaysState.value,
                                            onDaySelected = { day, isSelected ->
                                                selectedDaysState.value =
                                                    if (isSelected) selectedDaysState.value + day else selectedDaysState.value - day
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // Ringtone card (same as before)
                        Card(
                            modifier = Modifier
                                .fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f)
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "Ringtone",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )

                                Button(
                                    onClick = { showRingtonePicker.value = true },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    )
                                ) {
                                    Text(
                                        "Select Ringtone",
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                }

                                if (selectedRingtoneUriState.value == null) {
                                    Text(
                                        text = "* Please select a ringtone",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(top = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            Log.d("AlarmApp", "Create Alarm button clicked")
                            Log.d("AlarmApp", "Current time - Hour: ${timeHour.value}, Minute: ${timeMinute.value}")
                            Log.d("AlarmApp", "Selected days: ${selectedDaysState.value}")
                            Log.d("AlarmApp", "Selected ringtone: ${selectedRingtoneUriState.value}")
                            val newAlarm = scheduleAlarmCallback(
                                timeHour.value,
                                timeMinute.value,
                                selectedRingtoneUriState.value,
                                selectedDaysState.value
                            )
                            val exists = alarms.any { it.id == newAlarm.id }
                            if (!exists) alarms.add(newAlarm)

                            selectedDaysState.value = emptySet()
                            selectedRingtoneUriState.value = null
                            showAddDialog.value = false
                        },
                        enabled = selectedRingtoneUriState.value != null,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF64FFDA)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "Create Alarm",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = { showAddDialog.value = false },
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
        }

        // Ringtone picker dialog (unchanged behavior)
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
                Log.d("DayToggleButton", "Day $dayString clicked, isSelected: ${!isSelected}")
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
                    items(ringtones) { (name, uri) ->
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
            .padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (alarm.isEnabled) Icons.Filled.Alarm else Icons.Filled.AlarmOff,
                        contentDescription = null,
                        tint = if (alarm.isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier
                            .size(20.dp)
                            .padding(end = 8.dp)
                    )
                    Text(
                        text = formatTime12Hour(alarm.hour, alarm.minute),
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (alarm.isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                    )
                }
                Text(
                    text = formatDays(alarm.days),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                val ringtoneTitle = remember(alarm.ringtoneUri) {
                    try {
                        if (alarm.ringtoneUri != null) {
                            if (alarm.ringtoneUri.contains("glassy_bell")) {
                                return@remember "🎵 glassy_bell"
                            }
                            if (alarm.ringtoneUri.startsWith("android.resource://")) {
                                val segments = alarm.ringtoneUri.split("/")
                                if (segments.size > 0) {
                                    val resourceId = segments.last()
                                    try {
                                        val resId = resourceId.toInt()
                                        val resourceName = context.resources.getResourceEntryName(resId)
                                        return@remember "🎵 $resourceName"
                                    } catch (e: Exception) {
                                        return@remember "🎵 Custom Ringtone"
                                    }
                                }
                            }
                            val uri = Uri.parse(alarm.ringtoneUri)
                            val ringtone = RingtoneManager.getRingtone(context, uri)
                            ringtone?.getTitle(context) ?: "Unknown Ringtone"
                        } else {
                            "Unknown Ringtone"
                        }
                    } catch (e: Exception) {
                        "Error getting ringtone name"
                    }
                }
                Text(
                    text = ringtoneTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.Center
            ) {
                Switch(
                    checked = alarm.isEnabled,
                    onCheckedChange = { onToggleEnable(alarm, it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surface
                    )
                )
                Spacer(Modifier.height(10.dp))
                IconButton(
                    onClick = { onRemove(alarm) },
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(10.dp)
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
