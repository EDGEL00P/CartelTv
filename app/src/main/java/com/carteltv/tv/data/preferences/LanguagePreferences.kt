package com.carteltv.tv.data.preferences

import android.content.SharedPreferences
import com.carteltv.tv.data.xtream.LanguageFilter

/**
 * Language / content filtering preferences.
 *
 * Extracted from [AppPreferences] — language filtering has its own responsibility.
 */
class LanguagePreferences(private val sp: SharedPreferences) {

    /** Hide live channels, movies and series that are in a language not picked in [contentLanguages]. */
    var englishOnly: Boolean
        get() = sp.getBoolean("english_only", true)
        set(v) = sp.edit().putBoolean("english_only", v).apply()

    /** Language codes to keep when [englishOnly] filtering is on; English until the viewer picks others. */
    var contentLanguages: Set<LanguageFilter.Lang>
        get() = LanguageFilter.Lang.fromCodes(sp.getString("content_languages", "EN") ?: "EN")
            .ifEmpty { setOf(LanguageFilter.Lang.EN) }
        set(v) = sp.edit().putString("content_languages", v.joinToString(",") { it.code }).apply()

    /** Preferred audio language code (e.g. "eng", "fre"). */
    var preferredAudioLanguage: String
        get() = sp.getString("pref_audio_lang", "") ?: ""
        set(v) = sp.edit().putString("pref_audio_lang", v.trim()).apply()

    /**
     * "auto" shows subtitles only when the audio is not in the viewer's language;
     * "off" never shows them; a language code always shows that language.
     */
    var preferredSubtitleLanguage: String
        get() = sp.getString("pref_sub_lang", "auto") ?: "auto"
        set(v) = sp.edit().putString("pref_sub_lang", v.trim()).apply()

    /** Recently used online subtitle language codes, most recent first. */
    fun subtitleLangs(): List<String> =
        sp.getString("subtitle_langs", null)?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

    fun addSubtitleLang(lang: String) {
        val cur = (listOf(lang) + subtitleLangs().filter { it != lang }).take(3)
        sp.edit().putString("subtitle_langs", cur.joinToString(",")).apply()
    }
}
