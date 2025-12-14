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

class ForensicLoggerTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AlarmTheme {
                ForensicLoggerTestScreen()
            }
        }
    }
}

@Composable
fun ForensicLoggerTestScreen() {
    var logMessage by remember { mutableStateOf("Test forensic log message") }
    val context = LocalContext.current
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Forensic Logger Test",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 32.dp)
        )
        
        OutlinedTextField(
            value = logMessage,
            onValueChange = { logMessage = it },
            label = { Text("Log Message") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        )
        
        Button(
            onClick = {
                android.widget.Toast.makeText(context, "Logging disabled in this build", android.widget.Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            Text("Generate Test Logs & Notification")
        }
        
        Button(
            onClick = {
                android.widget.Toast.makeText(context, "Sharing disabled in this build", android.widget.Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            Text("Share Forensic Log Directly")
        }
        
        Button(
            onClick = {
                android.widget.Toast.makeText(context, "Logger removed from this build", android.widget.Toast.LENGTH_SHORT).show()
            }
        ) {
            Text("Test All Log Levels")
        }
    }
}