package com.carteltv.tv.engine.stability

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.ui.screensaver.CartelScreensaver

/**
 * Elevated HomeAuthority — operations allowed once the app holds
 * WRITE_SECURE_SETTINGS (grantable from adb without root, or via
 * the in-app wizard's copied command).
 *
 * Each action returns a line describing what happened, for the restore
 * ledger and for Settings display. Every mutating call first SNAPSHOTS the
 * prior value into AppPreferences.authorityLedger.
 */
object HomeAuthority {

    const val LEDGER_SEP = "\n---\n"
    private fun ledger(): MutableSet<String> =
        AppPreferences.authorityLedger.split(LEDGER_SEP).filter { it.isNotBlank() }.toMutableSet()

    private fun push(op: String) {
        val set = ledger()
        set.add("${System.currentTimeMillis()}|$op")
        AppPreferences.authorityLedger = set.joinToString(LEDGER_SEP)
    }

    fun canWriteSecure(ctx: Context): Boolean =
        ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    fun currentScreensaver(ctx: Context): String? =
        Settings.Secure.getString(ctx.contentResolver, "screensaver_components")

    fun registerScreensaver(ctx: Context): String {
        if (!canWriteSecure(ctx)) return "Needs WRITE_SECURE_SETTINGS grant first"
        val mine = ComponentName(ctx, com.carteltv.tv.ui.screensaver.CartelScreensaver::class.java)
            .flattenToString()
        val prev = currentScreensaver(ctx)
        if (prev != null) push("screensaver:${prev}")
        Settings.Secure.putString(ctx.contentResolver, "screensaver_components", mine)
        return "Screensaver set to $mine (previous: ${prev ?: "none"})"
    }

    fun listenerGranted(ctx: Context): Boolean {
        val mine = ComponentName(ctx, NotificationListenerGuard::class.java).flattenToString()
        val cur = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: ""
        return cur.split(':').contains(mine)
    }

    fun grantRevivalSelf(ctx: Context): String {
        if (!canWriteSecure(ctx)) return "Needs WRITE_SECURE_SETTINGS grant first"
        val mine = ComponentName(ctx, NotificationListenerGuard::class.java).flattenToString()
        val cur = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: ""
        if (cur.split(':').contains(mine)) return "NotificationListenerGuard already granted"
        push("listeners:${cur}")
        val merged = (cur.split(':').filter { it.isNotBlank() } + mine).joinToString(":")
        Settings.Secure.putString(ctx.contentResolver, "enabled_notification_listeners", merged)
        return "NotificationListenerGuard granted."
    }

    fun homeSummary(ctx: Context): String {
        val resolver = ctx.packageManager.resolveActivity(
            Intent(android.content.Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
        )
        val pkg = resolver?.activityInfo?.packageName ?: "?"
        val mine = ctx.packageName
        return if (pkg == mine) "CartelTV owns HOME" else "HOME belongs to $pkg"
    }
}
