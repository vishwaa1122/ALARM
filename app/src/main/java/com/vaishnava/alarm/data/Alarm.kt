package com.vaishnava.alarm.data

import android.net.Uri

data class Alarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val isEnabled: Boolean,
    val ringtoneUri: String?,
    val days: List<Int>? = emptyList(),
    val alarmTime: Long = 0L,        // Added to support rescheduling
    val isHidden: Boolean = false
)
