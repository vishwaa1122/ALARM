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
            // Special handling for bundled raw resources
            val packageName = context.packageName

            val glossyBellId = context.resources.getIdentifier("glassy_bell", "raw", packageName)
            val glossyBellUri = if (glossyBellId != 0) {
                Uri.parse("android.resource://$packageName/$glossyBellId")
            } else {
                Uri.parse("android.resource://$packageName/raw/glassy_bell")
            }

            val pyaroVrindavanId = context.resources.getIdentifier("pyaro_vrindavan", "raw", packageName)
            val pyaroVrindavanUri = if (pyaroVrindavanId != 0) {
                Uri.parse("android.resource://$packageName/$pyaroVrindavanId")
            } else {
                Uri.parse("android.resource://$packageName/raw/pyaro_vrindavan")
            }

            // Add new songs
            val agamKaalbhairavId = context.resources.getIdentifier("agam_kaalbhairav_ashtakam", "raw", packageName)
            val agamKaalbhairavUri = if (agamKaalbhairavId != 0) {
                Uri.parse("android.resource://$packageName/$agamKaalbhairavId")
            } else {
                Uri.parse("android.resource://$packageName/raw/agam_kaalbhairav_ashtakam")
            }

            val alaipayutheId = context.resources.getIdentifier("alaipayuthe", "raw", packageName)
            val alaipayutheUri = if (alaipayutheId != 0) {
                Uri.parse("android.resource://$packageName/$alaipayutheId")
            } else {
                Uri.parse("android.resource://$packageName/raw/alaipayuthe")
            }

            val gentleOceanId = context.resources.getIdentifier("gentle_ocean_and_birdsong_24068", "raw", packageName)
            val gentleOceanUri = if (gentleOceanId != 0) {
                Uri.parse("android.resource://$packageName/$gentleOceanId")
            } else {
                Uri.parse("android.resource://$packageName/raw/gentle_ocean_and_birdsong_24068")
            }

            val harekrishnaId = context.resources.getIdentifier("harekrishna_chant", "raw", packageName)
            val harekrishnaUri = if (harekrishnaId != 0) {
                Uri.parse("android.resource://$packageName/$harekrishnaId")
            } else {
                Uri.parse("android.resource://$packageName/raw/harekrishna_chant")
            }

            val krishnaFluteId = context.resources.getIdentifier("krishna_flute", "raw", packageName)
            val krishnaFluteUri = if (krishnaFluteId != 0) {
                Uri.parse("android.resource://$packageName/$krishnaFluteId")
            } else {
                Uri.parse("android.resource://$packageName/raw/krishna_flute")
            }

            val radhaKrishnaId = context.resources.getIdentifier("radha_krishna_flute", "raw", packageName)
            val radhaKrishnaUri = if (radhaKrishnaId != 0) {
                Uri.parse("android.resource://$packageName/$radhaKrishnaId")
            } else {
                Uri.parse("android.resource://$packageName/raw/radha_krishna_flute")
            }

            val silenceNoSoundId = context.resources.getIdentifier("silence_no_sound", "raw", packageName)
            val silenceNoSoundUri = if (silenceNoSoundId != 0) {
                Uri.parse("android.resource://$packageName/$silenceNoSoundId")
            } else {
                Uri.parse("android.resource://$packageName/raw/silence_no_sound")
            }

            when (uri) {
                glossyBellUri -> "Glossy Bell"
                pyaroVrindavanUri -> "Pyaro Vrindavan"
                agamKaalbhairavUri -> "Kaalbhairav Ashtakam"
                alaipayutheUri -> "Alaipayuthe"
                gentleOceanUri -> "Gentle Ocean & Birdsong"
                harekrishnaUri -> "Hare Krishna Chant"
                krishnaFluteUri -> "Krishna Flute"
                radhaKrishnaUri -> "Radha Krishna Flute"
                silenceNoSoundUri -> "Silence No Sound"
                else -> RingtoneManager.getRingtone(context, uri)?.getTitle(context) ?: uri.toString()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "getRingtoneTitle failed: ${t.message}")
            uri.toString()
        }
    }
}
