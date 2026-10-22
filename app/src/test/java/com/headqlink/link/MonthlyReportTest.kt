package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.TimeZone

/** Informe del mes: qué viajes cuenta (coche o app), el dinero, las cargas, el coche parado, los destacados y qué se guarda. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = android.app.Application::class)
class MonthlyReportTest {
    private val tz: TimeZone = TimeZone.getTimeZone("Europe/Madrid")
    private val prices = MonthlyReport.Prices(0.20, 0.50, 1.60, 6.5)
    private val oct = MonthlyReport.monthStart(2026, 10, tz)
    private val nov = MonthlyReport.monthStart(2026, 11, tz)
    private val day = 86_400_000L

    private fun car(at: Long, km: Double, kwh: Double) = CloudHistory.Trip(at, at + 3_600_000L, km, kwh, Double.NaN, 120.0)

    private fun app(at: Long, km: Double, kwh: Double, socS: Double = Double.NaN, socE: Double = Double.NaN, odo: Double = Double.NaN) =
        TripLog.Trip().apply {
            startMs = at
            minutes = 60
            this.km = km
            this.kwh = kwh
            socStart = socS
            socEnd = socE
            odoStart = odo
            odoEnd = if (odo.isNaN()) odo else odo + km
            capKwh = 70.0
        }

    @Test
    fun monthsAndKeys() {
        assertEquals("2026-10", MonthlyReport.key(2026, 10))
        assertEquals(listOf(2025, 12), MonthlyReport.shift(2026, 1, -1).toList())
        assertEquals(listOf(2027, 1), MonthlyReport.shift(2026, 12, 1).toList())
        // Octubre tiene 31 días y, en Madrid, una hora más (el cambio de hora del último domingo).
        assertEquals(31 * day + 3_600_000L, nov - oct)
        assertEquals(listOf(2026, 10), MonthlyReport.monthOf(oct + 5 * day, tz).toList())
    }

    @Test
    fun theCarHistoryCountsWhenItCoversTheWholeMonth() {
        // El historial se leyó el 2 de noviembre: cubre desde el 28 de septiembre (35 días), todo octubre.
        val history = listOf(car(oct + 2 * day, 100.0, 15.0), car(oct + 2 * day + 7_200_000L, 50.0, 9.0),
            car(oct + 20 * day, 300.0, 54.0), car(oct - 2 * day, 80.0, 12.0), car(nov + day, 20.0, 3.0))
        val s = MonthlyReport.compute(2026, 10, tz, history, nov + day, emptyList(), 1, emptyList(), 70.0, prices)
        assertTrue(s.fromCar)
        assertTrue(s.complete)
        assertEquals(3, s.trips)
        assertEquals(450.0, s.km, 1e-9)
        assertEquals(78.0, s.kwh, 1e-9)
        assertEquals(78.0 / 450 * 100, s.per100(), 1e-9)
        // Sin cargas rápidas: todo al precio de casa. Gasolina: 450 km a 6,5 L/100 y 1,60 €/L.
        assertEquals(78 * 0.20, s.costEur, 1e-9)
        assertEquals(450 * 6.5 / 100 * 1.60, s.petrolEur, 1e-9)
        assertEquals(s.petrolEur - s.costEur, s.savedEur(), 1e-9)
        // Destacados: el más largo, el más eficiente (15/100 = 15; 9/50 = 18; 54/300 = 18) y el día con más km.
        assertEquals(300.0, s.longestKm, 0.0)
        assertEquals(15.0, s.bestPer100, 1e-9)
        assertEquals(100.0, s.bestKm, 0.0)
        assertEquals(300.0, s.topDayKm, 0.0)
        assertEquals(TripStats.startOfDay(oct + 20 * day, tz), s.topDayMs)
    }

    @Test
    fun withoutTheWholeMonthFromTheCarTheAppTripsCountIfTheAppWasThere() {
        // El historial se leyó el 22: no cubre el principio del mes. La app graba desde antes de octubre.
        val history = listOf(car(oct + 20 * day, 300.0, 54.0))
        val trips = listOf(app(oct + 3 * day, 40.0, 6.0), app(oct + 10 * day, 60.0, 9.0))
        val s = MonthlyReport.compute(2026, 10, tz, history, oct + 22 * day, trips, oct - 100 * day, emptyList(), 70.0, prices)
        assertFalse(s.fromCar)
        assertTrue(s.complete)
        assertEquals(100.0, s.km, 1e-9)
        // La app recién instalada: el coche, a medias.
        val partial = MonthlyReport.compute(2026, 10, tz, history, oct + 22 * day, trips, oct + day, emptyList(), 70.0, prices)
        assertTrue(partial.fromCar)
        assertFalse(partial.complete)
        assertEquals(300.0, partial.km, 1e-9)
        // Sin cuenta de Leapmotor: la app.
        val noAccount = MonthlyReport.compute(2026, 10, tz, emptyList(), 0, trips, oct - day, emptyList(), 70.0, prices)
        assertFalse(noAccount.fromCar)
        assertTrue(noAccount.complete)
        assertEquals(2, noAccount.trips)
    }

    @Test
    fun chargesSplitHomeAndFastAndTheFastPartCostsMore() {
        val fast = ChargeSession(oct + 5 * day, 20.0, true, null, 70.0, 180.0, 800.0).apply {
            samples.add(ChargeSession.Sample(startMs + 60_000, 30.0, 120.0))
            samples.add(ChargeSession.Sample(startMs + 600_000, 60.0, 152.4))
            samples.add(ChargeSession.Sample(startMs + 900_000, 70.0, 90.0))
        }
        val home = ChargeSession(oct + 9 * day, 40.0, false, null, 70.0, 180.0, 800.0).apply {
            samples.add(ChargeSession.Sample(startMs + 3_600_000L, 80.0, 7.0))
        }
        val other = ChargeSession(nov + day, 40.0, false, null, 70.0, 180.0, 800.0)
        val history = listOf(car(oct + 6 * day, 400.0, 70.0))
        val s = MonthlyReport.compute(2026, 10, tz, history, nov + day, emptyList(), 1, listOf(fast, home, other), 70.0, prices)
        assertEquals(2, s.charges)
        assertEquals(35.0, s.fastKwh, 1e-9) // 20 → 70 % de 70 kWh
        assertEquals(35.0 + 28.0, s.chargedKwh, 1e-9)
        assertEquals(152.4, s.fastestKw, 1e-9)
        assertEquals(35 * 0.50 + 28 * 0.20, s.chargeCostEur, 1e-9)
        // Lo gastado: los 35 kWh de la rápida a su precio y el resto al de casa.
        assertEquals(35 * 0.50 + 35 * 0.20, s.costEur, 1e-9)
    }

    @Test
    fun parkedDropsOfTheMonth() {
        // Dos viajes con una noche parado en medio (−0,5 %) y otro tras 3 días (−1 %).
        val a = app(oct + 4 * day, 30.0, 5.0, 80.0, 70.0, 1000.0)
        val b = app(oct + 5 * day, 30.0, 5.0, 69.5, 60.0, 1030.0)
        val c = app(oct + 8 * day, 30.0, 5.0, 59.0, 50.0, 1060.0)
        val s = MonthlyReport.compute(2026, 10, tz, emptyList(), 0, listOf(a, b, c), oct - day, emptyList(), 70.0, prices)
        assertEquals(2, s.parks)
        assertEquals(1.5, s.parkedPct, 1e-9)
        assertEquals(1.5 / 100 * 70, s.parkedKwh, 1e-9)
    }

    @Test
    fun aCompleteMonthIsNotReplacedByAHalfOne() {
        val done = MonthlyReport.Summary().apply { year = 2026; month = 10; complete = true; km = 900.0 }
        val half = MonthlyReport.Summary().apply { year = 2026; month = 10; complete = false; km = 1200.0 }
        assertSame(done, MonthlyReport.keep(done, half))
        assertSame(done, MonthlyReport.keep(half, done))
        val newer = MonthlyReport.Summary().apply { year = 2026; month = 10; complete = true; km = 910.0 }
        assertSame(newer, MonthlyReport.keep(done, newer))
        val lessHalf = MonthlyReport.Summary().apply { year = 2026; month = 10; complete = false; km = 100.0 }
        assertSame(half, MonthlyReport.keep(half, lessHalf))
        assertSame(half, MonthlyReport.keep(null, half))
    }

    @Test
    fun comparisonOnlyBetweenCompleteMonthsFromTheSameSource() {
        val cur = MonthlyReport.Summary().apply { complete = true; fromCar = true; trips = 3; km = 1100.0 }
        val prev = MonthlyReport.Summary().apply { complete = true; fromCar = true; trips = 5; km = 1000.0 }
        assertTrue(MonthlyReport.comparable(cur, prev))
        assertEquals(10.0, MonthlyReport.changePct(1100.0, 1000.0), 1e-9)
        assertFalse(MonthlyReport.comparable(cur, MonthlyReport.Summary().apply { complete = false; fromCar = true; trips = 5 }))
        assertFalse(MonthlyReport.comparable(cur, MonthlyReport.Summary().apply { complete = true; fromCar = false; trips = 5 }))
        assertFalse(MonthlyReport.comparable(cur, null))
        assertTrue(MonthlyReport.changePct(5.0, 0.0).isNaN())
    }

    @Test
    fun savedAndReadBackNewestFirstAndABrokenFileGivesNothing() {
        val all = HashMap<String, MonthlyReport.Summary>()
        for (m in 1..12) for (y in 2024..2026) {
            all[MonthlyReport.key(y, m)] = MonthlyReport.Summary().apply { year = y; month = m; km = m * 10.0; kwh = Double.NaN }
        }
        val back = MonthlyReport.read(MonthlyReport.write(all))
        assertEquals(MonthlyReport.KEEP_MONTHS, back.size)
        assertTrue(back.containsKey("2026-12"))
        assertFalse(back.containsKey("2024-12"))
        assertEquals(100.0, back["2026-10"]!!.km, 0.0)
        assertTrue(back["2026-10"]!!.kwh.isNaN())
        assertTrue(MonthlyReport.read("{roto").isEmpty())
        assertNull(MonthlyReport.read("")["2026-10"])
    }

    @Test
    fun theNoticeIsOnlyForTheFirstDaysAndOnce() {
        assertTrue(MonthlyReportJob.due(1, "2026-10", null))
        assertTrue(MonthlyReportJob.due(MonthlyReportJob.NOTIFY_UNTIL_DAY, "2026-10", "2026-09"))
        assertFalse(MonthlyReportJob.due(1, "2026-10", "2026-10"))
        assertFalse(MonthlyReportJob.due(MonthlyReportJob.NOTIFY_UNTIL_DAY + 1, "2026-10", "2026-09"))
    }
}
