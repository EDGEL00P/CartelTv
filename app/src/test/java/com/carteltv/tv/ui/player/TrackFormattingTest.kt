package com.carteltv.tv.ui.player

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackLabelsTest {

    @Test fun languageCodesBecomeFullNames() {
        assertEquals("English", trackLanguage("eng"))
        assertEquals("Spanish", trackLanguage("spa"))
        assertEquals("French", trackLanguage("FR"))
        assertEquals("Portuguese", trackLanguage("pt-BR"))
        assertEquals("XX", trackLanguage("xx"))
        assertEquals(null, trackLanguage(null))
    }

    @Test fun subtitleRowsShowLanguageCodecAndForcedLabel() {
        val info = describeFacts(
            TrackFacts(language = "eng", label = "English (Forced)", codecs = "application/x-subrip"),
            TrackType.SUBTITLE,
        )
        assertEquals("English", info.title)
        assertEquals("English (Forced) · SubRip", info.detail)
        assertEquals(listOf("FORCED"), info.badges)
    }

    @Test fun roleFlagsAreReadAsBadges() {
        val sdh = describeFacts(
            TrackFacts(language = "eng", roleFlags = C.ROLE_FLAG_CAPTION or C.ROLE_FLAG_TRANSCRIBES_DIALOG),
            TrackType.SUBTITLE,
        )
        assertEquals(listOf("SDH"), sdh.badges)

        val commentary = describeFacts(
            TrackFacts(roleFlags = C.ROLE_FLAG_DESCRIBES_VIDEO or C.ROLE_FLAG_COMMENTARY),
            TrackType.AUDIO,
        )
        assertEquals(listOf("AD", "COMMENTARY"), commentary.badges)
    }

    @Test fun audioRowsShowCodecChannelsAndRate() {
        val info = describeFacts(
            TrackFacts(language = "eng", codecs = "ec-3", channelCount = 6, sampleRate = 48_000),
            TrackType.AUDIO,
        )
        assertEquals("English", info.title)
        assertEquals("E-AC3 · 6 ch · 48 kHz", info.detail)
    }

    @Test fun rowsAreSortedByLanguageWithOffFirst() {
        val rows = listOf(
            TrackRow("Spanish", "WebVTT", emptyList(), false, 1, 0),
            TrackRow("English", "SubRip", emptyList(), false, 0, 0),
            TrackRow("Arabic", "SubRip", emptyList(), false, 2, 0),
        )
        val ordered = listOf(TrackRow("Off", "Turn subtitles off", emptyList(), true, -1, -1, true)) + sortRows(rows)
        assertEquals(
            listOf("Off", "Arabic", "English", "Spanish"),
            ordered.map { it.title },
        )
    }

    @Test fun audioCodecLabelsDistinguishDtsVariantsAndMpegObjectTypes() {
        assertEquals("DTS-HD", describeFacts(TrackFacts(codecs = "dtsh"), TrackType.AUDIO).detail.substringBefore(" ·"))
        assertEquals("DTS:X", describeFacts(TrackFacts(codecs = "dtsx"), TrackType.AUDIO).detail.substringBefore(" ·"))
        assertEquals("DTS Express", describeFacts(TrackFacts(codecs = "dtse"), TrackType.AUDIO).detail.substringBefore(" ·"))
        assertEquals("MP3", describeFacts(TrackFacts(codecs = "mp4a.6B"), TrackType.AUDIO).detail.substringBefore(" ·"))
        assertEquals("AAC", describeFacts(TrackFacts(codecs = "mp4a.40.2"), TrackType.AUDIO).detail.substringBefore(" ·"))
    }

    @Test fun audioRowsReadTracksThatCarryOnlyAMimeType() {
        fun detail(mime: String) = describeFacts(TrackFacts(sampleMimeType = mime), TrackType.AUDIO).detail
        assertEquals("MP2", detail("audio/mpeg-L2"))
        assertEquals("DTS-HD", detail("audio/vnd.dts.hd"))
        assertEquals("AC3", detail("audio/ac3"))
    }

    @Test fun subtitleCodecRecognizesStppTtml() {
        val info = describeFacts(TrackFacts(codecs = "stpp"), TrackType.SUBTITLE)
        assertEquals("TTML", info.detail)
    }

    @Test fun recommendedAudioIsViewerLanguageWithMostChannels() {
        val rows = listOf(
            TrackRow("Spanish", "", emptyList(), false, 0, 0, language = "spa", channels = 6),
            TrackRow("English", "", emptyList(), false, 1, 0, language = "eng", channels = 2),
            TrackRow("English", "", listOf("COMMENTARY"), false, 2, 0, language = "eng", channels = 8),
            TrackRow("English", "", emptyList(), false, 3, 0, language = "eng", channels = 6),
        )
        assertEquals(3, recommendedRow(rows, TrackType.AUDIO, "en")?.groupIndex)
    }

    @Test fun recommendedSubtitleSkipsForcedAndIsNullWithoutMatch() {
        val rows = listOf(
            TrackRow("English", "", listOf("FORCED"), false, 0, 0, language = "eng"),
            TrackRow("English", "", listOf("SDH"), false, 1, 0, language = "eng"),
            TrackRow("English", "", emptyList(), false, 2, 0, language = "eng"),
        )
        assertEquals(2, recommendedRow(rows, TrackType.SUBTITLE, "en")?.groupIndex)
        assertEquals(null, recommendedRow(rows, TrackType.SUBTITLE, "de"))
    }}
