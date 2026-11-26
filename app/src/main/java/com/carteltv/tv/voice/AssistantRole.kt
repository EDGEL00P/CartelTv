package com.carteltv.tv.voice

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.app.role.RoleManager
import android.os.Build
import android.provider.Settings

/**
 * Cartel Assistant: claims the system ASSISTANT role so the remote mic button and voice-search keys
 * launch Cartel instead of Google's overlay. Preferred path is RoleManager (API 29+, user-confirmed);
 * older firmware falls back to a direct secure-settings write, which this app may perform because it
 * holds WRITE_SECURE_SETTINGS. Per the AOSP role contract the role holder must be an activity that
 * handles Intent.ACTION_ASSIST, so keep voice_interaction_service empty.
 */
object AssistantRole {

    private fun assistComponent(ctx: Context): ComponentName =
        ComponentName(ctx.packageName, "com.carteltv.tv.MainActivity")

    /** True when the system already routes the mic key to Cartel. */
    fun isAssistant(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = ctx.getSystemService(RoleManager::class.java)
            if (rm != null) return rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        }
        val current = Settings.Secure.getString(ctx.contentResolver, "assistant") ?: return false
        return current == assistComponent(ctx).flattenToString() ||
            current == assistComponent(ctx).flattenToShortString()
    }

    /**
     * Make Cartel the assistant. RoleManager shows the system confirmation dialog; on pre-29
     * firmware (no RoleManager) the settings write happens silently. Returns a short status line.
     */
    fun claim(activity: Activity): String {
        return if (Build.VERSION.SDK_INT >= 29) {
            val rm = activity.getSystemService(RoleManager::class.java)
            if (rm == null || !rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                "Assistant role not available on this device"
            } else if (rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
                "Cartel is already the assistant"
            } else {
                activity.startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT), 4712)
                "Confirm the Cartel Assistant dialog"
            }
        } else {
            // Pre-29 fallback: the activity assist path only needs the `assistant` setting.
            writeAssistantSetting(activity)
            if (isAssistant(activity)) "Cartel is now the assistant" else "Could not set Cartel as assistant"
        }
    }

    private fun writeAssistantSetting(ctx: Context): Boolean = runCatching {
        Settings.Secure.putString(ctx.contentResolver, "assistant", assistComponent(ctx).flattenToString())
        Settings.Secure.putString(ctx.contentResolver, "voice_interaction_service", "")
    }.isSuccess
}
