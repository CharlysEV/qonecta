package com.headqlink.link

import android.app.Application
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Las emisoras de una lista de radio con usuario (revisión 3-11): en favoritos, recientes y «seguir al conectar» se
 * guardan sin él (marcas e id, como los favoritos de la TV), vuelven con él al leerlas y, con HTTP Basic, guardan su
 * servidor.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class RadioLoginTest {
    private val login = M3uSource.Login("pepe", "s3cr3ta")
    private val logins = RadioLibrary.LoginLookup { id -> if (id == "id1") login else M3uSource.Login.NONE }
    private val none = RadioLibrary.LoginLookup { M3uSource.Login.NONE }

    private fun xtream(name: String = "Rock", n: Int = 7) = RadioPlayer.Station().apply {
        this.name = name
        url = "http://tv.example:8080/live/pepe/s3cr3ta/$n.ts"
        logo = ""
        group = "radio"
        login = this@RadioLoginTest.login
        loginId = "id1"
    }

    private fun plain(name: String, url: String) = RadioPlayer.Station().apply {
        this.name = name
        this.url = url
        logo = ""
        group = "pop"
    }

    @Test
    fun aFavoriteIsSavedWithoutTheLoginAndComesBackWithIt() {
        val o = RadioLibrary.item(xtream())
        assertEquals("http://tv.example:8080/live/{u}/{p}/7.ts", o.getString("url"))
        assertEquals("id1", o.getString("login"))
        assertFalse(o.toString().contains("s3cr3ta"))
        assertFalse(o.has("auth"))

        val back = RadioLibrary.stations(JSONArray().put(o), logins).single()
        assertEquals("http://tv.example:8080/live/pepe/s3cr3ta/7.ts", back.url)
        assertEquals("id1", back.loginId)
        assertEquals("pepe", back.login.user)
    }

    @Test
    fun theStarFindsAndRemovesTheSavedOne() {
        val favs = JSONArray()
        assertTrue(Favorites.toggle(favs, RadioLibrary.item(xtream())))
        assertTrue(Favorites.contains(favs, RadioLibrary.storedUrl(xtream())))
        // Desde la pestaña ★ (leída de lo guardado) se quita.
        val fromFavs = RadioLibrary.stations(favs, logins).single()
        assertFalse(Favorites.toggle(favs, RadioLibrary.item(fromFavs)))
        assertEquals(0, favs.length())
    }

    @Test
    fun withoutItsLoginTheMarksStayAndItCanStillBeRemoved() {
        val favs = JSONArray().put(RadioLibrary.item(xtream()))
        val orphan = RadioLibrary.stations(favs, none).single()
        assertEquals("http://tv.example:8080/live/{u}/{p}/7.ts", orphan.url)
        assertFalse(Favorites.toggle(favs, RadioLibrary.item(orphan)))
        assertEquals(0, favs.length())
    }

    @Test
    fun httpBasicKeepsItsServerAndNoHeader() {
        val s = plain("Basic", "http://radio.example/stream.mp3").apply {
            login = this@RadioLoginTest.login
            loginId = "id1"
            authHost = "radio.example"
        }
        val o = RadioLibrary.item(s)
        assertEquals("http://radio.example/stream.mp3", o.getString("url"))
        assertEquals("radio.example", o.getString("auth"))
        assertFalse(o.toString().contains("pepe"))
        val back = RadioLibrary.stations(JSONArray().put(o), logins).single()
        assertEquals("radio.example", back.authHost)
        assertEquals("s3cr3ta", back.login.pass)
    }

    @Test
    fun aStationWithoutLoginIsSavedAsBefore() {
        val o = RadioLibrary.item(plain("Libre", "https://r.example/s"))
        assertEquals("https://r.example/s", o.getString("url"))
        assertFalse(o.has("login"))
        val back = RadioLibrary.stations(JSONArray().put(o), logins).single()
        assertEquals("", back.loginId)
        assertNull(back.authHost)
    }

    @Test
    fun recentsAreSavedWithoutTheLoginAndDoNotRepeat() {
        var recent = RadioLibrary.withRecent(JSONArray(), xtream(), 30)
        recent = RadioLibrary.withRecent(recent, plain("Otra", "https://r.example/o"), 30)
        recent = RadioLibrary.withRecent(recent, xtream(), 30)
        assertEquals(listOf("Rock", "Otra"), RadioLibrary.stations(recent, logins).map { it.name })
        assertFalse(recent.toString().contains("s3cr3ta"))
        assertEquals("http://tv.example:8080/live/pepe/s3cr3ta/7.ts", RadioLibrary.stations(recent, logins)[0].url)
    }

    @Test
    fun oldRecentsWithTheLoginInsideAreHidden() {
        // Guardadas por la 0.3.14: la URL con el usuario dentro y sin id.
        val old = RadioLibrary.withRecent(JSONArray(), plain("Rock", "http://tv.example:8080/live/pepe/s3cr3ta/7.ts"), 30)
        assertEquals(1, M3uSource.hideInFavorites(old, login, "id1"))
        assertFalse(old.toString().contains("s3cr3ta"))
        assertEquals("http://tv.example:8080/live/pepe/s3cr3ta/7.ts", RadioLibrary.stations(old, logins).single().url)
    }

    @Test
    fun resumeIsSavedWithoutTheLoginAndComesBackWithIt() {
        val list = listOf(plain("Libre", "https://r.example/s"), xtream("Rock", 7), xtream("Jazz", 8))
        val json = RadioPlayer.resumeJson(list, 1, 0)!!
        assertFalse(json.contains("s3cr3ta"))
        val r = RadioPlayer.resumeFrom(json, logins)!!
        assertEquals(1, r.index)
        assertEquals("http://tv.example:8080/live/pepe/s3cr3ta/7.ts", r.list[1].url)
        assertEquals("http://tv.example:8080/live/pepe/s3cr3ta/8.ts", r.list[2].url)
        assertEquals("https://r.example/s", r.list[0].url)
        // Sin el usuario (otro móvil), las marcas: un error al ponerla, no otra emisora.
        assertEquals("http://tv.example:8080/live/{u}/{p}/7.ts", RadioPlayer.resumeFrom(json)!!.list[1].url)
    }
}
