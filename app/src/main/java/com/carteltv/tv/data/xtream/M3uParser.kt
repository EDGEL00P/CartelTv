package com.carteltv.tv.data.xtream

/** Reads extended M3U playlists. Pure, so it can be tested without a network. */
object M3uParser {
    enum class Kind { LIVE, MOVIE, SERIES }

    /** Kodi-style DRM metadata from #KODIPROP; scheme is lowercase (clearkey/widevine/playready). */
    data class DrmInfo(
        val scheme: String,
        val licenseUrl: String,
        val headers: Map<String, String> = emptyMap(),
        val keyId: String = "",
    )

    data class Entry(
        val name: String,
        val url: String,
        val tvgId: String = "",
        val logo: String = "",
        val group: String = "",
        val catchupSource: String = "",
        val catchupDays: Int = 0,
        /** Request headers declared by the playlist (headers survive into the player's data source). */
        val headers: Map<String, String> = emptyMap(),
        val drm: DrmInfo? = null,
    )

    data class Playlist(val entries: List<Entry>, val guideUrls: List<String>)

    private val attr = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")
    private val vodExt = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "m4v")
    private val episodeTag = Regex("""^(.*?)[\s._-]*[Ss](\d{1,2})\s*[Ee](\d{1,4})\b.*$""")
    /** The worded spelling: "Show Name Season 2 Episode 5" (kept separate so lone "Episode N" movie titles stay movies). */
    private val wordedEpisode = Regex("""^(.+?)[\s._-]+[Ss]eason\s*(\d{1,2})[\s._-]+[Ee]p(?:isode)?\s*(\d{1,4})\b.*$""")

    /** (show, season, episode) when [name] carries an episode tag in either spelling; null when it's a movie. */
    fun episodeInfo(name: String): Triple<String, Int, Int>? {
        episodeTag.matchEntire(name)?.let {
            val show = it.groupValues[1].trim().trimEnd('-', ':', '.').trim()
            if (show.isNotBlank()) return Triple(show, it.groupValues[2].toInt(), it.groupValues[3].toInt())
        }
        wordedEpisode.matchEntire(name)?.let {
            val show = it.groupValues[1].trim().trimEnd('-', ':', '.').trim()
            if (show.isNotBlank()) return Triple(show, it.groupValues[2].toInt(), it.groupValues[3].toInt())
        }
        return null
    }

    fun parse(lines: Sequence<String>): Playlist {
        val entries = ArrayList<Entry>()
        val guides = ArrayList<String>()
        var pending: Entry? = null
        var extGroup = ""
        var pendingHeaders = LinkedHashMap<String, String>()
        var pendingDrm = DrmAcc()
        for ((i, raw) in lines.withIndex()) {
            val line = (if (i == 0) raw.removePrefix("\uFEFF") else raw).trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTM3U", true) -> attrs(line).let { a ->
                    listOf("url-tvg", "x-tvg-url", "tvg-url").forEach { k ->
                        a[k]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.let { guides += it }
                    }
                }
                line.startsWith("#EXTINF", true) -> pending = extinf(line, extGroup)
                line.startsWith("#EXTGRP:", true) -> extGroup = line.substringAfter(':').trim()
                line.startsWith("#EXTVLCOPT:", true) -> vlcOpt(line.substringAfter(':').trim(), pendingHeaders)
                line.startsWith("#EXTHTTP:", true) -> extHttp(line.substringAfter(':').trim(), pendingHeaders)
                line.startsWith("#KODIPROP:", true) -> kodiProp(line.substringAfter(':').trim(), pendingHeaders, pendingDrm)
                line.startsWith("#") -> Unit
                else -> {
                    val p = pending
                    if (p != null && isPlayable(line)) {
                        entries += p.copy(url = line, headers = pendingHeaders.toMap(), drm = pendingDrm.toInfo())
                        pendingHeaders = LinkedHashMap()
                        pendingDrm = DrmAcc()
                    }
                    pending = null
                }
            }
        }
        return Playlist(entries, guides.distinctBy { it.lowercase() })
    }

    private fun isPlayable(u: String) = u.contains("://")

    /** #EXTVLCOPT:http-header=Key: Value and the shortcut http-user-agent/referrer/cookie options. */
    private fun vlcOpt(value: String, into: MutableMap<String, String>) {
        val eq = value.indexOf('=')
        if (eq < 0) return
        val key = value.substring(0, eq).trim().lowercase()
        val v = value.substring(eq + 1).trim()
        when (key) {
            "http-header" -> {
                val colon = v.indexOf(':')
                if (colon > 0) into[v.substring(0, colon).trim()] = v.substring(colon + 1).trim()
            }
            "http-user-agent" -> into["User-Agent"] = v
            "http-referrer" -> into["Referer"] = v
            "http-cookie" -> into["Cookie"] = v
        }
    }

    /** #EXTHTTP:{"header":{"User-Agent":"...","Referer":"..."},"useragent":"..."} - no JSON lib needed here. */
    private fun extHttp(value: String, into: MutableMap<String, String>) {
        val headerBody = Regex(""""header"\s*:\s*\{([^}]*)}""", RegexOption.IGNORE_CASE)
            .find(value)?.groupValues?.get(1) ?: ""
        Regex(""""([^"]+)"\s*:\s*"([^"]*)"""").findAll(headerBody).forEach {
            into[it.groupValues[1]] = it.groupValues[2]
        }
        Regex(""""useragent"\s*:\s*"([^"]*)"""", RegexOption.IGNORE_CASE).find(value)?.let {
            into.putIfAbsent("User-Agent", it.groupValues[1])
        }
        Regex(""""referer"\s*:\s*"([^"]*)"""", RegexOption.IGNORE_CASE).find(value)?.let {
            into.putIfAbsent("Referer", it.groupValues[1])
        }
    }

    private class DrmAcc {
        var type = ""
        var url = ""
        var keyId = ""
        val headers = LinkedHashMap<String, String>()

        fun toInfo(): DrmInfo? = if (url.isBlank()) null else
            DrmInfo(type.ifBlank { "widevine" }, url, headers.toMap(), keyId)
    }

    private fun kvHeaders(value: String, into: MutableMap<String, String>) {
        if (value.isBlank()) return
        value.split('&').forEach { pair ->
            val eq = pair.indexOf('=')
            if (eq <= 0) return@forEach
            val k = java.net.URLDecoder.decode(pair.substring(0, eq), Charsets.UTF_8.name())
            val v = java.net.URLDecoder.decode(pair.substring(eq + 1), Charsets.UTF_8.name())
            into[k] = v
        }
    }

    /** Kodi #KODIPROP: license_type, license_key (URL||headers|post|keyId) and stream_headers. */
    private fun kodiProp(value: String, headers: MutableMap<String, String>, drm: DrmAcc) {
        val eq = value.indexOf('=')
        if (eq < 0) return
        when (value.substring(0, eq).trim().lowercase()) {
            "inputstream.adaptive.stream_headers" -> kvHeaders(value.substring(eq + 1).trim(), headers)
            "inputstream.adaptive.license_type" -> drm.type = value.substring(eq + 1).trim().lowercase()
            "inputstream.adaptive.license_key" -> {
                // Kodi uses URL || header=val&... | POST data | key id; tolerate a doubled separator.
                val raw = value.substring(eq + 1).trim()
                val cut = raw.indexOf("||")
                drm.url = if (cut >= 0) raw.substring(0, cut) else raw
                val rest = if (cut >= 0) raw.substring(cut + 2) else ""
                val parts = rest.split('|', limit = 3)
                drm.keyId = parts.getOrNull(2).orEmpty().trimStart('|')
                kvHeaders(parts[0], headers)
                drm.headers.putAll(headers)
            }
        }
    }

    private fun attrs(line: String): Map<String, String> =
        attr.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

    private fun extinf(line: String, extGroup: String): Entry {
        val a = attrs(line)
        // The title follows the comma after the last attribute; attribute values may hold commas themselves.
        val afterAttrs = attr.findAll(line).lastOrNull()?.range?.last?.plus(1) ?: line.indexOf(':')
        val comma = line.indexOf(',', afterAttrs.coerceAtLeast(0))
        val title = if (comma >= 0) line.substring(comma + 1).trim() else ""
        val name = title.ifBlank { a["tvg-name"].orEmpty() }.ifBlank { "Channel" }
        return Entry(
            name = name,
            url = "",
            tvgId = a["tvg-id"].orEmpty().trim(),
            logo = a["tvg-logo"].orEmpty().trim(),
            group = a["group-title"].orEmpty().trim().ifBlank { extGroup },
            catchupSource = a["catchup-source"].orEmpty().trim(),
            catchupDays = (a["catchup-days"] ?: a["tvg-rec"])?.trim()?.toIntOrNull() ?: 0,
        )
    }

    fun kindOf(e: Entry): Kind {
        val path = e.url.lowercase().substringBefore('?')
        return when {
            "/movie/" in path -> Kind.MOVIE
            "/series/" in path -> Kind.SERIES
            path.substringAfterLast('.', "") in vodExt ->
                if (episodeInfo(e.name) != null) Kind.SERIES else Kind.MOVIE
            else -> Kind.LIVE
        }
    }

    data class Episode(val title: String, val season: Int, val number: Int, val entry: Entry)

    /** Splits "Show Name S02E05 - Title" (or "Show Name Season 2 Episode 5") into the show and its episode. */
    fun episodeOf(e: Entry): Pair<String, Episode>? =
        episodeInfo(e.name)?.let { (show, s, n) -> show to Episode(e.name, s, n, e) }

    /** A stable, non-negative id for a link, so favourites and the guide survive a reload. */
    fun idOf(key: String): Long {
        var h = -0x340d631b7bdddcdbL // FNV-1a 64-bit offset basis
        for (c in key) { h = h xor c.code.toLong(); h *= 0x100000001b3L }
        return h and Long.MAX_VALUE
    }

    /** Fills a catch-up URL template; supports the common `{utc}` and `${start}` placeholder spellings. */
    fun catchupUrl(template: String, liveUrl: String, startMs: Long, endMs: Long): String {
        val s = (startMs / 1000).toString()
        val e = (endMs / 1000).toString()
        val filled = template
            .replace("\${start}", s).replace("{utc}", s).replace("\${timestamp}", s)
            .replace("\${end}", e).replace("{utcend}", e)
        return if (filled.contains("://")) filled else liveUrl + filled
    }
}
