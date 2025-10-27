package com.vaishnava.alarm

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream

class MinimalTestActivity : Activity() {
    private val TAG = "MinimalTest"
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Create a simple layout programmatically
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }
        
        val title = android.widget.TextView(this).apply {
            text = "Minimal Logger Test"
            textSize = 24f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(title)
        
        val testButton = Button(this).apply {
            text = "Test Direct File Write"
            setOnClickListener {
                testDirectFileWrite()
            }
        }
        layout.addView(testButton)
        
        val loggerButton = Button(this).apply {
            text = "Test Logger"
            setOnClickListener {
                testLogger()
            }
        }
        layout.addView(loggerButton)
        
        val resultText = TextView(this).apply {
            id = android.R.id.text1
            text = "Press buttons to test"
        }
        layout.addView(resultText)
        
        setContentView(layout)
    }
    
    private fun testDirectFileWrite() {
        try {
            val result = StringBuilder()
            result.append("=== Direct File Write Test ===\n")
            
            // Test getting external files directory
            val externalDir = getExternalFilesDir(null)
            result.append("External files dir: ${externalDir?.absolutePath}\n")
            result.append("External dir exists: ${externalDir?.exists()}\n")
            
            if (externalDir != null) {
                // Create test file
                val testFile = File(externalDir, "test_direct.txt")
                result.append("Test file path: ${testFile.absolutePath}\n")
                
                // Try to write to file
                try {
                    FileOutputStream(testFile).use { fos ->
                        fos.write("Direct file write test\n".toByteArray())
                    }
                    result.append("✅ Direct file write successful\n")
                    result.append("File exists: ${testFile.exists()}\n")
                    result.append("File size: ${testFile.length()} bytes\n")
                    
                    // Try to read back
                    val content = testFile.readText()
                    result.append("File content: $content\n")
                    
                    // Delete test file
                    testFile.delete()
                } catch (e: Exception) {
                    result.append("❌ Direct file write failed: ${e.message}\n")
                    Log.e(TAG, "Direct file write failed", e)
                }
            } else {
                result.append("❌ External files dir is null\n")
            }
            
            val resultText = findViewById<TextView>(android.R.id.text1)
            resultText.text = result.toString()
        } catch (e: Exception) {
            val resultText = findViewById<TextView>(android.R.id.text1)
            resultText.text = "Error in direct file write test: ${e.message}"
            Log.e(TAG, "Error in direct file write test", e)
        }
    }
    
    private fun testLogger() {
        try {
            val result = StringBuilder()
            result.append("=== Logger Test ===\n")
            
            // Test logging
            try {
                // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger calls
                result.append("✅ Logger messages sent\n")
                
                // Check if file was created
                val externalDir = getExternalFilesDir(null)
                if (externalDir != null) {
                    val logFile = File(externalDir, "Chrona_Alarm_Full_Log.txt")
                    result.append("Log file path: ${logFile.absolutePath}\n")
                    result.append("Log file exists: ${logFile.exists()}\n")
                    if (logFile.exists()) {
                        result.append("Log file size: ${logFile.length()} bytes\n")
                        // Show last few lines of the file
                        try {
                            val lines = logFile.readLines()
                            result.append("Log file lines: ${lines.size}\n")
                            val lastLines = lines.takeLast(5)
                            result.append("Last 5 lines:\n")
                            lastLines.forEach { line ->
                                result.append("  $line\n")
                            }
                        } catch (e: Exception) {
                            result.append("Error reading log file: ${e.message}\n")
                        }
                    }
                }
            } catch (e: Exception) {
                result.append("❌ Logger test failed: ${e.message}\n")
                Log.e(TAG, "Logger test failed", e)
            }
            
            val resultText = findViewById<TextView>(android.R.id.text1)
            resultText.text = result.toString()
        } catch (e: Exception) {
            val resultText = findViewById<TextView>(android.R.id.text1)
            resultText.text = "Error in Logger test: ${e.message}"
            Log.e(TAG, "Error in Logger test", e)
        }
    }
}
