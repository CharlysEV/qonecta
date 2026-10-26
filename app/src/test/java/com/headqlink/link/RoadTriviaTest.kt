package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.Locale
import java.util.Random

/** El trivial del camino: preguntas con los sitios de la Guía. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "es-rES")
class RoadTriviaTest {
    init {
        Str.init(RuntimeEnvironment.getApplication())
    }

    private val es = Locale.forLanguageTag("es-ES")

    private fun place(id: Long, title: String, dLatKm: Double, type: String = "", views: Int = 500, text: String = "") =
        RoadGuide.Place(id, title, 40.0 + dLatKm / 111.2, -3.0).also {
            it.type = type
            it.views = views
            it.extract = text
            it.detailed = true
            it.bytes = 20000
        }

    @Test
    fun centuriesInEachLanguage() {
        assertEquals(12, RoadTrivia.centuryIn("Fortaleza levantada en el siglo XII sobre un cerro.", es))
        assertEquals(14, RoadTrivia.centuryIn("Built in the 14th century by the Order.", Locale.ENGLISH))
        assertEquals(12, RoadTrivia.centuryIn("Château fort du XIIe siècle.", Locale.FRENCH))
        assertEquals(13, RoadTrivia.centuryIn("Eine Burg aus dem 13. Jahrhundert.", Locale.GERMAN))
        assertEquals(15, RoadTrivia.centuryIn("Castello del XV secolo.", Locale.ITALIAN))
        assertEquals(16, RoadTrivia.centuryIn("Igreja do século XVI.", Locale.forLanguageTag("pt-PT")))
        assertEquals(11, RoadTrivia.centuryIn("Een kerk uit de 11e eeuw.", Locale.forLanguageTag("nl")))
        assertEquals(0, RoadTrivia.centuryIn("Un embalse del río Tajo.", es))
        assertEquals("siglo XIV", RoadTrivia.centuryLabel(14, es))
        assertEquals("21st century", RoadTrivia.centuryLabel(21, Locale.ENGLISH))
        assertEquals("XIIe siècle", RoadTrivia.centuryLabel(12, Locale.FRENCH))
        assertEquals(19, RoadTrivia.fromRoman("XIX"))
        assertEquals("XIX", RoadTrivia.toRoman(19))
    }

    @Test
    fun aCenturyQuestionHasTheRightOneAmongThree() {
        val castle = place(1, "Castillo de Ejemplo", 3.0, "landmark", text = "Fortaleza del siglo XII.")
        val q = RoadTrivia.make(RoadTrivia.Kind.CENTURY, listOf(castle), 40.0, -3.0, HashSet(), Random(1), es)!!
        assertEquals("¿De qué siglo es «Castillo de Ejemplo»?", q.text)
        assertEquals(3, q.options.size)
        assertEquals("siglo XII", q.options[q.answer])
        assertEquals(1L, q.placeId)
        // Ya preguntado: no se repite.
        assertNull(RoadTrivia.make(RoadTrivia.Kind.CENTURY, listOf(castle), 40.0, -3.0, hashSetOf(1L), Random(1), es))
    }

    @Test
    fun whatNearestAndFamous() {
        val pool = listOf(place(1, "Castillo", 2.0, "landmark", 3000), place(2, "Embalse", 6.0, "waterbody", 400),
            place(3, "Sierra", 12.0, "mountain", 100))
        val what = RoadTrivia.make(RoadTrivia.Kind.WHAT, pool, 40.0, -3.0, HashSet(), Random(2), es)!!
        assertTrue(what.text.startsWith("¿Qué es «"))
        val near = RoadTrivia.make(RoadTrivia.Kind.NEAREST, pool, 40.0, -3.0, HashSet(), Random(3), es)!!
        assertEquals("Castillo", near.options[near.answer])
        val famous = RoadTrivia.make(RoadTrivia.Kind.FAMOUS, pool, 40.0, -3.0, HashSet(), Random(4), es)!!
        assertEquals("Castillo", famous.options[famous.answer])
        // Con menos de tres sitios no hay «más cerca».
        assertNull(RoadTrivia.make(RoadTrivia.Kind.NEAREST, pool.take(2), 40.0, -3.0, HashSet(), Random(3), es))
        assertNotNull(RoadTrivia.next(pool, 40.0, -3.0, HashSet(), Random(5), es))
        assertNull(RoadTrivia.next(emptyList(), 40.0, -3.0, HashSet(), Random(5), es))
    }
}
