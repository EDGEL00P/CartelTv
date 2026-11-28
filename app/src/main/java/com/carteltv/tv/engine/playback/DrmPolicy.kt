package com.carteltv.tv.engine.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import com.carteltv.tv.data.xtream.M3uParser

/** Maps playlist-declared DRM to a Media3 configuration. No provider assumes a scheme it cannot name. */
object DrmPolicy {
    fun schemeUuid(scheme: String): java.util.UUID? = when (scheme.trim().lowercase()) {
        "widevine" -> C.WIDEVINE_UUID
        "playready" -> C.PLAYREADY_UUID
        "clearkey" -> C.CLEARKEY_UUID
        else -> null
    }

    fun configuration(info: M3uParser.DrmInfo, fallbackHeaders: Map<String, String>): MediaItem.DrmConfiguration? {
        val uuid = schemeUuid(info.scheme) ?: return null
        if (info.licenseUrl.isBlank()) return null
        return MediaItem.DrmConfiguration.Builder(uuid)
            .setLicenseUri(info.licenseUrl)
            .setLicenseRequestHeaders(info.headers.ifEmpty { fallbackHeaders })
            // Clear lead (e.g. DASH intro) plays while keys are still being fetched.
            .setPlayClearContentWithoutKey(true)
            .build()
    }
}
