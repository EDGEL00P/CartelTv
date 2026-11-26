package com.carteltv.tv.data.xtream

import com.carteltv.tv.data.xtream.LanguageFilter.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every line of language_corpus.tsv must detect as written; a wrong detection names the title and the evidence. */
class LanguageCorpusTest {

    private val rows: List<Pair<String, String>> = checkNotNull(javaClass.getResourceAsStream("/language_corpus.tsv"))
        .bufferedReader(Charsets.UTF_8).readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { line -> line.substringBefore('\t') to line.substringAfter('\t').trim() }

    @Test fun corpusIsNotEmpty() {
        assertTrue(rows.size >= 30)
    }

    @Test fun everyTitleDetectsAsExpected() {
        val wrong = rows.mapNotNull { (name, expected) ->
            val got = LanguageFilter.detect(name)?.code ?: "-"
            if (got == expected) null else "\"$name\": expected $expected, got $got (${LanguageFilter.explain(name, setOf(Lang.EN))})"
        }
        assertEquals("Wrong detections:\n" + wrong.joinToString("\n"), 0, wrong.size)
    }

    @Test fun nothingUndetectedIsEverHidden() {
        for ((name, expected) in rows.filter { it.second == "-" }) {
            for (wanted in listOf(setOf(Lang.EN), setOf(Lang.FR), Lang.selectable.toSet())) {
                assertTrue("\"$name\" must be kept", LanguageFilter.keep(name, wanted))
            }
        }
    }

    @Test fun explainNamesTheEvidence() {
        assertEquals("hidden: French (marker FR)", LanguageFilter.explain("FR| Cinema", setOf(Lang.EN)))
        assertEquals("kept: French (marker FR)", LanguageFilter.explain("FR| Cinema", setOf(Lang.FR)))
        assertEquals("kept: language unknown", LanguageFilter.explain("Inception", setOf(Lang.EN)))
        assertTrue(LanguageFilter.explain("Amélie VOSTFR", setOf(Lang.EN)).startsWith("hidden: French (release tag"))
    }
}
