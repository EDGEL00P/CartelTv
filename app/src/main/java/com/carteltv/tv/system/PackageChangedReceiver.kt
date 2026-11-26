package com.carteltv.tv.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.carteltv.tv.data.apps.InstalledAppsCache

/**
 * Keeps [InstalledAppsCache] synchronized in real time when apps are installed, updated, or removed.
 * Registered both in AndroidManifest.xml and dynamically in [MainActivity] (required for
 * implicit package broadcasts on Android 8+).
 */
class PackageChangedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action ?: return
        if (action !in WATCHED_ACTIONS) return
        val pkg = intent.data?.schemeSpecificPart
        if (pkg == context.packageName) return
        InstalledAppsCache.reload(context.applicationContext)
    }

    companion object {
        private val WATCHED_ACTIONS = setOf(
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REMOVED,
            Intent.ACTION_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_CHANGED,
            Intent.ACTION_PACKAGE_FULLY_REMOVED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )

        fun intentFilter(): IntentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
            addAction(Intent.ACTION_MY_PACKAGE_REPLACED)
            addDataScheme("package")
        }
    }
}
