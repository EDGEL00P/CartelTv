package com.carteltv.tv.data.xtream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EpgIdExportTest {

    @Test fun idsAreDistinctSortedOnePerLine() {
        val text = EpgIdExport.format(listOf("TSN1.ca", "cnn.us", "CNN.us", " ABC.us ", "", null, "tsn1.CA"))
        assertEquals("ABC.us\ncnn.us\nTSN1.ca\n", text)
        assertEquals(3, EpgIdExport.count(listOf("TSN1.ca", "cnn.us", "CNN.us", " ABC.us ", "", null, "tsn1.CA")))
    }

    @Test fun nothingToExportIsEmpty() {
        assertEquals("", EpgIdExport.format(emptyList()))
        assertEquals(0, EpgIdExport.count(listOf(null, " ", "")))
    }

    @Test fun theFileNeverNamesAServerOrAChannel() {
        // The export takes ids only; a caller cannot pass anything else through it.
        val text = EpgIdExport.format(listOf("a.us"))
        assertFalse(text.contains("http"))
        assertFalse(text.contains("password"))
    }

    @Test fun theOutputFeedsTheCleanerFormat() {
        // clean_epg.py --only-ids reads one id per line, ignores "#" lines and blank lines, and lowercases ids.
        val lines = EpgIdExport.format(listOf("Fox.News.us")).lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(listOf("Fox.News.us"), lines)
    }
}
