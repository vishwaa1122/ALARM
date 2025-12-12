package com.vaishnava.alarm.sequencer

data class MissionSpec(
    val id: String,
    val params: Map<String, String> = emptyMap(),
    val timeoutMs: Long = 30000L,
    val retryCount: Int = 3,
    val sticky: Boolean = false,
    val retryDelayMs: Long = 1000L
)
