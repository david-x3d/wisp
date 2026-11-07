package dev.davidx3d.wisp.services

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A notification, flattened for the assistant. */
data class StoredNotification(
    val key: String,
    val packageName: String,
    val appName: String,
    val sender: String,
    val conversation: String?,
    val text: String,
    val time: Long,
    val isMessage: Boolean,
    internal val reply: Notification.Action?,
) {
    val canReply get() = reply != null
}

/**
 * In-memory list of recent notifications, filled by [WispNotificationListener]. Nothing is
 * written to disk: message content stays in RAM and is gone when the process dies.
 */
object NotificationStore {
    private const val MAX = 60
    private val _items = MutableStateFlow<List<StoredNotification>>(emptyList())
    val items: StateFlow<List<StoredNotification>> = _items.asStateFlow()

    @Volatile var connected = false
        internal set

    internal fun post(context: Context, sbn: StatusBarNotification) {
        if (sbn.packageName == context.packageName || sbn.isOngoing) return
        val parsed = parse(context, sbn) ?: return
        _items.value = (listOf(parsed) + _items.value.filterNot { it.key == parsed.key }).take(MAX)
    }

    internal fun remove(key: String) {
        _items.value = _items.value.filterNot { it.key == key }
    }

    internal fun replaceAll(context: Context, list: Array<StatusBarNotification>) {
        _items.value = list.filterNot { it.packageName == context.packageName || it.isOngoing }
            .mapNotNull { parse(context, it) }
            .sortedByDescending { it.time }
            .take(MAX)
    }

    private fun parse(context: Context, sbn: StatusBarNotification): StoredNotification? {
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val extras = n.extras
        val appName = runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)

        // MessagingStyle carries the real last message and its sender.
        @Suppress("DEPRECATION")
        val messages: Array<Parcelable>? = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        val last = messages?.lastOrNull() as? Bundle
        val sender = last?.getCharSequence("sender")?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: return null
        val text = last?.getCharSequence("text")?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: return null
        val conversation = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        val reply = n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        val isMessage = n.category == Notification.CATEGORY_MESSAGE || messages != null || reply != null
        return StoredNotification(sbn.key, sbn.packageName, appName, sender, conversation, text, sbn.postTime, isMessage, reply)
    }

    /** Sends [message] through the notification's inline reply action. */
    fun reply(context: Context, item: StoredNotification, message: String): Boolean {
        val action = item.reply ?: return false
        val inputs = action.remoteInputs ?: return false
        val intent = Intent()
        val results = Bundle()
        inputs.forEach { results.putCharSequence(it.resultKey, message) }
        RemoteInput.addResultsToIntent(inputs, intent, results)
        return runCatching {
            action.actionIntent.send(context, 0, intent)
            true
        }.getOrDefault(false)
    }
}
