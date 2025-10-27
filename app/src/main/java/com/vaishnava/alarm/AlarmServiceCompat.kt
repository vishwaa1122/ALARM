package com.vaishnava.alarm

import android.content.Context
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.PowerManager
import android.util.Log

object AlarmServiceCompat {
    private const val TAG = "AlarmServiceCompat"
    private var wakeLock: PowerManager.WakeLock? = null
    private var fallbackRingtone: Ringtone? = null

    fun acquireWakeLock(context: Context) {
        try {
            if (wakeLock?.isHeld == true) return
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AlarmCompat:Wake")
            wakeLock?.setReferenceCounted(false)
            wakeLock?.acquire(10 * 60 * 1000L)
            Log.d(TAG, "acquireWakeLock acquired")
        } catch (t: Throwable) {
            Log.w(TAG, "acquireWakeLock failed: ${t.message}")
        }
    }

    fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "releaseWakeLock failed: ${t.message}")
        } finally {
            wakeLock = null
        }
    }

    fun playDefaultAlarmSound(context: Context, uri: Uri? = null) {
        stopDefaultAlarmSound()
        try {
            val playUri = uri ?: RingtoneHelper.getDefaultAlarmUri(context)
            fallbackRingtone = RingtoneManager.getRingtone(context, playUri)
            fallbackRingtone?.play()
            Log.d(TAG, "playDefaultAlarmSound playing $playUri")
        } catch (t: Throwable) {
            Log.w(TAG, "playDefaultAlarmSound failed: ${t.message}")
            fallbackRingtone = null
        }
    }

    fun stopDefaultAlarmSound() {
        try {
            fallbackRingtone?.let { if (it.isPlaying) it.stop() }
        } catch (t: Throwable) {
            Log.w(TAG, "stopDefaultAlarmSound failed: ${t.message}")
        } finally {
            fallbackRingtone = null
        }
    }
}
