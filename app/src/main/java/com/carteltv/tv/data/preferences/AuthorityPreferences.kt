package com.carteltv.tv.data.preferences

import android.content.SharedPreferences

/**
 * HomeAuthority / device assessment preferences: ledger, revival flag, assessment notes.
 *
 * Extracted from [AppPreferences] — launcher authority state has its own responsibility.
 */
class AuthorityPreferences(private val sp: SharedPreferences) {

    /** rolling restore ledger */
    var authorityLedger: String
        get() = sp.getString("auth_ledger", "") ?: ""
        set(v) = sp.edit().putString("auth_ledger", v).apply()

    /** one-time revival-listener attempt flag */
    var revivalGranted: Boolean
        get() = sp.getBoolean("rev_grant", false)
        set(v) = sp.edit().putBoolean("rev_grant", v).apply()

    /** device assessment already ran once */
    var assessedTuned: Boolean
        get() = sp.getBoolean("assessed", false)
        set(v) = sp.edit().putBoolean("assessed", v).apply()

    /** cached verdict for the settings card */
    var assessmentNotes: String
        get() = sp.getString("assess_notes", "") ?: ""
        set(v) = sp.edit().putString("assess_notes", v).apply()
}
