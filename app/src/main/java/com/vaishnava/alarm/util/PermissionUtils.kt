package com.vaishnava.alarm.util

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

object PermissionUtils {
    
    /**
     * Check if the app has permission to schedule exact alarms
     */
    fun canScheduleExactAlarms(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.canScheduleExactAlarms()
        } else {
            true // Older versions don't require this permission
        }
    }
    
    /**
     * Request the SCHEDULE_EXACT_ALARM permission
     */
    fun requestScheduleExactAlarmsPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        }
    }
    
    /**
     * Check if the app has been whitelisted from battery optimization
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true // Older versions don't have battery optimization
        }
    }
    
    /**
     * Request to be whitelisted from battery optimization
     */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        }
    }
    
    /**
     * Check if all necessary permissions are granted for the alarm to work properly
     */
    fun hasAllRequiredPermissions(context: Context): Boolean {
        // Check basic permissions
        val hasWakeLockPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WAKE_LOCK
        ) == PackageManager.PERMISSION_GRANTED
        
        val hasBootCompletedPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECEIVE_BOOT_COMPLETED
        ) == PackageManager.PERMISSION_GRANTED
        
        // Check exact alarm permission (Android 12+)
        val hasExactAlarmPermission = canScheduleExactAlarms(context)
        
        // Check battery optimization
        val isIgnoringBatteryOptimization = isIgnoringBatteryOptimizations(context)
        
        return hasWakeLockPermission && 
               hasBootCompletedPermission && 
               hasExactAlarmPermission && 
               isIgnoringBatteryOptimization
    }
    
    /**
     * Check if the app has WAKE_LOCK permission
     */
    fun hasWakeLockPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WAKE_LOCK
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Check if the app can draw overlays (SYSTEM_ALERT_WINDOW)
     */
    fun canDrawOverlays(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true // Older versions don't require this permission
        }
    }
    
    /**
     * Check if the app has notification access
     */
    fun hasNotificationAccess(                 context: Context): Boolean {
        // This is a simplified check - in a real implementation you might want to check
        // if your notification listener service is enabled
        return true
    }
}
