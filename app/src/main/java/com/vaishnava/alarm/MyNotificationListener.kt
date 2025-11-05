package com.vaishnava.alarm

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Minimal implementation to satisfy manifest declaration and avoid ClassNotFoundException.
 * You can expand this later to react to notifications as needed.
 */
class MyNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // no-op
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // no-op
    }
}
