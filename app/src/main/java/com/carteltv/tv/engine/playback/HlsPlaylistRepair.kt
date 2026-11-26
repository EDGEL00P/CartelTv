package com.carteltv.tv.engine.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.ParsingLoadable
import androidx.media3.extractor.ExtractorsFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import com.carteltv.tv.engine.captions.LiveCaptions
import com.carteltv.tv.ui.player.TS_PAYLOAD_FLAGS

/** The cause carried by a primary playlist load whose body turned out to be an MPEG-TS stream. */
class HlsBodyIsTs : IOException("The playlist link sent an MPEG-TS stream")

/** True when [error] or any of its causes is [HlsBodyIsTs]: the link was typed HLS but serves plain TS. */
fun Throwable.servedTsForHls(): Boolean = generateSequence(this) { it.cause }.any { it is HlsBodyIsTs }

/** [item] re-typed so the player reads it as a progressive MPEG-TS stream instead of an HLS playlist. */
fun MediaItem.asProgressiveTs(): MediaItem = buildUpon().setMimeType(MimeTypes.VIDEO_MP2T).build()

/** Playlist repairs for sloppy IPTV providers, as plain text in and out. */
object HlsPlaylistRepair {
    private const val TS_PACKET = 188
    private const val TS_SYNC = 0x47.toByte()
    private const val DEFAULT_BANDWIDTH = 5_000_000

    /** An MPEG-TS stream: sync bytes at 0 and 188 (a short body starting with 'G' is text). */
    fun isTs(head: ByteArray): Boolean = head.size > TS_PACKET && head[0] == TS_SYNC && head[TS_PACKET] == TS_SYNC

    /**
     * [playlist] with the faults Media3's parser rejects put right: a UTF-8 BOM, a missing #EXTM3U,
     * a variant without BANDWIDTH, a rendition without NAME or GROUP-ID. Null when it is no HLS playlist.
     */
    fun fix(playlist: String): String? {
        var text = playlist.removePrefix("\uFEFF")
        if (!text.trimStart().startsWith("#EXTM3U")) {
            if (!text.contains("#EXTINF") && !text.contains("#EXT-X-")) return null
            text = "#EXTM3U\n" + text.trimStart()
        }
        if (!text.contains("#EXT-X-STREAM-INF") && !text.contains("#EXT-X-I-FRAME-STREAM-INF") && !text.contains("#EXT-X-MEDIA:")) {
            return text
        }
        return text.lineSequence().joinToString("\n", transform = ::fixLine)
    }

    private fun fixLine(line: String): String {
        val l = line.trimEnd()
        return when {
            (l.startsWith("#EXT-X-STREAM-INF:") || l.startsWith("#EXT-X-I-FRAME-STREAM-INF:")) && !HAS_BANDWIDTH.containsMatchIn(l) -> {
                val colon = l.indexOf(':')
                val attributes = l.substring(colon + 1)
                l.substring(0, colon + 1) + "BANDWIDTH=$DEFAULT_BANDWIDTH" + if (attributes.isNotEmpty()) ",$attributes" else ""
            }
            l.startsWith("#EXT-X-MEDIA:") -> {
                var out = l
                if (!HAS_NAME.containsMatchIn(out)) out += ",NAME=\"Main\""
                if (!HAS_GROUP_ID.containsMatchIn(out)) out += ",GROUP-ID=\"default\""
                out
            }
            else -> line
        }
    }

    // What Media3's parser accepts; an unusable value is replaced as well as a missing one.
    private val HAS_BANDWIDTH = Regex("""[:,]\s*BANDWIDTH=\d+\b""")
    private val HAS_NAME = Regex("""[:,]\s*NAME="[^"]""")
    private val HAS_GROUP_ID = Regex("""[:,]\s*GROUP-ID="[^"]""")
}

/**
 * Runs [HlsPlaylistRepair] before Media3 parses a playlist. A TS body is refused after 512 bytes, before an endless
 * stream is read, and only the primary playlist load marks it [HlsBodyIsTs]: a media playlist sending TS is a
 * broken variant, not a TS link.
 */
@UnstableApi
class LenientHlsPlaylistParserFactory : HlsPlaylistParserFactory {
    private val inner = DefaultHlsPlaylistParserFactory()

    override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> =
        wrap(inner.createPlaylistParser(), primary = true)

    override fun createPlaylistParser(
        multivariantPlaylist: HlsMultivariantPlaylist,
        previousMediaPlaylist: HlsMediaPlaylist?,
    ): ParsingLoadable.Parser<HlsPlaylist> =
        wrap(inner.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist), primary = false)

    private fun wrap(parser: ParsingLoadable.Parser<HlsPlaylist>, primary: Boolean) =
        ParsingLoadable.Parser { uri: Uri, input: InputStream ->
            val head = readUpTo(input, HEAD_BYTES)
            if (HlsPlaylistRepair.isTs(head)) {
                throw ParserException.createForMalformedManifest("Not a playlist (an MPEG-TS stream)", if (primary) HlsBodyIsTs() else null)
            }
            val rest = readUpTo(input, MAX_BYTES - head.size + 1)
            if (head.size + rest.size > MAX_BYTES) {
                throw ParserException.createForMalformedManifest("Not a playlist (over ${MAX_BYTES / (1024 * 1024)} MiB)", null)
            }
            val text = HlsPlaylistRepair.fix(String(head + rest, Charsets.UTF_8))
                ?: throw ParserException.createForMalformedManifest("Not a playlist (no #EXTM3U)", null)
            parser.parse(uri, ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
        }

    private fun readUpTo(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (out.size() < max) {
            val n = input.read(buffer, 0, minOf(buffer.size, max - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private companion object {
        const val HEAD_BYTES = 512
        const val MAX_BYTES = 4 * 1024 * 1024
    }
}

/** HLS goes through the lenient playlist parser; every other type through the stock factory. */
@UnstableApi
class LenientMediaSourceFactory(
    dataSource: DataSource.Factory,
    extractors: ExtractorsFactory,
    captions: Boolean = false,
    loadErrorPolicy: LoadErrorHandlingPolicy? = null,
) : MediaSource.Factory {
    private val stock = DefaultMediaSourceFactory(dataSource, extractors)
    private val hls = HlsMediaSource.Factory(dataSource).setPlaylistParserFactory(LenientHlsPlaylistParserFactory()).apply {
        val extractorFactory = DefaultHlsExtractorFactory(TS_PAYLOAD_FLAGS, false)
        setExtractorFactory(if (captions) LiveCaptions.Hls(extractorFactory) else extractorFactory)
        loadErrorPolicy?.let { setLoadErrorHandlingPolicy(it) }
    }

    init {
        loadErrorPolicy?.let { stock.setLoadErrorHandlingPolicy(it) }
    }

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory = apply {
        stock.setDrmSessionManagerProvider(provider)
        hls.setDrmSessionManagerProvider(provider)
    }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory = apply {
        stock.setLoadErrorHandlingPolicy(policy)
        hls.setLoadErrorHandlingPolicy(policy)
    }

    override fun getSupportedTypes(): IntArray = stock.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val config = mediaItem.localConfiguration
        val type = if (config == null) C.CONTENT_TYPE_OTHER else Util.inferContentTypeForUriAndMimeType(config.uri, config.mimeType)
        return if (type == C.CONTENT_TYPE_HLS) hls.createMediaSource(mediaItem) else stock.createMediaSource(mediaItem)
    }
}
