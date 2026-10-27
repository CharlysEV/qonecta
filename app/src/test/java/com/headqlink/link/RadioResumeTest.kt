package com.headqlink.link

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** «Seguir con la radio al conectar»: lo que se guarda al cerrar y lo que vuelve en la sesión siguiente. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class RadioResumeTest {
    private fun station(i: Int) = RadioPlayer.Station().apply {
        name = "Emisora $i"
        url = "https://radio.example/$i"
        group = "pop"
    }

    private fun song(i: Int) = RadioPlayer.Station().apply {
        name = "Canción $i"
        url = "content://media/external/audio/media/$i"
        group = "Artista"
        album = "Álbum"
        durMs = 200_000
        local = true
    }

    @Test
    fun aStationComesBackWithItsListAndWithoutPosition() {
        val list = (0 until 5).map { station(it) }
        val r = RadioPlayer.resumeFrom(RadioPlayer.resumeJson(list, 3, 99_000))!!
        assertEquals(5, r.list.size)
        assertEquals(3, r.index)
        assertEquals("Emisora 3", r.list[r.index].name)
        assertEquals("https://radio.example/3", r.list[r.index].url)
        // La radio en directo vuelve al directo, no a una posición.
        assertEquals(0, r.posMs)
    }

    @Test
    fun aSongComesBackWhereItWas() {
        val list = (0 until 3).map { song(it) }
        val r = RadioPlayer.resumeFrom(RadioPlayer.resumeJson(list, 1, 83_500))!!
        val s = r.list[r.index]
        assertTrue(s.local)
        assertEquals("Canción 1", s.name)
        assertEquals("Álbum", s.album)
        assertEquals(200_000, s.durMs)
        assertEquals(83_500, r.posMs)
    }

    @Test
    fun aLongListKeepsAWindowAroundTheOnePlaying() {
        val list = (0 until 1000).map { station(it) }
        val r = RadioPlayer.resumeFrom(RadioPlayer.resumeJson(list, 700, 0))!!
        assertEquals(RadioPlayer.RESUME_MAX, r.list.size)
        assertEquals("Emisora 700", r.list[r.index].name)
        // Cerca del final: la ventana no se sale de la lista.
        val end = RadioPlayer.resumeFrom(RadioPlayer.resumeJson(list, 995, 0))!!
        assertEquals("Emisora 995", end.list[end.index].name)
        assertEquals("Emisora 999", end.list.last().name)
        // Al principio, igual.
        val start = RadioPlayer.resumeFrom(RadioPlayer.resumeJson(list, 2, 0))!!
        assertEquals(2, start.index)
        assertEquals("Emisora 0", start.list.first().name)
    }

    @Test
    fun nothingSavedOrBrokenGivesNothing() {
        assertNull(RadioPlayer.resumeFrom(null))
        assertNull(RadioPlayer.resumeFrom(""))
        assertNull(RadioPlayer.resumeFrom("{roto"))
        assertNull(RadioPlayer.resumeFrom("""{"list":[],"index":0,"pos":0}"""))
        assertNull(RadioPlayer.resumeFrom("""{"list":[{"name":"x","url":""}],"index":0,"pos":0}"""))
    }
}
