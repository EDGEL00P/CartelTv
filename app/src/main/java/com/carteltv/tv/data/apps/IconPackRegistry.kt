package com.carteltv.tv.data.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/**
 * Standard launcher icon-pack loader.
 *
 * Discovers installed icon pack APKs, parses both compiled `res/xml/appfilter.xml` and
 * `assets/appfilter.xml` into component + package lookup tables with an LRU pack cache
 * (up to 10 packs), and resolves themed drawables for [AppsRepository].
 */
object IconPackRegistry {

    data class PackInfo(val packageName: String, val label: String)

    data class AppFilterIndex(
        val byComponent: Map<String, String>,
        val byPackage: Map<String, String>,
    )

    private data class LoadedPack(
        val packageName: String,
        val resources: Resources,
        val index: AppFilterIndex,
    )

    private val PACK_ACTIONS = listOf(
        "org.adw.launcher.THEMES",
        "com.novalauncher.THEME",
        "com.gau.go.launcherex.theme",
        "org.adw.launcher.icons.ACTION_PICK_ICON",
    )

    private val loaded = object : LinkedHashMap<String, LoadedPack>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LoadedPack>?) = size > 10
    }
    private val failed = HashSet<String>()

    fun invalidate() {
        synchronized(loaded) {
            loaded.clear()
            failed.clear()
        }
    }

    /** Lists installed ADW / Nova / Go icon packs sorted alphabetically. */
    fun installed(ctx: Context): List<PackInfo> {
        val pm = ctx.packageManager
        val found = LinkedHashMap<String, PackInfo>()
        for (action in PACK_ACTIONS) {
            val activities = runCatching { pm.queryIntentActivities(Intent(action), 0) }.getOrDefault(emptyList())
            for (ri in activities) {
                val pkg = ri.activityInfo?.packageName ?: continue
                found.putIfAbsent(
                    pkg,
                    PackInfo(pkg, runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg)),
                )
            }
        }
        return found.values.sortedBy { it.label.lowercase() }
    }

    fun resolveDrawableName(index: AppFilterIndex, packageName: String, className: String? = null): String? {
        if (!className.isNullOrBlank()) {
            val fullCls = if (className.startsWith(".")) packageName + className else className
            index.byComponent["$packageName/$fullCls"]?.let { return it }
        }
        return index.byPackage[packageName]
    }

    /** Loads a themed icon for [component] (or [packageName]) from [packPkg], or null if unmapped. */
    fun loadIcon(ctx: Context, packPkg: String, packageName: String, component: ComponentName? = null): Drawable? {
        if (packPkg.isBlank()) return null
        val pack = ensureLoaded(ctx, packPkg) ?: return null
        val drawableName = resolveDrawableName(pack.index, packageName, component?.className) ?: return null
        val resId = pack.resources.getIdentifier(drawableName, "drawable", packPkg)
            .takeIf { it != 0 }
            ?: pack.resources.getIdentifier(drawableName, "mipmap", packPkg)
        if (resId == 0) return null
        return runCatching { pack.resources.getDrawable(resId, null) }.getOrNull()
    }

    private fun ensureLoaded(ctx: Context, packPkg: String): LoadedPack? {
        synchronized(loaded) {
            loaded[packPkg]?.let { return it }
            if (packPkg in failed) return null
        }
        val parsed = runCatching { parsePackage(ctx, packPkg) }.getOrNull()
        synchronized(loaded) {
            if (parsed != null) loaded[packPkg] = parsed else failed += packPkg
        }
        return parsed
    }

    private fun parsePackage(ctx: Context, packPkg: String): LoadedPack {
        val packCtx = ctx.createPackageContext(packPkg, 0)
        val res = packCtx.resources
        val byComp = HashMap<String, String>(2048)
        val byPkg = HashMap<String, String>(1024)

        val sources = mutableListOf<Pair<XmlPullParser, InputStream?>>()
        val xmlId = res.getIdentifier("appfilter", "xml", packPkg)
        if (xmlId != 0) runCatching { sources += res.getXml(xmlId) to null }
        runCatching { packCtx.assets.open("appfilter.xml") }.getOrNull()?.let { stream ->
            sources += Xml.newPullParser().apply { setInput(stream, null) } to stream
        }

        for ((parser, stream) in sources) {
            runCatching {
                var ev = parser.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG && parser.name == "item") {
                        val comp = parser.getAttributeValue(null, "component")
                        val draw = parser.getAttributeValue(null, "drawable")
                        if (comp != null && !draw.isNullOrBlank() &&
                            comp.startsWith("ComponentInfo{") && comp.endsWith("}")
                        ) {
                            val inner = comp.substring(14, comp.length - 1)
                            val pkg = inner.substringBefore('/')
                            var cls = inner.substringAfter('/', "")
                            if (cls.startsWith(".")) cls = pkg + cls
                            if (pkg.isNotEmpty()) {
                                if (cls.isNotEmpty()) byComp.putIfAbsent("$pkg/$cls", draw)
                                byPkg.putIfAbsent(pkg, draw)
                            }
                        }
                    }
                    ev = parser.next()
                }
            }
            runCatching { stream?.close() }
        }
        return LoadedPack(packPkg, res, AppFilterIndex(byComp, byPkg))
    }
}
