package com.carteltv.tv.data.xtream

import android.content.Context
import com.carteltv.tv.data.network.HttpSupport
import com.carteltv.tv.data.network.OkHttpTransport
import com.carteltv.tv.data.preferences.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** When to fetch the provider's XMLTV again. A recent failure backs the retry off, and survives restarts. */
object EpgFreshness {
    const val REFRESH_MS = 6 * 60 * 60_000L
    const val RETRY_MS = 30 * 60_000L

    fun due(nowMs: Long, okMs: Long, failMs: Long): Boolean {
        if (failMs > okMs && nowMs - failMs < RETRY_MS) return false
        return nowMs - okMs >= REFRESH_MS
    }
}

/**
 * Downloads the provider's `xmltv.php`, streams it into a staging table, and only then replaces the programmes
 * of channels the file actually covers. A failed or empty download leaves the existing guide untouched.
 */
object EpgSync {
    private const val PAST_MS = 24 * 60 * 60_000L
    private const val AHEAD_MS = 7 * 24 * 60 * 60_000L
    private val gate = Mutex()

    suspend fun refreshIfDue(ctx: Context, client: XtreamClient, force: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        if (!force && !EpgFreshness.due(now, AppPreferences.epgSyncOkMs, AppPreferences.epgSyncFailMs)) return false
        if (!gate.tryLock()) return false
        try {
            val ok = withContext(Dispatchers.IO) {
                try { sync(ctx, client, force) }
                catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e }
                catch (_: Exception) { false }
            }
            if (ok) AppPreferences.epgSyncOkMs = System.currentTimeMillis() else AppPreferences.epgSyncFailMs = System.currentTimeMillis()
            return ok
        } finally {
            gate.unlock()
        }
    }

    /** Most guides one sync reads: each extra is a full download and parse on a TV box. */
    const val MAX_SOURCES = 6

    /**
     * The guides to read, in priority order: the provider's (or the custom URL that replaces it) first, then the
     * extra guides. Only valid http(s) links, each once, up to [MAX_SOURCES].
     */
    fun sources(primary: String, extras: List<String>): List<String> =
        (listOf(primary) + extras).map { it.trim() }
            .filter { it.startsWith("http://", true) || it.startsWith("https://", true) }
            .distinctBy { it.lowercase() }
            .take(MAX_SOURCES)

    /**
     * Channels still worth name-matching against the next guide: not already given a guide channel by name, and
     * not carrying an id that an earlier guide declares (those are served directly).
     */
    fun pending(own: List<EpgMatcher.Channel>, matched: Map<Long, String>, seenGuideIds: Set<String>): List<EpgMatcher.Channel> =
        own.filter { ch ->
            ch.streamId !in matched && (ch.epgId.isNullOrBlank() || ch.epgId.trim().lowercase() !in seenGuideIds)
        }

    private const val UA = com.carteltv.tv.data.network.OkHttpTransport.USER_AGENT

    /** The validator a server reports for [url] without sending the body, or blank when it offers none. */
    private suspend fun probeValidator(url: String): String {
        val (code, headers, _) = HttpSupport.requestFull(
            url, "HEAD",
            mapOf("User-Agent" to UA),
            connectMs = 8_000, readMs = 10_000,
        )
        return if (code == -1 || code !in 200..299) ""
        else headers["ETag"]?.firstOrNull() ?: headers["Last-Modified"]?.firstOrNull() ?: ""
    }

    /** True when every guide reports the validator it had at the last full read, so a download would change nothing. */
    private suspend fun allUnchanged(urls: List<String>, channels: Int): Boolean {
        if (urls.isEmpty() || channels != AppPreferences.epgSyncChannels) return false
        for (url in urls) {
            val known = AppPreferences.epgValidator(url)
            if (known.isBlank()) return false
            val current = try { probeValidator(url) }
            catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e }
            catch (_: Exception) { "" }
            if (current != known) return false
        }
        return true
    }

    private suspend fun sync(ctx: Context, client: XtreamClient, force: Boolean): Boolean {
        val db = EpgIndexDatabase.get(ctx)
        val own = db.channelsForMatching()
        if (db.epgIdIndex().isEmpty() && own.isEmpty()) return false
        val urls = sources(guideUrl(client, AppPreferences.customXmltvUrl), AppPreferences.extraGuideUrls)
        if (!force && allUnchanged(urls, own.size)) return true
        val validators = HashMap<String, String>()
        val now = System.currentTimeMillis()
        val matched = HashMap<Long, String>(EventSlots.platformAliases(own)) // idle event slots start on their platform's schedule
        val seenGuideIds = HashSet<String>()       // ids declared by the guides read so far
        val doneIds = HashSet<String>()            // ids that already received programmes: the first guide wins
        var kept = 0
        db.openStage().use { stage ->
            for (url in urls) {
                // One guide failing (offline, bad file) never stops the others.
                kept += try { readGuide(url, db, stage, own, matched, seenGuideIds, doneIds, now, validators) }
                catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e }
                catch (_: Exception) { 0 }
            }
        }
        if (kept == 0) { db.dropStage(); return false }
        db.applyStage(System.currentTimeMillis() - PAST_MS)
        // A guide that failed or reported no validator is cleared, so the next sync reads it again.
        urls.forEach { AppPreferences.setEpgValidator(it, validators[it].orEmpty()) }
        AppPreferences.epgSyncChannels = own.size
        return true
    }

    /** Streams one guide into [stage]; returns the programmes kept. Updates the cross-guide sets as it goes. */
    private suspend fun readGuide(
        url: String, db: EpgIndexDatabase, stage: EpgIndexDatabase.Stage, own: List<EpgMatcher.Channel>,
        matched: MutableMap<Long, String>, seenGuideIds: MutableSet<String>, doneIds: MutableSet<String>, now: Long,
        validators: MutableMap<String, String>,
    ): Int {
        val stream = try {
            HttpSupport.getStream(url, mapOf("User-Agent" to UA), connectMs = 15_000, readMs = 60_000)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (_: Exception) {
            return 0
        }

        if (stream.code !in 200..299) {
            stream.close()
            return 0
        }
        var kept = 0
        val keptIds = HashSet<String>()
        stream.use { response ->
            // OkHttp transparently decompresses gzip; the body is already the decoded XML stream.
            val guide = ArrayList<EpgMatcher.MajorEventChannel>()
            var wanted: Set<String> = emptySet()
            var started = false
            XmltvReader.parse(response.body, onChannel = { id, names -> guide += EpgMatcher.MajorEventChannel(id, names) }, onProgramme = { pr ->
                if (!started) {
                    // Guides list every channel ahead of the programmes, so the names are all in by now.
                    started = true
                    matched += EpgMatcher.match(pending(own, matched, seenGuideIds), guide)
                    db.replaceAliases(matched)
                    wanted = db.epgIdIndex().keys
                    guide.forEach { seenGuideIds += it.id.lowercase() }
                }
                val id = pr.channel.lowercase()
                if (id in wanted && id !in doneIds && pr.stopMs > now - PAST_MS && pr.startMs < now + AHEAD_MS) {
                    stage.add(id, pr.startMs, pr.stopMs, pr.title, pr.desc)
                    keptIds += id
                    kept++
                }
            })
        }
        validators[url] = stream.headers["ETag"]?.firstOrNull() ?: stream.headers["Last-Modified"]?.firstOrNull() ?: ""
        doneIds += keptIds
        return kept
    }

    /** The custom URL when it is a valid http(s) link, otherwise the provider's own XMLTV endpoint. */
    fun guideUrl(client: XtreamClient, custom: String): String {
        if (custom.startsWith("http://", true) || custom.startsWith("https://", true)) return custom
        return client.defaultGuideUrl()
    }
}
