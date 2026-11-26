package com.carteltv.tv.data.setup

import android.util.Base64
import com.carteltv.tv.data.preferences.AppPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * CONFIG-AS-CODE.
 * Whole launcher configuration compresses into a shareable code string.
 * Secrets NEVER travel — tokens and credentials stay in the vault.
 */
object SetupCodec {

    private const val MAX_DECODED_BYTES = 1 shl 20

    /** portable snapshot — non-secret prefs only */
    @Serializable
    data class Snapshot(
        val homeRows: List<String> = emptyList(),
        val hiddenApps: List<String> = emptyList(),
            val countdownTargetMs: Long = 0,
        val cwHideTitles: Boolean = false,
        val cwColumns: Int = 4,
        val bufferPreset: String = "STD",
        val ambience: Boolean = true,
        val city: String = "",
        val lat: Double = 0.0,
        val lon: Double = 0.0,
        val debridProvider: String = "rd",
        val overrideHomeButton: Boolean = true,
        val enhancedHomeDetection: Boolean = false,
        val overrideBackButton: Boolean = true,
        val startOnBoot: Boolean = true,
        val showWatchNext: Boolean = true,
    )

    private val json get() = com.carteltv.tv.data.network.HttpSupport.json

    /** Current configuration as a snapshot. */
    fun capture(): Snapshot = Snapshot(
        homeRows = AppPreferences.homeRows(),
        hiddenApps = AppPreferences.hiddenApps().toList(),
        countdownTargetMs = AppPreferences.countdownTargetMs,
        cwHideTitles = AppPreferences.cwHideTitles,
        cwColumns = AppPreferences.cwColumns,
        bufferPreset = AppPreferences.bufferPreset.name,
        ambience = AppPreferences.ambienceEnabled,
        city = AppPreferences.cityName,
        lat = AppPreferences.weatherLat,
        lon = AppPreferences.weatherLon,
        debridProvider = AppPreferences.debridProvider,
        overrideHomeButton = AppPreferences.overrideHomeButton,
        enhancedHomeDetection = AppPreferences.enhancedHomeDetection,
        overrideBackButton = AppPreferences.overrideBackButton,
        startOnBoot = AppPreferences.startOnBoot,
        showWatchNext = AppPreferences.showWatchNext,
    )

    /** Snapshot → CARTEL-….code (gzip + Crockford base32, dashed for reading aloud). */
    fun encode(s: Snapshot): String {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(json.encodeToString(s).toByteArray(Charsets.UTF_8)) }
        val b32 = base32Encode(bos.toByteArray())
        return b32.chunked(8).joinToString("-")
    }

    fun decode(code: String): Snapshot {
        val cleaned = code.trim()
            .removePrefix("CARTEL").removeSuffix("=")
            .replace("-", "").replace(" ", "")
            .uppercase()
        val bytes = base32Decode(cleaned)
        val text = GZIPInputStream(ByteArrayInputStream(bytes)).use { gz ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = gz.read(buf)
                if (n < 0) break
                // A few KB of text can inflate by orders of magnitude, so cap the output, not just the input.
                require(out.size() + n <= MAX_DECODED_BYTES) { "Configuration code is too large" }
                out.write(buf, 0, n)
            }
            out.toString(Charsets.UTF_8.name())
        }
        return json.decodeFromString(text)
    }

    /** Returns the pre-apply snapshot so the caller can offer UNDO. */
    fun apply(s: Snapshot): String {
        val before = capture()
        if (s.homeRows.isNotEmpty()) AppPreferences.setHomeRows(s.homeRows)
        if (s.hiddenApps.isNotEmpty()) AppPreferences.setHiddenApps(s.hiddenApps.toSet())
        if (s.countdownTargetMs > 0) AppPreferences.countdownTargetMs = s.countdownTargetMs
        AppPreferences.cwHideTitles = s.cwHideTitles
        AppPreferences.cwColumns = s.cwColumns
        AppPreferences.BufferPreset.entries.firstOrNull { it.name == s.bufferPreset }
            ?.let { AppPreferences.bufferPreset = it }
        AppPreferences.ambienceEnabled = s.ambience
        if (s.city.isNotBlank()) AppPreferences.cityName = s.city
        if (s.lat != 0.0 || s.lon != 0.0) { AppPreferences.weatherLat = s.lat; AppPreferences.weatherLon = s.lon }
        AppPreferences.debridProvider = s.debridProvider
        AppPreferences.overrideHomeButton = s.overrideHomeButton
        AppPreferences.enhancedHomeDetection = s.enhancedHomeDetection
        AppPreferences.overrideBackButton = s.overrideBackButton
        AppPreferences.startOnBoot = s.startOnBoot
        AppPreferences.showWatchNext = s.showWatchNext
        return encode(before)
    }

    /* ── Crockford base32 (no I/L/O/U confusion when spoken aloud) ── */
    private const val ALPHA = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    fun base32Encode(data: ByteArray): String {
        var bits = 0; var acc = 0; val out = StringBuilder()
        for (b in data) {
            acc = (acc shl 8) or (b.toInt() and 0xFF); bits += 8
            while (bits >= 5) {
                out.append(ALPHA[(acc ushr (bits - 5)) and 31]); bits -= 5
            }
        }
        if (bits > 0) out.append(ALPHA[(acc shl (5 - bits)) and 31])
        return out.toString()
    }

    fun base32Decode(s: String): ByteArray {
        val vals = s.map { c -> ALPHA.indexOf(c).also { require(it >= 0) { "bad char $c" } } }
        var bits = 0; var acc = 0; val out = ByteArrayOutputStream()
        for (v in vals) {
            acc = (acc shl 5) or v; bits += 5
            if (bits >= 8) { out.write((acc ushr (bits - 8)) and 0xFF); bits -= 8 }
        }
        return out.toByteArray()
    }
}
