package com.vaishnava.alarm.sequencer

import android.util.Log
import com.vaishnava.alarm.MainActivity
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MissionLogger {
    private const val TAG = "ChronaLog"
    @Volatile private var correlationId: String? = null
    @Volatile private var lastByComponent: MutableMap<String, Pair<String, Long>> = mutableMapOf()
    @Volatile private var lastByCompPrefix: MutableMap<String, Long> = mutableMapOf()
    @Volatile private var lastByBody: MutableMap<String, Long> = mutableMapOf()
    @Volatile private var suppressedPrefix: MutableMap<String, Int> = mutableMapOf()
    @Volatile private var suppressedBody: MutableMap<String, Int> = mutableMapOf()
    @Volatile private var verboseEnabled: Boolean = false
    @Volatile private var verboseThrottleMs: Long = 1500L
    @Volatile private var timestampEnabled: Boolean = false
    @Volatile private var perComponentThrottleMs: MutableMap<String, Long> = mutableMapOf()
    @Volatile private var globalDedupeMs: Long = 1000L
    @Volatile private var suppressedVerbosePrefixes: MutableSet<String> = mutableSetOf(
        "GET_QUEUE_SIZE",
        "IS_MISSION_RUNNING",
        "QUEUE_STORE_LOAD_EMPTY_VERBOSE"
    )

    fun setCorrelation(id: String?) { correlationId = id }
    fun setVerboseEnabled(enabled: Boolean) { verboseEnabled = enabled }
    fun setVerboseThrottle(windowMs: Long) { verboseThrottleMs = windowMs.coerceAtLeast(0L) }
    fun setTimestampEnabled(enabled: Boolean) { timestampEnabled = enabled }
    fun setComponentThrottle(component: String, windowMs: Long) {
        synchronized(this) { perComponentThrottleMs[component] = windowMs.coerceAtLeast(0L) }
    }
    fun setGlobalDedupeWindow(windowMs: Long) { globalDedupeMs = windowMs.coerceAtLeast(0L) }
    fun suppressVerbosePrefix(prefix: String) { synchronized(this) { suppressedVerbosePrefixes.add(prefix) } }
    fun allowVerbosePrefix(prefix: String) { synchronized(this) { suppressedVerbosePrefixes.remove(prefix) } }

    private fun kv(fields: Map<String, Any?>): String =
        fields.entries.joinToString(" ") { (k, v) -> "$k=${(v ?: "-")}" }

    private fun emit(level: String, component: String, message: String?, fields: Map<String, Any?>) {
        val cid = correlationId
        val comp = component.ifBlank { "App" }
        val msg = message?.takeIf { it.isNotBlank() }
        val body = buildString {
            append("[")
            append(level)
            append("]")
            if (cid != null) {
                append(" ")
                append(cid)
            }
            append(" ")
            append(comp)
            if (msg != null) {
                append(" ")
                append(msg)
            }
            val kvs = kv(fields)
            if (kvs.isNotBlank()) {
                append(" ")
                append(kvs)
            }
        }
        // Optional throttle for noisy components by message prefix (applies to all components)
        val now = System.currentTimeMillis()
        val prefix = run {
            // Verbose global switch
            if (comp == "Verbose" && !verboseEnabled) return
            val p = when {
                msg != null && ':' in msg -> msg.substringBefore(':')
                msg != null && ' ' in msg -> msg.substringBefore(' ')
                else -> msg ?: ""
            }
            if (comp == "Verbose") {
                synchronized(this) {
                    if (suppressedVerbosePrefixes.contains(p)) return
                }
            }
            val key = "$comp|$p"
            val window = synchronized(this) { perComponentThrottleMs[comp] } ?: (
                if (comp == "Verbose") verboseThrottleMs else 0L
            )
            if (window > 0) {
                synchronized(this) {
                    val prevTs = lastByCompPrefix[key]
                    if (prevTs != null && (now - prevTs) < window) {
                        val cur = suppressedPrefix[key] ?: 0
                        suppressedPrefix[key] = cur + 1
                        return
                    }
                    lastByCompPrefix[key] = now
                }
            }
            p
        }

        // Deduplicate: if the same component emits the same body within the window, skip
        synchronized(this) {
            val prev = lastByComponent[comp]
            if (prev != null) {
                val (prevBody, prevTs) = prev
                if (prevBody == body && (now - prevTs) < globalDedupeMs) {
                    val cur = suppressedBody[body] ?: 0
                    suppressedBody[body] = cur + 1
                    return
                }
            }
            lastByComponent[comp] = body to now
        }

        // Global body-based dedupe window (covers cross-component duplicates)
        synchronized(this) {
            val prevTs = lastByBody[body]
            if (prevTs != null && (now - prevTs) < globalDedupeMs) {
                val cur = suppressedBody[body] ?: 0
                suppressedBody[body] = cur + 1
                return
            }
            lastByBody[body] = now
        }

        // Build final line. Avoid timestamp here to prevent double time markers in UI overlay.
        val baseLine = if (timestampEnabled) {
            val ts = try { SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(now)) } catch (_: Exception) { "--:--:--.---" }
            "$ts $body"
        } else body
        // Append suppression summaries, if any
        val summary = synchronized(this) {
            val pKey = "$comp|$prefix"
            val s1 = suppressedPrefix.remove(pKey) ?: 0
            val s2 = suppressedBody.remove(body) ?: 0
            val total = s1 + s2
            if (total > 0) " (+$total suppressed)" else ""
        }
        val line = baseLine + summary
        
        // Write to external log file
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(now))
            val logFile = File("/sdcard/Download/alarm_debug.log")
            
            // CRITICAL: Clear old log file when basic START_WHEN_ALARM_FIRES logs are detected
            val shouldClearFile = msg?.contains("BASIC_START_WHEN_ALARM_FIRES") == true
            val writer = FileWriter(logFile, !shouldClearFile) // Don't append if clearing
            
            if (shouldClearFile) {
                writer.write("=== LOG CLEARED FOR NEW ALARM SESSION ===\n")
            }
            
            writer.append("[$timestamp] [SEQUENCER] $line\n")
            writer.close()
        } catch (e: Exception) {
            // Silently fail if we can't write to external storage
        }
        
        try {
            MainActivity.getInstance()?.let { activity ->
                activity.runOnUiThread {
                    try { activity.addSequencerLog(line) } catch (_: Exception) { }
                }
            }
        } catch (_: Exception) { }
    }

    fun d(component: String, message: String? = null, fields: Map<String, Any?> = emptyMap()) =
        emit("D", component, message, fields)
    fun w(component: String, message: String? = null, fields: Map<String, Any?> = emptyMap()) =
        emit("W", component, message, fields)
    fun e(component: String, message: String? = null, fields: Map<String, Any?> = emptyMap()) =
        emit("E", component, message, fields)

    // Backward-compat convenience
    fun log(message: String) = d("Sequencer", message)
    fun logWarning(message: String) = w("Sequencer", message)
    fun logError(message: String, throwable: Throwable? = null) = e("Sequencer", message, mapOf("err" to (throwable?.message ?: "")))
    fun logVerbose(message: String) = d("Verbose", message)
}
