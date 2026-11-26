package com.carteltv.tv.data.xtream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageFilterTest {
    @Test fun englishTagsKept() {
        assertTrue(LanguageFilter.isEnglish("US| Movies"))
        assertTrue(LanguageFilter.isEnglish("[UK] Drama"))
        assertTrue(LanguageFilter.isEnglish("EN | Sports"))
    }

    @Test fun foreignTagsDropped() {
        assertFalse(LanguageFilter.isEnglish("FR| Cinema"))
        assertFalse(LanguageFilter.isEnglish("[DE] Sport"))
        assertFalse(LanguageFilter.isEnglish("AR | Series"))
    }

    @Test fun foreignWordsDropped() {
        assertFalse(LanguageFilter.isEnglish("Arabic Movies"))
        assertFalse(LanguageFilter.isEnglish("Latino Series"))
    }

    @Test fun foreignTitlesAndReleaseTagsDropped() {
        assertFalse(LanguageFilter.isEnglish("Les Misérables des Pauvres"))
        assertFalse(LanguageFilter.isEnglish("Los Amantes del Círculo Polar"))
        assertFalse(LanguageFilter.isEnglish("Das Leben der Anderen und Mehr"))
        assertFalse(LanguageFilter.isEnglish("Amélie VOSTFR"))
        assertFalse(LanguageFilter.isEnglish("Dark Knight DUBLADO"))
        assertFalse(LanguageFilter.isEnglish("Война и мир"))
        assertFalse(LanguageFilter.isEnglish("寄生虫"))
    }

    @Test fun englishTitlesWithForeignLookingWordsKept() {
        assertTrue(LanguageFilter.isEnglish("La La Land"))
        assertTrue(LanguageFilter.isEnglish("Le Mans '66"))
        assertTrue(LanguageFilter.isEnglish("El Camino: A Breaking Bad Movie"))
        assertTrue(LanguageFilter.isEnglish("Die Hard"))
        assertTrue(LanguageFilter.isEnglish("Café Society"))
        assertTrue(LanguageFilter.isEnglish("Léon: The Professional"))
        assertTrue(LanguageFilter.isEnglish("Los Angeles Story"))
    }

    @Test fun pickedLanguagesAreKept() {
        val enFr = setOf(LanguageFilter.Lang.EN, LanguageFilter.Lang.FR)
        assertTrue(LanguageFilter.keep("FR| Cinema", enFr))
        assertTrue(LanguageFilter.keep("Amélie VOSTFR", enFr))
        assertTrue(LanguageFilter.keep("US| Movies", enFr))
        assertFalse(LanguageFilter.keep("DE| Sport", enFr))
        assertFalse(LanguageFilter.keep("Los Amantes del Círculo Polar", enFr))
        assertTrue(LanguageFilter.keep("Los Amantes del Círculo Polar", setOf(LanguageFilter.Lang.ES)))
        assertFalse(LanguageFilter.keep("Thai Drama", enFr))
    }

    @Test fun detectsLanguageFromScriptAndWords() {
        assertEquals(LanguageFilter.Lang.RU, LanguageFilter.detect("Война и мир"))
        assertEquals(LanguageFilter.Lang.ZH, LanguageFilter.detect("寄生虫"))
        assertEquals(LanguageFilter.Lang.JA, LanguageFilter.detect("となりのトトロ"))
        assertEquals(LanguageFilter.Lang.KO, LanguageFilter.detect("기생충"))
        assertEquals(LanguageFilter.Lang.DE, LanguageFilter.detect("Das Leben der Anderen und Mehr"))
        assertEquals(null, LanguageFilter.detect("Inception"))
    }

    @Test fun languageCodesRoundTrip() {
        val set = LanguageFilter.Lang.fromCodes("EN, fr ,xx")
        assertEquals(setOf(LanguageFilter.Lang.EN, LanguageFilter.Lang.FR), set)
    }

    @Test fun untaggedAndPlainTitlesKept() {
        assertTrue(LanguageFilter.isEnglish("Up - Pixar"))
        assertTrue(LanguageFilter.isEnglish("It (2017)"))
        assertTrue(LanguageFilter.isEnglish("Documentaries"))
    }

    @Test fun compoundAndDecoratedPrefixesAreRead() {
        for (n in listOf("LATIN-BR| DISNEY+ 041", "24/7-ES| I Am a Stalker", "FR-CAR| 6TER", "ASIA-IN| Hub Sports 3",
            "✦| FR DAZN NBA |✦", "✦●✦ |RO| CINEMA✦●✦", "24/7-AR| KIDS", "24/7-TR| Kardeş Çocukları")) {
            assertFalse(n, LanguageFilter.isEnglish(n))
        }
        for (n in listOf("24/7-EN - GOTHAM", "US| ESPN HD", "UK| BBC One", "CA| TSN1", "AU| Fox Sports", "IE| RTE One")) {
            assertTrue(n, LanguageFilter.isEnglish(n))
        }
    }

    @Test fun regionFeedSuffixesAreReadAsMarkers() {
        assertTrue(LanguageFilter.isEnglish("beIN SPORTS 1 ASIA - NZ"))
        assertFalse(LanguageFilter.isEnglish("beIN SPORTS 1 ASIA - IN"))
        assertFalse(LanguageFilter.isEnglish("beIN SPORTS 1 LATIN - BR"))
        assertEquals(LanguageFilter.Lang.EN, LanguageFilter.detect("beIN SPORTS 1 ASIA - NZ"))
    }

    @Test fun countryMarkersThatWereMissingAreHidden() {
        for (c in listOf("SW", "SH", "IC", "SC", "SU", "MY", "DO", "CO", "ARG", "UY", "SRB", "LV", "LT")) {
            assertFalse(c, LanguageFilter.isEnglish("$c| Some Channel HD"))
        }
    }

    @Test fun englishTitlesStartingWithACountryWordAreKept() {
        assertTrue(LanguageFilter.isEnglish("IT Crowd | Season 1"))
        assertTrue(LanguageFilter.isEnglish("No Country for Old Men"))
        assertTrue(LanguageFilter.isEnglish("CAR| CNN FHD"))
        assertTrue(LanguageFilter.isEnglish("PPV| FLO SPORTS TV 40 [EVENT ONLY]"))
    }}
