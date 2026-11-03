package com.headqlink.link

import android.app.Application
import android.content.Context
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Los favoritos de radio editados en el móvil (foro #342, RadioFavsEditor): mover cambia el orden guardado y el que ven
 * el coche y Android Auto; renombrar no toca la URL, el usuario ni su servidor (y vacío vuelve al nombre de su lista);
 * quitar quita solo esa.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class RadioFavsEditTest {
    private val app: Context = RuntimeEnvironment.getApplication().also { Str.init(it) }
    private val login = M3uSource.Login("pepe", "s3cr3ta")
    private val logins = RadioLibrary.LoginLookup { id -> if (id == "id1") login else M3uSource.Login.NONE }

    private fun plain(name: String, n: Int) = RadioPlayer.Station().apply {
        this.name = name
        url = "https://r$n.example/stream"
        logo = ""
        group = "pop"
    }

    private fun xtream() = RadioPlayer.Station().apply {
        name = "Rock"
        url = "http://tv.example:8080/live/pepe/s3cr3ta/7.ts"
        logo = "https://logo.example/rock.png"
        group = "radio"
        login = this@RadioFavsEditTest.login
        loginId = "id1"
    }

    private fun basic() = RadioPlayer.Station().apply {
        name = "Basic"
        url = "http://radio.example/stream.mp3"
        logo = ""
        group = "pop"
        login = this@RadioFavsEditTest.login
        loginId = "id2"
        authHost = "radio.example"
    }

    private fun names(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it).getString("name") }

    private fun aaTitles() = RadioLibrary.children(app, RadioLibrary.FAVS, true).map { it.description.title.toString() }

    @Before
    fun clean() {
        Favorites.save(Config(app), Favorites.RADIO, JSONArray())
    }

    @Test
    fun movingChangesTheSavedOrderAndTheOneAndroidAutoSees() {
        val favs = JSONArray()
        for (i in 1..4) favs.put(RadioLibrary.item(plain("Radio $i", i)))
        Favorites.save(Config(app), Favorites.RADIO, favs)
        assertEquals(listOf("Radio 1", "Radio 2", "Radio 3", "Radio 4"), aaTitles())

        // La última, arriba (arrastrar hacia arriba); luego la segunda, abajo del todo.
        Favorites.move(favs, 3, 0)
        assertEquals(listOf("Radio 4", "Radio 1", "Radio 2", "Radio 3"), names(favs))
        Favorites.move(favs, 1, 3)
        assertEquals(listOf("Radio 4", "Radio 2", "Radio 3", "Radio 1"), names(favs))
        Favorites.save(Config(app), Favorites.RADIO, favs)

        // Lo guardado, la pestaña ★ del coche (RadioLibrary.favorites) y Android Auto, en el orden nuevo.
        assertEquals(listOf("Radio 4", "Radio 2", "Radio 3", "Radio 1"), names(Favorites.load(Config(app), Favorites.RADIO)))
        assertEquals(listOf("Radio 4", "Radio 2", "Radio 3", "Radio 1"), RadioLibrary.favorites(app).map { it.name })
        assertEquals(listOf("Radio 4", "Radio 2", "Radio 3", "Radio 1"), aaTitles())
        // «▶» en Android Auto: el id sigue llevando a la misma emisora (va por la URL, no por el puesto).
        val id = RadioLibrary.children(app, RadioLibrary.FAVS, true)[0].mediaId!!
        assertEquals("https://r4.example/stream", RadioLibrary.favorites(app)[RadioLibrary.indexOf(RadioLibrary.favorites(app), id)].url)

        // Fuera de rango o al mismo sitio, nada.
        Favorites.move(favs, 0, 4)
        Favorites.move(favs, -1, 2)
        Favorites.move(favs, 2, 2)
        assertEquals(listOf("Radio 4", "Radio 2", "Radio 3", "Radio 1"), names(favs))
    }

    @Test
    fun renamingKeepsUrlLoginAndAuthAndEmptyGoesBack() {
        val favs = JSONArray().put(RadioLibrary.item(xtream())).put(RadioLibrary.item(basic()))
        val before = favs.getJSONObject(0).toString()
        val rock = favs.getJSONObject(0)

        Favorites.rename(rock, "  Mi rock  ")
        assertEquals("Mi rock", rock.getString("name"))
        assertTrue(Favorites.renamed(rock))
        assertEquals("Rock", Favorites.originalName(rock))
        // La URL guardada (con las marcas), el id del usuario, el logo y el grupo, igual; la contraseña, en ningún sitio.
        assertEquals("http://tv.example:8080/live/{u}/{p}/7.ts", rock.getString("url"))
        assertEquals("id1", rock.getString("login"))
        assertEquals("https://logo.example/rock.png", rock.getString("logo"))
        assertEquals("radio", rock.getString("group"))
        assertFalse(favs.toString().contains("s3cr3ta"))
        assertFalse(favs.toString().contains("pepe"))
        // Sigue siendo la misma favorita: la ★ del coche la encuentra y suena con su usuario.
        assertTrue(Favorites.contains(favs, RadioLibrary.storedUrl(xtream())))
        val back = RadioLibrary.stations(favs, logins)[0]
        assertEquals("Mi rock", back.name)
        assertEquals("http://tv.example:8080/live/pepe/s3cr3ta/7.ts", back.url)

        // HTTP Basic: su servidor se queda.
        val b = favs.getJSONObject(1)
        Favorites.rename(b, "Otra")
        assertEquals("radio.example", b.getString("auth"))
        assertEquals("id2", b.getString("login"))
        assertEquals("http://radio.example/stream.mp3", b.getString("url"))

        // Renombrar otra vez guarda el de su lista, no el anterior; vacío (o el suyo) vuelve a él y quita la marca.
        Favorites.rename(rock, "Rock 2")
        assertEquals("Rock", Favorites.originalName(rock))
        Favorites.rename(rock, "   ")
        assertEquals("Rock", rock.getString("name"))
        assertFalse(Favorites.renamed(rock))
        assertEquals(before, rock.toString())
        Favorites.rename(b, "Basic")
        assertFalse(Favorites.renamed(b))

        // Guardado, Android Auto lo ve con el nombre nuevo.
        Favorites.rename(favs.getJSONObject(1), "La de casa")
        Favorites.save(Config(app), Favorites.RADIO, JSONArray().put(RadioLibrary.item(plain("Uno", 1))).put(favs.getJSONObject(1)))
        assertEquals(listOf("Uno", "La de casa"), aaTitles())
    }

    @Test
    fun removingRemovesOnlyThatOne() {
        val favs = JSONArray()
        for (i in 1..3) favs.put(RadioLibrary.item(plain("Radio $i", i)))
        favs.put(RadioLibrary.item(xtream()))
        Favorites.removeAt(favs, 1)
        assertEquals(listOf("Radio 1", "Radio 3", "Rock"), names(favs))
        assertEquals("id1", favs.getJSONObject(2).getString("login"))
        Favorites.removeAt(favs, 7)
        Favorites.removeAt(favs, -1)
        assertEquals(3, favs.length())
        Favorites.save(Config(app), Favorites.RADIO, favs)
        assertEquals(listOf("Radio 1", "Radio 3", "Rock"), aaTitles())
        assertFalse(Favorites.contains(Favorites.load(Config(app), Favorites.RADIO), "https://r2.example/stream"))
    }

    @Test
    fun theSecondLineShowsTheServerNotTheUrl() {
        val rock = RadioLibrary.item(xtream())
        assertEquals("radio · tv.example", RadioFavsEditor.detail(rock))
        Favorites.rename(rock, "Mi rock")
        assertEquals("Rock · radio · tv.example", RadioFavsEditor.detail(rock))
        // Una vieja con el usuario dentro de la URL (antes de la 0.3.14): tampoco sale.
        val old = Favorites.item("Vieja", "http://pepe:s3cr3ta@old.example/s", "", "", null)
        assertEquals("old.example", RadioFavsEditor.detail(old))
    }
}
