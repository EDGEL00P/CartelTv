package com.carteltv.tv.core

import android.content.Intent

/** Intents parsed from TV-provider rows are authored by other apps; never run them with our identity unchecked. */
object ProviderIntentSanitizer {

    private val ALLOWED_ACTIONS = setOf(Intent.ACTION_VIEW, Intent.ACTION_MAIN)
    private val GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

    /** Returns a launchable intent pinned to [ownerPackage], or null when the row asks for anything else. */
    fun fromProvider(rawUri: String, ownerPackage: String): Intent? {
        val parsed = runCatching { Intent.parseUri(rawUri, Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return null
        if (parsed.action != null && parsed.action !in ALLOWED_ACTIONS) return null
        val componentPackage = parsed.component?.packageName
        if (componentPackage != null && componentPackage != ownerPackage) return null
        if (parsed.`package` != null && parsed.`package` != ownerPackage) return null
        if (parsed.data?.scheme.equals("content", true) || parsed.data?.scheme.equals("file", true)) return null
        return Intent(parsed).apply {
            selector = null
            sourceBounds = null
            clipData = null
            setPackage(ownerPackage)
            flags = (flags and GRANT_FLAGS.inv()) or Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }
}
