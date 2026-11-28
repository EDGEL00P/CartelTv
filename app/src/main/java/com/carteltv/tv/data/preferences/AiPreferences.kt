package com.carteltv.tv.data.preferences

import android.content.SharedPreferences
import com.carteltv.tv.engine.stability.SecretVault

/**
 * AI enhancement preferences: voice button, learned key, speak replies, MCP link secret.
 *
 * Extracted from [AppPreferences] — AI settings have their own responsibility.
 */
class AiPreferences(private val sp: SharedPreferences) {

    /** Show/hide AI voice button. */
    var aiVoiceButton: Boolean
        get() = sp.getBoolean("ai_voice_button", false)
        set(v) = sp.edit().putBoolean("ai_voice_button", v).apply()

    /** Learned remote key code for AI activation. */
    var aiLearnedKey: Int
        get() = sp.getInt("ai_learned_key", 0)
        set(v) = sp.edit().putInt("ai_learned_key", v).apply()

    /** AI speaks replies aloud. */
    var aiSpeakReplies: Boolean
        get() = sp.getBoolean("ai_speak_replies", false)
        set(v) = sp.edit().putBoolean("ai_speak_replies", v).apply()

    /** MCP connector secret for AI apps (SecretVault-backed). */
    var aiLinkSecret: String
        get() = SecretVault.decrypt(sp.getString("ai_link_secret", null)) ?: ""
        set(v) = sp.edit().putString("ai_link_secret", SecretVault.encrypt(v.trim())).apply()

    /** MCP connector enabled for AI apps. */
    var aiLinkEnabled: Boolean
        get() = sp.getBoolean("ai_link_enabled", false)
        set(v) = sp.edit().putBoolean("ai_link_enabled", v).apply()

    /** Ask on TV before AI plays a title remotely. */
    var aiConfirmRemotePlay: Boolean
        get() = sp.getBoolean("ai_confirm_remote", true)
        set(v) = sp.edit().putBoolean("ai_confirm_remote", v).apply()
}
