package com.carteltv.tv.data.preferences

import android.content.SharedPreferences
import com.carteltv.tv.data.stremio.StremioClient

/**
 * Stremio preferences: addons list and cloud account.
 *
 * Extracted from [AppPreferences] — Stremio integration has its own responsibility.
 */
class StremioPreferences(private val sp: SharedPreferences) {

    /**
     * The installed addons in priority order. Cinemeta is seeded once as an ordinary first entry (it used to be
     * pinned in code), so a fresh install has metadata and the user can then reorder or remove it like Stremio/Nuvio.
     */
    fun addons(): MutableList<String> {
        val saved = sp.getString("addons", null)?.split("\n")?.filter { it.isNotBlank() }?.toMutableList() ?: mutableListOf()
        if (sp.getBoolean(SEEDED, false)) return saved
        synchronized(addonLock) {
            if (StremioClient.CINEMETA !in saved) saved.add(0, StremioClient.CINEMETA)
            sp.edit().putString("addons", saved.joinToString("\n")).putBoolean(SEEDED, true).apply()
        }
        return saved
    }

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

    private companion object {
        const val SEEDED = "addons_cinemeta_seeded"
    }
}
