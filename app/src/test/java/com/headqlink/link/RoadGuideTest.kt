package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.Locale

/**
 * Guía del camino: lo que se lee de Wikipedia, qué merece la pena, qué queda por delante, qué se cuenta y cómo se
 * acorta. Sitios inventados (con org.json: Robolectric).
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = android.app.Application::class)
class RoadGuideTest {
    /** 1 km al norte y al este, en grados, a la latitud 40. */
    private val kmLat = 1 / 111.2
    private val kmLon = 1 / (111.2 * Math.cos(Math.toRadians(40.0)))

    private fun place(id: Long, title: String, dLatKm: Double, dLonKm: Double, views: Int, text: String = "Un sitio. Con historia.") =
        RoadGuide.Place(id, title, 40.0 + dLatKm * kmLat, -3.0 + dLonKm * kmLon).also {
            it.views = views
            it.bytes = 20000
            it.extract = text
            it.detailed = true
        }

    @Test
    fun theSearchIsReadWithViewsPhotoAndTypeAndPagesWithoutCoordinatesAreLeftOut() {
        val json = """{"batchcomplete":true,"query":{"pages":[
            {"pageid":101,"ns":0,"title":"Castillo de Ejemplo","coordinates":[{"lat":40.5,"lon":-3.0,"primary":true,"type":"landmark"}],
             "thumbnail":{"source":"https://upload.wikimedia.org/x.jpg","width":500,"height":375},
             "pageviews":{"2026-10-01":200,"2026-10-02":null,"2026-10-03":150},"length":24000},
            {"pageid":102,"ns":0,"title":"Autovía del Ejemplo","coordinates":[{"lat":40.51,"lon":-3.01,"type":"landmark"}],
             "pageviews":{"2026-10-01":5000},"length":30000},
            {"pageid":103,"ns":0,"title":"Sin coordenadas","length":100},
            {"pageid":104,"ns":0,"title":"Villaejemplo","coordinates":[{"lat":40.52,"lon":-3.02,"type":"city(3000)"}],"length":9000}
        ]}}"""
        val l = RoadGuide.parseSearch(json)
        assertEquals(listOf(101L, 102L, 104L), l.map { it.id })
        val castle = l[0]
        assertEquals(350, castle.views)
        assertEquals("https://upload.wikimedia.org/x.jpg", castle.thumb)
        assertEquals(24000, castle.bytes)
        assertEquals("landmark", castle.type)
        // Sin visitas en la respuesta: -1 (no 0), y el tipo sin sus datos.
        assertEquals(-1, l[2].views)
        assertEquals("city", RoadGuide.baseType(l[2].type))
        assertTrue(RoadGuide.parseSearch("""{"batchcomplete":true}""").isEmpty())
    }

    @Test
    fun summariesAndVisitsArriveInBatchesAndEachBatchOnlyFillsWhatItBrings() {
        val castle = RoadGuide.Place(101, "Castillo de Ejemplo", 40.5, -3.0)
        val village = RoadGuide.Place(104, "Villaejemplo", 40.52, -3.02)
        val byId = mapOf(101L to castle, 104L to village)
        // Primera tanda: los dos resúmenes y las visitas de uno; Wikipedia dice cómo seguir.
        val first = """{"continue":{"pvipcontinue":"Villaejemplo","continue":"||extracts"},"query":{"pages":[
            {"pageid":101,"title":"Castillo de Ejemplo","extract":" Es un castillo. ","pageviews":{"2026-10-01":300,"2026-10-02":75}},
            {"pageid":104,"title":"Villaejemplo","extract":"Es un pueblo."}]}}"""
        RoadGuide.applyDetails(first, byId)
        assertEquals("Es un castillo.", castle.extract)
        assertEquals(375, castle.views)
        assertEquals(-1, village.views)
        assertEquals("&pvipcontinue=Villaejemplo&continue=%7C%7Cextracts", RoadGuide.continueParams(first))
        // Segunda: solo las visitas que faltaban; los resúmenes se quedan.
        val second = """{"batchcomplete":true,"query":{"pages":[{"pageid":104,"title":"Villaejemplo","pageviews":{"2026-10-01":90}},
            {"pageid":101,"title":"Castillo de Ejemplo"}]}}"""
        RoadGuide.applyDetails(second, byId)
        assertEquals(90, village.views)
        assertEquals("Es un pueblo.", village.extract)
        assertEquals(375, castle.views)
        assertEquals(null, RoadGuide.continueParams(second))
    }

    @Test
    fun roadsStreetsAndStationsAreNotPlacesToSeeAndFewVisitsAreNotShown() {
        val castle = place(1, "Castillo de Ejemplo", 0.0, 0.0, 350)
        assertTrue(RoadGuide.worth(castle))
        assertTrue(RoadGuide.sayable(castle))
        for (t in listOf("Autovía del Ejemplo", "A-2", "AP-7", "N-II", "Estación de Ejemplo", "Calle Mayor (Ejemplo)",
                "Anexo:Monumentos de Ejemplo", "Bahnhof Beispiel", "Gare de Exemple")) {
            assertFalse(t, RoadGuide.worth(place(2, t, 0.0, 0.0, 5000)))
        }
        assertFalse(RoadGuide.worth(place(3, "Rincón poco visitado", 0.0, 0.0, 10)))
        assertFalse(RoadGuide.worth(place(4, "Provincia de Ejemplo", 0.0, 0.0, 9000).also { it.type = "adm2nd" }))
        // Se enseña pero no se cuenta: pocas visitas o sin resumen.
        assertTrue(RoadGuide.worth(place(5, "Ermita de Ejemplo", 0.0, 0.0, 120)))
        assertFalse(RoadGuide.sayable(place(5, "Ermita de Ejemplo", 0.0, 0.0, 120)))
        assertFalse(RoadGuide.sayable(place(6, "Puente de Ejemplo", 0.0, 0.0, 900, "")))
        // Más visitas, foto y monumento: más arriba.
        val sight = place(7, "Embalse de Ejemplo", 0.0, 0.0, 350).also { it.type = "waterbody" }
        assertTrue(RoadGuide.score(sight) > RoadGuide.score(castle))
    }

    @Test
    fun sidesAndAnglesFollowTheHeading() {
        assertEquals(20.0, RoadGuide.rel(350.0, 10.0), 1e-9)
        assertEquals(-20.0, RoadGuide.rel(10.0, 350.0), 1e-9)
        assertEquals(180.0, RoadGuide.rel(0.0, 180.0), 1e-9)
        assertEquals(RoadGuide.AHEAD, RoadGuide.side(20.0))
        assertEquals(RoadGuide.RIGHT, RoadGuide.side(60.0))
        assertEquals(RoadGuide.LEFT, RoadGuide.side(-60.0))
        assertEquals(RoadGuide.BEHIND, RoadGuide.side(150.0))
        val p = RoadGuide.ahead(40.0, -3.0, 90.0, 6.0)
        assertEquals(6000.0, RoadInfo.dist(40.0, -3.0, p[0], p[1]), 30.0)
        assertEquals(90.0, RoadInfo.bearing(40.0, -3.0, p[0], p[1]), 0.5)
    }

    @Test
    fun onlyWhatIsAheadIsListedClosestFirst() {
        val ahead = place(1, "Castillo de Ejemplo", 3.0, 0.0, 900)
        val behind = place(2, "Torre de Atrás", -3.0, 0.0, 900)
        val beside = place(3, "Ermita del Lado", 0.0, 1.5, 900)
        val farSide = place(4, "Sierra Lejana", 0.0, 5.0, 900)
        val tooFar = place(5, "Lago Lejano", 20.0, 0.0, 900)
        val all = listOf(ahead, behind, beside, farSide, tooFar)
        // Hacia el norte: delante y lo que queda justo al lado de la carretera; ni lo de atrás, ni lo lejano.
        assertEquals(listOf(3L, 1L), RoadGuide.upcoming(all, 40.0, -3.0, 0.0).map { it.id })
        // Parado: lo que está a menos de 2 km, por distancia.
        assertEquals(listOf(3L), RoadGuide.nearby(all, 40.0, -3.0).map { it.id })
    }

    @Test
    fun theNextToTellIsTheBestWithinFiveKmAndNeverTwice() {
        val castle = place(1, "Castillo de Ejemplo", 3.0, 0.0, 4000)
        val hermitage = place(2, "Ermita de Ejemplo", 1.0, 0.0, 400)
        val lake = place(3, "Lago de Ejemplo", 8.0, 0.0, 9000)
        val list = RoadGuide.upcoming(listOf(castle, hermitage, lake), 40.0, -3.0, 0.0)
        // El lago está a 8 km: aún no. De los otros dos, el más visitado, aunque esté más lejos.
        assertEquals(1L, RoadGuide.next(list, setOf(), 40.0, -3.0)?.id)
        assertEquals(2L, RoadGuide.next(list, setOf(1L), 40.0, -3.0)?.id)
        assertNull(RoadGuide.next(list, setOf(1L, 2L), 40.0, -3.0))
        assertEquals(3L, RoadGuide.best(list)?.id)
    }

    @Test
    fun itSearchesAgainWhenTheCarMovesOnOrStops() {
        val now = 10_000_000L
        assertTrue(RoadGuide.needQuery(Double.NaN, Double.NaN, 0, false, 40.0, -3.0, false, now))
        assertFalse(RoadGuide.needQuery(40.0, -3.0, now, false, 40.0 + kmLat, -3.0, false, now + 1000))
        assertTrue(RoadGuide.needQuery(40.0, -3.0, now, false, 40.0 + 5 * kmLat, -3.0, false, now + 1000))
        assertTrue(RoadGuide.needQuery(40.0, -3.0, now, false, 40.0, -3.0, false, now + RoadGuide.REFRESH_MS + 1))
        // Al pararse, una búsqueda a pie; parado en el mismo sitio, ninguna más.
        assertTrue(RoadGuide.needQuery(40.0, -3.0, now, false, 40.0, -3.0, true, now + 1000))
        assertFalse(RoadGuide.needQuery(40.0, -3.0, now, true, 40.0 + 0.3 * kmLat, -3.0, true, now + 1000))
    }

    @Test
    fun theSummaryIsReadWithoutParenthesesNorReferencesAndInWholeSentences() {
        assertEquals("El castillo de Ejemplo es una fortaleza del siglo XII. Tiene tres torres.",
            RoadGuide.clean("El castillo de Ejemplo (en latín, Castellum (antiguo)) es una fortaleza[1] del siglo XII . Tiene tres torres.", 320))
        val s = "a".repeat(140) + ". " + "b".repeat(140) + ". " + "c".repeat(140) + "."
        assertEquals("a".repeat(140) + ". " + "b".repeat(140) + ".", RoadGuide.clean(s, 320))
        val long = ("palabra ").repeat(80).trim()
        val cut = RoadGuide.clean(long, 320)
        assertTrue(cut, cut.endsWith("…") && cut.length <= 321 && !cut.contains("palabr…"))
        assertEquals("长城是古代的防御工程。", RoadGuide.clean("长城是古代的防御工程。它很长，也很古老。", 12))
        assertEquals("", RoadGuide.clean(null, 100))
    }

    @Test
    fun distancesAndWalkingTimes() {
        val es = Locale("es", "ES")
        assertEquals("3,2 km", RoadGuide.distText(3.24, es))
        assertEquals("800 m", RoadGuide.distText(0.83, es))
        assertEquals("100 m", RoadGuide.distText(0.02, es))
        assertEquals("12 km", RoadGuide.distText(12.4, es))
        assertEquals(16, RoadGuide.walkMin(1.0))
        assertEquals(1, RoadGuide.walkMin(0.05))
    }

    @Test
    fun wikipediaInTheLanguageOfTheAppAndTheQueriesAsExpected() {
        assertEquals("es", RoadGuide.wikiLang(Locale("es", "ES")))
        assertEquals("zh", RoadGuide.wikiLang(Locale.SIMPLIFIED_CHINESE))
        assertEquals("en", RoadGuide.wikiLang(Locale("ca")))
        val u = RoadGuide.searchUrl("es", 40.5, -3.0, 50_000)
        assertTrue(u, u.startsWith("https://es.wikipedia.org/w/api.php?"))
        assertTrue(u, u.contains("ggscoord=40.50000%7C-3.00000") && u.contains("ggsradius=10000"))
        assertTrue(RoadGuide.detailsUrl("fr", listOf(1L, 2L, 3L)).let { it.startsWith("https://fr.wikipedia.org/") && it.contains("pvipdays=30") && it.endsWith("pageids=1%7C2%7C3") })
    }
}
