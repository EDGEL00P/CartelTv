@file:androidx.media3.common.util.UnstableApi

package com.carteltv.tv.data.youtube

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource

/**
 * YouTube-aware Media3 HttpDataSource (synthesized from Flow's YouTubeHttpDataSource +
 * StreamHostFallback and NewPipe's YoutubeHttpDataSource):
 *  - Matches User-Agent and browser CORS headers to the URL's `c=` minting client
 *  - Forces `Accept-Encoding: identity` on media byte ranges (gzip allowed for HLS playlists)
 *  - Fails over across `mn=` googlevideo edge mirrors when a CDN node is unreachable
 *  - Reports HTTP 403 refusals to [YouTubeApiClient.reportStreamDenied] for immediate client demotion
 */
class YouTubeDataSource private constructor(
    private val fallbackUserAgent: String,
    private val hostFallback: YouTubeHostFallback,
    private val onForbidden: ((failedUrl: String, kind: StreamDenialKind) -> Unit)?,
) : BaseDataSource(true), HttpDataSource {

    private var delegate: HttpDataSource? = null
    private var currentUri: Uri? = null
    private var opened = false
    private val customProperties = LinkedHashMap<String, String>()

    class Factory(
        private val fallbackUserAgent: String,
        private val hostFallback: YouTubeHostFallback = sharedHostFallback,
        private val onForbidden: ((failedUrl: String, kind: StreamDenialKind) -> Unit)? = null,
    ) : HttpDataSource.Factory {
        private val defaults = LinkedHashMap<String, String>()

        override fun createDataSource(): HttpDataSource =
            YouTubeDataSource(fallbackUserAgent, hostFallback, onForbidden).also { ds ->
                defaults.forEach { (k, v) -> ds.setRequestProperty(k, v) }
            }

        override fun setDefaultRequestProperties(
            defaultRequestProperties: MutableMap<String, String>,
        ): HttpDataSource.Factory {
            defaults.clear()
            defaults.putAll(defaultRequestProperties)
            return this
        }
    }

    override fun open(dataSpec: DataSpec): Long {
        var spec = applyHostRoute(dataSpec)
        currentUri = spec.uri
        transferInitializing(spec)

        var lastError: HttpDataSource.HttpDataSourceException? = null
        repeat(MAX_HOST_ATTEMPTS) { attempt ->
            val ds = buildDelegate(spec)
            delegate = ds
            try {
                val len = ds.open(spec)
                opened = true
                transferStarted(spec)
                return len
            } catch (e: HttpDataSource.InvalidResponseCodeException) {
                runCatching { ds.close() }
                delegate = null
                if (e.responseCode == 403) {
                    val url = spec.uri.toString()
                    val kind = YouTubeApiClient.reportStreamDenied(url)
                    Log.w(
                        TAG,
                        "HTTP 403 c=${YouTubeStreamDenial.clientOf(url)} itag=${YouTubeStreamDenial.itagOf(url)} " +
                            "pot=${YouTubeStreamDenial.hasPoToken(url)} pos=${spec.position} kind=$kind",
                    )
                    onForbidden?.invoke(url, kind)
                }
                throw e
            } catch (e: HttpDataSource.HttpDataSourceException) {
                runCatching { ds.close() }
                delegate = null
                lastError = e
                val url = spec.uri.toString()
                if (attempt + 1 >= MAX_HOST_ATTEMPTS || !isGoogleVideo(spec.uri)) throw e
                hostFallback.markUnreachable(url)
                val nextUrl = hostFallback.route(url)
                if (nextUrl == null || nextUrl == url) throw e
                Log.w(TAG, "CDN edge unreachable, rerouting to mirror: ${Uri.parse(nextUrl).host}")
                spec = spec.withUri(Uri.parse(nextUrl))
                currentUri = spec.uri
            }
        }
        throw lastError ?: HttpDataSource.HttpDataSourceException(
            "Unable to open YouTube stream",
            spec,
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            HttpDataSource.HttpDataSourceException.TYPE_OPEN,
        )
    }

    private fun applyHostRoute(dataSpec: DataSpec): DataSpec {
        if (!isGoogleVideo(dataSpec.uri)) return dataSpec
        val raw = dataSpec.uri.toString()
        val routed = hostFallback.route(raw) ?: return dataSpec
        return if (routed == raw) dataSpec else dataSpec.withUri(Uri.parse(routed))
    }

    private fun buildDelegate(dataSpec: DataSpec): HttpDataSource {
        val uri = dataSpec.uri
        val clientParam = if (isYouTubeHost(uri)) uri.getQueryParameter("c") else null
        val ua = if (isYouTubeHost(uri)) {
            YouTubeRequestPolicy.userAgentForClient(clientParam, fallbackUserAgent)
        } else {
            fallbackUserAgent
        }
        val allowGzip = dataSpec.isFlagSet(DataSpec.FLAG_ALLOW_GZIP) ||
            uri.path?.endsWith(".m3u8", ignoreCase = true) == true
        val headers = LinkedHashMap<String, String>()
        if (isYouTubeHost(uri)) {
            headers.putAll(YouTubeRequestPolicy.headersForClient(clientParam, allowGzip = allowGzip))
        }
        headers.putAll(customProperties)

        return DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(30_000)
            .setDefaultRequestProperties(headers)
            .createDataSource()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = delegate?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
        if (read > 0) bytesTransferred(read)
        return read
    }

    override fun close() {
        try {
            delegate?.close()
        } finally {
            delegate = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    override fun getUri(): Uri? = delegate?.uri ?: currentUri
    override fun getResponseCode(): Int = delegate?.responseCode ?: -1
    override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders ?: emptyMap()

    override fun setRequestProperty(name: String, value: String) {
        customProperties[name] = value
    }

    override fun clearRequestProperty(name: String) {
        customProperties.remove(name)
    }

    override fun clearAllRequestProperties() {
        customProperties.clear()
    }

    private fun isGoogleVideo(uri: Uri): Boolean =
        uri.host?.contains("googlevideo.com", ignoreCase = true) == true

    private fun isYouTubeHost(uri: Uri): Boolean {
        val host = uri.host ?: return false
        return host.contains("googlevideo.com", ignoreCase = true) ||
            host.contains("youtube.com", ignoreCase = true) ||
            host.contains("ytimg.com", ignoreCase = true)
    }

    companion object {
        private const val TAG = "CartelTubeDS"
        private const val MAX_HOST_ATTEMPTS = 3
        val sharedHostFallback = YouTubeHostFallback()
    }
}
