package com.carteltv.tv.data.xtream

/**
 * Keeps content in the languages the viewer picked in Settings. A name's language is read from, in order:
 * a country/language marker ("FR| Cinema", "[DE] Sport"), language words and release tags ("Arabic Movies",
 * "VOSTFR"), a non-Latin script, then clusters of function words ("Les Misérables des ...").
 * Content whose language cannot be told is kept: absence of evidence is not a foreign language.
 */
object LanguageFilter {

    enum class Lang(val code: String, val label: String) {
        EN("EN", "English"), FR("FR", "French"), ES("ES", "Spanish"), DE("DE", "German"), IT("IT", "Italian"),
        PT("PT", "Portuguese"), NL("NL", "Dutch"), PL("PL", "Polish"), TR("TR", "Turkish"), AR("AR", "Arabic"),
        RU("RU", "Russian"), EL("EL", "Greek"), HI("HI", "Hindi / South Asian"), ZH("ZH", "Chinese"),
        JA("JA", "Japanese"), KO("KO", "Korean"),

        /** A language with no entry above (Nordic, Balkan, Persian, Thai ...): kept only when filtering is off. */
        OTHER("OTHER", "Other");

        companion object {
            /** The pickable languages, in the order Settings shows them. */
            val selectable: List<Lang> = entries.filter { it != OTHER }
            fun fromCodes(csv: String): Set<Lang> =
                csv.split(',').mapNotNull { c -> entries.firstOrNull { it.code == c.trim().uppercase() } }.toSet()
        }
    }

    private val markerLang: Map<String, Lang> = buildMap {
        for (c in listOf("EN", "ENG", "US", "USA", "UK", "GB", "CA", "AU", "IE", "NZ")) put(c, Lang.EN)
        for (c in listOf("FR", "BE")) put(c, Lang.FR)
        for (c in listOf("DE", "AT", "CH")) put(c, Lang.DE)
        for (c in listOf("ES", "MX", "LAT")) put(c, Lang.ES)
        for (c in listOf("PT", "BR")) put(c, Lang.PT)
        put("IT", Lang.IT); put("NL", Lang.NL); put("PL", Lang.PL); put("TR", Lang.TR)
        put("AR", Lang.AR); put("RU", Lang.RU); put("UA", Lang.RU); put("GR", Lang.EL)
        for (c in listOf("IN", "HI", "PK", "BD", "LK")) put(c, Lang.HI)
        put("CN", Lang.ZH); put("JP", Lang.JA); put("KR", Lang.KO)
        put("SH", Lang.DE)
        for (c in listOf("DO", "CO", "ARG", "UY", "CL", "PE", "VE", "EC", "PY", "BO", "CR", "PA", "GT", "SV", "HN", "NI", "CU")) put(c, Lang.ES)
        for (c in listOf(
            "SW", "IC", "SC", "SU", "MY", "SRB", "HT", "MK", "BIH", "ARM", "LV", "AFG", "LT", "EE", "SI", "MD", "GE", "AZ",
            "RO", "HU", "CZ", "SK", "BG", "RS", "HR", "AL", "SE", "NO", "DK", "FI", "IR", "IL", "TH", "VN", "ID",
            "PH", "AF", "KU", "SO", "NG", "ZA", "EX", "EXYU",
        )) put(c, Lang.OTHER)
    }

    private val wordLang: List<Pair<Regex, Lang>> = listOf(
        "arabic" to Lang.AR, "french" to Lang.FR, "german" to Lang.DE, "spanish|latino" to Lang.ES,
        "italian" to Lang.IT, "turkish" to Lang.TR, "portuguese|brazil\\w*" to Lang.PT,
        "hindi|indian|punjabi|tamil|telugu|urdu|pakistan\\w*|bangla" to Lang.HI, "polish" to Lang.PL,
        "dutch" to Lang.NL, "russian|ukrain\\w*" to Lang.RU, "greek" to Lang.EL, "korean" to Lang.KO,
        "japanese" to Lang.JA, "chinese" to Lang.ZH,
        "romanian|hungarian|czech|albanian|balkan|persian|farsi|kurdish|thai|vietnam\\w*|filipino|african|" +
            "scandinavian|swedish|norwegian|danish|finnish|hebrew|israel\\w*" to Lang.OTHER,
    ).map { (words, lang) -> Regex("\\b($words)\\b", RegexOption.IGNORE_CASE) to lang }

