package com.vaishnava.alarm

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import com.vaishnava.alarm.data.AlarmRepository

class AlarmApplication : Application() {
    lateinit var repository: AlarmRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = AlarmRepository(this)
        
        // Set flag to detect force restart
        setForceRestartFlag()

        // Create dummy integrity check file
        createDummyIntegrityCheckFile()
        
        // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger initialization
        // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.i call
    }
    
    /**
     * Sets a flag to detect force restart so the app doesn't uninstall during restart
     */
    private fun setForceRestartFlag() {
        try {
            // Set flag in both regular and device protected storage
            getSharedPreferences("alarm_system", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("is_force_restart", true)
                .apply()
                
            // Also set in device protected storage if available
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                createDeviceProtectedStorageContext()
                    .getSharedPreferences("alarm_system", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("is_force_restart", true)
                    .apply()
            }
            
            Log.d("AlarmApp", "Force restart flag set to prevent uninstallation during restart")
        } catch (e: Exception) {
            Log.e("AlarmApp", "Failed to set force restart flag: ${e.message}")
        }
    }
    
    /**
     * Creates a dummy file in device-protected storage for app integrity checks.
     */
    private fun createDummyIntegrityCheckFile() {
        try {
            val dpsContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                createDeviceProtectedStorageContext() ?: this
            } else this

            val file = dpsContext.getFileStreamPath("dummy_integrity_check.txt")
            if (!file.exists()) {
                dpsContext.openFileOutput("dummy_integrity_check.txt", Context.MODE_PRIVATE).use {
                    it.write("integrity_check_data".toByteArray())
                }
                Log.d("AlarmApp", "dummy_integrity_check.txt created successfully.")
            } else {
                Log.d("AlarmApp", "dummy_integrity_check.txt already exists.")
            }
        } catch (e: Exception) {
            Log.e("AlarmApp", "Failed to create dummy_integrity_check.txt: ${e.message}", e)
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        // Clear force restart flag when app terminates normally
        try {
            getSharedPreferences("alarm_system", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("is_force_restart", false)
                .apply()
                
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                createDeviceProtectedStorageContext()
                    .getSharedPreferences("alarm_system", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("is_force_restart", false)
                    .apply()
            }
        } catch (_: Exception) {}
    }
}
