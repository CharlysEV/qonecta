package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Calidad fija de YouTube en la pantalla Web. */
class YouTubeQualityTest {
    @Test
    fun theMenuCyclesThroughTheChoices() {
        assertEquals(360, YouTubeQuality.next(0))
        assertEquals(480, YouTubeQuality.next(360))
        assertEquals(720, YouTubeQuality.next(480))
        assertEquals(0, YouTubeQuality.next(720))
        assertEquals(0, YouTubeQuality.next(1080))
    }

    @Test
    fun onlyYouTubePages() {
        for (h in listOf("youtube.com", "m.youtube.com", "www.youtube.com", "music.youtube.com", "youtu.be")) {
            assertTrue(h, YouTubeQuality.isYouTube(h))
        }
        for (h in listOf("notyoutube.com", "youtube.com.evil.org", "google.com", "", null)) assertFalse(YouTubeQuality.isYouTube(h))
    }

    @Test
    fun theScriptAsksThePlayerForTheRightLevel() {
        assertEquals("medium", YouTubeQuality.level(360))
        assertEquals("large", YouTubeQuality.level(480))
        assertEquals("hd720", YouTubeQuality.level(720))
        val js = YouTubeQuality.js(360)
        assertTrue(js.contains("setPlaybackQualityRange") && js.contains("movie_player") && js.endsWith("})(360)"))
        // Se puede volver a poner: cambia la calidad sin crear otro temporizador.
        assertTrue(js.contains("if(window.__hqlYtqT)return"))
        assertTrue(YouTubeQuality.js(0).endsWith("})(0)"))
    }
}
