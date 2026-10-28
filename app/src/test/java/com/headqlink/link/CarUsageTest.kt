package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** «Consumo» de «Mi coche» (28-10): Semana / Mes / Año con los viajes del coche, los de la app o los meses guardados. */
class CarUsageTest {
    private val tz: TimeZone = TimeZone.getTimeZone("Europe/Madrid")
    private val day = 86_400_000L
    // 28-10-2026 12:00 en Madrid.
    private val now = 1793185200000L

    private fun car(daysAgo: Double, km: Double, kwh: Double): CloudHistory.Trip {
        val s = now - (daysAgo * day).toLong()
        return CloudHistory.Trip(s, s + 1_800_000L, km, kwh, Double.NaN, 100.0)
    }

    @Test
    fun weekUsesTheCarTripsOfTheLastSevenDays() {
        val history = listOf(car(1.0, 40.0, 7.5), car(3.0, 60.0, 11.0), car(8.0, 300.0, 55.0))
        val t = CarUsage.compute(CarUsage.Period.WEEK, now, tz, history, emptyList(), emptyMap())
        assertEquals(2, t.trips)
        assertEquals(100.0, t.km, 1e-9)
        assertEquals(18.5, t.kwh, 1e-9)
        assertEquals(18.5, t.per100(), 1e-9)
        assertTrue(t.fromCar)
        // Del más reciente al más antiguo.
        assertTrue(t.rows[0].startMs > t.rows[1].startMs)
    }

    @Test
    fun monthStartsOnTheFirstOfTheMonth() {
        val from = CarUsage.fromMs(CarUsage.Period.MONTH, now, tz)
        // 1-10-2026 00:00 en Madrid (UTC+2).
        assertEquals(1790805600000L, from)
        val history = listOf(car(2.0, 10.0, 2.0), car(27.5, 20.0, 4.0), car(28.5, 30.0, 6.0))
        val t = CarUsage.compute(CarUsage.Period.MONTH, now, tz, history, emptyList(), emptyMap())
        // El de hace 28,5 días es de septiembre.
        assertEquals(2, t.trips)
        assertEquals(30.0, t.km, 1e-9)
    }

    @Test
    fun withoutTheCarItUsesTheTripsOfTheApp() {
        val a = TripLog.Trip()
        a.startMs = now - day
        a.minutes = 30
        a.km = 25.0
        a.kwh = 4.0
        val t = CarUsage.compute(CarUsage.Period.WEEK, now, tz, emptyList(), listOf(a), emptyMap())
        assertEquals(1, t.trips)
        assertFalse(t.fromCar)
        assertEquals(25.0, t.km, 1e-9)
        assertEquals(1, t.rows.size)
    }

    @Test
    fun yearAddsTheSavedMonthsAndSaysSinceWhen() {
        fun month(m: Int, km: Double, kwh: Double, car: Boolean) = MonthlyReport.Summary().apply {
            year = 2026; month = m; trips = 10; this.km = km; this.kwh = kwh; fromCar = car
        }
        val months = mapOf(
            "2026-08" to month(8, 800.0, 140.0, true),
            "2026-09" to month(9, 900.0, 160.0, true),
            "2026-10" to month(10, 1000.0, 180.0, true),
            "2025-12" to month(12, 5000.0, 900.0, true),
        )
        val t = CarUsage.compute(CarUsage.Period.YEAR, now, tz, emptyList(), emptyList(), months)
        assertEquals(2700.0, t.km, 1e-9)
        assertEquals(480.0, t.kwh, 1e-9)
        assertEquals(30, t.trips)
        assertTrue(t.fromCar)
        // Los datos empiezan en agosto: se dice.
        assertEquals(MonthlyReport.monthStart(2026, 8, tz), t.sinceMs)
    }

    @Test
    fun aCloudWindowOnlyCountsForItsOwnPeriod() {
        val from = CarUsage.fromMs(CarUsage.Period.MONTH, now, tz)
        val w = CloudUsage.Window(from, 283.0, CloudUsage.Split(50.1, 1.1, 1.5), now)
        assertTrue(CarUsage.matches(w, from))
        assertFalse(CarUsage.matches(w, CarUsage.fromMs(CarUsage.Period.YEAR, now, tz)))
        assertFalse(CarUsage.matches(CloudUsage.Window(from, Double.NaN, null, now), from))
        assertEquals(52.7, w.kwh(), 1e-9)
        assertEquals(52.7 / 283 * 100, w.per100(), 1e-9)
    }
}
