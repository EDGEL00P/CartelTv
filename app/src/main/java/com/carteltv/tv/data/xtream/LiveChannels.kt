package com.carteltv.tv.data.xtream
import com.carteltv.tv.AppScope
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.core.suspendRunCatching
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One shared copy of the provider's channel and group lists, so no screen refetches thousands of rows.
 * What callers see has the user's group choices applied (hidden groups, renames, order); [rawGroups] does not.
 *
 * The first screen to ask after a cold start gets the copy saved on disk (if any) straight away while a fresh
 * download runs behind it; callers that ask while that download is running wait for it rather than starting a second.
 */
object LiveChannels {
    private const val TTL_MS = 10 * 60_000L

    /** Bump when the language rules change, so a list saved under the old rules is not served. */
    private const val FILTER_VERSION = 2

    /** Where the saved copy lives. Set once at startup; without it nothing is saved and every cold start downloads. */
    @Volatile var cacheDir: File? = null

    private val fetchLock = Mutex()

    @Volatile private var streams: List<XtreamClient.LiveStream> = emptyList()
    @Volatile private var groups: List<XtreamClient.Category> = emptyList()
    @Volatile private var streamsAt = 0L
    @Volatile private var groupsAt = 0L
    @Volatile private var owner: String? = null

    /** True when the last refresh could not reach the provider, so an empty list is a failure, not an answer. */
    @Volatile var refreshFailed: Boolean = false
        private set

    // The filtered view is rebuilt only when the list or the user's choices change.
    @Volatile private var viewSrc: List<XtreamClient.LiveStream>? = null
    @Volatile private var viewConfig: ChannelGroupConfig? = null
    @Volatile private var view: List<XtreamClient.LiveStream> = emptyList()

    private fun claim(client: XtreamClient) {
        val id = "${client.baseUrl}|${client.username}"
        if (owner != id) { owner = id; streams = emptyList(); groups = emptyList(); streamsAt = 0L; groupsAt = 0L }
    }

    /** A saved list is only good for this provider account and this language choice, and never holds the password. */
    private fun diskKey(client: XtreamClient): String {
        val raw = "$FILTER_VERSION|${client.baseUrl}|${client.username}|${AppPreferences.englishOnly}|" +
            AppPreferences.contentLanguages.map { it.code }.sorted().joinToString(",")
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
    }

    suspend fun all(client: XtreamClient, force: Boolean = false): List<XtreamClient.LiveStream> {
        claim(client)
        val now = System.currentTimeMillis()
        if (!force && streams.isNotEmpty() && now - streamsAt < TTL_MS) return cached()
        val dir = cacheDir
        if (!force && streams.isEmpty() && dir != null) {
            val saved = withContext(Dispatchers.IO) { ChannelListCache.read(dir, diskKey(client), now) }
            if (saved != null && streams.isEmpty()) {
                streams = saved
                streamsAt = 0L   // stale on purpose: the next caller refreshes
                AppScope.io.launch { refresh(client) }
                return cached()
            }
        }
        return refresh(client)
    }

    private suspend fun refresh(client: XtreamClient): List<XtreamClient.LiveStream> = fetchLock.withLock {
        val now = System.currentTimeMillis()
        // Someone else finished a download while this caller waited for the lock.
        if (streams.isNotEmpty() && streamsAt != 0L && now - streamsAt < TTL_MS) return@withLock cached()
        val fresh = withContext(Dispatchers.IO) {
            suspendRunCatching { client.liveStreams(null) }
        }
        // A dead provider and an empty playlist must not read the same to the viewer.
        refreshFailed = fresh.isFailure
        val list = fresh.getOrDefault(emptyList())
        if (list.isNotEmpty()) {
            streams = list
            streamsAt = System.currentTimeMillis()
            cacheDir?.let { dir -> withContext(Dispatchers.IO) { ChannelListCache.write(dir, diskKey(client), streamsAt, list) } }
        }
        cached()
    }
    /** The provider's groups as the user arranged them. */
    suspend fun groups(client: XtreamClient): List<XtreamClient.Category> =
        ChannelGroups.apply(rawGroups(client), AppPreferences.channelGroupConfig())

    /** Every group the provider has, untouched: what the group manager lists. */
    suspend fun rawGroups(client: XtreamClient): List<XtreamClient.Category> {
        claim(client)
        val now = System.currentTimeMillis()
        if (groups.isNotEmpty() && now - groupsAt < TTL_MS) return groups
        val fresh = withContext(Dispatchers.IO) {
            suspendRunCatching { client.liveCategories() }.getOrDefault(emptyList())
        }
        if (fresh.isNotEmpty()) { groups = fresh; groupsAt = now }
        return groups
    }

    /** Forgets the cached lists so the next screen reloads them, e.g. after the language selection changed. */
    fun invalidate() { streamsAt = 0L; groupsAt = 0L }

    /** The already-loaded list, never a network call. Empty until a screen has loaded it. */
    fun cached(): List<XtreamClient.LiveStream> {
        val src = streams
        val config = AppPreferences.channelGroupConfig()
        if (viewSrc !== src || viewConfig != config) {
            view = ChannelGroups.visible(src, config)
            viewSrc = src
            viewConfig = config
        }
        return view
    }

    /** Looks up a channel even when its group is hidden, so a saved link or a recent still plays. */
    fun byId(id: Long): XtreamClient.LiveStream? {
        val src = streams
        if (idSource !== src) {
            val index = buildMap {
                src.forEach { s -> putIfAbsent(s.streamId, s) }
            }
            idIndex = index
            idSource = src
        }
        return idIndex[id]
    }

    @Volatile private var idSource: List<XtreamClient.LiveStream>? = null
    @Volatile private var idIndex: Map<Long, XtreamClient.LiveStream> = emptyMap()
}
