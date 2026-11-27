package com.carteltv.tv.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.carteltv.tv.data.apps.LauncherApp
import com.carteltv.tv.data.network.HttpSupport
import com.carteltv.tv.engine.stability.SecretVault
import kotlinx.serialization.Serializable

/**
 * All local state: session creds, favourites, recents, UX prefs. No cloud.
 *
 * Refactored: [AppPreferences] is now a thin backward-compatible facade over domain-specific preference
 * classes (see [ProviderPreferences], [PinPreferences], [WatchHistoryPreferences], etc.). Each domain class has a
 * single responsibility; this object delegates to them so all existing call sites keep working
 * unchanged.
 */
object AppPreferences {
    private lateinit var sp: SharedPreferences
    private val json get() = HttpSupport.json

    /** App context for Watch Next publishing hooks; null in JVM tests that call install() directly. */
    private var appContext: Context? = null

    // ── domain instances (lazy; sp is set by init() / install()) ──

    private val providerPreferences by lazy { ProviderPreferences(sp, json) }
    private val pinPreferences by lazy { PinPreferences(sp) }
    private val watchHistoryPreferences by lazy { WatchHistoryPreferences(sp, json, providerPreferences) { activeProfileId } }
    private val playbackPreferences by lazy { PlaybackPreferences(sp) }
    private val weatherPreferences by lazy { WeatherPreferences(sp) }
    private val dnsPreferences by lazy { DnsPreferences(sp) }
    private val traktPreferences by lazy { TraktPreferences(sp) }
    private val youtubeModsPreferences by lazy { YouTubeModsPreferences(sp) }
    private val homePreferences by lazy { HomePreferences(sp) }
    private val launcherPreferences by lazy { LauncherPreferences(sp) }
    private val sportsPreferences by lazy { SportsPreferences(sp) }
    private val epgPreferences by lazy { EpgPreferences(sp, providerPreferences) }
    private val playerPreferences by lazy { PlayerPreferences(sp) }
    private val aiPreferences by lazy { AiPreferences(sp) }
    private val debridPreferences by lazy { DebridPreferences(sp) }
    private val simklPreferences by lazy { SimklPreferences(sp) }
    private val stremioPreferences by lazy { StremioPreferences(sp) }
    private val peerSyncPreferences by lazy { PeerSyncPreferences(sp) }
    private val languagePreferences by lazy { LanguagePreferences(sp) }
    private val financePreferences by lazy { FinancePreferences(sp) }
    private val authorityPreferences by lazy { AuthorityPreferences(sp) }
    private val generalPreferences by lazy { GeneralPreferences(sp) }
    private val libraryPreferences by lazy { LibraryPreferences(sp, json) { activeProfileId } }
    private val streamPreferences by lazy { StreamPreferences(sp) }
    private val shelfPreferences by lazy { ShelfPreferences(sp, json) }

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        install(ctx.getSharedPreferences("carteltv", Context.MODE_PRIVATE))
    }

    /** Binds the backing store; tests pass an in-memory SharedPreferences. */
    internal fun install(store: SharedPreferences) {
        sp = store
        val stored = sp.getString("recents", null)
        if (stored != null) {
            val clean = redactStreamCredentials(stored)
            if (clean != stored) sp.edit().putString("recents", clean).apply()
        }
    }

    private fun redactStreamCredentials(text: String): String =
        com.carteltv.tv.data.provider.StreamCredentials
            .redact(text)

    /** Every saved provider, oldest first. A pre-multi-provider single login is imported on first read. */
    fun providers(): List<com.carteltv.tv.data.provider.Provider> = providerPreferences.providers()

    fun provider(id: String): com.carteltv.tv.data.provider.Provider? = providerPreferences.provider(id)

    /** Persists what the server last said about one provider's account (status, expiry, connections). */
    fun setProviderHealth(
        id: String,
        health: com.carteltv.tv.data.provider.ProviderHealth,
    ) {
        providerPreferences.setProviderHealth(id, health)
    }

    fun activeProvider(): com.carteltv.tv.data.provider.Provider? = providerPreferences.activeProvider()

    /** Adds the provider, or replaces the one with the same id. */
    fun upsertProvider(p: com.carteltv.tv.data.provider.Provider) {
        providerPreferences.upsertProvider(p)
    }

    /** Removes a provider; when it was the active one the first remaining provider takes over. */
    fun removeProvider(id: String) {
        providerPreferences.removeProvider(id)
    }

    /**
     * Saves a login that arrived without a provider id (the phone companion): the same server and user is updated,
     * otherwise it fills the provider being added or edited, otherwise it replaces the active one. Becomes active.
     */
    fun saveCredentials(c: Credentials) {
        providerPreferences.saveCredentials(c)
    }

    fun clearCredentials() {
        providerPreferences.clearCredentials()
    }

    /** The active provider's login, or null when none is set. */
    val credentials: Credentials?
        get() = providerPreferences.credentials

    var activeProviderId: String
        get() = providerPreferences.activeProviderId
        set(v) {
            providerPreferences.activeProviderId = v
        }

    /** Moves a provider one place up (-1) or down (+1); the list order is the failover priority. */
    fun moveProvider(
        id: String,
        delta: Int,
    ) {
        providerPreferences.moveProvider(id, delta)
    }

    /** Switch to the next working provider when the active one stops answering. Off unless the user turns it on. */
    var providerFailover: Boolean
        get() = providerPreferences.providerFailover
        set(v) {
            providerPreferences.providerFailover = v
        }

    /** Whether [id] is flagged for the deeper live rebuffer cushion (per provider). */
    fun deepBufferProvider(id: String): Boolean = providerPreferences.deepBufferProvider(id)

    fun setDeepBufferProvider(
        id: String,
        on: Boolean,
    ) = providerPreferences.setDeepBufferProvider(id, on)

    /** The active provider's deep-buffer flag: the live rebuffer resumes at 5 s instead of 3 s when on. */
    var deepBufferActive: Boolean
        get() = activeProvider()?.let { providerPreferences.deepBufferProvider(it.id) } ?: false
        set(v) {
            activeProvider()?.let { providerPreferences.setDeepBufferProvider(it.id, v) }
        }

    /** Hide, rename and order choices for the active provider's live groups, kept per provider. */
    fun channelGroupConfig(): com.carteltv.tv.data.xtream.ChannelGroupConfig = providerPreferences.channelGroupConfig()

    fun setChannelGroupConfig(config: com.carteltv.tv.data.xtream.ChannelGroupConfig) {
        providerPreferences.setChannelGroupConfig(config)
    }

    fun appPins(): Map<String, String> = pinPreferences.appPins()

    fun setAppPin(
        pkg: String,
        pin: String,
    ) = pinPreferences.setAppPin(pkg, pin)

    fun clearAppPin(pkg: String) = pinPreferences.clearAppPin(pkg)

    /** Milliseconds until another PIN attempt is allowed; 0 when not locked out. */
    fun pinLockRemainingMs(): Long = pinPreferences.pinLockRemainingMs()

    fun verifyAppPin(
        pkg: String,
        pin: String,
    ): Boolean = pinPreferences.verifyAppPin(pkg, pin)

    fun settingsPinSet(pin: String) = pinPreferences.settingsPinSet(pin)

    fun settingsPinClear() = pinPreferences.settingsPinClear()

    fun settingsPinIsSet(): Boolean = pinPreferences.settingsPinIsSet()

    fun settingsPinVerify(pin: String): Boolean = pinPreferences.settingsPinVerify(pin)

    var settingsPinOn: Boolean
        get() = pinPreferences.settingsPinOn
        set(v) {
            pinPreferences.settingsPinOn = v
        }

    fun favIds(): MutableSet<String> = (sp.getStringSet("favs", emptySet()) ?: emptySet()).toMutableSet()

    fun toggleFav(id: String) {
        val cur = favIds()
        if (!cur.add(id)) cur.remove(id)
        sp.edit().putStringSet("favs", cur).apply()
    }

    fun isFav(id: String) = id in favIds()

    typealias LibraryEntry = LibraryPreferences.Entry

    fun libraryEntries(): List<LibraryEntry> = libraryPreferences.entries()

    fun inLibrary(id: String): Boolean = libraryPreferences.has(id)

    fun addToLibrary(entry: LibraryEntry): Boolean = libraryPreferences.add(entry)

    fun removeFromLibrary(id: String): Boolean = libraryPreferences.remove(id)

    /** Adds when absent, removes when saved; returns the new saved state. */
    fun toggleLibrary(entry: LibraryEntry): Boolean = libraryPreferences.toggle(entry)

    fun clearLibrary() = libraryPreferences.clear()

    var streamAutoPlay: StreamPreferences.AutoPlay
        get() = streamPreferences.autoPlay
        set(v) {
            streamPreferences.autoPlay = v
        }

    var streamAutoPlayRegex: String
        get() = streamPreferences.autoPlayRegex
        set(v) {
            streamPreferences.autoPlayRegex = v
        }

    var streamSort: StreamPreferences.Sort
        get() = streamPreferences.sort
        set(v) {
            streamPreferences.sort = v
        }

    var streamResolutions: Set<String>
        get() = streamPreferences.resolutions
        set(v) {
            streamPreferences.resolutions = v
        }

    var streamHdrOnly: Boolean
        get() = streamPreferences.hdrOnly
        set(v) {
            streamPreferences.hdrOnly = v
        }

    var streamCachedOnly: Boolean
        get() = streamPreferences.cachedOnly
        set(v) {
            streamPreferences.cachedOnly = v
        }

    fun hiddenStreamSources(): Set<String> = streamPreferences.hiddenSources

    fun setHiddenStreamSources(sources: Set<String>) {
        streamPreferences.hiddenSources = sources
    }

    var streamReuseLastLink: Boolean
        get() = streamPreferences.reuseLastLink
        set(v) {
            streamPreferences.reuseLastLink = v
        }

    var streamLastLinkMinutes: Int
        get() = streamPreferences.lastLinkMinutes
        set(v) {
            streamPreferences.lastLinkMinutes = v
        }

    fun rememberLastLink(
        ref: String,
        url: String,
        infoHash: String?,
        title: String?,
    ) = streamPreferences.remember(ref, url, infoHash, title)

    fun lastLink(ref: String): StreamPreferences.LastLink? = streamPreferences.lastLink(ref)

    // ═════════ SHELVES ═════════

    typealias Shelf = ShelfPreferences.Shelf

    fun shelves(): List<Shelf> = shelfPreferences.shelves()

    fun addShelf(shelf: Shelf): Shelf = shelfPreferences.add(shelf)

    fun removeShelf(id: String) = shelfPreferences.remove(id)

    fun updateShelf(shelf: Shelf) = shelfPreferences.update(shelf)

    /** Sticky stream-id → channel-number assignments, kept in [com.carteltv.tv.data.xtream.ChannelNumbering.encode]'s form. */
    fun channelNumbers(): Map<Long, Int> =
        com.carteltv.tv.data.xtream.ChannelNumbering
            .decode(sp.getString("channel_numbers", null))

    fun saveChannelNumbers(numbers: Map<Long, Int>) {
        sp
            .edit()
            .putString(
                "channel_numbers",
                com.carteltv.tv.data.xtream.ChannelNumbering
                    .encode(numbers),
            ).apply()
    }

    /** Merges a [com.carteltv.tv.data.setup.LibraryBackup] into favourites and recents; returns the counts added. */
    fun restoreLibrary(backup: com.carteltv.tv.data.setup.LibraryBackup.Snapshot): Pair<Int, Int> {
        val favsBefore = favIds()
        val recentsBefore = recents()
        val merged =
            com.carteltv.tv.data.setup.LibraryBackup.merge(
                favsBefore,
                recentsBefore,
                backup,
            ) { parseRecent(it).ref }
        sp
            .edit()
            .putStringSet("favs", merged.favs)
            .apply()
        watchHistoryPreferences.replaceRecents(merged.recents)
        return (merged.favs.size - favsBefore.size) to (merged.recents.size - recentsBefore.size)
    }

    fun recents(): List<String> = watchHistoryPreferences.recents()

    fun removeRecent(entry: String) {
        watchHistoryPreferences.removeRecent(entry)
        removeWatchNextFor(parseRecent(entry).ref)
    }

    /** Drops a continue-watching card by ref, whatever entry grammar stored it. */
    fun removeRecentByRef(ref: String) {
        watchHistoryPreferences.removeRecentByRef(ref)
        removeWatchNextFor(ref)
    }

    /** A dismissed recent must not survive in the system Watch Next row. No-op for non-publishable refs. */
    private fun removeWatchNextFor(ref: String) {
        if (ref.isBlank()) return
        appContext?.let {
            com.carteltv.tv.data.watch.WatchNextPublisher
                .recentRemoved(it, ref)
        }
    }

    fun hiddenWatchNext(): Set<String> = watchHistoryPreferences.hiddenWatchNext()

    fun hideWatchNext(key: String) = watchHistoryPreferences.hideWatchNext(key)

    fun addRecent(entry: String) = watchHistoryPreferences.addRecent(entry)

    /** countdown widget target (epoch ms); 0 = unset */
    var countdownTargetMs: Long
        get() = watchHistoryPreferences.countdownTargetMs
        set(v) {
            watchHistoryPreferences.countdownTargetMs = v
        }

    typealias RecentEntry = WatchHistoryPreferences.RecentEntry
    typealias BufferPreset = PlaybackPreferences.BufferPreset
    typealias MultiViewSlot = PlayerPreferences.MultiViewSlot
    typealias MultiViewLayout = PlayerPreferences.MultiViewLayout

    fun recentEntries(): List<RecentEntry> = watchHistoryPreferences.recentEntries()

    fun parseRecent(raw: String): RecentEntry = watchHistoryPreferences.parseRecent(raw)

    fun encodeRecent(e: RecentEntry): String = watchHistoryPreferences.encodeRecent(e)

    /** Upsert by ref (any entry grammar), carrying forward poster when the writer omits it. */
    fun notePlayback(
        ref: String,
        title: String,
        url: String,
        poster: String,
        positionMs: Long,
        durationMs: Long,
    ) {
        watchHistoryPreferences.notePlayback(ref, title, url, poster, positionMs, durationMs)
        // Google TV Watch Next: publish/remove the system row for this ref, off the main thread.
        val app = appContext ?: return
        val entry = watchHistoryPreferences.recentEntries().firstOrNull { it.ref == ref } ?: return
        com.carteltv.tv.data.watch.WatchNextPublisher
            .notePlayback(app, entry)
    }

    /** 0.25..4.0, 1f when unset (per-title speed). */
    fun titleSpeed(ref: String): Float = playbackPreferences.titleSpeed(ref)

    fun setTitleSpeed(
        ref: String,
        speed: Float,
    ) = playbackPreferences.setTitleSpeed(ref, speed)

    /** Resume position override for this ref; 0 = follow the global RecentEntry. */
    fun titleResumeMs(ref: String): Long = playbackPreferences.titleResumeMs(ref)

    fun setTitleResumeMs(
        ref: String,
        posMs: Long,
    ) = playbackPreferences.setTitleResumeMs(ref, posMs)

    /** Per-title subtitle text scale (percent, 100 = default); 100 when unset. */
    fun titleSubtitleScale(ref: String): Int = playbackPreferences.titleSubtitleScale(ref)

    fun setTitleSubtitleScale(
        ref: String,
        pct: Int,
    ) = playbackPreferences.setTitleSubtitleScale(ref, pct)

    var cwHideTitles: Boolean
        get() = playbackPreferences.cwHideTitles
        set(v) {
            playbackPreferences.cwHideTitles = v
        }

    // ── Dialogue Boost: 0 OFF .. 3 STRONG; applies on next playback ──

    /** Send Dolby TrueHD (incl. Atmos) to the receiver as a MAT bitstream when the box has no native TrueHD passthrough. */
    var matTrueHd: Boolean
        get() = playbackPreferences.matTrueHd
        set(v) {
            playbackPreferences.matTrueHd = v
        }

    /** Show preview frames on the seek bar. Opens a second connection to the stream, so it is off by default. */
    var seekThumbs: Boolean
        get() = playbackPreferences.seekThumbs
        set(v) {
            playbackPreferences.seekThumbs = v
        }

    /** Hours of playback with no remote input before asking "Still watching?"; 0 is off. */
    var passOutHours: Int
        get() = playbackPreferences.passOutHours
        set(v) {
            playbackPreferences.passOutHours = v
        }

    var dialogueBoost: Int
        get() = playbackPreferences.dialogueBoost
        set(v) {
            playbackPreferences.dialogueBoost = v
        }

    /** resume-card columns; continueWatchingCardColumns */
    var cwColumns: Int
        get() = playbackPreferences.cwColumns
        set(v) {
            playbackPreferences.cwColumns = v
        }

    var bufferPreset: PlaybackPreferences.BufferPreset
        get() = playbackPreferences.bufferPreset
        set(v) {
            playbackPreferences.bufferPreset = v
        }

    var upNextMode: String
        get() = playbackPreferences.upNextMode
        set(v) {
            playbackPreferences.upNextMode = v
        }

    var ambienceEnabled: Boolean
        get() = playbackPreferences.ambienceEnabled
        set(v) {
            playbackPreferences.ambienceEnabled = v
        }

    /** "Hold ▸ to resume" reminder pill on Home. */
    var resumePill: Boolean
        get() = playbackPreferences.resumePill
        set(v) {
            playbackPreferences.resumePill = v
        }

    var weatherLat: Double
        get() = weatherPreferences.weatherLat
        set(v) {
            weatherPreferences.weatherLat = v
        }
    var weatherLon: Double
        get() = weatherPreferences.weatherLon
        set(v) {
            weatherPreferences.weatherLon = v
        }
    var cityName: String
        get() = weatherPreferences.cityName
        set(v) {
            weatherPreferences.cityName = v
        }

    var weatherAuto: Boolean
        get() = weatherPreferences.weatherAuto
        set(v) {
            weatherPreferences.weatherAuto = v
        }
    var weatherFahrenheit: Boolean
        get() = weatherPreferences.weatherFahrenheit
        set(v) {
            weatherPreferences.weatherFahrenheit = v
        }

    /** One of [com.carteltv.tv.data.weather.WindUnit] names. */
    var weatherWindUnit: String
        get() = weatherPreferences.weatherWindUnit
        set(v) {
            weatherPreferences.weatherWindUnit = v
        }
    var weatherRefreshMin: Int
        get() = weatherPreferences.weatherRefreshMin
        set(v) {
            weatherPreferences.weatherRefreshMin = v
        }
    var weatherDecimals: Boolean
        get() = weatherPreferences.weatherDecimals
        set(v) {
            weatherPreferences.weatherDecimals = v
        }

    /** Replace model "now" with the nearest airport observation when one is close and fresh. */
    var weatherUseStation: Boolean
        get() = weatherPreferences.weatherUseStation
        set(v) {
            weatherPreferences.weatherUseStation = v
        }

    /** Opt-in: the Blitzortung community websocket. Its operators restrict app use, so it is never silent. */
    var lightningFeed: Boolean
        get() = weatherPreferences.lightningFeed
        set(v) {
            weatherPreferences.lightningFeed = v
        }
    var weatherAmbient: Boolean
        get() = weatherPreferences.weatherAmbient
        set(v) {
            weatherPreferences.weatherAmbient = v
        }
    var radarZoom: Int
        get() = weatherPreferences.radarZoom
        set(v) {
            weatherPreferences.radarZoom = v
        }
    var radarFrameMs: Int
        get() = weatherPreferences.radarFrameMs
        set(v) {
            weatherPreferences.radarFrameMs = v
        }
    var radarOpacity: Int
        get() = weatherPreferences.radarOpacity
        set(v) {
            weatherPreferences.radarOpacity = v
        }
    var radarLabels: Boolean
        get() = weatherPreferences.radarLabels
        set(v) {
            weatherPreferences.radarLabels = v
        }
    var radarLightning: Boolean
        get() = weatherPreferences.radarLightning
        set(v) {
            weatherPreferences.radarLightning = v
        }
    var radarAutoPlay: Boolean
        get() = weatherPreferences.radarAutoPlay
        set(v) {
            weatherPreferences.radarAutoPlay = v
        }

    /** Saved places, one `name|region|country|lat|lon` per line. */
    var weatherPlaces: List<com.carteltv.tv.data.weather.Place>
        get() = weatherPreferences.weatherPlaces
        set(v) {
            weatherPreferences.weatherPlaces = v
        }
    var weatherAqi: Boolean
        get() = weatherPreferences.weatherAqi
        set(v) {
            weatherPreferences.weatherAqi = v
        }

    var currencyBase: String
        get() = financePreferences.currencyBase
        set(v) {
            financePreferences.currencyBase = v
        }

    var currencyWatch: List<String>
        get() = financePreferences.currencyWatch
        set(v) {
            financePreferences.currencyWatch = v
        }

    var nextDnsProfileTyped: String
        get() = dnsPreferences.nextDnsProfileTyped
        set(v) {
            dnsPreferences.nextDnsProfileTyped = v
        }

    var nextDnsProfileBuiltIn: String
        get() = dnsPreferences.nextDnsProfileBuiltIn
        set(v) {
            dnsPreferences.nextDnsProfileBuiltIn = v
        }

    var dnsProvider: String
        get() = dnsPreferences.dnsProvider
        set(v) {
            dnsPreferences.dnsProvider = v
        }

    var dnsPauseApps: Set<String>
        get() = dnsPreferences.dnsPauseApps
        set(v) {
            dnsPreferences.dnsPauseApps = v
        }

    var traktClientId: String
        get() = traktPreferences.traktClientId
        set(v) {
            traktPreferences.traktClientId = v
        }

    var traktClientSecret: String
        get() = traktPreferences.traktClientSecret
        set(v) {
            traktPreferences.traktClientSecret = v
        }

    var traktToken: String
        get() = traktPreferences.traktToken
        set(v) {
            traktPreferences.traktToken = v
        }

    var traktRefresh: String
        get() = traktPreferences.traktRefresh
        set(v) {
            traktPreferences.traktRefresh = v
        }

    /** When the stored Trakt access token stops working; 0 means unknown, which is treated as due for a refresh. */
    var traktExpiresAtMs: Long
        get() = traktPreferences.traktExpiresAtMs
        set(v) {
            traktPreferences.traktExpiresAtMs = v
        }

    /** rolling restore ledger */
    var authorityLedger: String
        get() = authorityPreferences.authorityLedger
        set(v) {
            authorityPreferences.authorityLedger = v
        }

    /** one-time revival-listener attempt flag */
    var revivalGranted: Boolean
        get() = authorityPreferences.revivalGranted
        set(v) {
            authorityPreferences.revivalGranted = v
        }

    /** device assessment already ran once */
    var assessedTuned: Boolean
        get() = authorityPreferences.assessedTuned
        set(v) {
            authorityPreferences.assessedTuned = v
        }

    /** cached verdict for the settings card */
    var assessmentNotes: String
        get() = authorityPreferences.assessmentNotes
        set(v) {
            authorityPreferences.assessmentNotes = v
        }

    var youTubeSponsorBlock: Boolean
        get() = youtubeModsPreferences.youTubeSponsorBlock
        set(v) {
            youtubeModsPreferences.youTubeSponsorBlock = v
        }

    var youTubeCategoryPreset: Int
        get() = youtubeModsPreferences.youTubeCategoryPreset
        set(v) {
            youtubeModsPreferences.youTubeCategoryPreset = v
        }

    var youTubeMinSeconds: Int
        get() = youtubeModsPreferences.youTubeMinSeconds
        set(v) {
            youtubeModsPreferences.youTubeMinSeconds = v
        }

    var youTubeHideWatched: Boolean
        get() = youtubeModsPreferences.youTubeHideWatched
        set(v) {
            youtubeModsPreferences.youTubeHideWatched = v
        }

    var youTubeBlockWords: String
        get() = youtubeModsPreferences.youTubeBlockWords
        set(v) {
            youtubeModsPreferences.youTubeBlockWords = v
        }

    var youTubeBlockChannels: String
        get() = youtubeModsPreferences.youTubeBlockChannels
        set(v) {
            youtubeModsPreferences.youTubeBlockChannels = v
        }

    var youTubeDislikes: Boolean
        get() = youtubeModsPreferences.youTubeDislikes
        set(v) {
            youtubeModsPreferences.youTubeDislikes = v
        }

    var youTubeCodec: Int
        get() = youtubeModsPreferences.youTubeCodec
        set(v) {
            youtubeModsPreferences.youTubeCodec = v
        }

    var youTubeSpeed: Float
        get() = youtubeModsPreferences.youTubeSpeed
        set(v) {
            youtubeModsPreferences.youTubeSpeed = v
        }

    var youTubeQuality: Int
        get() = youtubeModsPreferences.youTubeQuality
        set(v) {
            youtubeModsPreferences.youTubeQuality = v
        }

    var youTubeRefreshToken: String
        get() = youtubeModsPreferences.youTubeRefreshToken
        set(v) {
            youtubeModsPreferences.youTubeRefreshToken = v
        }

    var youTubeSubSeenMs: Long
        get() = youtubeModsPreferences.youTubeSubSeenMs
        set(v) {
            youtubeModsPreferences.youTubeSubSeenMs = v
        }

    var youTubeHistoryRaw: String
        get() = youtubeModsPreferences.youTubeHistoryRaw
        set(v) {
            youtubeModsPreferences.youTubeHistoryRaw = v
        }

    var youTubeSavedRaw: String
        get() = youtubeModsPreferences.youTubeSavedRaw
        set(v) {
            youtubeModsPreferences.youTubeSavedRaw = v
        }

    var youTubeSearchesRaw: String
        get() = youtubeModsPreferences.youTubeSearchesRaw
        set(v) {
            youtubeModsPreferences.youTubeSearchesRaw = v
        }

    var youTubeSubsRaw: String
        get() = youtubeModsPreferences.youTubeSubsRaw
        set(v) {
            youtubeModsPreferences.youTubeSubsRaw = v
        }

    /** Canonical row ids in their default presentation order. */
    val homeRowDefaults get() = homePreferences.homeRowDefaults

    fun homeRows(): List<String> = homePreferences.homeRows()

    fun setHomeRows(order: List<String>) {
        homePreferences.setHomeRows(order)
    }

    fun hideHomeRow(id: String) = homePreferences.hideHomeRow(id)

    fun showHomeRowAt(
        id: String,
        index: Int = Int.MAX_VALUE,
    ) {
        homePreferences.showHomeRowAt(id, index)
    }

    fun hiddenApps(): MutableSet<String> = homePreferences.hiddenApps()

    fun setHiddenApps(pkgs: Set<String>) {
        homePreferences.setHiddenApps(pkgs)
    }

    fun bumpLaunchCount(pkg: String) = homePreferences.bumpLaunchCount(pkg)

    fun launchCounts(): Map<String, Int> = homePreferences.launchCounts()

    fun bumpSectionVisit(section: String) = homePreferences.bumpSectionVisit(section)

    fun sectionVisits(): Map<String, Int> = homePreferences.sectionVisits()

    fun onboardingDone(): Boolean = homePreferences.onboardingDone()

    fun setOnboardingDone() {
        homePreferences.setOnboardingDone()
    }

    var debridToken: String
        get() = debridPreferences.debridToken
        set(v) {
            debridPreferences.debridToken = v
        }

    /** Real-Debrid device-flow grant (secret): client credentials and refresh token. Null for a hand-pasted key. */
    var debridOauth: com.carteltv.tv.data.debrid.DebridOauth?
        get() = debridPreferences.debridOauth
        set(v) {
            debridPreferences.debridOauth = v
        }

    var torboxToken: String
        get() = debridPreferences.torboxToken
        set(v) {
            debridPreferences.torboxToken = v
        }

    /** Which cloudLocker the Debrid UI shows: "rd" | "tb" */
    var debridProvider: String
        get() = debridPreferences.debridProvider
        set(v) {
            debridPreferences.debridProvider = v
        }

    /** TMDB v3 API key (ForYou rail); free key at themoviedb.org */
    var tmdbKey: String
        get() = debridPreferences.tmdbKey
        set(v) {
            debridPreferences.tmdbKey = v
        }

    /** YouTube Data v3 API key (optional for online search); free key at console.cloud.google.com */
    var youtubeKey: String
        get() = debridPreferences.youtubeKey
        set(v) {
            debridPreferences.youtubeKey = v
        }

    var simklClientId: String
        get() = simklPreferences.simklClientId
        set(v) {
            simklPreferences.simklClientId = v
        }

    var simklToken: String
        get() = simklPreferences.simklToken
        set(v) {
            simklPreferences.simklToken = v
        }

    var simklRefresh: String
        get() = simklPreferences.simklRefresh
        set(v) {
            simklPreferences.simklRefresh = v
        }

    var simklUser: String
        get() = simklPreferences.simklUser
        set(v) {
            simklPreferences.simklUser = v
        }

    var simklExpiresAt: Long
        get() = simklPreferences.simklExpiresAt
        set(v) {
            simklPreferences.simklExpiresAt = v
        }

    var simklBlockedUntil: Long
        get() = simklPreferences.simklBlockedUntil
        set(v) {
            simklPreferences.simklBlockedUntil = v
        }

    var stremioAuthKey: String
        get() = stremioPreferences.stremioAuthKey
        set(v) {
            stremioPreferences.stremioAuthKey = v
        }

    var stremioEmail: String
        get() = stremioPreferences.stremioEmail
        set(v) {
            stremioPreferences.stremioEmail = v
        }

    fun addons(): MutableList<String> = stremioPreferences.addons()

    fun setAddons(list: List<String>) {
        stremioPreferences.setAddons(list)
    }

    fun addAddon(url: String): Boolean = stremioPreferences.addAddon(url)

    fun removeAddon(url: String) = stremioPreferences.removeAddon(url)

    var peerSyncRole: String
        get() = peerSyncPreferences.peerSyncRole
        set(v) {
            peerSyncPreferences.peerSyncRole = v
        }

    var peerSyncHost: String
        get() = peerSyncPreferences.peerSyncHost
        set(v) {
            peerSyncPreferences.peerSyncHost = v
        }

    var peerSyncPort: Int
        get() = peerSyncPreferences.peerSyncPort
        set(v) {
            peerSyncPreferences.peerSyncPort = v
        }

    var peerSyncKey: String
        get() = peerSyncPreferences.peerSyncKey
        set(v) {
            peerSyncPreferences.peerSyncKey = v
        }

    var peerSyncGroups: Set<String>
        get() = peerSyncPreferences.peerSyncGroups
        set(v) {
            peerSyncPreferences.peerSyncGroups = v
        }

    fun categories(): List<LauncherPreferences.AppCategory> = launcherPreferences.categories()

    fun setCategories(list: List<LauncherPreferences.AppCategory>) {
        launcherPreferences.setCategories(list)
    }

    fun addCategory(cat: LauncherPreferences.AppCategory) {
        launcherPreferences.addCategory(cat)
    }

    fun removeCategory(id: String) {
        launcherPreferences.removeCategory(id)
    }

    fun updateCategory(updated: LauncherPreferences.AppCategory) {
        launcherPreferences.updateCategory(updated)
    }

    fun addAppToCategory(
        catId: String,
        pkg: String,
    ) {
        launcherPreferences.addAppToCategory(catId, pkg)
    }

    fun removeAppFromCategory(
        catId: String,
        pkg: String,
    ) {
        launcherPreferences.removeAppFromCategory(catId, pkg)
    }

    /** Whether the launcher shows categories (true) or the old flat rows (false). */
    var categoryMode: Boolean
        get() = launcherPreferences.categoryMode
        set(v) {
            launcherPreferences.categoryMode = v
        }

    /** Generate a short unique ID for new categories. */
    fun nextCategoryId(): String = launcherPreferences.nextCategoryId()

    fun folders(): List<LauncherPreferences.AppFolder> = launcherPreferences.folders()

    fun setFolders(list: List<LauncherPreferences.AppFolder>) {
        launcherPreferences.setFolders(list)
    }

    fun addFolder(folder: LauncherPreferences.AppFolder) {
        launcherPreferences.addFolder(folder)
    }

    fun removeFolder(id: String) {
        launcherPreferences.removeFolder(id)
    }

    fun updateFolder(updated: LauncherPreferences.AppFolder) {
        launcherPreferences.updateFolder(updated)
    }

    fun nextFolderId(): String = launcherPreferences.nextFolderId()

    /** Which folder (if any) an app belongs to. */
    fun folderForApp(pkg: String): LauncherPreferences.AppFolder? = launcherPreferences.folderForApp(pkg)

    /** Apps not in any folder. */
    fun unfiledApps(allApps: List<LauncherApp>): List<LauncherApp> = launcherPreferences.unfiledApps(allApps)

    var rowArrangement: String
        get() = launcherPreferences.rowArrangement
        set(v) {
            launcherPreferences.rowArrangement = v
        }

    var rowAlignment: String
        get() = launcherPreferences.rowAlignment
        set(v) {
            launcherPreferences.rowAlignment = v
        }

    var showAllRowNames: Boolean
        get() = launcherPreferences.showAllRowNames
        set(v) {
            launcherPreferences.showAllRowNames = v
        }

    var showCardTitles: Boolean
        get() = launcherPreferences.showCardTitles
        set(v) {
            launcherPreferences.showCardTitles = v
        }

    var centeredIcons: Boolean
        get() = launcherPreferences.centeredIcons
        set(v) {
            launcherPreferences.centeredIcons = v
        }

    var singleRowMode: Boolean
        get() = launcherPreferences.singleRowMode
        set(v) {
            launcherPreferences.singleRowMode = v
        }

    var carouselMode: Boolean
        get() = launcherPreferences.carouselMode
        set(v) {
            launcherPreferences.carouselMode = v
        }

    var switcherMaxEntries: Int
        get() = launcherPreferences.switcherMaxEntries
        set(v) {
            launcherPreferences.switcherMaxEntries = v
        }

    var switcherNoKill: Boolean
        get() = launcherPreferences.switcherNoKill
        set(v) {
            launcherPreferences.switcherNoKill = v
        }

    var appSizeMultiplier: Float
        get() = launcherPreferences.appSizeMultiplier
        set(v) {
            launcherPreferences.appSizeMultiplier = v
        }

    var mediaCardFocusScale: Float
        get() = launcherPreferences.mediaCardFocusScale
        set(v) {
            launcherPreferences.mediaCardFocusScale = v
        }

    var themePreset: String
        get() = launcherPreferences.themePreset
        set(v) {
            launcherPreferences.themePreset = v
        }

    var reduceTransparency: Boolean
        get() = launcherPreferences.reduceTransparency
        set(v) {
            launcherPreferences.reduceTransparency = v
        }

    var iconPack: String
        get() = launcherPreferences.iconPack
        set(v) {
            launcherPreferences.iconPack = v
        }

    var showAppChannels: Boolean
        get() = launcherPreferences.showAppChannels
        set(v) {
            launcherPreferences.showAppChannels = v
        }

    var watchCountry: String
        get() = launcherPreferences.watchCountry
        set(v) {
            launcherPreferences.watchCountry = v
        }

    var playInStreamingApp: Boolean
        get() = launcherPreferences.playInStreamingApp
        set(v) {
            launcherPreferences.playInStreamingApp = v
        }

    var platformLinksJustWatch: Boolean
        get() = launcherPreferences.platformLinksJustWatch
        set(v) {
            launcherPreferences.platformLinksJustWatch = v
        }

    var sportsCardSize: String
        get() = sportsPreferences.sportsCardSize
        set(v) {
            sportsPreferences.sportsCardSize = v
        }

    var sportsLayout: String
        get() = sportsPreferences.sportsLayout
        set(v) {
            sportsPreferences.sportsLayout = v
        }

    var sportsShowOdds: Boolean
        get() = sportsPreferences.sportsShowOdds
        set(v) {
            sportsPreferences.sportsShowOdds = v
        }

    var sportsHideRedZone: Boolean
        get() = sportsPreferences.sportsHideRedZone
        set(v) {
            sportsPreferences.sportsHideRedZone = v
        }

    var sportsRailExpanded: Boolean
        get() = sportsPreferences.sportsRailExpanded
        set(v) {
            sportsPreferences.sportsRailExpanded = v
        }

    var sportsAutoPlay: Boolean
        get() = sportsPreferences.sportsAutoPlay
        set(v) {
            sportsPreferences.sportsAutoPlay = v
        }

    fun sportsPreferredCountries(): List<String> = sportsPreferences.sportsPreferredCountries()

    fun setSportsPreferredCountries(countries: List<String>) {
        sportsPreferences.setSportsPreferredCountries(countries)
    }

    var sportsHideEmptyLeagues: Boolean
        get() = sportsPreferences.sportsHideEmptyLeagues
        set(v) {
            sportsPreferences.sportsHideEmptyLeagues = v
        }

    var sportsFavoriteLeague: String
        get() = sportsPreferences.sportsFavoriteLeague
        set(v) {
            sportsPreferences.sportsFavoriteLeague = v
        }

    var sportsZoneMode: String
        get() = sportsPreferences.sportsZoneMode
        set(v) {
            sportsPreferences.sportsZoneMode = v
        }

    var sportsZoneManual: String
        get() = sportsPreferences.sportsZoneManual
        set(v) {
            sportsPreferences.sportsZoneManual = v
        }

    var ipZoneId: String
        get() = sportsPreferences.ipZoneId
        set(v) {
            sportsPreferences.ipZoneId = v
        }

    var ipZoneAtMs: Long
        get() = sportsPreferences.ipZoneAtMs
        set(v) {
            sportsPreferences.ipZoneAtMs = v
        }

    var sportsPreferQuality: Boolean
        get() = sportsPreferences.sportsPreferQuality
        set(v) {
            sportsPreferences.sportsPreferQuality = v
        }

    var goalAlertDelay: Int
        get() = sportsPreferences.goalAlertDelay
        set(v) {
            sportsPreferences.goalAlertDelay = v
        }

    var sportsChannelPicks: String
        get() = sportsPreferences.sportsChannelPicks
        set(v) {
            sportsPreferences.sportsChannelPicks = v
        }

    var epgSyncOkMs: Long
        get() = epgPreferences.epgSyncOkMs
        set(v) {
            epgPreferences.epgSyncOkMs = v
        }

    var epgSyncFailMs: Long
        get() = epgPreferences.epgSyncFailMs
        set(v) {
            epgPreferences.epgSyncFailMs = v
        }

    fun epgValidator(url: String): String = epgPreferences.epgValidator(url)

    fun setEpgValidator(
        url: String,
        v: String,
    ) {
        epgPreferences.setEpgValidator(url, v)
    }

    var epgSyncChannels: Int
        get() = epgPreferences.epgSyncChannels
        set(v) {
            epgPreferences.epgSyncChannels = v
        }

    var extraGuideUrls: List<String>
        get() = epgPreferences.extraGuideUrls
        set(v) {
            epgPreferences.extraGuideUrls = v
        }

    var customXmltvUrl: String
        get() = epgPreferences.customXmltvUrl
        set(v) {
            epgPreferences.customXmltvUrl = v
        }

    var catchupKeepPlaying: Boolean
        get() = epgPreferences.catchupKeepPlaying
        set(v) {
            epgPreferences.catchupKeepPlaying = v
        }

    fun catchupTsPlaylists(): MutableSet<String> = epgPreferences.catchupTsPlaylists()

    fun setCatchupTsPlaylists(ids: Set<String>) {
        epgPreferences.setCatchupTsPlaylists(ids)
    }

    var videoMediaOverlay: Boolean
        get() = playerPreferences.videoMediaOverlay
        set(v) {
            playerPreferences.videoMediaOverlay = v
        }

    var nativeVideoSurface: Boolean
        get() = playerPreferences.nativeVideoSurface
        set(v) {
            playerPreferences.nativeVideoSurface = v
        }

    var compatVideo: Boolean
        get() = playerPreferences.compatVideo
        set(v) {
            playerPreferences.compatVideo = v
        }

    var autoTrailers: Boolean
        get() = playerPreferences.autoTrailers
        set(v) {
            playerPreferences.autoTrailers = v
        }

    var transparentPopups: Boolean
        get() = playerPreferences.transparentPopups
        set(v) {
            playerPreferences.transparentPopups = v
        }

    var minimalist: Boolean
        get() = playerPreferences.minimalist
        set(v) {
            playerPreferences.minimalist = v
        }

    var minimalistBefore: String
        get() = playerPreferences.minimalistBefore
        set(v) {
            playerPreferences.minimalistBefore = v
        }

    var skippedUpdateVersion: String
        get() = playerPreferences.skippedUpdateVersion
        set(v) {
            playerPreferences.skippedUpdateVersion = v
        }

    var providerExpiryNoticeDay: String
        get() = playerPreferences.providerExpiryNoticeDay
        set(v) {
            playerPreferences.providerExpiryNoticeDay = v
        }

    fun real4kChannels(): MutableSet<String> = playerPreferences.real4kChannels()

    fun setReal4kChannels(ids: Set<String>) {
        playerPreferences.setReal4kChannels(ids)
    }

    fun isReal4k(id: String): Boolean = playerPreferences.isReal4k(id)

    fun catalogSort(category: String): String = playerPreferences.catalogSort(category)

    fun setCatalogSort(
        category: String,
        sort: String,
    ) {
        playerPreferences.setCatalogSort(category, sort)
    }

    var autoLargest: Boolean
        get() = playerPreferences.autoLargest
        set(v) {
            playerPreferences.autoLargest = v
        }

    var archiveSourcePreferences: String
        get() = playerPreferences.archiveSourcePreferences
        set(v) {
            playerPreferences.archiveSourcePreferences = v
        }

    var nextEpisodesJson: String
        get() = playerPreferences.nextEpisodesJson
        set(v) {
            playerPreferences.nextEpisodesJson = v
        }

    var dispatcharrSources: String
        get() = playerPreferences.dispatcharrSources
        set(v) {
            playerPreferences.dispatcharrSources = v
        }

    var tuliprox: String
        get() = playerPreferences.tuliprox
        set(v) {
            playerPreferences.tuliprox = v
        }

    var posterPattern: String
        get() = playerPreferences.posterPattern
        set(v) {
            playerPreferences.posterPattern = v
        }

    var barAutoHide: Boolean
        get() = playerPreferences.barAutoHide
        set(v) {
            playerPreferences.barAutoHide = v
        }

    var playerClock: Boolean
        get() = playerPreferences.playerClock
        set(v) {
            playerPreferences.playerClock = v
        }

    fun recentLive(): List<Long> = playerPreferences.recentLive()

    fun noteRecentLive(streamId: Long) = playerPreferences.noteRecentLive(streamId)

    var guideCompact: Boolean
        get() = playerPreferences.guideCompact
        set(v) {
            playerPreferences.guideCompact = v
        }

    var guidePreview: Boolean
        get() = playerPreferences.guidePreview
        set(v) {
            playerPreferences.guidePreview = v
        }

    var activeLeagues: String
        get() = playerPreferences.activeLeagues
        set(v) {
            playerPreferences.activeLeagues = v
        }

    var spoilerShield: Boolean
        get() = playerPreferences.spoilerShield
        set(v) {
            playerPreferences.spoilerShield = v
        }

    var refreshSync: Boolean
        get() = playerPreferences.refreshSync
        set(v) {
            playerPreferences.refreshSync = v
        }

    var tunneling: Boolean
        get() = playerPreferences.tunneling
        set(v) {
            playerPreferences.tunneling = v
        }

    var audioOffload: Boolean
        get() = playerPreferences.audioOffload
        set(v) {
            playerPreferences.audioOffload = v
        }

    var videoEnhance: Boolean
        get() = playerPreferences.videoEnhance
        set(v) {
            playerPreferences.videoEnhance = v
        }

    var lowLatencyLive: Boolean
        get() = playerPreferences.lowLatencyLive
        set(v) {
            playerPreferences.lowLatencyLive = v
        }

    var dvNativeProfile7: Boolean
        get() = playerPreferences.dvNativeProfile7
        set(v) {
            playerPreferences.dvNativeProfile7 = v
        }

    fun multiViewLayouts(providerId: String): List<PlayerPreferences.MultiViewLayout> = playerPreferences.multiViewLayouts(providerId)

    fun saveMultiViewLayout(
        providerId: String,
        layout: PlayerPreferences.MultiViewLayout,
    ) {
        playerPreferences.saveMultiViewLayout(providerId, layout)
    }

    fun deleteMultiViewLayout(
        providerId: String,
        name: String,
    ) {
        playerPreferences.deleteMultiViewLayout(providerId, name)
    }

    var aiVoiceButton: Boolean
        get() = aiPreferences.aiVoiceButton
        set(v) {
            aiPreferences.aiVoiceButton = v
        }

    var aiLearnedKey: Int
        get() = aiPreferences.aiLearnedKey
        set(v) {
            aiPreferences.aiLearnedKey = v
        }

    var aiSpeakReplies: Boolean
        get() = aiPreferences.aiSpeakReplies
        set(v) {
            aiPreferences.aiSpeakReplies = v
        }

    var aiLinkSecret: String
        get() = aiPreferences.aiLinkSecret
        set(v) {
            aiPreferences.aiLinkSecret = v
        }

    var aiLinkEnabled: Boolean
        get() = aiPreferences.aiLinkEnabled
        set(v) {
            aiPreferences.aiLinkEnabled = v
        }

    var aiConfirmRemotePlay: Boolean
        get() = aiPreferences.aiConfirmRemotePlay
        set(v) {
            aiPreferences.aiConfirmRemotePlay = v
        }

    /** Epoch-ms of the last completed search query (online results cache / invalidation). */
    var lastQuery: Long
        get() = generalPreferences.lastQuery
        set(v) {
            generalPreferences.lastQuery = v
        }

    var clock24: Boolean
        get() = generalPreferences.clock24
        set(v) {
            generalPreferences.clock24 = v
        }

    /** clock size in sp */
    var clockSizeSp: Int
        get() = generalPreferences.clockSizeSp
        set(v) {
            generalPreferences.clockSizeSp = v
        }

    // epg-latest is published with --latest=false, so "latest" always resolves to the newest app release.
    const val DEFAULT_UPDATE_FEED = "https://github.com/EDGEL00P/CartelTv/releases/latest/download/update.json"

    var updaterFeedUrl: String
        get() = generalPreferences.updaterFeedUrl
        set(v) {
            generalPreferences.updaterFeedUrl = v
        }

    var focusSounds: Boolean
        get() = generalPreferences.focusSounds
        set(v) {
            generalPreferences.focusSounds = v
        }

    /** last-chosen sleep minutes for one-press re-arm */
    var sleepMinutes: Int
        get() = generalPreferences.sleepMinutes
        set(v) {
            generalPreferences.sleepMinutes = v
        }

    /** notification popup overlay on Home (notification_popup) */
    var noticePopups: Boolean
        get() = generalPreferences.noticePopups
        set(v) {
            generalPreferences.noticePopups = v
        }

    fun remapRaw(): String = generalPreferences.remapRaw()

    fun setRemapRaw(v: String) = generalPreferences.setRemapRaw(v)

    fun remapBindings(): List<com.carteltv.tv.data.remap.KeyRemap.Bind> = generalPreferences.remapBindings()

    fun addRemapBinding(b: com.carteltv.tv.data.remap.KeyRemap.Bind) {
        generalPreferences.addRemapBinding(b)
    }

    fun clearRemapBinding(b: com.carteltv.tv.data.remap.KeyRemap.Bind) = generalPreferences.clearRemapBinding(b)

    var profilesJson: String
        get() = generalPreferences.profilesJson
        set(v) {
            generalPreferences.profilesJson = v
        }

    var activeProfileId: String
        get() = generalPreferences.activeProfileId
        set(v) {
            generalPreferences.activeProfileId = v
        }

    var usageJson: String
        get() = generalPreferences.usageJson
        set(v) {
            generalPreferences.usageJson = v
        }

    var askProfileOnBoot: Boolean
        get() = generalPreferences.askProfileOnBoot
        set(v) {
            generalPreferences.askProfileOnBoot = v
        }

    /** bits: time,date,weather,profile,apps,media,network,bt,gear,alerts,sleep,sports,voice */
    var barClusters: String
        get() = generalPreferences.barClusters
        set(v) {
            generalPreferences.barClusters = v
        }

    /** settings_top_bar_position: "top" | "bottom" */
    var barPosition: String
        get() = generalPreferences.barPosition
        set(v) {
            generalPreferences.barPosition = v
        }

    /** settings_top_bar_hide_until_focused — focus-visibility, no timer */
    var barHideUntilFocused: Boolean
        get() = generalPreferences.barHideUntilFocused
        set(v) {
            generalPreferences.barHideUntilFocused = v
        }

    /** user-ordered dock tabs ("HOME,LIVE,…" subset); empty = default order */
    var barTabs: String
        get() = generalPreferences.barTabs
        set(v) {
            generalPreferences.barTabs = v
        }

    /** Redirect stock TV launcher windows back to CartelTV via KeyRemapService. */
    var overrideHomeButton: Boolean
        get() = generalPreferences.overrideHomeButton
        set(v) {
            generalPreferences.overrideHomeButton = v
        }

    /** Direct KEYCODE_HOME interception via FLAG_REQUEST_FILTER_KEY_EVENTS (zero flash). */
    var enhancedHomeDetection: Boolean
        get() = generalPreferences.enhancedHomeDetection
        set(v) {
            generalPreferences.enhancedHomeDetection = v
        }

    /** Keep Back from exiting CartelTV at the root home surface. */
    var overrideBackButton: Boolean
        get() = generalPreferences.overrideBackButton
        set(v) {
            generalPreferences.overrideBackButton = v
        }

    /** Relaunch CartelTV automatically on BOOT_COMPLETED / QUICKBOOT_POWERON. */
    var startOnBoot: Boolean
        get() = generalPreferences.startOnBoot
        set(v) {
            generalPreferences.startOnBoot = v
        }

    /** Show Android TV system Watch Next rail on Home when programs exist. */
    var showWatchNext: Boolean
        get() = generalPreferences.showWatchNext
        set(v) {
            generalPreferences.showWatchNext = v
        }

    /** Tracks whether initial Accessibility-enabled defaults were applied once. */
    var accessibilityDefaultsConfigured: Boolean
        get() = generalPreferences.accessibilityDefaultsConfigured
        set(v) {
            generalPreferences.accessibilityDefaultsConfigured = v
        }

    var notifPromptDone: Boolean
        get() = generalPreferences.notifPromptDone
        set(v) {
            generalPreferences.notifPromptDone = v
        }

    /** URL to fetch badge definitions from. */
    var streamBadgesUrl: String
        get() = generalPreferences.streamBadgesUrl
        set(v) {
            generalPreferences.streamBadgesUrl = v
        }

    /** Whether stream badges are displayed. */
    var streamBadgesOn: Boolean
        get() = generalPreferences.streamBadgesOn
        set(v) {
            generalPreferences.streamBadgesOn = v
        }

    /** Epoch-ms of last badge fetch. */
    var streamBadgesFetched: Long
        get() = generalPreferences.streamBadgesFetched
        set(v) {
            generalPreferences.streamBadgesFetched = v
        }

    /** Whether watched history is enabled on this TV. */
    var historyOn: Boolean
        get() = generalPreferences.historyOn
        set(v) {
            generalPreferences.historyOn = v
        }

    /** scheduled DVR jobs, SecretVault-encrypted JSON list */
    var scheduledJobsRaw: String
        get() = generalPreferences.scheduledJobsRaw
        set(v) {
            generalPreferences.scheduledJobsRaw = v
        }

    /** DVR padding: minutes pulled in front of / added after every scheduled capture. */
    var recPadBeforeMin: Int
        get() = generalPreferences.recPadBeforeMin
        set(v) {
            generalPreferences.recPadBeforeMin = v
        }

    var recPadAfterMin: Int
        get() = generalPreferences.recPadAfterMin
        set(v) {
            generalPreferences.recPadAfterMin = v
        }

    /** Search torrent indexes from inside the app, alongside any Stremio addons. */
    var builtInSources: Boolean
        get() = generalPreferences.builtInSources
        set(v) {
            generalPreferences.builtInSources = v
        }

    /** Hide live channels, movies and series that are in a language not picked in [contentLanguages]. */
    var englishOnly: Boolean
        get() = languagePreferences.englishOnly
        set(v) {
            languagePreferences.englishOnly = v
        }

    /** Language codes to keep when [englishOnly] filtering is on; English until the viewer picks others. */
    var contentLanguages: Set<com.carteltv.tv.data.xtream.LanguageFilter.Lang>
        get() = languagePreferences.contentLanguages
        set(v) {
            languagePreferences.contentLanguages = v
        }

    /** Preferred audio language code (e.g. "eng", "fre"). */
    var preferredAudioLanguage: String
        get() = languagePreferences.preferredAudioLanguage
        set(v) {
            languagePreferences.preferredAudioLanguage = v
        }

    /**
     * "auto" shows subtitles only when the audio is not in the viewer's language;
     * "off" never shows them; a language code always shows that language.
     */
    var preferredSubtitleLanguage: String
        get() = languagePreferences.preferredSubtitleLanguage
        set(v) {
            languagePreferences.preferredSubtitleLanguage = v
        }

    /** Recently used online subtitle language codes, most recent first. */
    fun subtitleLangs(): List<String> = languagePreferences.subtitleLangs()

    fun addSubtitleLang(lang: String) = languagePreferences.addSubtitleLang(lang)
}