    // Release tags that say the audio or subtitles are not English.
    private val tagLang: List<Pair<Regex, Lang>> = listOf(
        "vostfr|truefrench|vff|vfi|fran[cç]ais" to Lang.FR,
        "dublado|dublada|legendado|portugu[eê]s" to Lang.PT,
        "subtitulado|castellano|espa[nñ]ol" to Lang.ES,
        "deutsch" to Lang.DE, "italiano" to Lang.IT, "nederlands" to Lang.NL,
    ).map { (words, lang) -> Regex("\\b($words)\\b", RegexOption.IGNORE_CASE) to lang }

    // Function words that are not ordinary English words. One hit is not enough ("Le Mans '66", "El Camino"),
    // and ambiguous ones ("la", "de", "die", "no", "con") are left out ("La La Land", "Die Hard").
    private val stopLang: Map<Lang, Set<String>> = mapOf(
        Lang.FR to setOf("le", "les", "des", "une", "du", "aux", "avec", "pour", "dans", "est", "qui", "sont"),
        Lang.ES to setOf("el", "los", "las", "del", "una", "para", "por", "sus", "pero", "como"),
        Lang.DE to setOf("der", "das", "und", "ein", "eine", "nicht", "mit", "von", "ist"),
        Lang.IT to setOf("il", "gli", "della", "delle", "dei", "per", "che", "sono", "nel"),
        Lang.PT to setOf("os", "uma", "dos", "das", "não", "com", "mais"),
        Lang.NL to setOf("het", "een", "voor", "zijn", "niet"),
    )
    private val wordSplit = Regex("[^\\p{L}']+")

    private val tag = Regex("^[\\s|]*(?:\\[\\s*([A-Za-z]{2,4})\\s*]|\\(\\s*([A-Za-z]{2,4})\\s*\\)|([A-Za-z]{2,4})\\s*\\|)")

    // Provider decorations ("✦●✦"), and prefixes like "LATIN-BR|", "24/7-ES|", "FR-CAR|", "FR DAZN NBA |".
    private val decoration = Regex("[^\\p{L}\\p{N}|\\[\\]()/ \\-]")
    private val headSegment = Regex("^[\\s|]*(?:\\[([^\\]]{2,16})]|\\(([^)]{2,16})\\)|([^|\\[(]{2,16})\\|)")
    private val tokenSplit = Regex("[-\\s/]+")
    private val regionWords = setOf("LATIN", "ASIA", "EU", "24", "7", "VIP", "PPV")
    private val bareTag = Regex("^24/7[-\\s]*([A-Za-z]{2,3})\\b")
    // The same region/country pair can sit after the name instead of before it ("beIN Sports 1 ASIA - NZ").
    private val tailSegment = Regex("""(?i)\b(?:ASIA|LATIN|EU|EUROPE)\s*[-–—/|]\s*([A-Za-z]{2,3})\s*$""")

    private fun marker(name: String): String? {
        tag.find(name)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.uppercase()?.let { return it }
        val clean = decoration.replace(name, " ").trim()
        headSegment.find(clean)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.trim()?.let { seg ->
            // A longer segment only counts when shouted, so "IT Crowd |" is not read as Italian.
            if (seg == seg.uppercase()) {
                val tokens = seg.split(tokenSplit).filter { it.isNotEmpty() }
                val i = tokens.indexOfFirst { it in markerLang }
                if (i >= 0 && tokens.take(i).all { it in regionWords }) return tokens[i]
            }
        }
        tailSegment.find(clean)?.groupValues?.get(1)?.uppercase()?.let { if (it in markerLang) return it }
        return bareTag.find(clean)?.groupValues?.get(1)?.uppercase()
    }

