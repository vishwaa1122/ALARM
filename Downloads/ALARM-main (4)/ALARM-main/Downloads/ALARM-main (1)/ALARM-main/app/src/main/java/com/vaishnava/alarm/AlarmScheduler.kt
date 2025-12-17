package com.vaishnava.alarm

import com.vaishnava.alarm.data.Alarm // Added this import

interface AlarmScheduler {
    fun schedule(alarm: Alarm)
    fun cancel(alarm: Alarm)
}
