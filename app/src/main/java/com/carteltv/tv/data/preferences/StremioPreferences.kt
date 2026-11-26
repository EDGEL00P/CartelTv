package com.carteltv.tv.data.preferences

import android.content.SharedPreferences
import com.carteltv.tv.data.stremio.StremioClient

/**
 * Stremio preferences: addons list and cloud account.
 *
 * Extracted from [AppPreferences] — Stremio integration has its own responsibility.
 */
class StremioPreferences(private val sp: SharedPreferences) {

    fun addons(): MutableList<String> =
        sp.getString("addons", null)?.split("\n")?.filter { it.isNotBlank() }?.toMutableList()
            ?: mutableListOf()

    fun setAddons(list: List<String>) {
        sp.edit().putString("addons", list.joinToString("\n")).apply()
    }

    /** Serializes the read-modify-write cycle: the phone companion (IO) and the Settings UI
     *  (main) both mutate this list, and a bare RMW loses one side's update. */
    private val addonLock = Any()

    fun addAddon(url: String): Boolean = synchronized(addonLock) {
        val cur = addons()
        val norm = StremioClient.normalizeBaseUrl(url)
        if (norm.isBlank() || norm in cur) return false
        cur.add(norm); setAddons(cur); return true
    }

    fun removeAddon(url: String) = synchronized(addonLock) {
        val cur = addons(); cur.remove(url); setAddons(cur)
    }

    var stremioAuthKey: String
        get() = sp.getString("stremio_auth", "") ?: ""
        set(v) = sp.edit().putString("stremio_auth", v.trim()).apply()

    var stremioEmail: String
        get() = sp.getString("stremio_email", "") ?: ""
        set(v) = sp.edit().putString("stremio_email", v.trim()).apply()
}