    private fun scriptLang(name: String): Lang? {
        val counts = HashMap<Lang, Int>()
        name.codePoints().forEach { cp ->
            if (!Character.isLetter(cp)) return@forEach
            val lang = when (Character.UnicodeScript.of(cp)) {
                Character.UnicodeScript.LATIN, Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED -> null
                Character.UnicodeScript.CYRILLIC -> Lang.RU
                Character.UnicodeScript.ARABIC -> Lang.AR
                Character.UnicodeScript.GREEK -> Lang.EL
                Character.UnicodeScript.DEVANAGARI, Character.UnicodeScript.TAMIL, Character.UnicodeScript.TELUGU,
                Character.UnicodeScript.BENGALI, Character.UnicodeScript.GURMUKHI -> Lang.HI
                Character.UnicodeScript.HAN -> Lang.ZH
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> Lang.JA
                Character.UnicodeScript.HANGUL -> Lang.KO
                else -> Lang.OTHER
            }
            if (lang != null) counts.merge(lang, 1, Int::plus)
        }
        // Kana in a title with Han means Japanese, not Chinese.
        if (counts.containsKey(Lang.JA)) return Lang.JA
        return counts.entries.maxByOrNull { it.value }?.takeIf { it.value >= 2 }?.key
    }

    private fun stopwordLang(name: String): Lang? {
        val words = name.lowercase().split(wordSplit).filter { it.isNotEmpty() }
        val accented = name.lowercase().any { it in "ñüöäßçãõàâêîôûùìòíóúáï" } // not é/è: common in English titles
        val (lang, hits) = stopLang.map { (l, set) -> l to words.count { it in set } }.maxByOrNull { it.second } ?: return null
        return if (hits >= 2 || (hits == 1 && accented && words.size >= 3)) lang else null
    }

    /** A detected language and the evidence for it, so a hidden title can be explained. */
    data class Verdict(val lang: Lang, val reason: String)

    fun verdict(name: String): Verdict? {
        marker(name)?.let { m -> markerLang[m]?.let { return Verdict(it, "marker $m") } }
        wordLang.firstOrNull { it.first.containsMatchIn(name) }
            ?.let { return Verdict(it.second, "language word \"${it.first.find(name)?.value.orEmpty()}\"") }
        tagLang.firstOrNull { it.first.containsMatchIn(name) }
            ?.let { return Verdict(it.second, "release tag \"${it.first.find(name)?.value.orEmpty()}\"") }
        scriptLang(name)?.let { return Verdict(it, "script") }
        return stopwordLang(name)?.let { Verdict(it, "function words") }
    }

    /** The language [name] is written in or tagged with, or null when it cannot be told. */
    fun detect(name: String): Lang? = verdict(name)?.lang

    /** Why [name] is kept or hidden for the [wanted] languages, in one line. */
    fun explain(name: String, wanted: Set<Lang>): String {
        val v = verdict(name) ?: return "kept: language unknown"
        return if (v.lang in wanted) "kept: ${v.lang.label} (${v.reason})" else "hidden: ${v.lang.label} (${v.reason})"
    }

    /** True when [name] is in one of the [wanted] languages or its language cannot be told. */
    fun keep(name: String, wanted: Set<Lang>): Boolean = detect(name)?.let { it in wanted } ?: true

    fun isEnglish(name: String): Boolean = keep(name, setOf(Lang.EN))

    /** Category ids to hide, derived from category names. */
    fun blockedCategoryIds(categories: List<XtreamClient.Category>, wanted: Set<Lang> = setOf(Lang.EN)): Set<String> =
        categories.filterNot { keep(it.category_name, wanted) }.map { it.category_id }.toSet()
}
