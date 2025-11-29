package com.vaishnava.alarm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vaishnava.alarm.ui.theme.AlarmTheme

class TestLoggingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AlarmTheme {
                TestLoggingScreen()
            }
        }
    }
}

@Composable
fun TestLoggingScreen() {
    var message by remember { mutableStateOf("") }
    val context = LocalContext.current
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Test Logging System",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 32.dp)
        )
        
        OutlinedTextField(
            value = message,
            onValueChange = { message = it },
            label = { Text("Log Message") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        )
        
        Button(
            onClick = {
                // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger calls
            },
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            Text("Test All Log Levels")
        }
        
        Button(
            onClick = {
                // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.performForensicLogging call
            },
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            Text("Test Forensic Logging")
        }
        
        Button(
            onClick = {
                // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.createFreshForensicLog and UnifiedLogger.shareViaWhatsApp calls
            }
        ) {
            Text("Create & Share Forensic Log")
        }
    }
}
