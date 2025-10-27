package com.vaishnava.alarm

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vaishnava.alarm.ui.theme.AlarmTheme
import android.util.Log
import android.widget.Toast
import java.io.File

class DiagnosticsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AlarmTheme {
                DiagnosticsScreen()
            }
        }
    }
}

@Composable
fun DiagnosticsScreen() {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val showDiagnosticsDialog = remember { mutableStateOf(false) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Text(
            text = "Alarm Diagnostics",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        Text(
            text = "Tools for testing and debugging your alarms",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(bottom = 24.dp)
        )
        
        Button(
            onClick = {
                // Test alarm
                val intent = Intent(context, TestAlarmActivity::class.java)
                context.startActivity(intent)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        ) {
            Text("Test Alarm Now")
        }
        
        // Add button for generating user diagnostic report
        Button(
            onClick = {
                showDiagnosticsDialog.value = true
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Text("Generate Diagnostic Report")
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = {
                // Go back to main activity
                (context as DiagnosticsActivity).finish()
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            Text("Back to Main")
        }
    }
    
    if (showDiagnosticsDialog.value) {
        AlertDialog(
            onDismissRequest = { showDiagnosticsDialog.value = false },
            title = { Text("Generate Diagnostic Report") },
            text = { Text("This will generate a diagnostic report and share it with you.") },
            confirmButton = {
                Button(
                    onClick = {
                        try {
                            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.generateUserDiagnosticReport call
                            // Generate user-friendly diagnostic report
                            // val reportFile = // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
                            // if (reportFile.exists()) {
                                // Share the report
                                // val uri = FileProvider.getUriForFile(
                                //     context,
                                //     "${context.packageName}.fileprovider",
                                //     reportFile
                                // )
                                // 
                                // val shareIntent = Intent().apply {
                                //     action = Intent.ACTION_SEND
                                //     type = "text/plain"
                                //     putExtra(Intent.EXTRA_STREAM, uri)
                                //     addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                // }
                                // 
                                // context.startActivity(Intent.createChooser(shareIntent, "Share Diagnostic Report"))
                            // } else {
                                Toast.makeText(context, "Failed to generate diagnostic report", Toast.LENGTH_LONG).show()
                            // }
                        } catch (e: Exception) {
                            Log.e("AlarmApp", "Error generating/sharing diagnostic report: ${e.message}", e)
                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                        showDiagnosticsDialog.value = false
                    },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Text("Generate & Share")
                }
            },
            dismissButton = {
                Button(
                    onClick = { showDiagnosticsDialog.value = false },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}
