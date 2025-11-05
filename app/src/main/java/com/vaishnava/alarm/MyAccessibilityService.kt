package com.vaishnava.alarm

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class MyAccessibilityService : AccessibilityService() {

    private val TAG = "MyAccessibilityService"
    private val handler = Handler(Looper.getMainLooper())
    private var broadcastReceiver: BroadcastReceiver? = null

    @Volatile private var monitorRunning = false
    private var lastSeenRecentsAt = 0L

    companion object {
        const val ACTION_OPEN_NOTIF = "com.vaishnava.alarm.ACTION_OPEN_NOTIF"
        const val ACTION_CLOSE_RECENTS_AND_SHOW_ALARM = "com.vaishnava.alarm.ACTION_CLOSE_RECENTS_AND_SHOW_ALARM"

        private const val MONITOR_POLL_MS = 50L       // poll every 50ms
        private const val MONITOR_TIMEOUT_MS = 2000L  // stop monitor after 2s inactivity
    }

    // Known Recents/Overview identifiers
    private val recentsIdentifiers = listOf(
        "recents",
        "RecentsActivity",
        "Overview",
        "com.android.systemui",
        "launcher3",
        "Quickstep"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Service connected")

        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }

        val f = IntentFilter().apply {
            addAction(ACTION_OPEN_NOTIF)
            addAction(ACTION_CLOSE_RECENTS_AND_SHOW_ALARM)
        }
        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_OPEN_NOTIF -> bringAlarmActivityToFront()
                    ACTION_CLOSE_RECENTS_AND_SHOW_ALARM -> startRecentsMonitor()
                }
            }
        }
        try { registerReceiver(broadcastReceiver, f) } catch (_: Throwable) {}
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == KeyEvent.KEYCODE_APP_SWITCH) {
            Log.d(TAG, "Swallowed RECENTS (APP_SWITCH)")
            startRecentsMonitor()
            return true
        }
        return super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        try {
            val pkg = event.packageName?.toString() ?: ""
            val cls = event.className?.toString() ?: ""
            if (looksLikeRecents(pkg, cls)) {
                lastSeenRecentsAt = System.currentTimeMillis()
                startRecentsMonitor()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "onAccessibilityEvent error: ${t.message}")
        }
    }

    private fun startRecentsMonitor() {
        if (monitorRunning) {
            lastSeenRecentsAt = System.currentTimeMillis()
            return
        }
        monitorRunning = true
        lastSeenRecentsAt = System.currentTimeMillis()

        showBlockerActivity()

        handler.post(object : Runnable {
            override fun run() {
                if (!monitorRunning) return
                val now = System.currentTimeMillis()

                if (now - lastSeenRecentsAt < MONITOR_TIMEOUT_MS) {
                    handler.postDelayed(this, MONITOR_POLL_MS)
                } else {
                    monitorRunning = false
                }
            }
        })
    }

    private fun looksLikeRecents(pkg: String?, cls: String?): Boolean {
        val combined = (pkg ?: "") + "/" + (cls ?: "")
        return recentsIdentifiers.any { combined.contains(it, ignoreCase = true) }
    }

    private fun bringAlarmActivityToFront() {
        try {
            val i = Intent(this, AlarmActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("from_accessibility", true)
            }
            startActivity(i)
        } catch (_: Throwable) {}
    }

    private fun showBlockerActivity() {
        try {
            val i = Intent(this, BlockerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(i)
        } catch (_: Throwable) {}
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        try { broadcastReceiver?.let { unregisterReceiver(it) } } catch (_: Throwable) {}
    }
}
