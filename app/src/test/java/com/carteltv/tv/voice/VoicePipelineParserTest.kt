package com.carteltv.tv.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePipelineParserTest {

    private fun p(s: String) = VoiceParser.parse(s)

    @Test fun sectionsWithAndWithoutVerb() {
        assertEquals(VoiceIntent.OpenSection(Section.SPORTS), p("sports"))
        assertEquals(VoiceIntent.OpenSection(Section.YOUTUBE), p("Open YouTube"))
        assertEquals(VoiceIntent.OpenSection(Section.GUIDE), p("take me to the TV guide"))
        assertEquals(VoiceIntent.OpenSection(Section.SETTINGS), p("hey cartel please open settings"))
        assertEquals(VoiceIntent.OpenSection(Section.HOME), p("go home"))
    }

    @Test fun unknownNameAfterOpenIsAnApp() {
        assertEquals(VoiceIntent.OpenApp("netflix"), p("launch Netflix"))
        assertEquals(VoiceIntent.OpenApp("plex"), p("open the plex"))
    }

    @Test fun searchAndChannel() {
        assertEquals(VoiceIntent.Search("the office"), p("search for The Office"))
        assertEquals(VoiceIntent.Search("dune"), p("show me dune"))
        assertEquals(VoiceIntent.WatchChannel("espn"), p("watch ESPN"))
        assertEquals(VoiceIntent.WatchChannel("bbc one"), p("tune in to BBC One"))
    }

    @Test fun playSomethingSearchesAndCompanionOpens() {
        assertEquals(VoiceIntent.Search("stranger things"), p("play stranger things"))
        assertEquals(VoiceIntent.ShowCompanion, p("connect my phone"))
        assertEquals(VoiceIntent.ShowCompanion, p("show QR code"))
        assertEquals(VoiceIntent.ShowCompanion, p("phone remote"))
    }

    @Test fun mediaControls() {
        assertEquals(VoiceIntent.Media(Transport.PAUSE), p("pause"))
        assertEquals(VoiceIntent.Media(Transport.PLAY), p("Resume."))
        assertEquals(VoiceIntent.Media(Transport.NEXT), p("next episode"))
    }

    @Test fun volume() {
        assertEquals(VoiceIntent.Volume(VolumeChange.Up), p("turn it up"))
        assertEquals(VoiceIntent.Volume(VolumeChange.Down), p("volume down"))
        assertEquals(VoiceIntent.Volume(VolumeChange.Mute), p("mute"))
        assertEquals(VoiceIntent.Volume(VolumeChange.Set(30)), p("set volume to 30 percent"))
        assertEquals(VoiceIntent.Volume(VolumeChange.Set(100)), p("volume 250"))
    }

    @Test fun sleepTimerBeatsHomeAutomation() {
        assertEquals(VoiceIntent.Sleep(30), p("sleep in 30 minutes"))
        assertEquals(VoiceIntent.Sleep(120), p("turn off in two hours"))
        assertEquals(VoiceIntent.Sleep(30), p("go to sleep in half an hour"))
        assertEquals(VoiceIntent.Sleep(null), p("cancel sleep timer"))
        assertEquals(VoiceIntent.Sleep(null), p("turn off the sleep timer"))
    }

    @Test fun gibberishIsUnknownNotASearch() {
        assertTrue(p("purple monkey dishwasher") is VoiceIntent.Unknown)
        assertTrue(p("   ") is VoiceIntent.Unknown)
    }

    @Test fun fuzzyMatchPrefersExactThenShorter() {
        val names = listOf("YouTube Music", "YouTube", "Plex", "Prime Video")
        assertEquals(1, VoiceMatch.best("youtube", names))
        assertEquals(2, VoiceMatch.best("plex", names))
        assertEquals(3, VoiceMatch.best("prime", names))
        assertNull(VoiceMatch.best("hulu", names))
        assertNull(VoiceMatch.best("", names))
    }
}
