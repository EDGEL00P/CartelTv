package com.carteltv.tv.engine.stability

import android.content.Context
import android.os.Build
import com.carteltv.tv.data.preferences.AppPreferences
import java.security.MessageDigest

/**
 * Privacy-scrubbed diagnostic report builder & crash fingerprinter.
 *
 * Strips every private secret, credential, stream URI, email, JWT/Bearer token, SSID, MAC,
 * IPv4/IPv6 address, `.local` hostname, 32+ char token, and OS user home path before any
 * diagnostic snapshot leaves the TV, and computes a deterministic SHA-1 crash signature.
 */
object DiagnosticsReport {

    private const val HIDDEN = "[hidden]"

    data class Snapshot(
        val version: String,
        val device: String,
        val android: String,
        val settings: String,
        val crash: String,
        val crashSignature: String,
    )

    private val SCRUB_RULES: List<Pair<Regex, String>> = listOf(
        // Whole private camera/stream/NAS URLs (contain IP, port, credentials, and path)
        Regex("(?i)\\b(rtsps?|rtmps?|onvif|smb|ftp)://\\S+") to "[stream link]",
        // Xtream-style http(s) stream paths carry username/password as path segments
        Regex("(?i)(https?://[^/\\s]+)/(live|movie|series|timeshift)/\\S+") to "$1/[stream path]",
        // Embedded credentials inside any URL
        Regex("(?i)\\b([a-z][a-z0-9+.-]*://)[^\\s/@:]+(:[^\\s/@]*)?@") to "$1$HIDDEN@",
        // Email addresses
        Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+") to "[email]",
        // JWTs & Authorization headers
        Regex("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{4,}") to "[token]",
        Regex("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{8,}") to "$1 [token]",
        // Named secret key=value / JSON fields
        Regex("(?i)([\"']?[A-Za-z0-9_.-]*(token|secret|passw(or)?d|pwd|api[_-]?key|apikey|auth|cookie|session|credential|client[_-]?id|\\bkey|\\bpin|\\bcode)[A-Za-z0-9_.-]*[\"']?\\s*[=:]\\s*)(\\[hidden\\]|\"[^\"]*\"|'[^']*'|[^\\s,;&}\\])]+)") to "$1$HIDDEN",
        // CamelCase secret names (lockCode=…, tmdbKey=…), sparing versionCode
        Regex("(\\b[A-Za-z0-9_.-]*(?<!version)(Code|Pin|Key)\\b[\"']?\\s*[=:]\\s*)(\\[hidden\\]|\"[^\"]*\"|'[^']*'|[^\\s,;&}\\])]+)") to "$1$HIDDEN",
        // Wi-Fi SSIDs
        Regex("(?i)(\\b(b?ssid|wi-?fi name|network name)\\b[\"']?\\s*[=:]?\\s*)(\\[hidden\\]|\"[^\"]*\"|'[^']*'|[^\\s,;]+)") to "$1$HIDDEN",
        // MAC, IPv4, IPv6, LAN hostnames
        Regex("(?i)\\b[0-9a-f]{2}([:-][0-9a-f]{2}){5}\\b") to "[mac]",
        Regex("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}(:\\d{1,5})?\\b") to "[address]",
        Regex("(?i)(?<![0-9a-f:.])(?=[0-9a-f:]*::|([0-9a-f]{1,4}:){4})[0-9a-f]{0,4}(:[0-9a-f]{0,4}){2,7}(%[a-z0-9]+)?(?![0-9a-f:])") to "[address]",
        Regex("(?i)\\b[a-z0-9-]+\\.(local|lan|home|internal)\\b") to "[local name]",
        // High-entropy 32+ char tokens
        Regex("\\b[A-Za-z0-9_-]{32,}\\b") to HIDDEN,
        // Local workstation user paths
        Regex("(/Users/|/home/|C:\\\\Users\\\\)[^/\\\\\\s]+") to "$1$HIDDEN",
    )

    /** Removes all personally identifying or secret data from [text]. */
    fun scrub(text: String): String =
        SCRUB_RULES.fold(text) { acc, (re, replacement) -> re.replace(acc, replacement) }

    /** Extracts `<ExceptionClass>|<first com.carteltv frame>` for crash deduplication. */
    fun crashKey(stacktrace: String): String {
        val lines = stacktrace.lines().map { it.trim() }.filter { it.isNotBlank() }
        val head = lines.firstOrNull { !it.matches(Regex("\\d{4}-\\d{2}-\\d{2}.*")) }
            ?.substringBefore(':').orEmpty()
        val frame = lines.firstOrNull { it.startsWith("at com.carteltv.") }
            ?.substringBefore('(').orEmpty()
        return if (head.isBlank()) "" else "$head|$frame"
    }

    /** 12-char SHA-256 crash fingerprint derived from [crashKey]. */
    fun signatureOf(stacktrace: String): String =
        crashKey(stacktrace).takeIf { it.isNotBlank() }?.let { key ->
            MessageDigest.getInstance("SHA-256")
                .digest(key.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(12)
        }.orEmpty()

    fun build(ctx: Context): Snapshot {
        val pkgInfo = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()
        @Suppress("DEPRECATION")
        val ver = "${pkgInfo?.versionName ?: "unknown"} (${pkgInfo?.versionCode ?: 0})"
        val dm = ctx.resources.displayMetrics
        val device = scrub("${Build.MANUFACTURER} ${Build.MODEL} · ${dm.widthPixels}x${dm.heightPixels}")
        val androidVer = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        val safeSettings = scrub(
            listOf(
                "buf=${AppPreferences.bufferPreset.name}",
                    "homeOverride=${AppPreferences.overrideHomeButton}",
                "enhancedHome=${AppPreferences.enhancedHomeDetection}",
                "watchNext=${AppPreferences.showWatchNext}",
                "channels=${AppPreferences.showAppChannels}",
                "playInApp=${AppPreferences.playInStreamingApp}",
                "iconPack=${AppPreferences.iconPack.ifBlank { "stock" }}",
            ).joinToString("  "),
        )
        val rawCrash = CrashWatchdog.lastCrashTrace(ctx)
        val cleanCrash = scrub(rawCrash)
        return Snapshot(
            version = ver,
            device = device,
            android = androidVer,
            settings = safeSettings,
            crash = cleanCrash,
            crashSignature = signatureOf(rawCrash),
        )
    }

    fun format(snap: Snapshot): String = buildString {
        append("CartelTV ").append(snap.version).append('\n')
        append(snap.device).append(" · ").append(snap.android).append("\n\n")
        append("Settings: ").append(snap.settings).append('\n')
        if (snap.crash.isNotBlank()) {
            append("\nLast Crash [").append(snap.crashSignature).append("]:\n")
            append(snap.crash.trim()).append('\n')
        }
    }
}
