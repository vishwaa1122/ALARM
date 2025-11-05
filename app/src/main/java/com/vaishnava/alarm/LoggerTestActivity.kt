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

class LoggerTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AlarmTheme {
                LoggerTestScreen()
            }
        }
    }
}

@Composable
fun LoggerTestScreen() {
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
            text = "Logger Test",
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
        
        Text(
            text = "Test Different Log Levels",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            Button(
                onClick = {
                    android.widget.Toast.makeText(context, "Debug: $message", android.widget.Toast.LENGTH_SHORT).show()
                }
            ) {
                Text("Debug")
            }
            
            Button(
                onClick = {
                    android.widget.Toast.makeText(context, "Info: $message", android.widget.Toast.LENGTH_SHORT).show()
                }
            ) {
                Text("Info")
            }
            
            Button(
                onClick = {
                    android.widget.Toast.makeText(context, "Warning: $message", android.widget.Toast.LENGTH_SHORT).show()
                }
            ) {
                Text("Warning")
            }
        }
        
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 32.dp)
        ) {
            Button(
                onClick = {
                    android.widget.Toast.makeText(context, "Error: $message", android.widget.Toast.LENGTH_SHORT).show()
                }
            ) {
                Text("Error")
            }
            
            Button(
                onClick = {
                    android.widget.Toast.makeText(context, "Success: $message", android.widget.Toast.LENGTH_SHORT).show()
                }
            ) {
                Text("Success")
            }
            
            Button(
                onClick = {
                    android.widget.Toast.makeText(context, "Alarm: $message", android.widget.Toast.LENGTH_SHORT).show()
                }
            ) {
                Text("Alarm")
            }
        }
        
        Text(
            text = "Specialized Logging",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 32.dp)
        ) {
            Button(
                onClick = {
                    android.widget.Toast.makeText(context, "Audio: $message", android.widget.Toast.LENGTH_SHORT).show()
                }
            ) {
                Text("Audio")
            }
            
            Button(
                onClick = {
                    android.widget.Toast.makeText(context, "Service: $message", android.widget.Toast.LENGTH_SHORT).show()
                }
            ) {
                Text("Service")
            }
            
            Button(
                onClick = {
                    android.widget.Toast.makeText(context, "Track: $message", android.widget.Toast.LENGTH_SHORT).show()
                }
            ) {
                Text("Track")
            }
        }
        
        Text(
            text = "Forensic Logging & Sharing",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        Button(
            onClick = {
                android.widget.Toast.makeText(context, "Forensic logging disabled in this build", android.widget.Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            Text("Generate Forensic Log")
        }
        
        Button(
            onClick = {
                android.widget.Toast.makeText(context, "Direct sharing disabled in this build", android.widget.Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            Text("Share Forensic Log Directly")
        }
        
        Button(
            onClick = {
                android.widget.Toast.makeText(context, "Sharing via chooser disabled in this build", android.widget.Toast.LENGTH_SHORT).show()
            }
        ) {
            Text("Share via WhatsApp Chooser")
        }
    }
}