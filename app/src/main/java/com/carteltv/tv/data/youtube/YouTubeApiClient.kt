package com.carteltv.tv.data.youtube

import com.carteltv.tv.data.network.HttpSupport
import com.carteltv.tv.data.preferences.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * YouTubeApiClient — clean-room YouTube data & stream engine for CartelTV,
 * and modern YouTube clients (see docs/architecture.md).
 *
 * Extraction ladder:
 *   1. VISIONOS 1.04 (youtubei.googleapis.com) — direct 4K60 adaptive + HLS, no PoToken, no n-sig
 *   2. VISIONOS 1.02 (www.youtube.com) — Flow's web-UA visionOS understudy
 *   3. TV_TIZEN (TVHTML5 5.20260707 + PipePipe remote decoder + PrerollWait) — bypasses 60s visitor walls & label restrictions
 *   4. ANDROID_VR 1.65.10 / ANDROID 20.10.38 / IOS 20.10.4 — fallback & live manifests
 *   5. Visitor re-roll on bot-wall + last-resort demoted retry
 */
object YouTubeApiClient {
    private const val TAG = "CartelTube"
    private const val API_MAIN = "https://www.youtube.com/youtubei/v1/"
    private const val API_GAPIS = "https://youtubei.googleapis.com/youtubei/v1/"
    private const val PIPEPIPE_LATEST_PLAYER = "https://api.pipepipe.dev/decoder/latest-player"
    private const val PIPEPIPE_DECODE = "https://api.pipepipe.dev/decoder/decode"
    private const val PIPEPIPE_UA = "PipePipe/4.9.0"
    private const val DEARROW_THUMB_BASE = "https://dearrow-thumb.ajay.app/api/v1/getThumbnail"

    /** AppPreferences.youTubeCodec index → mime substring; 0 = automatic (no override). */
    val CODEC_TAGS = listOf<String?>(null, "avc1", "vp9", "av01")
    val CODEC_LABELS = listOf("Automatic", "H.264", "VP9", "AV1")

    private val VISITOR_PATTERN = Regex("^Cg[ts]")
    private val N_PARAM_REGEX = Regex("([?&])n=([^&]+)")
    private val CPN_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private val rng = SecureRandom()

    private data class Identity(
        val idKey: String,
        val name: String,
        val clientId: String,
        val version: String,
        val ua: String,
        val apiBase: String = API_MAIN,
        val deviceMake: String? = null,
        val model: String? = null,
        val osName: String? = null,
        val osVersion: String? = null,
        val androidSdkVersion: Int? = null,
        val platform: String? = null,
        val clientScreen: String? = null,
        val apiFormatVersion: String = "1",
        val sendOriginHeaders: Boolean = false,
        val loginSupported: Boolean = false,
    )

    private val CLIENT_VISIONOS_APP =
        Identity(
            idKey = "VISIONOS",
            name = "VISIONOS",
            clientId = "101",
            version = "1.04",
            ua = YouTubeRequestPolicy.UA_VISIONOS_APP,
            apiBase = API_GAPIS,
            deviceMake = "Apple",
            model = "RealityDevice17,1",
            osName = "visionOS",
            osVersion = "26.6.0.23O770",
            platform = "MOBILE",
            clientScreen = "WATCH",
            apiFormatVersion = "2",
            loginSupported = false,
        )

    private val CLIENT_VISIONOS_WEB =
        Identity(
            idKey = "VISIONOS",
            name = "VISIONOS",
            clientId = "101",
            version = "1.02",
            ua = YouTubeRequestPolicy.UA_VISIONOS_WEB,
            apiBase = API_MAIN,
            deviceMake = "Apple",
            model = "RealityDevice17,1",
            osName = "visionOS",
            osVersion = "26.5.23O471",
            loginSupported = false,
        )

    private val CLIENT_TV_TIZEN =
        Identity(
            idKey = "TVHTML5",
            name = "TVHTML5",
            clientId = "7",
            version = "5.20260707",
            ua = YouTubeRequestPolicy.UA_TV_TIZEN,
            apiBase = API_MAIN,
            deviceMake = "Samsung",
            model = "SmartTV",
            osName = "Tizen",
            osVersion = "2.4.0",
            sendOriginHeaders = true,
            loginSupported = false,
        )

    private val CLIENT_ANDROID_VR =
        Identity(
            idKey = "ANDROID_VR",
            name = "ANDROID_VR",
            clientId = "28",
            version = "1.65.10",
            ua = YouTubeRequestPolicy.UA_ANDROID_VR,
            apiBase = API_MAIN,
            deviceMake = "Oculus",
            model = "Quest 3",
            osName = "Android",
            osVersion = "12L",
            androidSdkVersion = 32,
            loginSupported = false,
        )

    private val CLIENT_ANDROID =
        Identity(
            idKey = "ANDROID",
            name = "ANDROID",
            clientId = "3",
            version = "20.10.38",
            ua = YouTubeRequestPolicy.UA_ANDROID,
            apiBase = API_MAIN,
            osName = "Android",
            osVersion = "11",
            androidSdkVersion = 30,
            loginSupported = true,
        )

    private val CLIENT_IOS =
        Identity(
            idKey = "IOS",
            name = "IOS",
            clientId = "5",
            version = "20.10.4",
            ua = YouTubeRequestPolicy.UA_IOS,
            apiBase = API_MAIN,
            deviceMake = "Apple",
            model = "iPhone16,2",
            osName = "iPhone",
            osVersion = "18.3.2.22D82",
            loginSupported = false,
        )

    private val CLIENT_WEB =
        Identity(
            idKey = "WEB",
            name = "WEB",
            clientId = "1",
            version = "2.20260805.01.00",
            ua = YouTubeRequestPolicy.UA_WEB,
            apiBase = API_MAIN,
            platform = "DESKTOP",
            sendOriginHeaders = true,
            loginSupported = false,
        )

    private val FAST_CLIENTS = listOf(CLIENT_VISIONOS_APP, CLIENT_VISIONOS_WEB)
    private val FALLBACK_CLIENTS = listOf(CLIENT_ANDROID_VR, CLIENT_ANDROID, CLIENT_IOS)

    private val visitorMutex = Mutex()

    @Volatile private var visitorData: String? = null

    @Volatile private var visitorGeneration: Int = 0

    private val pipePipeMutex = Mutex()

    @Volatile private var ppPlayerId: String? = null

    @Volatile private var ppSts: Int? = null

    @Volatile private var ppExpiryMs: Long = 0L

    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val VISITOR_WALLED_CLIENTS = setOf("VISIONOS", "ANDROID_VR")

    val clientGate =
        YouTubeClientGate(
            onGated = { gatedClient ->
                if (gatedClient in VISITOR_WALLED_CLIENTS) {
                    visitorData = null
                    visitorGeneration++
                }
            },
        )

    fun reportStreamDenied(url: String?): StreamDenialKind = clientGate.reportDenied(url)

