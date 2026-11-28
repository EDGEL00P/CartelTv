package com.carteltv.tv.data.youtube

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Chapters from an Innertube `next` response. Two shapes carry them (checked against yt-dlp,
 * YouTube.js and ytdl-core): the player bar's `markersMap[].value.chapters[].chapterRenderer`
 * with `timeRangeStartMillis`, and the chapters engagement panel's `macroMarkersListItemRenderer`
 * with a `timeDescription` clock label. Creator chapters win; the panel is the fallback.
 */
object YouTubeChapters {

    data class Chapter(val title: String, val startMs: Long)

    fun parse(root: JsonObject): List<Chapter> {
        val fromBar = markersMapChapters(root)
        val list = fromBar.ifEmpty { panelChapters(root) }
        return list.filter { it.title.isNotBlank() }
            .sortedBy { it.startMs }
            .distinctBy { it.startMs }
    }

    /** Index of the chapter playing at [posMs], or -1 before the first one. */
    fun indexAt(chapters: List<Chapter>, posMs: Long): Int = chapters.indexOfLast { it.startMs <= posMs }

    fun clockMs(text: String?): Long? {
        val parts = text?.trim()?.split(':') ?: return null
        if (parts.size !in 1..3) return null
        val secs = parts.fold(0L) { acc, p -> acc * 60 + (p.trim().toLongOrNull()?.takeIf { it >= 0 } ?: return null) }
        return secs * 1000
    }

    private fun markersMapChapters(root: JsonObject): List<Chapter> {
        val map = root.obj("playerOverlays")?.obj("playerOverlayRenderer")
            ?.obj("decoratedPlayerBarRenderer")?.obj("decoratedPlayerBarRenderer")
            ?.obj("playerBar")?.obj("multiMarkersPlayerBarRenderer")?.arr("markersMap")
            ?: return emptyList()
        val entries = map.mapNotNull { it as? JsonObject }
        val picked = entries.firstOrNull { it.str("key") == "DESCRIPTION_CHAPTERS" }
            ?: entries.firstOrNull { it.str("key") == "AUTO_CHAPTERS" }
            ?: return emptyList()
        return picked.obj("value")?.arr("chapters").orEmpty().mapNotNull { c ->
            val r = (c as? JsonObject)?.obj("chapterRenderer") ?: return@mapNotNull null
            val start = (r["timeRangeStartMillis"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return@mapNotNull null
            Chapter(text(r["title"]).orEmpty(), start)
        }
    }

    private fun panelChapters(root: JsonObject): List<Chapter> {
        for (panel in root.arr("engagementPanels").orEmpty()) {
            val items = (panel as? JsonObject)?.obj("engagementPanelSectionListRenderer")
                ?.obj("content")?.obj("macroMarkersListRenderer")?.arr("contents") ?: continue
            val out = items.mapNotNull { it as? JsonObject }.mapNotNull { i ->
                val r = i.obj("macroMarkersListItemRenderer") ?: return@mapNotNull null
                val start = r.obj("onTap")?.obj("watchEndpoint")?.get("startTimeSeconds")
                    ?.let { (it as? JsonPrimitive)?.content?.toLongOrNull() }?.times(1000)
                    ?: clockMs(text(r["timeDescription"]))
                    ?: return@mapNotNull null
                Chapter(text(r["title"]).orEmpty(), start)
            }
            if (out.isNotEmpty()) return out
        }
        return emptyList()
    }

    private fun text(e: JsonElement?): String? {
        val o = e as? JsonObject ?: return (e as? JsonPrimitive)?.content
        (o["simpleText"] as? JsonPrimitive)?.content?.let { return it }
        return o.arr("runs")?.joinToString("") { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.content.orEmpty() }
    }

    private fun JsonObject.obj(k: String) = get(k) as? JsonObject
    private fun JsonObject.arr(k: String) = get(k) as? JsonArray
    private fun JsonObject.str(k: String) = (get(k) as? JsonPrimitive)?.content
}
