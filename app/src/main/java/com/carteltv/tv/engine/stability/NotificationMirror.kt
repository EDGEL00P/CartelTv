package com.carteltv.tv.engine.stability

import android.service.notification.StatusBarNotification
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Process-wide live notification snapshot fed by [com.carteltv.tv.engine.stability.NotificationListenerGuard]. */
object NotificationMirror {
    data class Notice(
        val key: String,
        val pkg: String,
        val title: String,
        val text: String,
        val whenMs: Long,
        val ongoing: Boolean,
    )

    val items = mutableStateListOf<Notice>()
    var connected by mutableStateOf(false)

    /** latest-notice popup pulse (timestamp read by the Home overlay) */
    var lastPulseMs by mutableLongStateOf(0L)
        private set
    internal fun pulse() { lastPulseMs = System.currentTimeMillis() }

    @Synchronized
    fun upsert(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
        if (title.isBlank() && text.isBlank()) return
        val n = Notice(
            key = sbn.key ?: return,
            pkg = sbn.packageName,
            title = title,
            text = text,
            whenMs = sbn.notification.`when`,
            ongoing = sbn.isOngoing,
        )
        items.removeAll { it.key == n.key }
        items.add(0, n)
        if (items.size > 30) items.removeRange(30, items.size)
    }

    @Synchronized
    fun remove(key: String) {
        items.removeAll { it.key == key }
    }

}
