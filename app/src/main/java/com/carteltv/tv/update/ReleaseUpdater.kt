package com.carteltv.tv.update

import com.carteltv.tv.data.network.HttpSupport
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * UPDATER (fed-by-URL law):
 * checks a GitHub releases endpoint for a newer versionCode, downloads
 * the APK via DownloadManager, and triggers the system installer.
 *
 * Usage: call [check] periodically or on-demand from Settings.
 * The caller handles UI state (checking/downloading/installed/error).
 */
object ReleaseUpdater {

    private const val PREFS = "updater_download"
    private const val KEY_DL_ID = "dl_id"
    private const val KEY_SHA = "sha256"

    data class Release(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val notes: String,
        /** Lower-case hex SHA-256 of the APK from the feed's asset digest; empty if the feed gave none. */
        val sha256: String = "",
    )

    /** One per-ABI APK entry in the feed's `apks` map; keys are ABI names plus "universal". */
    @kotlinx.serialization.Serializable
    internal data class ApkEntry(
        val url: String = "",
        val sha256: String = "",
    )

    /**
     * The `update.json` the release workflow publishes: a plain versionCode, so no tag parsing is
     * involved. Top-level `apkUrl`/`sha256` stay for old app builds (they hold the universal APK);
     * newer builds prefer the matching entry from [apks].
     */
    @kotlinx.serialization.Serializable
    internal data class UpdateFeed(
        val versionCode: Int = 0,
        val versionName: String = "",
        val apkUrl: String = "",
        val sha256: String = "",
        val apks: Map<String, ApkEntry> = emptyMap(),
    ) {
        /**
         * ABI-matched pick: walk the device's [abis] in preference order and take the first
         * `apks` entry that names it; "universal" covers everything else. Empty [abis] (JVM
         * unit tests, exotic builds) or a missing map falls back to the universal fields.
         */
        fun toRelease(abis: List<String> = emptyList()): Release {
            val chosen = apks.entries.firstOrNull { it.key in abis && it.value.url.startsWith("https://") }?.value
                ?: apks["universal"]?.takeIf { it.url.startsWith("https://") }
            val url = chosen?.url ?: apkUrl
            val sha = chosen?.sha256 ?: sha256
            val validSha = sha.lowercase().takeIf { SHA256_HEX.matches(it) }.orEmpty()
            return Release(versionCode, versionName.ifBlank { versionCode.toString() }, url, "", validSha)
        }
    }

    /** The feed if [body] has the `update.json` shape (a positive versionCode and an https APK URL), else null. */
    internal fun parseUpdateFeed(body: String): UpdateFeed? =
        runCatching { com.carteltv.tv.data.network.HttpSupport.json.decodeFromString<UpdateFeed>(body) }.getOrNull()
            ?.takeIf { it.versionCode > 0 && it.apkUrl.startsWith("https://") }

    /** Current installed versionCode (from BuildConfig or PackageManager). */
    var installedCode: Int = 0

    /** Latest release found on the feed, or null if none/newer. */
    @Volatile var latest: Release? = null

    /** Human-readable status for the UI row. */
    @Volatile var status: String = ""

    /** True while a download is in progress. */
    @Volatile var downloading = false

    /** SHA-256 the finished download must match before the installer is shown. */
    @Volatile var expectedSha256: String = ""

    /** DownloadManager enqueue ID (for progress/cancellation). */
    @Volatile var dlId: Long = -1L

    /** GitHub releases endpoint (user/repo format). */
    var feedUrl: String = ""

