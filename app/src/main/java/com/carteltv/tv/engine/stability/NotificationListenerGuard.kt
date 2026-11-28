package com.carteltv.tv.engine.stability

import android.content.ComponentName
import android.os.UserManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.watch.WatchProgressRecorder
import com.carteltv.tv.nav.AppLauncher
import com.carteltv.tv.engine.stability.NotificationMirror

/**
 * Unified NotificationListenerService: handles process resurrection after firmware kills
 * AND captures live notifications into [NotificationMirror] for the Home toast + Diagnostics view.
 * Also starts [WatchProgressRecorder] for "Watched on this TV" history.
 */
class NotificationListenerGuard : NotificationListenerService() {

    private var recorder: WatchProgressRecorder? = null

    override fun onListenerConnected() {
        NotificationMirror.connected = true
        runCatching { activeNotifications?.forEach { NotificationMirror.upsert(it) } }
        val um = getSystemService(UserManager::class.java)
        if (um != null && !um.isUserUnlocked) return
        if (!CrashWatchdog.tooMany(this)) {
            runCatching {
                startActivity(com.carteltv.tv.nav.AppLauncher.mainIntent(this))
            }
        }
        recorder?.stop()
        recorder = WatchProgressRecorder(applicationContext).also { it.start() }
    }

    override fun onListenerDisconnected() {
        NotificationMirror.connected = false
        recorder?.stop()
        recorder = null
        requestRebind(ComponentName(this, NotificationListenerGuard::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        NotificationMirror.upsert(sbn)
        if (AppPreferences.noticePopups) NotificationMirror.pulse()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NotificationMirror.remove(sbn.key)
    }

    override fun onDestroy() {
        recorder?.stop()
        recorder = null
        super.onDestroy()
    }
}