    private val json get() = HttpSupport.json
    private val client by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            // Force HTTP/1.1: over H2 the Shield box sheds DATA-frame EOS and
            // YouTube answers 400 "parsing terminated" (seen live 2026-11-20).
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
    }
    private val postMedia = "application/json".toMediaType()

    data class YouTubeVideo(
        val videoId: String,
        val title: String,
        val channel: String?,
        val duration: String?,
        val thumb: String?,
        val channelId: String? = null,
        val publishedMs: Long = 0L,
        /** YouTube's own upload age text ("2 days ago") when the renderer ships one. */
        val publishedText: String? = null,
        /** YouTube's own view count text ("1.2M views") when the renderer ships one. */
        val viewsText: String? = null,
    )

    data class YouTubeStreams(
        val title: String,
        val videoUrl: String?,
        val audioUrl: String?,
        val muxedUrl: String?,
        val itag: String?,
        val userAgent: String = CLIENT_VISIONOS_APP.ua,
        val hlsUrl: String? = null,
        val isLive: Boolean = false,
        val clientName: String = "",
        /** Provider-classified stream details for the in-player stream-info card. */
        val classifications: Map<String, String> = emptyMap(),
        val viewCount: String? = null,
        val channelId: String? = null,
        /** Absolute upload instant from the player's microformat, when present. */
        val publishedAt: Long = 0L,
    )

    internal data class FormatCandidate(
        val itag: String,
        val url: String,
        val mimeType: String,
        val height: Int,
        val fps: Int,
        val bitrate: Int,
        val width: Int = 0,
        val isDefaultAudio: Boolean = false,
        val audioTrackId: String? = null,
        val audioTrackName: String? = null,
        val isOriginalAudio: Boolean = false,
        val isDrc: Boolean = false,
        val isHdr: Boolean = false,
    )

    /**
     * Pure adaptive video selector: respects quality cap (using short-side qualityClass for
     * vertical Shorts), prefers SDR over HDR when equal height exists, and prefers Shield
     * HW-decoded codecs (AVC1 > VP9 > AV1).
     */
    internal fun pickBestVideo(
        formats: List<FormatCandidate>,
        maxHeight: Int,
        preferredCodec: String? = null,
    ): FormatCandidate? {
        if (maxHeight <= 0) return null

        fun qualityClass(f: FormatCandidate): Int = if (f.width > 0 && f.height > 0) minOf(f.width, f.height) else f.height

        val eligible =
            formats.filter {
                it.mimeType.startsWith("video/", ignoreCase = true) &&
                    it.url.isNotBlank() &&
                    (qualityClass(it) <= 0 || qualityClass(it) <= maxHeight)
            }
        if (eligible.isEmpty()) return null

        fun codecRank(mime: String): Int {
            val m = mime.lowercase()
            if (preferredCodec != null && (preferredCodec in m || (preferredCodec == "vp9" && "vp09" in m))) return 4
            return when {
                "avc1" in m -> 3
                "vp9" in m || "vp09" in m -> 2
                "av01" in m -> 0
                else -> 1
            }
        }
        return eligible.maxWithOrNull(
            compareBy<FormatCandidate>(
                { qualityClass(it) },
                { if (it.isHdr) 0 else 1 },
                { codecRank(it.mimeType) },
                { it.fps },
                { it.bitrate },
            ),
        )
    }

    /**
     * Pure adaptive audio selector: selects original / default / preferred-language track
     * before ranking codec (AAC 140 > Opus 251 > 139) and bitrate, preventing multi-audio
     * videos from playing a random foreign dub.
     */
    internal fun pickBestAudio(
        formats: List<FormatCandidate>,
        preferredLang: String = "en",
    ): FormatCandidate? {
        val eligible =
            formats.filter {
                it.mimeType.startsWith("audio/", ignoreCase = true) && it.url.isNotBlank()
            }
        if (eligible.isEmpty()) return null

        val nonDubbedPool = selectPreferredAudioPool(eligible, preferredLang)

        fun audioRank(f: FormatCandidate): Int =
            when (f.itag) {
                "140" -> 3
                "251" -> 2
                "139" -> 1
                else -> if ("mp4a" in f.mimeType.lowercase()) 2 else 1
            }
        return nonDubbedPool.maxWithOrNull(
            compareBy<FormatCandidate>(
                { if (it.isDrc) 0 else 1 },
                { audioRank(it) },
                { it.bitrate },
            ),
        )
    }

    private fun selectPreferredAudioPool(
        eligible: List<FormatCandidate>,
        preferredLang: String,
    ): List<FormatCandidate> {
        // Single-track videos carry no audioTrack metadata at all.
        if (eligible.all { it.audioTrackId.isNullOrBlank() && it.audioTrackName.isNullOrBlank() }) {
            return eligible
        }
        val originals = eligible.filter { it.isOriginalAudio }
        if (originals.isNotEmpty()) return originals

        val defaults = eligible.filter { it.isDefaultAudio }
        if (defaults.isNotEmpty()) return defaults

        val lang = preferredLang.trim().lowercase()
        if (lang.isNotEmpty()) {
            val langHits =
                eligible.filter { f ->
                    val base = f.audioTrackId?.substringBefore('.')?.lowercase() ?: return@filter false
                    base == lang || base.startsWith("$lang-")
                }
            if (langHits.isNotEmpty()) return langHits
        }

        val untagged = eligible.filter { it.audioTrackId.isNullOrBlank() }
        return untagged.ifEmpty { eligible }
    }

    private fun generateCpn(): String =
        buildString(16) {
            repeat(16) { append(CPN_ALPHABET[rng.nextInt(CPN_ALPHABET.length)]) }
        }

    private suspend fun ensureVisitorData(forceFresh: Boolean = false): String? =
        withContext(Dispatchers.IO) {
            if (!forceFresh) {
                visitorData?.takeIf { it.isNotBlank() }?.let { return@withContext it }
            }
            visitorMutex.withLock {
                if (!forceFresh) {
                    visitorData?.takeIf { it.isNotBlank() }?.let { return@withLock it }
                }
                val minted = mintVisitorData()
                if (minted != null) {
                    visitorData = minted
                    visitorGeneration++
                }
                minted
            }
        }

    private fun mintVisitorData(): String? =
        runCatching {
            val body =
                "{\"context\":{\"client\":{\"clientName\":\"${CLIENT_WEB.name}\"," +
                    "\"clientVersion\":\"${CLIENT_WEB.version}\",\"hl\":\"en\",\"gl\":\"US\"}}}"
            val req =
                Request
                    .Builder()
                    .url("${API_MAIN}visitor_id?prettyPrint=false")
                    .post(body.toRequestBody(postMedia))
                    .header("User-Agent", CLIENT_WEB.ua)
                    .header("X-YouTube-Client-Name", CLIENT_WEB.clientId)
                    .header("X-YouTube-Client-Version", CLIENT_WEB.version)
                    .header("X-Origin", "https://www.youtube.com")
                    .header("Referer", "https://www.youtube.com/")
                    .build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@runCatching null
                val root = json.parseToJsonElement(r.body?.string() ?: return@runCatching null).jsonObject
                root
                    .obj("responseContext")
                    ?.str("visitorData")
                    ?.takeIf { VISITOR_PATTERN.containsMatchIn(it) }
            }
        }.getOrNull()

    private fun buildClientBody(
        id: Identity,
        vd: String?,
        extraMember: String,
    ): String {
        val extras =
            buildString {
                id.deviceMake?.let { append(",\"deviceMake\":\"$it\"") }
                id.model?.let { append(",\"deviceModel\":\"$it\"") }
                id.osName?.let { append(",\"osName\":\"$it\"") }
                id.osVersion?.let { append(",\"osVersion\":\"$it\"") }
                id.androidSdkVersion?.let { append(",\"androidSdkVersion\":$it") }
                id.platform?.let { append(",\"platform\":\"$it\"") }
                id.clientScreen?.let { append(",\"clientScreen\":\"$it\"") }
                vd?.takeIf { it.isNotBlank() }?.let { append(",\"visitorData\":\"$it\"") }
                append(",\"hl\":\"en\",\"gl\":\"US\"")
            }
        return "{\"context\":{\"client\":{\"clientName\":\"${id.name}\",\"clientVersion\":\"${id.version}\"$extras}}," +
            "\"contentCheckOk\":true,\"racyCheckOk\":true,\"prettyPrint\":false,$extraMember}"
    }

    private suspend fun post(
        route: String,
        extraBody: String,
        id: Identity,
        attachAuth: Boolean = false,
        vd: String? = null,
        extraQuery: String = "",
    ): JsonObject? =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = buildClientBody(id, vd, extraBody)
                val tok = if (attachAuth && id.loginSupported) YouTubeSession.accessOrRefresh() else null
                val url = "${id.apiBase}$route?prettyPrint=false$extraQuery"
                val req =
                    Request
                        .Builder()
                        .url(url)
                        .post(body.toRequestBody(postMedia))
                        .header("User-Agent", id.ua)
                        .header("X-Goog-Api-Format-Version", id.apiFormatVersion)
                        .header("X-YouTube-Client-Name", id.clientId)
                        .header("X-YouTube-Client-Version", id.version)
                        .apply {
                            if (id.sendOriginHeaders) {
                                header("X-Origin", "https://www.youtube.com")
                                header("Referer", "https://www.youtube.com/")
                            }
                            vd?.takeIf { it.isNotBlank() }?.let { header("X-Goog-Visitor-Id", it) }
                            if (tok != null) header("Authorization", "Bearer $tok")
                        }.build()
                client.newCall(req).execute().use { r ->
                    val txt = r.body?.string()
                    android.util.Log.d(TAG, "POST $route/${id.name} -> ${r.code} len=${txt?.length}")
                    if (r.code == 401 && tok != null) {
                        YouTubeSession.invalidateAccessToken()
                    }
                    if (!r.isSuccessful) {
                        android.util.Log.e(TAG, "POST $route/${id.name} HTTP ${r.code}: ${txt?.take(300)}")
                        return@runCatching null
                    }
                    val rootObj = json.parseToJsonElement(txt ?: return@runCatching null).jsonObject
                    rootObj.obj("playabilityStatus")?.str("status")?.takeIf { it != "OK" }?.let { st ->
                        val reason = rootObj.obj("playabilityStatus")?.str("reason")
                        android.util.Log.w(TAG, "PLAYER $route/${id.name} status=$st reason=$reason")
                    }
                    rootObj
                }
            }.onFailure {
                android.util.Log.e(TAG, "POST $route/${id.name} threw", it)
            }.getOrNull()
        }

    data class YouTubeShelf(
        val title: String,
        val videos: List<YouTubeVideo>,
    )

    // ── home feed — ANDROID_VR primary + WEB fallback ──

    suspend fun homeFeed(): List<YouTubeShelf> =
        withContext(Dispatchers.IO) {
            // Don't block on visitorData minting — browse works without it and we can
            // attach it on the next request. This saves ~300-800ms on cold start.
            val vd = visitorData
            val vrRoot = post("browse", "\"browseId\":\"FEwhat_to_watch\"", CLIENT_ANDROID_VR, attachAuth = false, vd = vd)
            val vrShelves = vrRoot?.let(::parseBrowseShelves).orEmpty()
            if (vrShelves.isNotEmpty()) {
                android.util.Log.d(TAG, "homeFeed(VR) shelves=${vrShelves.size} videos=${vrShelves.sumOf { it.videos.size }}")
                if (vd == null) this@YouTubeApiClient.bgScope.launch { ensureVisitorData() }
                return@withContext vrShelves
            }
            val webRoot = post("browse", "\"browseId\":\"FEwhat_to_watch\"", CLIENT_WEB, attachAuth = false, vd = vd)
            val webShelves = webRoot?.let(::parseBrowseShelves).orEmpty()
            android.util.Log.d(TAG, "homeFeed(WEB) shelves=${webShelves.size} videos=${webShelves.sumOf { it.videos.size }}")
            if (vd == null) this@YouTubeApiClient.bgScope.launch { ensureVisitorData() }
            webShelves
        }

    /**
     * The account's real subscription feed (FEsubscriptions), newest first as YouTube orders it.
     * Returns null when signed out or when the account request fails, so callers can fall back
     * to the local per-channel RSS merge. The ANDROID client is the only one here that accepts
     * a plain Bearer token for browse.
     */
    suspend fun subscriptionsFeed(): List<YouTubeVideo>? =
        withContext(Dispatchers.IO) {
            if (!YouTubeSession.signedIn()) return@withContext null
            val vd = visitorData
            val root =
                post("browse", "\"browseId\":\"FEsubscriptions\"", CLIENT_ANDROID, attachAuth = true, vd = vd)
                    ?: return@withContext null
            val videos = parseBrowseShelves(root).flatMap { it.videos }.distinctBy { it.videoId }
            android.util.Log.d(TAG, "subscriptionsFeed videos=${videos.size}")
            videos.takeIf { it.isNotEmpty() }
        }

    /** The account's liked videos (playlist LL); null when signed out or the request fails. */
    suspend fun likedVideos(): List<YouTubeVideo>? = accountPlaylist("VLLL")

    /** The account's Watch Later playlist (WL); null when signed out or the request fails. */
    suspend fun watchLater(): List<YouTubeVideo>? = accountPlaylist("VLWL")

    private suspend fun accountPlaylist(browseId: String): List<YouTubeVideo>? =
        withContext(Dispatchers.IO) {
            if (!YouTubeSession.signedIn()) return@withContext null
            val root =
                post("browse", "\"browseId\":\"$browseId\"", CLIENT_ANDROID, attachAuth = true, vd = visitorData)
                    ?: return@withContext null
            parseBrowseShelves(root).flatMap { it.videos }.distinctBy { it.videoId }.takeIf { it.isNotEmpty() }
        }

    internal fun parseBrowseShelves(root: JsonObject): List<YouTubeShelf> {
        val out = ArrayList<YouTubeShelf>(8)
        val tabs =
            root.obj("contents")?.obj("singleColumnBrowseResultsRenderer")?.arr("tabs")
                ?: root.obj("contents")?.obj("twoColumnBrowseResultsRenderer")?.arr("tabs")
                ?: emptyList()

        for (tab in tabs) {
            val tabContent = tab.obj("tabRenderer")?.obj("content") ?: continue

            // Shape A: sectionListRenderer -> shelfRenderer / itemSectionRenderer
            val secs = tabContent.obj("sectionListRenderer")?.arr("contents").orEmpty()
            for (sec in secs) {
                val shelf = sec.obj("shelfRenderer")
                if (shelf != null) {
                    val title = extractText(shelf.obj("title")) ?: continue
                    val videos = collectVideosFromContainer(shelf.obj("content"))
                    if (videos.isNotEmpty()) out += YouTubeShelf(title, videos.distinctBy { it.videoId })
                    continue
                }
                val isr = sec.obj("itemSectionRenderer")
                if (isr != null) {
                    val videos = collectVideosFromArray(isr.arr("contents").orEmpty())
                    if (videos.isNotEmpty()) out += YouTubeShelf("Recommended", videos.distinctBy { it.videoId })
                }
            }

            // Shape B: WEB richGridRenderer -> richItemRenderer -> videoRenderer / lockupViewModel
            val richItems = tabContent.obj("richGridRenderer")?.arr("contents").orEmpty()
            if (richItems.isNotEmpty()) {
                val gridVideos = ArrayList<YouTubeVideo>(24)
                for (entry in richItems) {
                    val richItem = entry.obj("richItemRenderer")?.obj("content")
                    if (richItem != null) {
                        parseAnyVideoRenderer(richItem)?.let { gridVideos += it }
                        continue
                    }
                    val richSec = entry.obj("richSectionRenderer")?.obj("content")?.obj("richShelfRenderer")
                    if (richSec != null) {
                        val title = extractText(richSec.obj("title")) ?: "Featured"
                        val secVideos =
                            richSec.arr("contents").orEmpty().mapNotNull { c ->
                                c.obj("richItemRenderer")?.obj("content")?.let(::parseAnyVideoRenderer)
                            }
                        if (secVideos.isNotEmpty()) out += YouTubeShelf(title, secVideos.distinctBy { it.videoId })
                    }
                }
                if (gridVideos.isNotEmpty()) {
                    out.add(0, YouTubeShelf("Recommended", gridVideos.distinctBy { it.videoId }))
                }
            }
        }
        return out
    }

    private fun collectVideosFromContainer(content: JsonObject?): List<YouTubeVideo> {
        content ?: return emptyList()
        val list =
            content.obj("verticalListRenderer")?.arr("items")
                ?: content.obj("horizontalListRenderer")?.arr("items")
                ?: content.obj("gridRenderer")?.arr("items")
                ?: content.obj("expandedShelfContentsRenderer")?.arr("items")
                ?: emptyList()
        return collectVideosFromArray(list)
    }

    private fun collectVideosFromArray(items: List<JsonElement>): List<YouTubeVideo> {
        val out = ArrayList<YouTubeVideo>(items.size)
        for (item in items) {
            val obj = item.obj() ?: continue
            parseAnyVideoRenderer(obj)?.let { out += it }
        }
        return out
    }

    private val AD_RENDERERS =
        setOf(
            "adSlotRenderer",
            "promotedSparklesWebRenderer",
            "promotedSparklesTextSearchRenderer",
            "promotedVideoRenderer",
            "compactPromotedVideoRenderer",
            "searchPyvRenderer",
            "displayAdRenderer",
            "inFeedAdLayoutRenderer",
            "videoMastheadAdV3Renderer",
            "statementBannerRenderer",
            "bannerPromoRenderer",
        )

    internal fun isAdContainer(container: JsonObject): Boolean = container.keys.any { it in AD_RENDERERS }

    internal fun parseAnyVideoRenderer(container: JsonObject): YouTubeVideo? {
        if (isAdContainer(container)) return null
        container.obj("compactVideoRenderer")?.let { return parseVideoObject(it) }
        container.obj("videoRenderer")?.let { return parseVideoObject(it) }
        container.obj("gridVideoRenderer")?.let { return parseVideoObject(it) }
        container.obj("lockupViewModel")?.let { return parseLockupViewModel(it) }
        if (container.str("videoId") != null) return parseVideoObject(container)
        return null
    }

    private fun parseVideoObject(v: JsonObject): YouTubeVideo? {
        val id = v.str("videoId") ?: return null
        val title = extractText(v.obj("title")) ?: return null
        val channel =
            extractText(v.obj("longBylineText"))
                ?: extractText(v.obj("shortBylineText"))
                ?: extractText(v.obj("ownerText"))
        val duration =
            extractText(v.obj("lengthText"))
                ?: v.arr("thumbnailOverlays").orEmpty().firstNotNullOfOrNull { ov ->
                    extractText(ov.obj("thumbnailOverlayTimeStatusRenderer")?.obj("text"))
                }
        val thumb =
            v
                .obj("thumbnail")
                ?.arr("thumbnails")
                .orEmpty()
                .lastOrNull()
                ?.obj()
                ?.str("url")
                ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"
        val publishedText = extractText(v.obj("publishedTimeText"))
        val viewsText = extractText(v.obj("viewCountText")) ?: extractText(v.obj("shortViewCountText"))
        return YouTubeVideo(
            id,
            title,
            channel,
            duration,
            thumb,
            channelId = findChannelId(v),
            publishedText = publishedText?.takeIf { it.isNotBlank() },
            viewsText = viewsText?.takeIf { it.isNotBlank() },
        )
    }

    /** Parses YouTube's newer `lockupViewModel` cards. */
    internal fun parseLockupViewModel(lockup: JsonObject): YouTubeVideo? {
        val type = lockup.str("contentType")
        if (type != null && type != "LOCKUP_CONTENT_TYPE_VIDEO") return null
        val id = lockup.str("contentId")?.takeIf { it.length == 11 } ?: return null
        val meta = lockup.obj("metadata")?.obj("lockupMetadataViewModel")
        val title = meta?.obj("title")?.str("content") ?: return null
        val rows =
            meta
                .obj("metadata")
                ?.obj("contentMetadataViewModel")
                ?.arr("metadataRows")
                .orEmpty()
        val parts =
            rows.flatMap { row ->
                row
                    .obj()
                    ?.arr("metadataParts")
                    .orEmpty()
                    .mapNotNull { part -> part.obj()?.obj("text")?.str("content") }
            }
        val channel = parts.firstOrNull()
        val afterChannel = parts.drop(1)
        val viewsText = afterChannel.firstOrNull { it.contains("view", ignoreCase = true) }
        val publishedText =
            afterChannel.firstOrNull {
                it != viewsText && (it.contains("ago", ignoreCase = true) || it.contains("streamed", ignoreCase = true))
            }
        val thumbVm = lockup.obj("contentImage")?.obj("thumbnailViewModel")
        val thumb =
            thumbVm
                ?.obj("image")
                ?.arr("sources")
                .orEmpty()
                .lastOrNull()
                ?.obj()
                ?.str("url")
                ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"
        val duration =
            thumbVm?.arr("overlays").orEmpty().firstNotNullOfOrNull { ov ->
                ov
                    .obj("thumbnailOverlayBadgeViewModel")
                    ?.arr("thumbnailBadges")
                    .orEmpty()
                    .firstOrNull()
                    ?.obj()
                    ?.obj("thumbnailBadgeViewModel")
                    ?.str("text")
            }
        return YouTubeVideo(
            id,
            title,
            channel,
            duration,
            thumb,
            channelId = findChannelId(lockup),
            publishedText = publishedText?.takeIf { it.isNotBlank() },
            viewsText = viewsText?.takeIf { it.isNotBlank() },
        )
    }

    private fun extractText(obj: JsonObject?): String? {
        obj ?: return null
        obj.str("simpleText")?.takeIf { it.isNotBlank() }?.let { return it }
        val runs = obj.arr("runs").orEmpty()
        if (runs.isEmpty()) return null
        return runs.mapNotNull { it.obj()?.str("text") }.joinToString("").takeIf { it.isNotBlank() }
    }

    /** First `browseId` that names a channel (starts with UC) anywhere inside a renderer. */
    internal fun findChannelId(node: JsonElement?): String? {
        when (node) {
            is JsonObject -> {
                node.str("browseId")?.takeIf { it.startsWith("UC") && it.length >= 20 }?.let { return it }
                for (child in node.values) findChannelId(child)?.let { return it }
            }

            is kotlinx.serialization.json.JsonArray -> {
                for (child in node) findChannelId(child)?.let { return it }
            }

            else -> {
                Unit
            }
        }
        return null
    }

    data class YouTubeChannel(
        val id: String,
        val name: String,
        val avatar: String?,
        val videos: List<YouTubeVideo>,
    )

    /** A channel's name, avatar and latest uploads (the Videos tab). */
    suspend fun channel(channelId: String): YouTubeChannel? =
        withContext(Dispatchers.IO) {
            val extra = "\"browseId\":${JsonPrimitive(channelId)},\"params\":\"EgZ2aWRlb3PyBgQKAjoA\""
            val vd = visitorData
            val root =
                post("browse", extra, CLIENT_WEB, attachAuth = false, vd = vd)
                    ?: post("browse", extra, CLIENT_ANDROID_VR, attachAuth = false, vd = vd)
                    ?: return@withContext null
            val meta = root.obj("metadata")?.obj("channelMetadataRenderer")
            val name =
                meta?.str("title")
                    ?: extractText(root.obj("header")?.obj("c4TabbedHeaderRenderer")?.obj("title"))
                    ?: return@withContext null
            val avatar =
                meta
                    ?.obj("avatar")
                    ?.arr("thumbnails")
                    ?.firstOrNull()
                    ?.obj()
                    ?.str("url")
            val videos =
                parseBrowseShelves(root)
                    .flatMap { it.videos }
                    .distinctBy { it.videoId }
                    .map { it.copy(channel = it.channel ?: name, channelId = channelId) }
            YouTubeChannel(channelId, name, avatar, videos)
        }
    // ── search — ANDROID_VR primary + WEB fallback ──

    suspend fun search(query: String): List<YouTubeVideo> =
        withContext(Dispatchers.IO) {
            val vd = ensureVisitorData()
            val vrRoot = post("search", "\"query\":${JsonPrimitive(query)}", CLIENT_ANDROID_VR, attachAuth = false, vd = vd)
            val vrVideos = vrRoot?.let(::parseSearchVideos).orEmpty()
            if (vrVideos.isNotEmpty()) {
                android.util.Log.d(TAG, "search(VR) parsed ${vrVideos.size}")
                return@withContext vrVideos
            }
            val webRoot = post("search", "\"query\":${JsonPrimitive(query)}", CLIENT_WEB, attachAuth = false, vd = vd)
            val webVideos = webRoot?.let(::parseSearchVideos).orEmpty()
            android.util.Log.d(TAG, "search(WEB) parsed ${webVideos.size}")
            webVideos
        }

    internal fun parseSearchVideos(root: JsonObject): List<YouTubeVideo> {
        val out = ArrayList<YouTubeVideo>(24)
        val rows =
            root.obj("contents")?.obj("sectionListRenderer")?.arr("contents")
                ?: root
                    .obj("contents")
                    ?.obj("twoColumnSearchResultsRenderer")
                    ?.obj("primaryContents")
                    ?.obj("sectionListRenderer")
                    ?.arr("contents")
                ?: emptyList()
        for (sec in rows) {
            val items = sec.obj("itemSectionRenderer")?.arr("contents").orEmpty()
            for (item in items) {
                val obj = item.obj() ?: continue
                parseAnyVideoRenderer(obj)?.let { out += it }
            }
        }
        return out.distinctBy { it.videoId }.take(24)
    }

    // ── stream resolution — cascade ──

    suspend fun resolve(
        videoId: String,
        forceFresh: Boolean = false,
    ): YouTubeStreams? =
        withContext(Dispatchers.IO) {
            // Use cached visitorData; mint in background if missing. Don't block playback
            // on a separate visitor_id round-trip.
            val vd =
                if (forceFresh) {
                    ensureVisitorData(forceFresh = true)
                } else {
                    (
                        visitorData ?: run {
                            this@YouTubeApiClient.bgScope.launch { ensureVisitorData() }
                            null
                        }
                    )
                }
            val wallDetected = booleanArrayOf(false)

            walkLadder(videoId, vd, wallDetected)?.let { return@withContext it }

            // Bot wall at /player: re-roll visitorData once and retry the ladder cleanly.
            if (wallDetected[0]) {
                android.util.Log.w(TAG, "Bot wall at /player for $videoId — rerolling visitorData")
                clientGate.clear()
                val freshVd = ensureVisitorData(forceFresh = true)
                walkLadder(videoId, freshVd, booleanArrayOf(false))?.let { return@withContext it }
            }

            null
        }

    private suspend fun walkLadder(
        videoId: String,
        vd: String?,
        wallDetected: BooleanArray,
    ): YouTubeStreams? {
        // 1) Fast direct clients (VISIONOS 1.04 + 1.02) — parallelized for speed; the player
        //    streams their adaptive HLS manifest, which is the reliable delivery.
        val fastUngated = FAST_CLIENTS.filterNot { clientGate.isGated(it.idKey) }
        if (fastUngated.size > 1) {
            val results =
                kotlinx.coroutines.coroutineScope {
                    fastUngated
                        .map { id ->
                            async { tryDirectClient(videoId, id, vd, wallDetected) }
                        }.map { it.await() }
                }
            results.firstOrNull { it != null }?.let { return it }
        } else {
            for (id in fastUngated) {
                tryDirectClient(videoId, id, vd, wallDetected)?.let { return it }
            }
        }

        // 2) Durable TV_TIZEN path (solves visitor walls #921 and label/kids restrictions #1292)
        if (!clientGate.isGated(CLIENT_TV_TIZEN.idKey)) {
            tryTizenClient(videoId, vd, wallDetected)?.let { return it }
        }

        // 3) Fallback direct clients (ANDROID_VR, ANDROID, IOS)
        val fallbackUngated = FALLBACK_CLIENTS.filterNot { clientGate.isGated(it.idKey) }
        for (id in fallbackUngated) {
            tryDirectClient(videoId, id, vd, wallDetected)?.let { return it }
        }

        // 4) Last resort: retry demoted direct clients rather than giving up completely
        val demoted = (FAST_CLIENTS + FALLBACK_CLIENTS).filter { clientGate.isGated(it.idKey) }
        for (id in demoted) {
            tryDirectClient(videoId, id, vd, wallDetected)?.let { return it }
        }

        return null
    }

    private suspend fun tryDirectClient(
        videoId: String,
        id: Identity,
        vd: String?,
        wallDetected: BooleanArray,
    ): YouTubeStreams? {
        val cpn = generateCpn()
        val extraQuery = if (id.apiBase == API_GAPIS) "&id=$videoId" else ""
        val root =
            post(
                route = "player",
                extraBody = "\"videoId\":\"$videoId\",\"cpn\":\"$cpn\"",
                id = id,
                attachAuth = id.loginSupported,
                vd = vd,
                extraQuery = extraQuery,
            ) ?: return null

        val ps = root.obj("playabilityStatus")
        val status = ps?.str("status")
        if (status != "OK") {
            val reason = ps?.str("reason").orEmpty()
            if (status == "LOGIN_REQUIRED" && ("bot" in reason.lowercase() || "sign in" in reason.lowercase())) {
                wallDetected[0] = true
            }
            return null
        }

        return buildStreams(root, videoId, id, cpn)
    }

    /**
     * Flow's TizenStreamResolver: TVHTML5 5.20260707 + PipePipe remote decoder API
     * (signatureTimestamp, signatureCipher batch solve, n-param batch solve, PrerollWait).
     */
    private suspend fun tryTizenClient(
        videoId: String,
        vd: String?,
        wallDetected: BooleanArray,
    ): YouTubeStreams? {
        val (playerId, sts) = ensurePipePipePlayer() ?: return null
        val cpn = generateCpn()
        val extra =
            "\"videoId\":\"$videoId\",\"cpn\":\"$cpn\"," +
                "\"playbackContext\":{\"contentPlaybackContext\":{\"signatureTimestamp\":$sts}}"
        val answeredStart = System.currentTimeMillis()
        val root =
            post(
                route = "player",
                extraBody = extra,
                id = CLIENT_TV_TIZEN,
                attachAuth = false,
                vd = vd,
            ) ?: return null

        val ps = root.obj("playabilityStatus")
        val status = ps?.str("status")
        if (status != "OK") {
            val reason = ps?.str("reason").orEmpty()
            if (status == "LOGIN_REQUIRED" && "bot" in reason.lowercase()) wallDetected[0] = true
            return null
        }

        val sd = root.obj("streamingData") ?: return null
        val rawAdaptive = sd.arr("adaptiveFormats").orEmpty().mapNotNull { it as? JsonObject }
        if (rawAdaptive.isEmpty()) return null

        val ciphers =
            rawAdaptive.associateWith { o ->
                YouTubeSignatureCipher.parse(o.str("signatureCipher") ?: o.str("cipher"))
            }
        val sigs = ciphers.values.mapNotNull { it?.signature }.distinct()
        val solvedSigs = if (sigs.isNotEmpty()) pipePipeDecode(playerId, "sig", sigs) else emptyMap()

        val signedPairs =
            rawAdaptive.mapNotNull { o ->
                val rawUrl = o.str("url")
                val resolvedUrl =
                    when {
                        !rawUrl.isNullOrBlank() -> rawUrl
                        else -> ciphers[o]?.let { c -> solvedSigs[c.signature]?.let { c.signedUrl(it) } }
                    } ?: return@mapNotNull null
                o to resolvedUrl
            }
        if (signedPairs.isEmpty()) return null

        val rawNs = signedPairs.mapNotNull { (_, u) -> YouTubeStreamDenial.queryParam(u, "n") }.distinct()
        val solvedNs = if (rawNs.isNotEmpty()) pipePipeDecode(playerId, "n", rawNs) else emptyMap()

        val candidates =
            signedPairs.mapNotNull { (o, u) ->
                val nRaw = YouTubeStreamDenial.queryParam(u, "n")
                val finalUrl =
                    if (nRaw != null) {
                        val dec = solvedNs[nRaw] ?: return@mapNotNull null
                        u.replaceFirst(N_PARAM_REGEX, "$1n=${URLEncoder.encode(dec, "UTF-8")}")
                    } else {
                        u
                    }
                toFormatCandidate(o, YouTubeStreamDenial.appendParamIfMissing(finalUrl, "cpn", cpn))
            }

        val maxHeight = qualityCapForPref()
        val effectiveCap = if (maxHeight == 0) 360 else maxHeight
        val bestV = pickBestVideo(candidates, effectiveCap, CODEC_TAGS.getOrNull(AppPreferences.youTubeCodec)) ?: return null
        val bestA = pickBestAudio(candidates) ?: return null

        val prerollMs = YouTubePrerollWait.of(root["adPlacements"] as? JsonArray)
        val elapsed = System.currentTimeMillis() - answeredStart
        val waitMs = prerollMs - elapsed
        if (waitMs > 0) {
            android.util.Log.d(TAG, "TV_TIZEN preroll wait ${waitMs}ms for $videoId")
            delay(waitMs)
        }

        val details = root.obj("videoDetails")
        val title = details?.str("title") ?: videoId
        val viewCount = details?.str("viewCount")?.takeIf { it.isNotBlank() }
        val channelId = details?.str("channelId")?.takeIf { it.isNotBlank() }
        val publishedAt =
            parsePublishDate(
                root.obj("microformat")?.obj("playerMicroformatRenderer")?.str("publishDate")
                    ?: root.obj("microformat")?.obj("playerMicroformatRenderer")?.str("uploadDate"),
            )
        android.util.Log.i(TAG, "Resolved $videoId via TV_TIZEN (${bestV.height}p, itag=${bestV.itag}+${bestA.itag})")
        return YouTubeStreams(
            title = title,
            videoUrl = bestV.url,
            audioUrl = bestA.url,
            muxedUrl = null,
            itag = "${bestV.itag} (${bestV.height}p${if (bestV.fps > 30) bestV.fps else ""})",
            userAgent = CLIENT_TV_TIZEN.ua,
            hlsUrl = sd.str("hlsManifestUrl"),
            isLive = false,
            clientName = CLIENT_TV_TIZEN.name,
            classifications =
                buildMap {
                    put("Delivery", "Adaptive (split A/V)")
                    put("Client", CLIENT_TV_TIZEN.name)
                    put("Quality", "${bestV.height}p${if (bestV.fps > 30) " ${bestV.fps}fps" else ""}")
                    if (bestV.isHdr) put("HDR", "Yes")
                },
            viewCount = viewCount,
            channelId = channelId,
            publishedAt = publishedAt,
        )
    }

    private fun buildStreams(
        root: JsonObject,
        videoId: String,
        id: Identity,
        cpn: String,
    ): YouTubeStreams? {
        val sd = root.obj("streamingData") ?: return null
        val details = root.obj("videoDetails")
        val title = details?.str("title") ?: videoId
        val viewCount = details?.str("viewCount")?.takeIf { it.isNotBlank() }
        val channelId = details?.str("channelId")?.takeIf { it.isNotBlank() }
        val publishedAt =
            parsePublishDate(
                root.obj("microformat")?.obj("playerMicroformatRenderer")?.str("publishDate")
                    ?: root.obj("microformat")?.obj("playerMicroformatRenderer")?.str("uploadDate"),
            )
        val hlsUrl = sd.str("hlsManifestUrl")?.takeIf { it.isNotBlank() }
        val isLive = (details?.str("isLive") == "true") && (details.str("isPostLiveDvr") != "true")

        if (isLive) {
            if (hlsUrl == null) return null
            android.util.Log.i(TAG, "Resolved LIVE $videoId via ${id.name} (HLS)")
            return YouTubeStreams(
                title = title,
                videoUrl = null,
                audioUrl = null,
                muxedUrl = null,
                itag = "LIVE · HLS",
                userAgent = id.ua,
                hlsUrl = hlsUrl,
                isLive = true,
                clientName = id.name,
                classifications = mapOf("Delivery" to "HLS (live)", "Client" to id.name),
                viewCount = viewCount,
                channelId = channelId,
                publishedAt = publishedAt,
            )
        }

        val muxedObjs = sd.arr("formats").orEmpty().mapNotNull { it as? JsonObject }
        val adaptiveObjs = sd.arr("adaptiveFormats").orEmpty().mapNotNull { it as? JsonObject }

        val adaptive =
            adaptiveObjs.mapNotNull { o ->
                val rawUrl = o.str("url")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                toFormatCandidate(o, YouTubeStreamDenial.appendParamIfMissing(rawUrl, "cpn", cpn))
            }
        val muxed =
            muxedObjs.mapNotNull { o ->
                val rawUrl = o.str("url")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                toFormatCandidate(o, YouTubeStreamDenial.appendParamIfMissing(rawUrl, "cpn", cpn))
            }

        val maxHeight = qualityCapForPref()
        val bestMuxed = muxed.maxWithOrNull(compareBy<FormatCandidate>({ it.height }, { it.bitrate }))
        // When user selected "360p Single" (maxHeight == 0) and this client ships no muxed
        // format (VISIONOS), fall back gracefully to 360p adaptive rather than failing.
        val adaptiveCap = if (maxHeight == 0 && bestMuxed == null) 360 else maxHeight
        val bestV = pickBestVideo(adaptive, adaptiveCap, CODEC_TAGS.getOrNull(AppPreferences.youTubeCodec))
        val bestA = if (bestV != null) pickBestAudio(adaptive) else null

        if ((bestV == null || bestA == null) && bestMuxed == null && hlsUrl == null) {
            return null
        }

        val chosenV = if (bestV != null && bestA != null) bestV else null
        val chosenA = if (chosenV != null) bestA else null

        android.util.Log.i(
            TAG,
            "Resolved $videoId via ${id.name}/${id.version}: v=${chosenV?.itag}(${chosenV?.height}p) " +
                "a=${chosenA?.itag}(${chosenA?.audioTrackName ?: "default"}) muxed=${bestMuxed?.itag} hls=${hlsUrl != null}",
        )
        return YouTubeStreams(
            title = title,
            videoUrl = chosenV?.url,
            audioUrl = chosenA?.url,
            muxedUrl = bestMuxed?.url,
            itag =
                chosenV?.let { "${it.itag} (${it.height}p${if (it.fps > 30) it.fps else ""})" }
                    ?: bestMuxed?.let { "${it.itag} (${it.height}p)" }
                    ?: if (hlsUrl != null) "HLS" else null,
            userAgent = id.ua,
            hlsUrl = hlsUrl,
            isLive = false,
            clientName = id.name,
            classifications =
                buildMap {
                    put(
                        "Delivery",
                        when {
                            chosenV != null -> "Adaptive (split A/V)"
                            bestMuxed != null -> "Muxed progressive"
                            else -> "HLS"
                        },
                    )
                    put("Client", id.name)
                    chosenV?.let {
                        put("Quality", "${it.height}p${if (it.fps > 30) " ${it.fps}fps" else ""}")
                        if (it.isHdr) put("HDR", "Yes")
                    }
                },
            viewCount = viewCount,
            channelId = channelId,
            publishedAt = publishedAt,
        )
    }

    /** Player microformat publishDate (ISO 8601 with offset) → epoch ms; 0 when absent. */
    private fun parsePublishDate(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        return runCatching {
            java.time.OffsetDateTime
                .parse(raw)
                .toInstant()
                .toEpochMilli()
        }.getOrDefault(0L)
    }

    private fun qualityCapForPref(): Int =
        when (AppPreferences.youTubeQuality.coerceIn(0, 2)) {
            1 -> 720

            2 -> 0

            // Uncapped up to 8K: pickBestVideo simply takes the best format the
            // video ships, so 4K and 8K are reached whenever YouTube offers them.
            else -> 4320
        }

    private fun toFormatCandidate(
        o: JsonObject,
        url: String,
    ): FormatCandidate {
        val audioTrack = o.obj("audioTrack")
        val trackId = audioTrack?.str("id")
        val trackName = audioTrack?.str("displayName")
        val isDefault = audioTrack?.str("audioIsDefault") == "true"
        val isOriginal =
            isDefault ||
                (trackName?.contains("original", ignoreCase = true) == true) ||
                (trackId?.endsWith(".4") == true)
        val isDrc =
            o.str("isDrc") == "true" ||
                (o.str("xtags")?.contains("drc=1") == true)
        val qualityLabel = o.str("qualityLabel").orEmpty()
        val isHdr = qualityLabel.contains("HDR", ignoreCase = true)
        return FormatCandidate(
            itag = o.str("itag") ?: "",
            url = url,
            mimeType = o.str("mimeType") ?: "",
            height = o.str("height")?.toIntOrNull() ?: 0,
            fps = o.str("fps")?.toIntOrNull() ?: 30,
            bitrate = o.str("bitrate")?.toIntOrNull() ?: 0,
            width = o.str("width")?.toIntOrNull() ?: 0,
            isDefaultAudio = isDefault,
            audioTrackId = trackId,
            audioTrackName = trackName,
            isOriginalAudio = isOriginal,
            isDrc = isDrc,
            isHdr = isHdr,
        )
    }

    // ── PipePipe remote decoder (TV_TIZEN sig & n-param solver) ──

    /** Pre-warm PipePipe decoder player ID at app startup so TV_TIZEN path is fast. */
    suspend fun preWarmPipePipe() {
        runCatching { ensurePipePipePlayer() }
    }

    private suspend fun ensurePipePipePlayer(): Pair<String, Int>? =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val cachedId = ppPlayerId
            val cachedSts = ppSts
            if (cachedId != null && cachedSts != null && now < ppExpiryMs) {
                return@withContext cachedId to cachedSts
            }
            pipePipeMutex.withLock {
                val id2 = ppPlayerId
                val sts2 = ppSts
                if (id2 != null && sts2 != null && System.currentTimeMillis() < ppExpiryMs) {
                    return@withLock id2 to sts2
                }
                runCatching {
                    val req =
                        Request
                            .Builder()
                            .url(PIPEPIPE_LATEST_PLAYER)
                            .header("User-Agent", PIPEPIPE_UA)
                            .build()
                    client.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) return@runCatching null
                        val obj = json.parseToJsonElement(r.body?.string() ?: return@runCatching null).jsonObject
                        val pid = obj.str("player")?.takeIf { it.isNotBlank() } ?: return@runCatching null
                        val sts = obj.str("signatureTimestamp")?.toIntOrNull() ?: return@runCatching null
                        ppPlayerId = pid
                        ppSts = sts
                        ppExpiryMs = System.currentTimeMillis() + 24L * 60L * 60L * 1000L
                        pid to sts
                    }
                }.getOrNull()
            }
        }

    private suspend fun pipePipeDecode(
        playerId: String,
        paramName: String,
        values: List<String>,
    ): Map<String, String> =
        withContext(Dispatchers.IO) {
            if (values.isEmpty()) return@withContext emptyMap()
            runCatching {
                val joined = values.joinToString(",") { URLEncoder.encode(it, "UTF-8") }
                val req =
                    Request
                        .Builder()
                        .url("$PIPEPIPE_DECODE?player=$playerId&$paramName=$joined")
                        .header("User-Agent", PIPEPIPE_UA)
                        .build()
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@runCatching emptyMap()
                    val root = json.parseToJsonElement(r.body?.string() ?: return@runCatching emptyMap()).jsonObject
                    val data =
                        root
                            .arr("responses")
                            ?.firstOrNull()
                            ?.obj()
                            ?.obj("data")
                            ?: return@runCatching emptyMap()
                    val out = LinkedHashMap<String, String>(values.size)
                    for (v in values) {
                        data.str(v)?.takeIf { it.isNotBlank() }?.let { out[v] = it }
                    }
                    out
                }
            }.getOrDefault(emptyMap())
        }

    // ── SponsorBlock — privacy-preserving 4-char SHA-256 prefix ──

    data class Segment(
        val startSec: Double,
        val endSec: Double,
        val category: String,
        val action: String = "skip",
    )

    private val SB_LABEL =
        mapOf(
            "sponsor" to "sponsor",
            "selfpromo" to "self-promo",
            "intro" to "intro",
            "outro" to "outro",
            "interaction" to "nag",
            "filler" to "filler",
            "music_offtopic" to "off-topic",
            "preview" to "preview",
        )

    fun label(category: String) = SB_LABEL[category] ?: category

    suspend fun sponsorSegments(
        videoId: String,
        categories: List<String>,
    ): List<Segment> =
        withContext(Dispatchers.IO) {
            val cats = categories.joinToString(",", "[", "]") { "\"$it\"" }
            val actions = "[\"skip\",\"mute\",\"full\"]"
            val hashPrefix = HttpSupport.sha256Hex(videoId).take(4)
            val hashUrl =
                "https://sponsor.ajay.app/api/skipSegments/$hashPrefix?categories=" +
                    URLEncoder.encode(cats, "UTF-8") +
                    "&actionTypes=" + URLEncoder.encode(actions, "UTF-8")

            val fromHash =
                runCatching {
                    client.newCall(Request.Builder().url(hashUrl).build()).execute().use { r ->
                        if (!r.isSuccessful) return@runCatching null
                        parseSponsorSegmentsHashed(videoId, r.body?.string().orEmpty())
                    }
                }.getOrNull()
            if (fromHash != null) return@withContext fromHash

            runCatching {
                val directUrl =
                    "https://sponsor.ajay.app/api/skipSegments?videoID=$videoId&categories=" +
                        URLEncoder.encode(cats, "UTF-8") +
                        "&actionTypes=" + URLEncoder.encode(actions, "UTF-8")
                client.newCall(Request.Builder().url(directUrl).build()).execute().use { r ->
                    if (!r.isSuccessful) return@runCatching emptyList()
                    parseSponsorSegmentsArray(json.parseToJsonElement(r.body?.string().orEmpty()).jsonArray)
                }
            }.getOrDefault(emptyList())
        }

    internal fun parseSponsorSegmentsHashed(
        videoId: String,
        rawJson: String,
    ): List<Segment> {
        val arr = runCatching { json.parseToJsonElement(rawJson).jsonArray }.getOrNull() ?: return emptyList()
        val entry = arr.firstOrNull { it.obj()?.str("videoID") == videoId }?.obj() ?: return emptyList()
        val segs = entry["segments"] as? JsonArray ?: return emptyList()
        return parseSponsorSegmentsArray(segs)
    }

    private fun parseSponsorSegmentsArray(arr: JsonArray): List<Segment> =
        arr
            .mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val seg = o["segment"] as? JsonArray ?: return@mapNotNull null
                if (seg.size < 2) return@mapNotNull null
                val action = o.str("actionType") ?: "skip"
                if (action != "skip" && action != "mute" && action != "full") return@mapNotNull null
                val start = (seg[0] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return@mapNotNull null
                val end = (seg[1] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return@mapNotNull null
                Segment(
                    startSec = start,
                    endSec = end,
                    category = o.str("category") ?: "segment",
                    action = action,
                )
            }.sortedBy { it.startSec }

    /** Chapters from the web client's `next` response; empty when the video has none or the call fails. */
    suspend fun chapters(videoId: String): List<YouTubeChapters.Chapter> {
        val root = post("next", "\"videoId\":${JsonPrimitive(videoId)}", CLIENT_WEB, attachAuth = false, vd = ensureVisitorData())
        return root?.let(YouTubeChapters::parse).orEmpty()
    }

    data class YouTubeComment(
        val author: String,
        val text: String,
        val time: String? = null,
        val likes: String? = null,
    )

    /** Best-effort top-level comments from InnerTube `next`. Returns empty when unavailable/parse fails. */
    suspend fun comments(
        videoId: String,
        limit: Int = 8,
    ): List<YouTubeComment> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root =
                    post("next", "\"videoId\":${JsonPrimitive(videoId)}", CLIENT_WEB, attachAuth = false, vd = ensureVisitorData())
                        ?: return@runCatching emptyList()
                val panels = root.arr("engagementPanels").orEmpty().mapNotNull { it as? JsonObject }
                val commentPanel =
                    panels
                        .firstOrNull { p ->
                            p.obj("engagementPanelSectionListRenderer")?.str("panelIdentifier")?.contains("comment", ignoreCase = true) ==
                                true
                        }?.obj("engagementPanelSectionListRenderer")
                val items =
                    commentPanel
                        ?.obj("content")
                        ?.obj("sectionListRenderer")
                        ?.arr("contents")
                        .orEmpty()
                        .flatMap { c ->
                            c
                                .obj()
                                ?.obj("itemSectionRenderer")
                                ?.arr("contents")
                                .orEmpty()
                        }
                val out = ArrayList<YouTubeComment>(limit)
                for (item in items) {
                    val o = item as? JsonObject ?: continue
                    val r =
                        o.obj("commentThreadRenderer")?.obj("comment")?.obj("commentRenderer")
                            ?: o.obj("commentRenderer")
                            ?: continue
                    val author =
                        r.obj("authorText")?.str("simpleText")
                            ?: extractText(r.obj("authorText"))
                            ?: continue
                    val text = extractText(r.obj("contentText"))?.takeIf { it.isNotBlank() } ?: continue
                    val time = extractText(r.obj("publishedTimeText"))
                    val likeText = extractText(r.obj("voteCount"))?.takeIf { it.isNotBlank() && it != "0" }
                    out += YouTubeComment(author = author, text = text, time = time, likes = likeText)
                    if (out.size >= limit) break
                }
                out
            }.getOrDefault(emptyList())
        }

    // ── DeArrow — real sponsor.ajay.app/api/branding/<sha256_4> API ──

    private val deArrowCache =
        object : LinkedHashMap<String, Optional<Pair<String, String>>>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Optional<Pair<String, String>>>?) = size > 512
        }

    /** Returns (title, thumbnailUrl) overrides, or null when the crowd has none. Transient failures are not cached. */
    suspend fun deArrow(videoId: String): Pair<String, String>? {
        synchronized(deArrowCache) { deArrowCache[videoId] }?.let { return it.orElse(null) }
        val fetched =
            withContext(Dispatchers.IO) {
                runCatching {
                    val prefix = HttpSupport.sha256Hex(videoId).take(4)
                    val url = "https://sponsor.ajay.app/api/branding/$prefix"
                    val req = Request.Builder().url(url).build()
                    client.newCall(req).execute().use { r ->
                        when {
                            r.isSuccessful -> Optional.ofNullable(parseDeArrowBranding(videoId, r.body?.string().orEmpty()))
                            r.code == 404 -> Optional.empty()
                            else -> null
                        }
                    }
                }.getOrNull()
            }
        if (fetched != null) synchronized(deArrowCache) { deArrowCache[videoId] = fetched }
        return fetched?.orElse(null)
    }

    internal fun parseDeArrowBranding(
        videoId: String,
        rawJson: String,
    ): Pair<String, String>? {
        val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull() ?: return null
        val entry = root.obj(videoId) ?: return null
        val title =
            entry
                .arr("titles")
                .orEmpty()
                .firstOrNull { t ->
                    val o = t.obj() ?: return@firstOrNull false
                    val votes = o.str("votes")?.toIntOrNull() ?: 0
                    val locked = o.str("locked") == "true"
                    (votes >= 0 || locked) && !o.str("title").isNullOrBlank()
                }?.obj()
                ?.str("title")
                .orEmpty()

        val thumbEntry =
            entry
                .arr("thumbnails")
                .orEmpty()
                .firstOrNull { th ->
                    val o = th.obj() ?: return@firstOrNull false
                    val original = o.str("original") == "true"
                    val votes = o.str("votes")?.toIntOrNull() ?: 0
                    val locked = o.str("locked") == "true"
                    !original && (votes >= 0 || locked) && o.str("timestamp") != null
                }?.obj()
        val thumbUrl =
            thumbEntry
                ?.str("timestamp")
                ?.let { ts ->
                    "$DEARROW_THUMB_BASE?videoID=$videoId&time=$ts"
                }.orEmpty()

        return if (title.isNotBlank() || thumbUrl.isNotBlank()) title to thumbUrl else null
    }

    private const val OAUTH_DEVICE = "https://oauth2.googleapis.com/device/code"
    private const val OAUTH_TOKEN = "https://oauth2.googleapis.com/token"

    // The old 851556794569 TV client was revoked by Google (invalid_client), which broke sign-in.
    // Defaults to the current TV-and-Limited-Input credentials SmartTube ships for the device flow;
    // override with your own Google Cloud OAuth client via youtube.clientId (local.properties) or
    // YT_CLIENT_ID (CI) so the consent screen shows your app name. Keep in sync with upstream if
    // Google rotates the client again. The secret is injected via BuildConfig (YT_CLIENT_SECRET env
    // var or youtube.clientSecret in local.properties) so no OAuth secret lives in source.
    private const val CLIENT_ID: String = com.carteltv.tv.BuildConfig.YT_CLIENT_ID
    private val CLIENT_SECRET: String = com.carteltv.tv.BuildConfig.YT_CLIENT_SECRET

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verifyUrl: String,
        /**
         * The URL to encode in the QR and show for "scan or enter". Prefers Google's
         * `verification_url_with_code` so a scan lands straight on the approval screen with the
         * code already attached; falls back to `verification_url` + `?user_code=` so scanning never
         * drops the viewer on a bare page that dead-ends. [verifyUrl] stays the plain site for display.
         */
        val scanUrl: String = verifyUrl,
        /** How long Google keeps the code alive (seconds); polling must run at least this long. */
        val expiresInSec: Long = 1800,
        /** Minimum seconds between token polls. */
        val intervalSec: Long = 5,
    )

    suspend fun beginLogin(): DeviceCode? =
        withContext(Dispatchers.IO) {
            runCatching {
                val form =
                    okhttp3.FormBody
                        .Builder()
                        .add("client_id", CLIENT_ID)
                        // youtube.readonly is what InnerTube account browse (subscriptions/likes) requires;
                        // drive.file rides along because playback-only tokens verified against SmartTube use it.
                        .add("scope", "https://www.googleapis.com/auth/youtube.readonly https://www.googleapis.com/auth/drive.file")
                        .build()
                val req =
                    Request
                        .Builder()
                        .url(OAUTH_DEVICE)
                        .post(form)
                        .build()
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@runCatching null
                    val o = json.parseToJsonElement(r.body?.string() ?: "").jsonObject
                    val deviceCode = o.str("device_code") ?: return@runCatching null
                    val userCode = o.str("user_code") ?: ""
                    val verifyUrl = o.str("verification_url") ?: "https://www.google.com/device"
                    // Carry the code into the scanned link so a phone camera opens the approval
                    // screen directly instead of a bare landing page (the "404 after scan" dead-end).
                    val scanUrl =
                        o.str("verification_url_with_code")
                            ?: if (userCode.isNotBlank()) "$verifyUrl?user_code=$userCode" else verifyUrl
                    DeviceCode(
                        deviceCode = deviceCode,
                        userCode = userCode,
                        verifyUrl = verifyUrl,
                        scanUrl = scanUrl,
                        expiresInSec = o.str("expires_in")?.toLongOrNull() ?: 1800,
                        intervalSec = o.str("interval")?.toLongOrNull() ?: 5,
                    )
                }
            }.getOrNull()
        }

    /** Polls once; returns "GRANTED:<refresh>" | "PENDING" | "DENIED" | "ERROR:<google error>". */
    suspend fun pollLogin(deviceCode: String): String =
        withContext(Dispatchers.IO) {
            runCatching {
                val form =
                    okhttp3.FormBody
                        .Builder()
                        .add("client_id", CLIENT_ID)
                        .add("client_secret", CLIENT_SECRET)
                        .add("device_code", deviceCode)
                        .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                        .build()
                val req =
                    Request
                        .Builder()
                        .url(OAUTH_TOKEN)
                        .post(form)
                        .build()
                client.newCall(req).execute().use { r ->
                    val o = json.parseToJsonElement(r.body?.string() ?: "").jsonObject
                    when {
                        // A grant without a refresh token can't outlive the hour — treat as failure
                        // so the UI doesn't report success and silently lose the session.
                        o.str("access_token") != null && !o.str("refresh_token").isNullOrBlank() -> {
                            "GRANTED:" + o.str("refresh_token")
                        }

                        o.str("error") == "authorization_pending" || o.str("error") == "slow_down" -> {
                            "PENDING"
                        }

                        o.str("error") == "access_denied" -> {
                            "DENIED"
                        }

                        else -> {
                            val err = o.str("error") ?: "http"
                            android.util.Log.w(TAG, "Login poll: $err")
                            "ERROR:$err"
                        }
                    }
                }
            }.getOrDefault("ERROR:network")
        }

    /** Exchanges a refresh token for a fresh access token. */
    suspend fun accessToken(refreshToken: String): String? = accessTokenWithExpiry(refreshToken)?.first

    /** Permanent OAuth rejection (revoked grant or dead client ID) — retrying can never succeed. */
    class OAuthRejectedException(
        message: String,
    ) : Exception(message)

    /** Exchanges a refresh token for (accessToken, expiresInSeconds). */
    suspend fun accessTokenWithExpiry(refreshToken: String): Pair<String, Long>? =
        withContext(Dispatchers.IO) {
            try {
                val form =
                    okhttp3.FormBody
                        .Builder()
                        .add("client_id", CLIENT_ID)
                        .add("client_secret", CLIENT_SECRET)
                        .add("refresh_token", refreshToken)
                        .add("grant_type", "refresh_token")
                        .build()
                val req =
                    Request
                        .Builder()
                        .url(OAUTH_TOKEN)
                        .post(form)
                        .build()
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) {
                        // Google answers invalid_grant / invalid_client with 4xx: the stored
                        // token can never mint an access token again (e.g. after Google revoked
                        // the old TV client ID). Signal permanent so the session signs out
                        // instead of looping a dead token every 30s while feeds silently rot.
                        if (r.code in 400..499) throw OAuthRejectedException("token refresh ${r.code}")
                        return@use null
                    }
                    val o = json.parseToJsonElement(r.body?.string() ?: "").jsonObject
                    val tok = o.str("access_token") ?: return@use null
                    val expiresIn = o.str("expires_in")?.toLongOrNull()?.coerceAtLeast(300L) ?: 3600L
                    tok to expiresIn
                }
            } catch (e: OAuthRejectedException) {
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

    private fun JsonElement.obj(k: String): JsonObject? = (this as? JsonObject)?.let { it[k] as? JsonObject }

    private fun JsonElement.arr(k: String): List<JsonElement>? =
        runCatching { (this as? JsonObject)?.get(k)?.jsonArray?.toList() }.getOrNull()

    private fun JsonElement.str(k: String): String? = (this as? JsonObject)?.let { (it[k] as? JsonPrimitive)?.content }

    private fun JsonElement.obj(): JsonObject? = this as? JsonObject
}

/** Category presets cycled by Settings → YOUTUBE (indexes stable for AppPreferences). */
object YouTubeCategoryPresets {
    private val LIST =
        listOf(
            listOf("sponsor", "selfpromo") to "Standard Filter",
            listOf("sponsor", "selfpromo", "intro", "outro", "interaction", "filler", "music_offtopic")
                to "Strict Filter",
            listOf("sponsor") to "Sponsors Only",
        )

    fun size(): Int = LIST.size

    fun cats(index: Int): List<String> = LIST[index.coerceIn(0, LIST.size - 1)].first

    fun label(index: Int): String = LIST[index.coerceIn(0, LIST.size - 1)].second
}
