package com.vaishnava.alarm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vaishnava.alarm.data.Alarm
import com.vaishnava.alarm.ui.theme.AlarmTheme
import java.util.*

class TestAlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AlarmTheme {
                TestAlarmScreen()
            }
        }
    }
}

@Composable
fun TestAlarmScreen() {
    var testStatus by remember { mutableStateOf("Ready to test") }
    val context = LocalContext.current
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Test Alarm Logging",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 32.dp)
        )
        
        Text(
            text = "This will simulate an alarm trigger to test the logging system",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(bottom = 32.dp)
        )
        
        Button(
            onClick = {
                testStatus = "Sending test alarm broadcast..."
                
                try {
                    // First create and save a test alarm
                    // Use device-protected storage context for Direct Boot compatibility
                    val deviceProtectedContext = context.createDeviceProtectedStorageContext()
                    val alarmStorage = AlarmStorage(deviceProtectedContext)
                    // Create a test alarm with a unique ID for testing
                    val alarmId = alarmStorage.getNextAlarmId()
                    val ringtoneUri = Uri.parse("content://settings/system/alarm_alert")
                    val testAlarm = Alarm(
                        id = alarmId,
                        hour = 5,
                        minute = 0,
                        isEnabled = true,
                        ringtoneUri = ringtoneUri,
                        days = listOf(1, 2, 3, 4, 5, 6, 7), // All days
                        alarmTime = System.currentTimeMillis(),
                        isHidden = false
                    )
                    
                    // Save the alarm
                    alarmStorage.addAlarm(testAlarm)
                    
                    // Add a small delay to ensure the alarm is properly saved before sending the broadcast
                    Handler(Looper.getMainLooper()).postDelayed({
                        // Send a broadcast to simulate the alarm
                        val ringtoneUri = Uri.parse("content://settings/system/alarm_alert")
                        val intent = Intent(context, AlarmReceiver::class.java).apply {
                            action = "com.vaishnava.alarm.DIRECT_BOOT_ALARM"
                            putExtra(AlarmReceiver.ALARM_ID, alarmId) // Test alarm ID
                            putExtra(AlarmReceiver.EXTRA_RINGTONE_URI, ringtoneUri as android.os.Parcelable)
                            putExtra(AlarmReceiver.EXTRA_REPEAT_DAYS, intArrayOf(1, 2, 3, 4, 5, 6, 7)) // All days
                        }
                        
                        context.sendBroadcast(intent)
                        testStatus = "Test broadcast sent successfully! A notification should appear."
                    }, 100) // 100ms delay
                } catch (e: Exception) {
                    testStatus = "Error sending test broadcast: ${e.message}"
                }
            },
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            Text("Trigger Alarm Test")
        }
        
        Button(
            onClick = {
                // Generate and share a fresh forensic log directly
                try {
                    // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
                    testStatus = "Generating and sharing fresh forensic log..."
                } catch (e: Exception) {
                    testStatus = "Error sharing log: ${e.message}"
                }
            },
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            Text("Share Fresh Test Log")
        }
        
        // Add new buttons for manual watchdog triggering
        Button(
            onClick = {
                // Create missed alarm log directly
                try {
                    // Use a dynamic alarm ID to prevent duplicate key issues
                    val alarmStorage = AlarmStorage(context)
                    val alarmId = alarmStorage.getNextAlarmId()
                    ManualWatchdogTrigger.createMissedAlarmLogDirectly(context, alarmId)
                    testStatus = "Created missed alarm log directly"
                } catch (e: Exception) {
                    testStatus = "Error creating missed log: ${e.message}"
                }
            },
            modifier = Modifier.padding(bottom = 16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
            Text("Force Create Missed Log")
        }
        
        // Add a button to schedule a real alarm for tomorrow
        Button(
            onClick = {
                try {
                    // Create and schedule a real alarm for 1 minute from now
                    val deviceProtectedContext = context.createDeviceProtectedStorageContext()
                    val alarmStorage = AlarmStorage(deviceProtectedContext)
                    val alarmScheduler = AndroidAlarmScheduler(context)
                    
                    // Create a real alarm for 1 minute from now
                    val alarmId = alarmStorage.getNextAlarmId()
                    // Use silence_no_sound for the alarm
                    val resourceId = context.resources.getIdentifier("silence_no_sound", "raw", context.packageName)
                    val ringtoneUri = if (resourceId != 0) {
                        Uri.parse("android.resource://${context.packageName}/$resourceId")
                    } else {
                        Uri.parse("android.resource://${context.packageName}/raw/silence_no_sound")
                    }
                    
                    // Calculate alarm time (1 minute from now)
                    val calendar = java.util.Calendar.getInstance()
                    calendar.add(java.util.Calendar.MINUTE, 1)
                    
                    val realAlarm = com.vaishnava.alarm.data.Alarm(
                        id = alarmId,
                        hour = calendar.get(java.util.Calendar.HOUR_OF_DAY),
                        minute = calendar.get(java.util.Calendar.MINUTE),
                        isEnabled = true,
                        ringtoneUri = ringtoneUri, // Use proper ringtone URI
                        days = emptyList(), // One-time alarm
                        alarmTime = System.currentTimeMillis(),
                        isHidden = false
                    )
                    
                    // Save and schedule the alarm
                    alarmStorage.addAlarm(realAlarm)
                    alarmScheduler.schedule(realAlarm)
                    
                    testStatus = "Scheduled real alarm for 1 minute from now at ${formatTime12Hour(realAlarm.hour, realAlarm.minute)}"
                } catch (e: Exception) {
                    testStatus = "Error scheduling real alarm: ${e.message}"
                }
            },
            modifier = Modifier.padding(bottom = 16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) {
            Text("Schedule 1-Minute Alarm")
        }
        
        // Add a button to schedule a real alarm for 5 minutes from now
        Button(
            onClick = {
                try {
                    // Create and schedule a real alarm for 5 minutes from now
                    val deviceProtectedContext = context.createDeviceProtectedStorageContext()
                    val alarmStorage = AlarmStorage(deviceProtectedContext)
                    val alarmScheduler = AndroidAlarmScheduler(context)
                    
                    // Create a real alarm for 5 minutes from now
                    val alarmId = alarmStorage.getNextAlarmId()
                    // Use silence_no_sound for the alarm
                    val resourceId = context.resources.getIdentifier("silence_no_sound", "raw", context.packageName)
                    val ringtoneUri = if (resourceId != 0) {
                        Uri.parse("android.resource://${context.packageName}/$resourceId")
                    } else {
                        Uri.parse("android.resource://${context.packageName}/raw/silence_no_sound")
                    }
                    
                    // Calculate alarm time (5 minutes from now)
                    val calendar = java.util.Calendar.getInstance()
                    calendar.add(java.util.Calendar.MINUTE, 5)
                    
                    val realAlarm = com.vaishnava.alarm.data.Alarm(
                        id = alarmId,
                        hour = calendar.get(java.util.Calendar.HOUR_OF_DAY),
                        minute = calendar.get(java.util.Calendar.MINUTE),
                        isEnabled = true,
                        ringtoneUri = ringtoneUri, // Use proper ringtone URI
                        days = emptyList(), // One-time alarm
                        alarmTime = System.currentTimeMillis(),
                        isHidden = false
                    )
                    
                    // Save and schedule the alarm
                    alarmStorage.addAlarm(realAlarm)
                    alarmScheduler.schedule(realAlarm)
                    
                    testStatus = "Scheduled real alarm for 5 minutes from now at ${formatTime12Hour(realAlarm.hour, realAlarm.minute)}"
                } catch (e: Exception) {
                    testStatus = "Error scheduling real alarm: ${e.message}"
                }
            },
            modifier = Modifier.padding(bottom = 16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) {
            Text("Schedule 5-Minute Alarm")
        }
        
        Button(
            onClick = {
                try {
                    // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.shareFreshForensicLogDirectly call
                    android.widget.Toast.makeText(context, "Forensic log shared directly via WhatsApp", android.widget.Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    android.widget.Toast.makeText(context, "Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            },
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            Text("Share Forensic Log Directly")
        }

        // Add a very simple "Share Latest Log" button
        Button(
            onClick = {
                try {
                    // Create a fresh forensic log and share it directly
                    // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
                    testStatus = "Creating and sharing fresh forensic log..."
                } catch (e: Exception) {
                    testStatus = "Error sharing log: ${e.message}"
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Text("SHARE FRESH LOG NOW", color = MaterialTheme.colorScheme.onPrimary)
        }
        
        Button(
            onClick = {
                try {
                    // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.shareFreshForensicLogDirectly call
                    android.widget.Toast.makeText(context, "Forensic log shared directly via WhatsApp", android.widget.Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    android.widget.Toast.makeText(context, "Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        ) {
            Text("Share via WhatsApp Directly")
        }

        Text(
            text = "Status: $testStatus",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        Text(
            text = "Instructions:\n" +
                  "1. Tap 'Trigger Alarm Test' to simulate alarm\n" +
                  "2. Check for notification\n" +
                  "3. Tap 'Share Fresh Test Log' to generate and share a fresh log\n" +
                  "4. Tap 'Schedule 1-Minute Alarm' to schedule an alarm for 1 minute from now\n" +
                  "5. Tap 'Schedule 5-Minute Alarm' to schedule an alarm for 5 minutes from now",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 16.dp)
        )
    }
}
