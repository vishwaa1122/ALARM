package com.vaishnava.alarm

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

class DebugLoggerActivity : Activity() {
    private val TAG = "DebugLogger"
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Create a simple layout programmatically
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }
        
        val title = android.widget.TextView(this).apply {
            text = "Debug Logger"
            textSize = 24f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(title)
        
        // Add developer mode toggle buttons
        val enableDevModeButton = Button(this).apply {
            text = "Enable Developer Mode (Detailed Logging)"
            setOnClickListener {
                val resultText = findViewById<TextView>(android.R.id.text1)
                resultText.text = "Developer mode not available in this build"
            }
        }
        layout.addView(enableDevModeButton)
        
        val disableDevModeButton = Button(this).apply {
            text = "Disable Developer Mode (User-Friendly Logging)"
            setOnClickListener {
                val resultText = findViewById<TextView>(android.R.id.text1)
                resultText.text = "Developer mode not available in this build"
            }
        }
        layout.addView(disableDevModeButton)
        
        val testButton = Button(this).apply {
            text = "Test File Paths"
            setOnClickListener {
                testFilePaths()
            }
        }
        layout.addView(testButton)
        
        val logButton = Button(this).apply {
            text = "Test Logging"
            setOnClickListener {
                testLogging()
            }
        }
        layout.addView(logButton)
        
        val shareButton = Button(this).apply {
            text = "Test Sharing"
            setOnClickListener {
                testSharing()
            }
        }
        layout.addView(shareButton)
        
        // Add a very simple button to share any available log file
        val simpleShareButton = Button(this).apply {
            text = "Share Any Log File"
            setOnClickListener {
                try {
                    val resultText = findViewById<TextView>(android.R.id.text1)
                    resultText.text = "Sharing disabled in this build"
                } catch (e: Exception) {
                    val resultText = findViewById<TextView>(android.R.id.text1)
                    resultText.text = "Error: ${e.message}"
                    Log.e(TAG, "Error sharing log", e)
                }
            }
        }
        layout.addView(simpleShareButton)
        
        // Add an even simpler button that just creates and shares a fresh log
        val freshLogButton = Button(this).apply {
            text = "Create & Share Fresh Log"
            setOnClickListener {
                try {
                    val resultText = findViewById<TextView>(android.R.id.text1)
                    resultText.text = "Logging/sharing disabled in this build"
                } catch (e: Exception) {
                    val resultText = findViewById<TextView>(android.R.id.text1)
                    resultText.text = "Error: ${e.message}"
                    Log.e(TAG, "Error creating/sharing log", e)
                }
            }
        }
        layout.addView(freshLogButton)
        
        // Add a button to save log to Downloads folder for easier access
        val saveToDownloadsButton = Button(this).apply {
            text = "Save Log to Downloads"
            setOnClickListener {
                try {
                    val resultText = findViewById<TextView>(android.R.id.text1)
                    resultText.text = "Saving logs disabled in this build"
                } catch (e: Exception) {
                    val resultText = findViewById<TextView>(android.R.id.text1)
                    resultText.text = "Error saving to Downloads: ${e.message}"
                    Log.e(TAG, "Error saving to Downloads", e)
                }
            }
        }
        layout.addView(saveToDownloadsButton)
        
        // Add button for generating developer diagnostic report
        val devDiagnosticButton = Button(this).apply {
            text = "Generate Developer Diagnostic Report"
            setOnClickListener {
                try {
                    val resultText = findViewById<TextView>(android.R.id.text1)
                    resultText.text = "Developer diagnostic report disabled in this build"
                } catch (e: Exception) {
                    val resultText = findViewById<TextView>(android.R.id.text1)
                    resultText.text = "Error: ${e.message}"
                    Log.e(TAG, "Error generating dev diagnostic report", e)
                }
            }
        }
        layout.addView(devDiagnosticButton)
        
        val resultText = TextView(this).apply {
            id = android.R.id.text1
            text = "Press buttons to test logger functionality"
        }
        layout.addView(resultText)
        
        setContentView(layout)
    }
    
    private fun testFilePaths() {
        try {
            val externalDir = getExternalFilesDir(null)
            val filesDir = filesDir
            
            val result = StringBuilder()
            result.append("=== File Paths ===\n")
            result.append("External Files Dir: ${externalDir?.absolutePath}\n")
            result.append("Files Dir: ${filesDir?.absolutePath}\n")
            result.append("External Dir Exists: ${externalDir?.exists()}\n")
            result.append("Files Dir Exists: ${filesDir?.exists()}\n")
            
            // Test creating a file in external directory
            if (externalDir != null) {
                val testFile = File(externalDir, "test_file.txt")
                try {
                    testFile.writeText("Test content")
                    result.append("Test file created successfully: ${testFile.absolutePath}\n")
                    result.append("Test file exists: ${testFile.exists()}\n")
                    result.append("Test file size: ${testFile.length()}\n")
                    testFile.delete()
                } catch (e: Exception) {
                    result.append("Failed to create test file: ${e.message}\n")
                }
            }
            
            val resultText = findViewById<TextView>(android.R.id.text1)
            resultText.text = result.toString()
        } catch (e: Exception) {
            val resultText = findViewById<TextView>(android.R.id.text1)
            resultText.text = "Error testing file paths: ${e.message}"
            Log.e(TAG, "Error testing file paths", e)
        }
    }
    
    private fun testLogging() {
        try {
            val result = StringBuilder()
            result.append("=== Testing Logging ===\n")
            result.append("Logging is disabled in this build.\n")
            result.append("No files were generated.\n")
            
            val resultText = findViewById<TextView>(android.R.id.text1)
            resultText.text = result.toString()
        } catch (e: Exception) {
            val resultText = findViewById<TextView>(android.R.id.text1)
            resultText.text = "Error testing logging: ${e.message}"
            Log.e(TAG, "Error testing logging", e)
        }
    }
    
    private fun testSharing() {
        try {
            val result = StringBuilder()
            result.append("=== Testing Sharing ===\n")
            result.append("Sharing is disabled in this build.\n")
            
            val resultText = findViewById<TextView>(android.R.id.text1)
            resultText.text = result.toString()
        } catch (e: Exception) {
            val resultText = findViewById<TextView>(android.R.id.text1)
            resultText.text = "Error testing sharing: ${e.message}"
            Log.e(TAG, "Error testing sharing", e)
        }
    }
    
    private fun shareAlarmLog() {
        try {
            val result = StringBuilder()
            result.append("=== Sharing Alarm Log ===\n")
            result.append("Sharing is disabled in this build.\n")
            
            val resultText = findViewById<TextView>(android.R.id.text1)
            resultText.text = result.toString()
        } catch (e: Exception) {
            val resultText = findViewById<TextView>(android.R.id.text1)
            resultText.text = "Error sharing log: ${e.message}"
            Log.e(TAG, "Error sharing log", e)
        }
    }
}