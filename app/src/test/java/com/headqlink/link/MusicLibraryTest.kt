package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Test

/** Mi música (Radio): lo que se enseña de cada canción. */
class MusicLibraryTest {
    @Test
    fun titleFallsBackToTheFileName() {
        assertEquals("Mediterráneo", MusicLibrary.title(" Mediterráneo ", "x.mp3"))
        assertEquals("01 - Pista", MusicLibrary.title("", "01 - Pista.flac"))
        assertEquals("sin extensión", MusicLibrary.title(null, "sin extensión"))
    }

    @Test
    fun unknownArtistIsHidden() {
        assertEquals("", MusicLibrary.artist("<unknown>"))
        assertEquals("", MusicLibrary.artist(null))
        assertEquals("Serrat", MusicLibrary.artist("Serrat "))
    }

    @Test
    fun theLineHasArtistAndDuration() {
        val s = RadioPlayer.Station()
        s.group = "Serrat"
        s.durMs = 225_000
        assertEquals("Serrat · 3:45", MusicLibrary.line(s))
        s.group = ""
        assertEquals("3:45", MusicLibrary.line(s))
        s.durMs = 0
        assertEquals("", MusicLibrary.line(s))
    }
}
