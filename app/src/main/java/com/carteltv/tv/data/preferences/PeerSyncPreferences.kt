package com.carteltv.tv.data.preferences

import android.content.SharedPreferences
import com.carteltv.tv.engine.stability.SecretVault

/**
 * Multi-TV LAN PeerSync preferences (opt-in; secrets never synced).
 *
 * Extracted from [AppPreferences] — peer-to-peer sync settings have their own responsibility.
 */
class PeerSyncPreferences(private val sp: SharedPreferences) {

    var peerSyncRole: String
        get() = sp.getString("peer_role", "off") ?: "off"
        set(v) = sp.edit().putString("peer_role", v).apply()

    var peerSyncHost: String
        get() = sp.getString("peer_host", "") ?: ""
        set(v) = sp.edit().putString("peer_host", v.trim()).apply()

    var peerSyncPort: Int
        get() = sp.getInt("peer_port", 0)
        set(v) = sp.edit().putInt("peer_port", v).apply()

    var peerSyncKey: String
        get() = SecretVault.decrypt(sp.getString("peer_key", null)) ?: ""
        set(v) = sp.edit().putString("peer_key", SecretVault.encrypt(v.trim())).apply()

    var peerSyncGroups: Set<String>
        get() = sp.getStringSet("peer_groups", null) ?: setOf("look", "layout", "playback", "addons")
        set(v) = sp.edit().putStringSet("peer_groups", v).apply()
}
