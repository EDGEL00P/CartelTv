package com.carteltv.tv.data.xtream

/**
 * Ready-made XMLTV guides from EPG Share (epgshare01.online), one file per country. Every URL was
 * checked to answer 200 on 2026-11-21. The all-sources file (about 210 MB gzip) is deliberately
 * absent: too big to parse on a TV box.
 */
object EpgPresets {
    data class Preset(val label: String, val url: String)

    private const val BASE = "https://epgshare01.online/epgshare01/epg_ripper_"

    private fun p(label: String, code: String) = Preset(label, "$BASE$code.xml.gz")

    /**
     * Guides to add on top of the provider's own, chosen by measuring how many of a real provider's guide ids each
     * one holds (`tools/find_epg_coverage.py`): iptv-epg.org's country files plus EPG Share's Canada guide.
     * English-speaking regions only. Which of them help depends on the provider, so none is on by default.
     */
    private const val OWN = "https://github.com/EDGEL00P/CartelTv/releases/download/epg-latest/"

    /** Built every 6 hours by `.github/workflows/epg.yml`; one file merges every English guide, so it uses one of the [EpgSync.MAX_SOURCES] slots. */
    val combined = Preset("CartelTV English (all markets, streaming, sports)", OWN + "epg_ENGLISH_ALL.xml.gz")

    val extras: List<Preset> = listOf(
        combined,
        Preset("CartelTV streaming platforms", OWN + "epg_STREAMING.xml.gz"),
        Preset("CartelTV new releases (TVmaze)", OWN + "epg_TVMAZE.xml.gz"),
        Preset("United States (iptv-epg.org)", "https://iptv-epg.org/files/epg-us.xml.gz"),
        Preset("United Kingdom (iptv-epg.org)", "https://iptv-epg.org/files/epg-gb.xml.gz"),
        Preset("Canada (EPG Share)", "https://epgshare01.online/epgshare01/epg_ripper_CA2.xml.gz"),
        Preset("Canada (iptv-epg.org)", "https://iptv-epg.org/files/epg-ca.xml.gz"),
        Preset("Australia (iptv-epg.org)", "https://iptv-epg.org/files/epg-au.xml.gz"),
        Preset("New Zealand (iptv-epg.org)", "https://iptv-epg.org/files/epg-nz.xml.gz"),
        Preset("South Africa (iptv-epg.org)", "https://iptv-epg.org/files/epg-za.xml.gz"),
        Preset("Singapore (iptv-epg.org)", "https://iptv-epg.org/files/epg-sg.xml.gz"),
    )

    val all: List<Preset> = listOf(
        p("Canada", "CA2"),
        p("United States", "US2"),
        p("US Locals", "US_LOCALS1"),
        p("US Sports", "US_SPORTS1"),
        p("United Kingdom", "UK1"),
        p("Ireland", "IE1"),
        p("Australia", "AU1"),
        p("New Zealand", "NZ1"),
    )
}
