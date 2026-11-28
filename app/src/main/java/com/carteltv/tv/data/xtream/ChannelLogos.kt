package com.carteltv.tv.data.xtream

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.FileInputStream
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.Normalizer

/**
 * Real channel logos from the bundled iptv-org catalogue (`assets/channel_logos.tsv` and `channel_logos.pack`, built by
 * `tools/build_logo_index.py`), matched by name instead of trusting the provider's icon.
 * A name that matches channels in several countries with different logos and carries no country
 * tag is left unmatched rather than guessed.
 */
object ChannelLogos {

    internal const val ASSET = "channel_logos.tsv"
    internal const val PACK_ASSET = "channel_logos.pack"
    internal const val PACK_MAGIC = 0x434C5031 // "CLP1"
    private val QUALITY = setOf(
        "8k", "4k", "uhd", "fhd", "hd", "sd", "1080p", "1080", "720p", "720",
        "hevc", "h265", "h264", "raw", "50fps", "60fps", "2160p",
    )
    private val MARKS = Regex("""\p{Mn}+""")
    private val SPLIT = Regex("""[^a-z0-9]+""")

    /** key to (country to url). Must fold names exactly like `fold_name` in the generator. */
    @Volatile private var index: Map<String, Map<String, String>> = emptyMap()
    private val loadLock = Mutex()

    /** Flips to true once the catalogue is in memory, so rows showing a logo recompose. */
    var loaded by mutableStateOf(false)
        private set

    fun fold(name: String): String {
        val s = MARKS.replace(Normalizer.normalize(name, Normalizer.Form.NFD), "")
            .lowercase()
            .replace("+", " plus ")
        return SPLIT.split(s).filter { it.isNotEmpty() && it !in QUALITY }.joinToString("")
    }

    suspend fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loadLock.withLock {
            if (loaded) return
            index = withContext(Dispatchers.IO) { runCatching { read(ctx.applicationContext) }.getOrDefault(emptyMap()) }
            loaded = true
        }
    }

    private fun read(ctx: Context): Map<String, Map<String, String>> {
        val out = HashMap<String, MutableMap<String, String>>(48_000)
        ctx.assets.open(ASSET).use { raw ->
            raw.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                val p = line.split('\t')
                if (p.size == 3) out.getOrPut(p[0]) { HashMap(2) }[p[1]] = p[2].intern()
            }
        }
        return out
    }

    /** Pure lookup for tests and callers that hold their own index. */
    internal fun pick(candidates: Map<String, String>?, country: String?): String? {
        if (candidates.isNullOrEmpty()) return null
        country?.let { c -> candidates[c]?.let { return it } }
        return candidates.values.distinct().singleOrNull()
    }

    private class Pack(val fd: android.content.res.AssetFileDescriptor, val base: Long, val offsets: IntArray)

    @Volatile private var pack: Pack? = null
    private val decoded = LruCache<Int, ImageBitmap>(400)

    private fun openPack(ctx: Context): Pack? = pack ?: synchronized(this) {
        pack ?: runCatching {
            val fd = ctx.applicationContext.assets.openFd(PACK_ASSET)
            val ch = FileInputStream(fd.fileDescriptor).channel
            val head = java.nio.ByteBuffer.allocate(8)
            ch.read(head, fd.startOffset); head.flip()
            require(head.int == PACK_MAGIC) { "bad logo pack" }
            val count = head.int
            val table = java.nio.ByteBuffer.allocate((count + 1) * 4)
            ch.read(table, fd.startOffset + 8); table.flip()
            Pack(fd, fd.startOffset + 8 + (count + 1) * 4L, IntArray(count + 1) { table.int })
        }.getOrNull()?.also { pack = it }
    }

    /** Decodes packed logo [id] (the number after `#` in a logo value); null when unavailable. */
    fun packed(ctx: Context, id: Int): ImageBitmap? {
        synchronized(decoded) { decoded.get(id) }?.let { return it }
        val p = openPack(ctx) ?: return null
        if (id < 0 || id >= p.offsets.size - 1) return null
        val len = p.offsets[id + 1] - p.offsets[id]
        val buf = java.nio.ByteBuffer.allocate(len)
        FileInputStream(p.fd.fileDescriptor).channel.read(buf, p.base + p.offsets[id])
        val bmp = BitmapFactory.decodeByteArray(buf.array(), 0, len)?.asImageBitmap() ?: return null
        synchronized(decoded) { decoded.put(id, bmp) }
        return bmp
    }

    /** The catalogue logo for [name] as the provider spells it (`CA| TSN 1 HD`): a URL, `#<packed id>`, or null. */
    fun urlFor(name: String): String? {
        if (!loaded) return null
        return synchronized(urlCache) {
            if (urlCache.containsKey(name)) return@synchronized urlCache[name]
            val key = fold(GuideModel.cleanName(name))
            val result = if (key.length < 2) null else pick(index[key], GuideModel.countryTag(name))
            urlCache[name] = result
            result
        }
    }

    /** Name cleaning and folding are regex-heavy; visible rows ask for the same names every recomposition. */
    private val urlCache = object : LinkedHashMap<String, String?>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>?): Boolean = size > 2048
    }

    internal fun installForTest(entries: Map<String, Map<String, String>>) {
        index = entries
        loaded = true
        synchronized(urlCache) { urlCache.clear() }
    }
}
