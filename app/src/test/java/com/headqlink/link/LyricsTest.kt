package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Letras del karaoke: el formato LRC, la línea que toca, los títulos limpios y la canción elegida en LRCLIB. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = android.app.Application::class)
class LyricsTest {
    @Test
    fun lrcLinesAreReadInOrderWithEveryTimestampAndTheOffset() {
        val l = Lyrics.parseLrc("""
            [ar:Alguien]
            [ti:Una canción]
            [00:12.50] Primera línea
            [00:05.1]Antes de todo
            [01:02.345][02:10.00] Estribillo
            [00:20.00]
            sin tiempo
        """.trimIndent())
        assertEquals(listOf(5100L, 12500L, 20000L, 62345L, 130000L), l.map { it.ms })
        assertEquals(listOf("Antes de todo", "Primera línea", "", "Estribillo", "Estribillo"), l.map { it.text })
        // [offset:+500]: todo medio segundo antes.
        val o = Lyrics.parseLrc("[offset:+500]\n[00:10.00] a\n[00:00.20] b")
        assertEquals(listOf(0L, 9500L), o.map { it.ms })
        assertTrue(Lyrics.parseLrc(null).isEmpty())
    }

    @Test
    fun theLineThatPlaysIsTheLastOneThatAlreadyStarted() {
        val l = Lyrics.parseLrc("[00:01.00] a\n[00:03.00] b\n[00:06.00] c")
        assertEquals(-1, Lyrics.indexAt(l, 500))
        assertEquals(0, Lyrics.indexAt(l, 1000))
        assertEquals(0, Lyrics.indexAt(l, 2999))
        assertEquals(1, Lyrics.indexAt(l, 3000))
        assertEquals(2, Lyrics.indexAt(l, 600_000))
        assertEquals(-1, Lyrics.indexAt(emptyList(), 1000))
        assertEquals("♪", KaraokeScreen.lineText(Lyrics.Line(0, "")))
    }

    @Test
    fun titlesAndArtistsAreCleanedForTheSearch() {
        assertEquals("Here Comes the Sun", Lyrics.cleanTitle("Here Comes the Sun - Remastered 2009"))
        assertEquals("Despacito", Lyrics.cleanTitle("Despacito (feat. Daddy Yankee)"))
        assertEquals("Mediterráneo", Lyrics.cleanTitle("Mediterráneo - En Directo"))
        assertEquals("Hotel California", Lyrics.cleanTitle("Hotel California - 2013 Remaster"))
        assertEquals("Bohemian Rhapsody", Lyrics.cleanTitle("Bohemian Rhapsody"))
        assertEquals("Luis Fonsi", Lyrics.mainArtist("Luis Fonsi, Daddy Yankee"))
        assertEquals("Simon & Garfunkel", Lyrics.mainArtist("Simon & Garfunkel"))
        assertEquals("Andy y Lucas", Lyrics.mainArtist("Andy y Lucas"))
        // Lo que Spotify añade detrás del artista (visto en el móvil el 24-10).
        assertEquals("Eternelles", Lyrics.stripBadge("Eternelles • Audio sin pérdida"))
        assertEquals("Mecano", Lyrics.stripBadge("Mecano"))
        val u = Lyrics.getUrl("Simon & Garfunkel", "The Boxer", "Bridge over Troubled Water", 308)
        assertEquals("https://lrclib.net/api/get?artist_name=Simon%20%26%20Garfunkel&track_name=The%20Boxer"
            + "&album_name=Bridge%20over%20Troubled%20Water&duration=308", u)
    }

    @Test
    fun fromASearchTheClosestDurationWinsAndSyncedBeatsPlainText() {
        val json = """[
            {"id":1,"duration":229.0,"instrumental":false,"plainLyrics":"solo texto","syncedLyrics":null},
            {"id":2,"duration":231.0,"instrumental":false,"plainLyrics":"texto","syncedLyrics":"[00:01.00] sincronizada"},
            {"id":3,"duration":273.0,"instrumental":false,"plainLyrics":"otra","syncedLyrics":"[00:01.00] otra versión"},
            {"id":4,"duration":228.0,"instrumental":false,"plainLyrics":null,"syncedLyrics":null}
        ]"""
        val r = Lyrics.pick(json, 228)
        assertEquals("sincronizada", r.synced[0].text)
        // Ninguna con una duración parecida: nada.
        assertFalse(Lyrics.pick(json, 400).found())
        // Instrumental: se encuentra, sin letra.
        val i = Lyrics.pick("""[{"duration":200.0,"instrumental":true,"plainLyrics":null,"syncedLyrics":null}]""", 199)
        assertTrue(i.found() && i.instrumental && i.synced.isEmpty())
    }

    @Test
    fun thePositionAdvancesWithTheClockOnlyWhilePlaying() {
        assertEquals(10_000L, KaraokeScreen.position(10_000, 1_000, 1f, false, 5_000))
        assertEquals(14_000L, KaraokeScreen.position(10_000, 1_000, 1f, true, 5_000))
        assertEquals(18_000L, KaraokeScreen.position(10_000, 1_000, 2f, true, 5_000))
        assertEquals(-1L, KaraokeScreen.position(-1, 1_000, 1f, true, 5_000))
    }
}
