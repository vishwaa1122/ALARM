package com.vaishnava.alarm.data

import android.net.Uri

data class Alarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val isEnabled: Boolean,
    val ringtoneUri: Uri?,
    val days: List<Int>? = emptyList(),
    val alarmTime: Long = 0L,        // Added to support rescheduling
    val isHidden: Boolean = false,
    val isProtected: Boolean = false,
    val missionType: String? = null,          // e.g., "none" or "password"
    val missionPassword: String? = null       // required if missionType == "password"
)