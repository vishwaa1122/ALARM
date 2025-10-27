package com.vaishnava.alarm

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.util.Log

object RingtoneHelper {
    private const val TAG = "RingtoneHelper"

    fun getDefaultAlarmUri(context: Context): Uri {
        return try {
            val alarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            alarm ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        } catch (e: Exception) {
            Log.w(TAG, "getDefaultAlarmUri failed: ${e.message}")
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        }
    }

    fun parseUriSafe(uriString: String?): Uri? {
        return try {
            if (uriString.isNullOrBlank()) null else Uri.parse(uriString)
        } catch (t: Throwable) {
            Log.w(TAG, "parseUriSafe: bad uri $uriString", t)
            null
        }
    }

    fun getRingtoneTitle(context: Context, uri: Uri?): String {
        if (uri == null) return "Alarm"
        return try {
            // Special handling for Glossy Bell resource
            val packageName = context.packageName
            val resourceId = context.resources.getIdentifier("glassy_bell", "raw", packageName)
            val glossyBellUri = if (resourceId != 0) {
                Uri.parse("android.resource://$packageName/$resourceId")
            } else {
                Uri.parse("android.resource://$packageName/raw/glassy_bell")
            }
            
            if (uri == glossyBellUri) {
                "Glossy Bell"
            } else {
                RingtoneManager.getRingtone(context, uri)?.getTitle(context) ?: uri.toString()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "getRingtoneTitle failed: ${t.message}")
            uri.toString()
        }
    }
}
