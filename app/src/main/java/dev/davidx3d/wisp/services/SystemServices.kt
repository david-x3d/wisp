package dev.davidx3d.wisp.services

import android.accessibilityservice.AccessibilityService
import android.app.assist.AssistStructure
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Feeds [NotificationStore]; also unlocks MediaSession access for the media tools. */
class WispNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        NotificationStore.connected = true
        runCatching { NotificationStore.replaceAll(this, activeNotifications) }
    }

    override fun onListenerDisconnected() {
        NotificationStore.connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = NotificationStore.post(this, sbn)
    override fun onNotificationRemoved(sbn: StatusBarNotification) = NotificationStore.remove(sbn.key)
}

/**
 * Optional: reads the text of the current screen for the `read_screen` tool. It does not
 * react to events or perform actions; it only walks the active window on demand.
 */
class WispAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    fun screenText(): String? {
        val root = rootInActiveWindow ?: return null
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40 || sb.length > 8000) return
            val t = n.text?.toString() ?: n.contentDescription?.toString()
            if (!t.isNullOrBlank() && n.isVisibleToUser && !sb.contains(t)) sb.append(t.trim()).append('\n')
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return sb.toString().trim().ifEmpty { null }
    }

    companion object {
        @Volatile var instance: WispAccessibilityService? = null
            private set
    }
}

/** Screen text the system hands to the assistant session (if "use text from screen" is on). */
object ScreenContext {
    @Volatile private var text: String? = null
    @Volatile private var at = 0L

    fun set(structure: AssistStructure?) {
        if (structure == null) return
        val sb = StringBuilder()
        fun walk(n: AssistStructure.ViewNode?, depth: Int) {
            if (n == null || depth > 40 || sb.length > 8000) return
            val t = n.text?.toString() ?: n.contentDescription?.toString()
            if (!t.isNullOrBlank() && n.visibility == View.VISIBLE) sb.append(t.trim()).append('\n')
            for (i in 0 until n.childCount) walk(n.getChildAt(i), depth + 1)
        }
        for (w in 0 until structure.windowNodeCount) walk(structure.getWindowNodeAt(w).rootViewNode, 0)
        text = sb.toString().trim().ifEmpty { null }
        at = System.currentTimeMillis()
    }

    /** Only valid for a few minutes; after that the screen has likely changed. */
    fun latest(): String? = text?.takeIf { System.currentTimeMillis() - at < 5 * 60_000 }

    fun clear() {
        text = null
    }
}
