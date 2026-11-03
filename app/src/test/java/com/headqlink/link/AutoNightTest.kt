package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

/**
 * Día o noche en «Automático» sin tema del coche (foro #271, móvil a oscuras en la consola): el sol en la posición del
 * móvil hasta que el sensor vea el día; luego el sensor, salvo 5 min a oscuras siendo de día; sin posición, el sensor.
 * Y salida y puesta del sol (sin red) como las oficiales, ±5 min.
 */
class AutoNightTest {
    private val madrid = doubleArrayOf(40.4168, -3.7038)
    private val berlin = doubleArrayOf(52.5200, 13.4050)

    private fun ms(zone: String, y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()

    private fun assertSun(city: DoubleArray, zone: String, y: Int, mo: Int, d: Int, rise: String, set: String) {
        val noon = ms(zone, y, mo, d, 12, 0)
        val t = RoadInfo.sunTimes(city[0], city[1], noon, TimeZone.getTimeZone(zone))
        val (rh, rm) = rise.split(":").map { it.toInt() }
        val (sh, sm) = set.split(":").map { it.toInt() }
        val r = ms(zone, y, mo, d, rh, rm)
        val s = ms(zone, y, mo, d, sh, sm)
        val tol = 5 * 60_000L
        assertTrue("salida $rise, sale ${(t[0] - r) / 60_000} min de diferencia", kotlin.math.abs(t[0] - r) <= tol)
        assertTrue("puesta $set, sale ${(t[1] - s) / 60_000} min de diferencia", kotlin.math.abs(t[1] - s) <= tol)
        // Y la decisión del sol: de noche 10 min antes de salir y 10 min después de ponerse, de día entre medias.
        assertTrue(AutoNight.sunNight(city[0], city[1], r - 10 * 60_000L))
        assertFalse(AutoNight.sunNight(city[0], city[1], r + 10 * 60_000L))
        assertFalse(AutoNight.sunNight(city[0], city[1], s - 10 * 60_000L))
        assertTrue(AutoNight.sunNight(city[0], city[1], s + 10 * 60_000L))
    }

    @Test
    fun sunriseAndSunsetLikeTheOfficialOnes() {
        assertSun(madrid, "Europe/Madrid", 2026, 6, 21, "06:45", "21:48")
        assertSun(madrid, "Europe/Madrid", 2026, 12, 21, "08:34", "17:51")
        assertSun(berlin, "Europe/Berlin", 2026, 6, 21, "04:43", "21:33")
        assertSun(berlin, "Europe/Berlin", 2026, 12, 21, "08:15", "15:54")
    }

    private val min = 60_000L

    @Test
    fun withoutPositionTheSensorAsBefore() {
        val a = AutoNight()
        assertTrue(a.isNight(0, 5f, true, null))
        assertFalse(a.isNight(min, 800f, false, null))
        assertFalse(a.bySun)
    }

    @Test
    fun aPhoneInTheDarkConsoleFollowsTheSun() {
        val a = AutoNight()
        // Todo el viaje a oscuras: de día, día (aunque el sensor diga noche) y, al ponerse el sol, noche.
        for (m in 0..120L) assertFalse(a.isNight(m * min, 3f, true, false))
        assertTrue(a.bySun)
        assertTrue(a.isNight(121 * min, 3f, true, true))
        // Sin lectura aún (lux < 0): el sol.
        val b = AutoNight()
        assertTrue(b.isNight(0, -1f, false, true))
    }

    @Test
    fun dimLightIsNotSeeingTheDay() {
        val a = AutoNight()
        // Un reflejo de 300 lux en la consola no basta: sigue mandando el sol.
        assertFalse(a.isNight(0, 300f, false, false))
        assertFalse(a.isNight(min, 10f, true, false))
        assertTrue(a.bySun)
        // Ni lo que no llegue al «día» de la histéresis, aunque pase de 500.
        val b = AutoNight()
        b.isNight(0, 600f, false, false, dayLux = 1000f)
        assertFalse(b.isNight(min, 10f, true, false, dayLux = 1000f))
        assertTrue(b.bySun)
    }

    @Test
    fun afterSeeingTheDayTheSensorDecidesUntilFiveMinutesDark() {
        val a = AutoNight()
        assertFalse(a.isNight(0, AutoNight.DAYLIGHT_LUX, false, false))
        assertFalse(a.bySun)
        // Túnel: noche al momento (lo de siempre), y de vuelta a la luz, día.
        assertTrue(a.isNight(min, 4f, true, false))
        assertTrue(a.isNight(3 * min, 4f, true, false))
        assertFalse(a.isNight(4 * min, 900f, false, false))
        // Se guarda el móvil: noche por el sensor 5 min y después manda el sol (día).
        assertTrue(a.isNight(10 * min, 2f, true, false))
        assertTrue(a.isNight(14 * min, 2f, true, false))
        assertFalse(a.isNight(15 * min, 2f, true, false))
        assertTrue(a.bySun)
        assertFalse(a.isNight(40 * min, 2f, true, false))
        // Al sacarlo a la luz vuelve el sensor; un túnel nuevo cuenta desde cero.
        assertFalse(a.isNight(41 * min, 900f, false, false))
        assertFalse(a.bySun)
        assertTrue(a.isNight(42 * min, 4f, true, false))
    }

    @Test
    fun atNightTheSensorStillDecidesOnceItSawTheDay() {
        val a = AutoNight()
        a.isNight(0, 2000f, false, false)
        // Ya de noche por el sol: el sensor manda (a oscuras, noche, por mucho que dure).
        assertTrue(a.isNight(60 * min, 1f, true, true))
        assertTrue(a.isNight(90 * min, 1f, true, true))
        assertFalse(a.bySun)
    }

    @Test
    fun aNewSessionHasNotSeenTheDay() {
        val a = AutoNight()
        a.isNight(0, 2000f, false, false)
        assertTrue(a.isNight(min, 2f, true, false))
        a.reset()
        assertFalse(a.isNight(2 * min, 2f, true, false))
        assertTrue(a.bySun)
        assertEquals(false, a.isNight(3 * min, 2f, true, false))
    }
}
