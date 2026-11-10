package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** «Carga barata»: la ventana seguida más barata antes de la hora de salida, y los tres tramos de la tarifa. */
class CheapWindowTest {
    private val tz = TimeZone.getTimeZone("Europe/Madrid")
    private val min = 60_000L

    /** «2026-11-10 22:00» (martes) en la hora de la península. */
    private fun at(s: String): Long =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).apply { timeZone = tz }.parse(s)!!.time

    private fun hhmm(ms: Long) = CheapWindow.hhmm(ms, tz)

    /** La 2.0TD de serie: valle 0,10, llano 0,15 y punta 0,22 €/kWh; fines de semana en valle. */
    private val bands = Tariff(Tariff.BANDS, null, null, true, 0.20)

    /** El PVPC real del 10-11-2026 (€/MWh por hora), repetido cada día. */
    private val pvpc = doubleArrayOf(181.88, 175.84, 170.44, 164.56, 149.97, 165.46, 173.04, 197.08, 235.49, 204.68, 254.32, 202.85,
        179.5, 179.83, 146.65, 192.94, 225.11, 249.5, 326.87, 326.4, 327.27, 323.16, 240.17, 239.65)
    private val pvpcPrices = CheapWindow.Prices { h ->
        val c = Calendar.getInstance(tz).apply { timeInMillis = h }
        pvpc[c.get(Calendar.HOUR_OF_DAY)] / 1000.0
    }

    @Test
    fun timeNeededFromTheBatteryTheChargerAndTheEfficiency() {
        // (80 − 50) % de 69,9 kWh = 20,97 kWh; a 7,4 kW con un 90 % de rendimiento, 3,149 h: 189 min.
        assertEquals(189 * min, CheapWindow.needMs(50.0, 80, 69.9, 7.4))
        // Justo dos horas: 13,32 kWh a 7,4 kW × 0,9.
        assertEquals(120 * min, CheapWindow.needMs(60.0, 80, 66.6, 7.4))
        assertEquals(0L, CheapWindow.needMs(80.0, 80, 69.9, 7.4))
        assertEquals(0L, CheapWindow.needMs(91.0, 80, 69.9, 7.4))
        assertEquals(0L, CheapWindow.needMs(Double.NaN, 80, 69.9, 7.4))
        assertEquals(0L, CheapWindow.needMs(50.0, 80, 69.9, 0.0))
    }

    @Test
    fun withThreeBandsItWaitsForTheValleyAndPicksTheEarliestStart() {
        val now = at("2026-11-10 22:00")
        val depart = CheapWindow.departure(now, 7 * 60 + 30, tz)
        assertEquals(at("2026-11-11 07:30"), depart)
        val r = CheapWindow.plan(now, depart, 50.0, 80, 69.9, 7.4, bands.prices(tz), tz)
        assertEquals(CheapWindow.Kind.WINDOW, r.kind)
        // Todo el valle (0–8) cuesta lo mismo: gana el comienzo más temprano.
        assertEquals("00:00", hhmm(r.startMs))
        assertEquals("03:09", hhmm(r.chargeEndMs))
        // 189 min × 1,15 = 217 min → 03:37, al cuarto siguiente.
        assertEquals("03:45", hhmm(r.endMs))
        assertEquals(189 * min, r.needMs)
        assertEquals(7.4 * 3.15, r.kwh, 1e-9)
        assertEquals(0.10 * 7.4 * 3.15, r.cost, 1e-9)
        // Cargando al enchufar: dos horas de llano (22–24) y el resto en valle.
        assertEquals(0.15 * 7.4 * 2 + 0.10 * 7.4 * 1.15, r.costNow, 1e-9)
        assertEquals(r.costNow - r.cost, r.saving(), 1e-9)
        assertTrue(r.saving() > 0.7)
    }

    @Test
    fun withHourlyPricesItFindsTheCheapestTwoHours() {
        val now = at("2026-11-10 00:00")
        val r = CheapWindow.plan(now, at("2026-11-10 07:30"), 60.0, 80, 66.6, 7.4, pvpcPrices, tz)
        assertEquals(CheapWindow.Kind.WINDOW, r.kind)
        // De 3 a 5 (164,56 + 149,97) es más barato que de 4 a 6 (149,97 + 165,46).
        assertEquals("03:00", hhmm(r.startMs))
        assertEquals("05:00", hhmm(r.chargeEndMs))
        // 120 min × 1,15 = 138 min → 05:18, al cuarto siguiente.
        assertEquals("05:30", hhmm(r.endMs))
        assertEquals((0.16456 + 0.14997) * 7.4, r.cost, 1e-9)
        assertEquals((0.18188 + 0.17584) * 7.4, r.costNow, 1e-9)
    }

    @Test
    fun aWindowMayEndOnTheHourInsteadOfStartingOnIt() {
        // Hora y media: la hora barata entera (4–5) y media de la de antes (164,56), mejor que media de la de después (165,46).
        val r = CheapWindow.plan(at("2026-11-10 00:00"), at("2026-11-10 07:30"), 60.0, 80, 49.95, 7.4, pvpcPrices, tz)
        assertEquals(90 * min, r.needMs)
        assertEquals("03:30", hhmm(r.startMs))
        assertEquals("05:00", hhmm(r.chargeEndMs))
        assertEquals((0.16456 * 0.5 + 0.14997) * 7.4, r.cost, 1e-9)
    }

    @Test
    fun noWindowBeatsAnyOtherStartMinuteByMinute() {
        // Contra la fuerza bruta: ningún comienzo, minuto a minuto, es más barato que el elegido.
        val now = at("2026-11-10 16:20")
        val depart = at("2026-11-11 07:30")
        for (cap in doubleArrayOf(20.0, 49.95, 69.9, 81.9)) {
            val r = CheapWindow.plan(now, depart, 35.0, 80, cap, 3.7, pvpcPrices, tz)
            if (r.kind == CheapWindow.Kind.NO_FIT) continue
            var best = Double.MAX_VALUE
            var s = now
            while (s + r.needMs <= depart) {
                best = minOf(best, CheapWindow.cost(s, r.needMs, 3.7, pvpcPrices, tz))
                s += min
            }
            assertTrue("cap $cap: ${r.cost} > $best", r.cost <= best + 1e-9)
        }
    }

    @Test
    fun theEndSentToTheCarNeverPassesTheDeparture() {
        // Barato solo de 5 a 7:30: dos horas y media de carga acaban justo a la salida; el margen no la pasa.
        val cheapLate = CheapWindow.Prices { h -> if (h >= at("2026-11-11 05:00")) 0.05 else 0.30 }
        val r = CheapWindow.plan(at("2026-11-10 22:00"), at("2026-11-11 07:30"), 50.0, 80, 55.5, 7.4, cheapLate, tz)
        assertEquals(150 * min, r.needMs)
        assertEquals(CheapWindow.Kind.WINDOW, r.kind)
        assertEquals("05:00", hhmm(r.startMs))
        assertEquals("07:30", hhmm(r.endMs))
        // Con una salida que no cae en un cuarto: tampoco.
        val odd = CheapWindow.plan(at("2026-11-10 22:00"), at("2026-11-11 07:40"), 50.0, 80, 55.5, 7.4, cheapLate, tz)
        assertEquals("05:00", hhmm(odd.startMs))
        assertEquals("07:40", hhmm(odd.endMs))
    }

    @Test
    fun ifItDoesNotFitItChargesNow() {
        val r = CheapWindow.plan(at("2026-11-11 06:00"), at("2026-11-11 07:30"), 50.0, 80, 69.9, 7.4, bands.prices(tz), tz)
        assertEquals(CheapWindow.Kind.NO_FIT, r.kind)
        assertEquals(189 * min, r.needMs)
        assertEquals(at("2026-11-11 06:00"), r.startMs)
        assertEquals(0.10 * 7.4 * 2 + 0.15 * 7.4 * 1.15, r.costNow, 1e-9)
        assertEquals(0.0, r.saving(), 0.0)
    }

    @Test
    fun ifItFitsExactlyItStartsNow() {
        // Queda justo lo que hace falta: el único comienzo posible es ahora.
        val r = CheapWindow.plan(at("2026-11-11 04:21"), at("2026-11-11 07:30"), 50.0, 80, 69.9, 7.4, bands.prices(tz), tz)
        assertEquals(CheapWindow.Kind.NOW, r.kind)
    }

    @Test
    fun ifTheCheapestStartsNowOrVerySoonThereIsNoWindow() {
        // Ya en valle: lo más barato (a igual coste, lo más temprano) es ahora.
        val inValley = CheapWindow.plan(at("2026-11-11 00:05"), at("2026-11-11 07:30"), 50.0, 80, 69.9, 7.4, bands.prices(tz), tz)
        assertEquals(CheapWindow.Kind.NOW, inValley.kind)
        assertEquals(0.0, inValley.saving(), 1e-9)
        // A diez minutos del valle: tampoco merece una ventana.
        val soon = CheapWindow.plan(at("2026-11-10 23:50"), at("2026-11-11 07:30"), 50.0, 80, 69.9, 7.4, bands.prices(tz), tz)
        assertEquals(CheapWindow.Kind.NOW, soon.kind)
        // A veinte, sí.
        val later = CheapWindow.plan(at("2026-11-10 23:40"), at("2026-11-11 07:30"), 50.0, 80, 69.9, 7.4, bands.prices(tz), tz)
        assertEquals(CheapWindow.Kind.WINDOW, later.kind)
        assertEquals("00:00", hhmm(later.startMs))
    }

    @Test
    fun atTheTargetOrWithoutPricesThereIsNothingToPlan() {
        val now = at("2026-11-10 22:00")
        val depart = at("2026-11-11 07:30")
        assertEquals(CheapWindow.Kind.NOT_NEEDED, CheapWindow.plan(now, depart, 80.0, 80, 69.9, 7.4, bands.prices(tz), tz).kind)
        val none = CheapWindow.plan(now, depart, 50.0, 80, 69.9, 7.4, { Double.NaN }, tz)
        assertEquals(CheapWindow.Kind.NO_PRICES, none.kind)
        assertTrue(none.cost.isNaN())
    }

    @Test
    fun hoursWithoutPriceAreNotUsed() {
        // Solo se saben los precios hasta medianoche: la ventana no entra en lo que no se sabe.
        val today = CheapWindow.Prices { h -> if (h < at("2026-11-11 00:00")) pvpcPrices.at(h) else Double.NaN }
        val r = CheapWindow.plan(at("2026-11-10 12:00"), at("2026-11-11 07:30"), 60.0, 80, 66.6, 7.4, today, tz)
        assertEquals(CheapWindow.Kind.WINDOW, r.kind)
        // Las dos horas seguidas más baratas de la tarde: de 13 a 15 (179,83 + 146,65).
        assertEquals("13:00", hhmm(r.startMs))
    }

    @Test
    fun theDepartureIsTheNextTimeTheClockShowsIt() {
        assertEquals(at("2026-11-10 07:30"), CheapWindow.departure(at("2026-11-10 06:00"), 450, tz))
        assertEquals(at("2026-11-11 07:30"), CheapWindow.departure(at("2026-11-10 08:00"), 450, tz))
        assertEquals(at("2026-11-11 07:30"), CheapWindow.departure(at("2026-11-10 07:30"), 450, tz))
        // El día del cambio de hora (25-10-2026, a las 3 son las 2): sigue siendo las 07:30 del reloj.
        assertEquals(at("2026-10-25 07:30"), CheapWindow.departure(at("2026-10-24 22:00"), 450, tz))
        assertEquals("07:30", CheapWindow.hhmm(450))
        assertEquals("00:05", CheapWindow.hhmm(5))
    }

    @Test
    fun quartersAndHoursFollowTheLocalClock() {
        assertEquals(at("2026-11-10 03:45"), CheapWindow.ceilQuarter(at("2026-11-10 03:37"), tz))
        assertEquals(at("2026-11-10 03:45"), CheapWindow.ceilQuarter(at("2026-11-10 03:45"), tz))
        assertEquals(at("2026-11-10 22:00"), CheapWindow.hourStart(at("2026-11-10 22:59"), tz))
    }

    // ------------------------------------------------------------------ la tarifa

    @Test
    fun theDefaultBandsAreTheSpanishOnes() {
        val h = Tariff.parseHours(Tariff.DEFAULT_HOURS)
        for (i in 0..7) assertEquals("hora $i", Tariff.VALLEY, h[i])
        for (i in listOf(8, 9, 14, 15, 16, 17, 22, 23)) assertEquals("hora $i", Tariff.MID, h[i])
        for (i in listOf(10, 11, 12, 13, 18, 19, 20, 21)) assertEquals("hora $i", Tariff.PEAK, h[i])
        assertEquals(Tariff.DEFAULT_HOURS, Tariff.hoursString(h))
        // Algo que no son 24 cifras de 0 a 2: los de serie.
        assertEquals(Tariff.DEFAULT_HOURS, Tariff.hoursString(Tariff.parseHours("0123")))
        assertEquals(Tariff.DEFAULT_HOURS, Tariff.hoursString(Tariff.parseHours(null)))
    }

    @Test
    fun weekendsAreAllValleyIfChosen() {
        // Martes a las 11: punta. Sábado y domingo a las 11: valle (o punta, sin «fines de semana en valle»).
        assertEquals(0.22, bands.priceAt(at("2026-11-10 11:00"), tz), 0.0)
        assertEquals(0.10, bands.priceAt(at("2026-11-14 11:00"), tz), 0.0)
        assertEquals(0.10, bands.priceAt(at("2026-11-15 19:00"), tz), 0.0)
        val noWeekend = Tariff(Tariff.BANDS, null, null, false, 0.20)
        assertEquals(0.22, noWeekend.priceAt(at("2026-11-14 11:00"), tz), 0.0)
        // El sábado por la mañana, con todo el fin de semana en valle, se carga ya.
        val r = CheapWindow.plan(at("2026-11-14 10:00"), at("2026-11-15 07:30"), 50.0, 80, 69.9, 7.4, bands.prices(tz), tz)
        assertEquals(CheapWindow.Kind.NOW, r.kind)
    }

    @Test
    fun tappingAnHourGoesValleyMidPeakAndBack() {
        assertEquals(Tariff.MID, Tariff.next(Tariff.VALLEY))
        assertEquals(Tariff.PEAK, Tariff.next(Tariff.MID))
        assertEquals(Tariff.VALLEY, Tariff.next(Tariff.PEAK))
    }

    @Test
    fun ownPricesAndHoursAndTheFlatRate() {
        val hours = IntArray(24) { if (it < 6) Tariff.VALLEY else Tariff.PEAK }
        val t = Tariff(Tariff.BANDS, hours, doubleArrayOf(0.05, 0.12, 0.30), false, 0.20)
        assertEquals(0.05, t.priceAt(at("2026-11-10 05:00"), tz), 0.0)
        assertEquals(0.30, t.priceAt(at("2026-11-10 06:00"), tz), 0.0)
        val flat = Tariff(Tariff.FLAT, null, null, true, 0.17)
        assertEquals(0.17, flat.priceAt(at("2026-11-10 11:00"), tz), 0.0)
        assertEquals(Tariff.BANDS, Tariff("otra", null, null, true, 0.2).mode)
        assertEquals(Tariff.PVPC, Tariff.mode("pvpc", Tariff.BANDS))
    }
}