    /** Check the feed for a newer release. */
    suspend fun check(ctx: Context): Release? = withContext(Dispatchers.IO) {
        if (feedUrl.isBlank()) {
            status = "No update feed configured"
            return@withContext null
        }
        if (!feedUrl.startsWith("https://")) {
            status = "Update feed must use HTTPS"
            return@withContext null
        }
        status = "Checking…"
        runCatching {
            val conn = URL(feedUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = HttpSupport.CONNECT_MS; conn.readTimeout = HttpSupport.READ_MS
            val body = try {
                conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } finally {
                conn.disconnect()
            }
            parseUpdateFeed(body)?.let { feed ->
                if (feed.versionCode > installedCode) {
                    val abis = Build.SUPPORTED_ABIS.toList()
                    return@withContext feed.toRelease(abis).also { latest = it; status = "Update available: ${it.versionName}" }
                }
                latest = null
                status = "Up to date"
                return@withContext null
            }
            val rel = JSONObject(body)
            val arr = rel.getJSONArray("assets")
            val tag = rel.optString("tag_name", "")
            val code = tag.trimStart('v', 'V').toIntOrNull() ?: 0
            for (i in 0 until arr.length()) {
                val asset = arr.getJSONObject(i)
                val name = asset.getString("name")
                // The updater serves one URL to every device: universal first, per-ABI only as fallback.
                val wanted = name.endsWith(".apk", ignoreCase = true) &&
                    (name.contains("universal", ignoreCase = true) || !arr.let { all ->
                        (0 until all.length()).any { k: Int ->
                            all.getJSONObject(k).getString("name").contains("universal", ignoreCase = true)
                        }
                    })
                if (wanted) {
                    val url = asset.getString("browser_download_url")
                    if (code > installedCode && url.startsWith("https://")) {
                        val notes = rel.optString("body", "").take(200)
                        val digest = asset.optString("digest", "").removePrefix("sha256:").lowercase()
                        val sha = if (SHA256_HEX.matches(digest)) digest else ""
                        return@withContext Release(code, tag, url, notes, sha).also {
                            latest = it
                            status = "Update available: ${it.versionName}"
                        }
                    }
                }
            }
            latest = null
            status = "Up to date"
            null
        }.onFailure { status = "Check failed: ${it.message}" }.getOrNull()
    }

    /** Download the APK via system DownloadManager. */
    fun download(ctx: Context, release: Release): Long {
        if (release.sha256.isEmpty()) {
            status = "Update has no checksum; refusing to download"
            return -1L
        }
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        // Supersede a still-pending earlier download: its completion broadcast would
        // otherwise verify against this release's checksum, or drop this one on the id match.
        if (downloading && dlId >= 0) runCatching { dm.remove(dlId) }
        expectedSha256 = release.sha256
        val req = DownloadManager.Request(Uri.parse(release.apkUrl))
            .setTitle("CartelTV ${release.versionName}")
            .setDescription("Downloading update…")
            .setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, "CartelTV-${release.versionName.filter { it.isLetterOrDigit() || it == '.' }}.apk")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
        req.setRequiresCharging(false)
        dlId = dm.enqueue(req)
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_DL_ID, dlId).putString(KEY_SHA, expectedSha256).apply()
        downloading = true
        status = "Downloading ${release.versionName}…"
        trackProgress(ctx.applicationContext, dlId, release.versionName)
        return dlId
    }

    private fun trackProgress(ctx: Context, id: Long, versionName: String) {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        com.carteltv.tv.AppScope.io.launch {
            while (downloading && dlId == id) {
                val snapshot = dm.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                    if (!cursor.moveToFirst()) null else Triple(
                        cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                        cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                        cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                    )
                }
                if (snapshot != null) {
                    val (state, downloaded, total) = snapshot
                    if (total > 0L) {
                        val percent = (downloaded.coerceIn(0L, total) * 100L / total).toInt()
                        status = "Downloading $versionName · $percent%"
                    }
                    if (state == DownloadManager.STATUS_FAILED) {
                        downloading = false
                        dlId = -1L
                        runCatching { dm.remove(id) }
                        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
                        status = "Download failed"
                        break
                    }
                }
                delay(1_000L)
            }
        }
    }

    /** BroadcastReceiver for download completion — triggers install. */
    class InstallReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            val appCtx = ctx.applicationContext
            // The process may have died during the download; restore the enqueued id and checksum.
            if (dlId < 0) {
                val p = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                dlId = p.getLong(KEY_DL_ID, -1L)
                expectedSha256 = p.getString(KEY_SHA, "").orEmpty()
            }
            if (id != dlId || id < 0) return
            // Capture before clearing: a download() racing this broadcast must not
            // substitute a newer release's checksum into the verification below.
            val expected = expectedSha256
            downloading = false
            appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
            val pending = goAsync()
            Thread {
                try {
                    verifyAndInstall(appCtx, id, expected)
                } finally {
                    pending.finish()
                }
            }.start()
        }

        private fun verifyAndInstall(ctx: Context, id: Long, expectedSha256: String) {
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val successful = dm.query(DownloadManager.Query().setFilterById(id))?.use {
                it.moveToFirst() &&
                    it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL
            } == true
            val uri = if (successful) dm.getUriForDownloadedFile(id) else null
            if (uri == null) {
                status = "Download failed"
                return
            }
            val actual = runCatching {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                dm.openDownloadedFile(id).use { pfd ->
                    java.io.FileInputStream(pfd.fileDescriptor).use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            digest.update(buf, 0, n)
                        }
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }.getOrNull()
            if (expectedSha256.isEmpty() || actual == null || !java.security.MessageDigest.isEqual(actual.toByteArray(), expectedSha256.toByteArray())) {
                runCatching { dm.remove(id) }
                status = "Update checksum mismatch; discarded"
                return
            }
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            ctx.startActivity(installIntent)
            status = "Install prompt shown"
        }
    }
}

private val SHA256_HEX = Regex("[0-9a-f]{64}")
