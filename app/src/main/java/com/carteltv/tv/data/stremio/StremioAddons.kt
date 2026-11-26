package com.carteltv.tv.data.stremio

import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.core.suspendRunCatching
import kotlinx.coroutines.async

/** Loaded-addon registry with Cinemeta always onboard. */
object StremioAddons {

    data class Loaded(val url: String, val manifest: StremioClient.Manifest, val client: StremioClient)

    @Volatile private var cache: List<Loaded> = emptyList()
    @Volatile var lastErrors: List<String> = emptyList(); private set

    fun normalize(raw: String): String = StremioClient.normalizeBaseUrl(raw)

    fun defaultUrls(): List<String> {
        val saved = AppPreferences.addons()
        return if (saved.none { it == StremioClient.CINEMETA }) listOf(StremioClient.CINEMETA) + saved else saved
    }

    suspend fun refresh(force: Boolean = false): List<Loaded> {
        if (cache.isNotEmpty() && !force) return cache
        // Parallel fetch: addons are independent, and one dead addon must not add its
        // full read timeout to every other addon's load. Failures are kept per-URL so
        // the UI (and logs) can show which addons are unreachable instead of silence.
        val urls = defaultUrls()
        data class Outcome(val loaded: Loaded?, val error: String?)
        val outcomes = kotlinx.coroutines.coroutineScope {
            urls.map { url ->
                async {
                    val c = StremioClient(url)
                    try {
                        Outcome(Loaded(url, c.manifest(), c), null)
                    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.w("StremioAddons", "unreachable: ${com.carteltv.tv.data.network.HttpSupport.redact("$url → ${e.javaClass.simpleName}: ${e.message}")}")
                        Outcome(null, "$url → ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
            }.map { it.await() }
        }
        lastErrors = outcomes.mapNotNull { it.error }
        cache = outcomes.mapNotNull { it.loaded }
        return cache
    }

    fun loaded(): List<Loaded> = cache

    /** Torrent (infoHash) → debrid → direct url bridge. Prefers RD, falls to TB. */
    suspend fun resolveTorrent(infoHash: String, sources: List<String>): String? {
        val trackers = sources.filter { it.startsWith("tracker:") }.map { it.removePrefix("tracker:") }
        val magnet = buildString {
            append("magnet:?xt=urn:btih:$infoHash")
            trackers.take(4).forEach { append("&tr=$it") }
        }
        // TorBox goes first and only takes a torrent it already holds, so a miss costs one request, not a download.
        com.carteltv.tv.data.preferences.AppPreferences.torboxToken.takeIf { it.isNotBlank() }?.let { tok ->
            val tb = com.carteltv.tv.data.debrid.TorboxClient(tok)
            suspendRunCatching {
                val id = tb.addMagnet(magnet, onlyIfCached = true) ?: return@suspendRunCatching null
                repeat(6) { attempt ->
                    tb.torrent(id)?.let { t -> tb.bestLink(t)?.let { return@suspendRunCatching it } }
                    if (attempt < 5) kotlinx.coroutines.delay(700)
                }
                null
            }.getOrNull()?.let { return it }
        }
        com.carteltv.tv.data.debrid.DebridAuth.ensureFreshOrKeep()
        com.carteltv.tv.data.preferences.AppPreferences.debridToken.takeIf { it.isNotBlank() }?.let { tok ->
            val rd = com.carteltv.tv.data.debrid.DebridClient(tok)
            suspendRunCatching {
                val id = rd.addMagnet(magnet) ?: return@suspendRunCatching null
                rd.selectAllFiles(id)
                repeat(4) { attempt ->
                    val t = rd.torrentInfo(id)
                    if (t != null && t.status == "downloaded" && t.links.isNotEmpty()) {
                        return@suspendRunCatching rd.bestStreamLink(t)?.let { rd.unrestrict(it)?.download }
                    }
                    if (attempt < 3) kotlinx.coroutines.delay(1_200)
                }
                null
            }.getOrNull()?.let { return it }
        }
        return null
    }
}
