package com.headqlink.link

import android.app.Application
import org.junit.After
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Los precios del PVPC de Red Eléctrica: lo que se lee, lo que se guarda y cuándo se pregunta (la red, de mentira). */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class PvpcPricesTest {
    private val tz = TimeZone.getTimeZone("Europe/Madrid")
    private val hour = 3_600_000L
    private val urls = mutableListOf<String>()

    private fun at(s: String): Long =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).apply { timeZone = tz }.parse(s)!!.time

    /** El PVPC real del 10-11-2026 (€/MWh), como lo da apidatos.ree.es. */
    private val real = doubleArrayOf(181.88, 175.84, 170.44, 164.56, 149.97, 165.46, 173.04, 197.08, 235.49, 204.68, 254.32, 202.85,
        179.5, 179.83, 146.65, 192.94, 225.11, 249.5, 326.87, 326.4, 327.27, 323.16, 240.17, 239.65)

    /** Una respuesta como la de verdad (recortada): el PVPC (1001) de los días pedidos y el mercado (600), cada cuarto de hora. */
    private fun answer(vararg days: String): String {
        val pvpc = days.flatMap { d ->
            real.mapIndexed { h, v -> """{"value":$v,"percentage":0.5,"datetime":"${d}T${"%02d".format(h)}:00:00.000+01:00"}""" }
        }.joinToString(",")
        val spot = (0 until 8).joinToString(",") { q ->
            """{"value":${90 + q},"percentage":0.5,"datetime":"${days[0]}T${"%02d".format(q / 4)}:${"%02d".format(q % 4 * 15)}:00.000+01:00"}"""
        }
        return """{"data":{"type":"Precios mercado peninsular en tiempo real","id":"mer13","attributes":{"title":"Precios","last-update":"2026-11-09T20:46:53.000+01:00","description":null}},"included":[{"type":"PVPC","id":"1001","groupId":null,"attributes":{"title":"PVPC","magnitude":"price","composite":false,"values":[$pvpc]}},{"type":"Precio mercado spot","id":"600","groupId":null,"attributes":{"title":"Precio mercado spot","values":[$spot]}}]}"""
    }

    private val ctx get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        PvpcPrices.resetForTest()
        File(ctx.filesDir, "pvpc.json").delete()
        urls.clear()
    }

    @After
    fun tearDown() {
        Http.fake = null
        PvpcPrices.resetForTest()
    }

    @Test
    fun readsThePvpcHoursInEurosPerKwhAndNotTheSpotMarket() {
        val t = PvpcPrices.parse(answer("2026-11-10"))
        assertEquals(24, t.size())
        assertEquals(0.18188, t.at(at("2026-11-10 00:00")), 1e-12)
        assertEquals(0.14997, t.at(at("2026-11-10 04:00")), 1e-12)
        assertEquals(0.23965, t.at(at("2026-11-10 23:00")), 1e-12)
        assertTrue(t.at(at("2026-11-11 00:00")).isNaN())
        assertEquals(at("2026-11-10 23:00"), t.lastHour())
        assertTrue(t.covers(at("2026-11-10 08:20"), at("2026-11-11 00:00")))
        assertFalse(t.covers(at("2026-11-10 08:20"), at("2026-11-11 07:30")))
    }

    @Test
    fun aStrangeAnswerGivesNoPricesInsteadOfFailing() {
        assertTrue(PvpcPrices.parse("{}").isEmpty)
        assertTrue(PvpcPrices.parse("""{"included":[{"id":"1001","attributes":{"values":[{"value":"x","datetime":"ayer"},{"datetime":"2026-11-10T00:00:00.000+01:00"}]}}]}""").isEmpty)
        assertTrue(PvpcPrices.parse("""{"included":[{"id":"600","attributes":{"values":[{"value":90,"datetime":"2026-11-10T00:00:00.000+01:00"}]}}]}""").isEmpty)
    }

    @Test
    fun timesWithTheirOffset() {
        assertEquals(at("2026-11-10 00:00"), PvpcPrices.time("2026-11-10T00:00:00.000+01:00"))
        // En verano, +02:00.
        assertEquals(at("2026-07-01 13:00"), PvpcPrices.time("2026-07-01T13:00:00.000+02:00"))
        assertEquals(at("2026-11-10 01:00"), PvpcPrices.time("2026-11-10T00:00:00Z"))
        assertEquals(0L, PvpcPrices.time("2026-11-10"))
        assertEquals(0L, PvpcPrices.time(null))
    }

    @Test
    fun asksForTodayAndTomorrow() {
        assertEquals("https://apidatos.ree.es/es/datos/mercados/precios-mercados-tiempo-real?start_date=2026-11-10T00:00"
            + "&end_date=2026-11-11T23:59&time_trunc=hour", PvpcPrices.url(at("2026-11-10 22:10")))
    }

    @Test
    fun tomorrowIsEstimatedWithTodayAtTheSameHour() {
        val t = PvpcPrices.parse(answer("2026-11-10"))
        val est = t.withEstimates()
        assertEquals(0.14997, est.at(at("2026-11-11 04:00")), 1e-12)
        assertEquals(0.14997, est.at(at("2026-11-12 04:00")), 1e-12)
        assertEquals(0.18188, est.at(at("2026-11-10 00:00")), 1e-12)
        // Tres días después ya no se inventa.
        assertTrue(est.at(at("2026-11-13 04:00")).isNaN())
        // En otra moneda, por su cambio.
        assertEquals(0.14997 * 0.94, t.times(0.94).at(at("2026-11-10 04:00")), 1e-12)
    }

    @Test
    fun whenToAsk() {
        val none = PvpcPrices.Table.EMPTY
        val today = PvpcPrices.parse(answer("2026-11-10"))
        val both = PvpcPrices.parse(answer("2026-11-10", "2026-11-11"))
        val depart = at("2026-11-11 07:30")
        // Sin nada: se pregunta; pero no otra vez antes de media hora.
        assertTrue(PvpcPrices.due(none, at("2026-11-10 18:00"), depart, 0))
        assertFalse(PvpcPrices.due(none, at("2026-11-10 18:00"), depart, at("2026-11-10 17:45")))
        assertTrue(PvpcPrices.due(none, at("2026-11-10 18:00"), depart, at("2026-11-10 17:30")))
        // Con lo de hoy y antes de las 20:15: los de mañana no están publicados, no se pregunta.
        assertFalse(PvpcPrices.due(today, at("2026-11-10 18:00"), depart, 0))
        assertFalse(PvpcPrices.due(today, at("2026-11-10 20:14"), depart, 0))
        assertTrue(PvpcPrices.due(today, at("2026-11-10 20:15"), depart, 0))
        assertFalse(PvpcPrices.due(today, at("2026-11-10 20:40"), depart, at("2026-11-10 20:20")))
        // Pasada la medianoche sin los del día: se pregunta a cualquier hora.
        assertTrue(PvpcPrices.due(today, at("2026-11-11 00:10"), depart, 0))
        // Con todo lo que hace falta: nunca.
        assertFalse(PvpcPrices.due(both, at("2026-11-10 22:00"), depart, 0))
        // La salida es hoy mismo: con lo de hoy basta.
        assertFalse(PvpcPrices.due(today, at("2026-11-10 02:00"), at("2026-11-10 07:30"), 0))
    }

    @Test
    fun whatIsReadIsKeptAndNotAskedAgainWithinHalfAnHour() {
        Http.fake = Http.Fake { url, _ ->
            urls.add(url)
            if (urls.size == 1) answer("2026-11-10") else answer("2026-11-10", "2026-11-11")
        }
        val depart = at("2026-11-11 07:30")
        // 21:00: se pregunta, pero mañana aún no está.
        val first = PvpcPrices.get(ctx, at("2026-11-10 21:00"), depart)
        assertEquals(1, urls.size)
        assertTrue(urls[0].startsWith(PvpcPrices.URL + "?start_date=2026-11-10T00:00"))
        assertEquals(24, first.size())
        // A los 15 min: lo guardado, sin red.
        assertEquals(24, PvpcPrices.get(ctx, at("2026-11-10 21:15"), depart).size())
        assertEquals(1, urls.size)
        // A los 31 min: otra vez, y ya está mañana.
        val second = PvpcPrices.get(ctx, at("2026-11-10 21:31"), depart)
        assertEquals(2, urls.size)
        assertEquals(48, second.size())
        assertTrue(second.covers(at("2026-11-10 21:31"), depart))
        // Con todo: ni una petición más, tampoco tras morir el proceso (lo lee del móvil).
        PvpcPrices.resetForTest()
        assertEquals(48, PvpcPrices.get(ctx, at("2026-11-10 23:50"), depart).size())
        assertEquals(48, PvpcPrices.cached(ctx).size())
        assertEquals(2, urls.size)
    }

    @Test
    fun aFailedReadKeepsWhatThereWasAndWaitsToo() {
        Http.fake = Http.Fake { url, _ ->
            urls.add(url)
            if (urls.size == 1) answer("2026-11-10") else throw java.io.IOException("sin red")
        }
        val depart = at("2026-11-11 07:30")
        PvpcPrices.get(ctx, at("2026-11-10 20:30"), depart)
        val t = PvpcPrices.get(ctx, at("2026-11-10 21:05"), depart)
        assertEquals(2, urls.size)
        assertEquals(24, t.size())
        // El fallo también cuenta para la media hora.
        PvpcPrices.get(ctx, at("2026-11-10 21:20"), depart)
        assertEquals(2, urls.size)
        // Lo de hace más de tres días se va de lo guardado.
        Http.fake = Http.Fake { _, _ -> answer("2026-11-14") }
        val later = PvpcPrices.get(ctx, at("2026-11-14 10:00"), at("2026-11-14 20:00"))
        assertEquals(24, later.size())
        assertTrue(later.at(at("2026-11-10 04:00")).isNaN())
    }
}
