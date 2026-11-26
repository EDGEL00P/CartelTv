package com.carteltv.tv.data.xtream

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/** An ETag or Last-Modified kept as one string, so a later request can ask whether the download changed. */
internal object ProviderUrlValidator {
    private const val ETAG = "E:"
    private const val MODIFIED = "M:"

    /** The ETag when the server sent one, else Last-Modified, else blank. */
    fun of(etag: String?, lastModified: String?): String = when {
        !etag.isNullOrBlank() -> ETAG + etag
        !lastModified.isNullOrBlank() -> MODIFIED + lastModified
        else -> ""
    }

    /** The conditional request header for [validator], or null when there is nothing to ask. */
    fun requestHeader(validator: String): Pair<String, String>? = when {
        validator.startsWith(ETAG) -> "If-None-Match" to validator.removePrefix(ETAG)
        validator.startsWith(MODIFIED) -> "If-Modified-Since" to validator.removePrefix(MODIFIED)
        else -> null
    }
}

/** Fails with an [IOException] once more than [limit] bytes have been read. */
internal class LimitedInputStream(source: InputStream, private val limit: Long) : FilterInputStream(source) {
    private var count = 0L

    private fun add(n: Int): Int {
        if (n > 0) {
            count += n
            if (count > limit) throw IOException("Playlist is too large (over ${limit / 1_000_000} MB)")
        }
        return n
    }

    override fun read(): Int = super.read().also { if (it >= 0) add(1) }

    override fun read(b: ByteArray, off: Int, len: Int): Int = add(super.read(b, off, len))
}
