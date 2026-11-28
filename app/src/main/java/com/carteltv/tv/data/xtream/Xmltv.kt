package com.carteltv.tv.data.xtream

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.Calendar
import java.util.TimeZone

/** XMLTV timestamps: `yyyyMMddHHmm[ss][ +HHMM]`, zone optional (taken as UTC). Pure. */
object XmltvTime {
    fun parse(raw: String?): Long? {
        val s = raw?.trim().orEmpty()
        var i = 0
        while (i < s.length && s[i].isDigit()) i++
        if (i < 12) return null
        fun n(from: Int, len: Int) = s.substring(from, from + len).toInt()
        val sec = if (i >= 14) n(12, 2) else 0
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(n(0, 4), n(4, 2) - 1, n(6, 2), n(8, 2), n(10, 2), sec)
        var ms = cal.timeInMillis
        val zone = s.substring(i).trim()
        if (zone.length >= 5 && (zone[0] == '+' || zone[0] == '-') && zone.substring(1, 5).all { it.isDigit() }) {
            val off = (zone.substring(1, 3).toInt() * 60 + zone.substring(3, 5).toInt()) * 60_000L
            ms -= if (zone[0] == '+') off else -off
        }
        return ms
    }
}

/** Streaming XMLTV reader. Never holds the document; each programme (and, optionally, each channel) goes straight to a callback. */
object XmltvReader {
    class Programme(val channel: String, val startMs: Long, val stopMs: Long, val title: String, val desc: String?)

    /** [onChannel] hears each `<channel>` (id and display names); guides list them ahead of the programmes. */
    fun parse(input: InputStream, onProgramme: (Programme) -> Unit, onChannel: ((String, List<String>) -> Unit)? = null) {
        val p = Xml.newPullParser()
        p.setInput(input, null)
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                if (p.name == "programme") readProgramme(p, onProgramme)
                else if (p.name == "channel" && onChannel != null) readChannel(p, onChannel)
            }
            ev = p.next()
        }
    }

    private fun readChannel(p: XmlPullParser, cb: (String, List<String>) -> Unit) {
        val id = p.getAttributeValue(null, "id")?.trim().orEmpty()
        val names = ArrayList<String>()
        while (true) {
            when (p.next()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.END_TAG -> if (p.name == "channel") break
                XmlPullParser.START_TAG -> if (p.name == "display-name") {
                    p.nextText().trim().takeIf { it.isNotEmpty() }?.let { names += it }
                } else skip(p)
            }
        }
        if (id.isNotEmpty()) cb(id, names)
    }

    private fun readProgramme(p: XmlPullParser, cb: (Programme) -> Unit) {
        val channel = p.getAttributeValue(null, "channel")?.trim().orEmpty()
        val start = XmltvTime.parse(p.getAttributeValue(null, "start"))
        val stop = XmltvTime.parse(p.getAttributeValue(null, "stop"))
        var title: String? = null
        var desc: String? = null
        while (true) {
            when (p.next()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.END_TAG -> if (p.name == "programme") break
                XmlPullParser.START_TAG -> when (p.name) {
                    "title" -> { val t = p.nextText(); if (title == null) title = t }
                    "desc" -> { val t = p.nextText(); if (desc == null) desc = t }
                    else -> skip(p)
                }
            }
        }
        val t = title?.trim().orEmpty()
        if (channel.isEmpty() || start == null || t.isEmpty()) return
        // The DTD makes <stop> optional; a missing or invalid end gets a synthetic slot that
        // GuideModel.slots clips at the next programme's start — same rule the Xtream path uses.
        val end = stop?.takeIf { it > start } ?: (start + GuideModel.SLOT_MS)
        cb(Programme(channel, start, end, t, desc?.trim()?.takeIf { it.isNotEmpty() }))
    }
    private fun skip(p: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }
}
