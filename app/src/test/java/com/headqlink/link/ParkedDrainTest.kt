package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gasto con el coche parado: lo que baja la batería entre dos viajes, lo normal en cada coche y lo raro. */
class ParkedDrainTest {
    private val h = 3_600_000L

    private fun trip(startH: Long, minutes: Long, socS: Double, socE: Double, odoS: Double, odoE: Double) = TripLog.Trip().apply {
        startMs = startH * h
        this.minutes = minutes
        socStart = socS
        socEnd = socE
        odoStart = odoS
        odoEnd = odoE
        capKwh = 81.9
    }

    @Test
    fun aNightParkedIsMeasuredInPercentAndKwh() {
        // Llega a las 22:00 con 70,0 % y sale a las 08:00 con 69,8 %: 10 h, 0,2 % (0,16 kWh), 0,48 %/día.
        val p = ParkedDrain.park(70.0, 5000.0, 22 * h, 69.8, 5000.2, 32 * h, 81.9)
        assertNotNull(p)
        assertEquals(10.0, p!!.hours(), 1e-9)
        assertEquals(0.2, p.dropPct(), 1e-9)
        assertEquals(0.164, p.kwh(), 0.001)
        assertEquals(0.48, p.perDayPct(), 0.001)
    }

    @Test
    fun drivingChargingOrShortStopsDoNotCount() {
        assertNull(ParkedDrain.park(70.0, 5000.0, 0, 68.0, 5012.0, 10 * h, 81.9)) // se condujo sin la app
        assertNull(ParkedDrain.park(40.0, 5000.0, 0, 80.0, 5000.0, 10 * h, 81.9)) // se cargó
        assertNull(ParkedDrain.park(70.0, 5000.0, 0, 69.9, 5000.0, h, 81.9)) // menos de 2 h
        assertNull(ParkedDrain.park(Double.NaN, 5000.0, 0, 69.9, 5000.0, 10 * h, 81.9)) // sin datos del coche
        // Una lectura de 0,1 % más alta (el redondeo) no es una carga: bajada 0.
        assertEquals(0.0, ParkedDrain.park(70.0, 5000.0, 0, 70.1, 5000.0, 10 * h, 81.9)!!.dropPct(), 1e-9)
    }

    @Test
    fun parksBetweenTripsMostRecentFirst() {
        val trips = listOf(
            trip(48, 30, 69.0, 66.0, 5020.0, 5030.0), // tercer viaje
            trip(0, 30, 80.0, 75.0, 5000.0, 5010.0), // primero
            trip(20, 60, 74.8, 69.5, 5010.0, 5020.0), // segundo
        )
        val parks = ParkedDrain.parks(trips, 81.9)
        assertEquals(2, parks.size)
        // El más reciente: del segundo (acaba a las 21 h con 69,5) al tercero (48 h con 69,0).
        assertEquals(0.5, parks[0].dropPct(), 1e-9)
        assertEquals(27.0, parks[0].hours(), 1e-9)
        assertEquals(0.2, parks[1].dropPct(), 1e-9)
    }

    @Test
    fun typicalAndUnusual() {
        val night = { drop: Double -> ParkedDrain.Park(0, 12 * h, 70.0, 70.0 - drop, 81.9) }
        val parks = listOf(night(0.1), night(0.2), night(0.3), night(0.2))
        // Mediana de 0,2 % en 12 h = 0,4 %/día.
        assertEquals(0.4, ParkedDrain.typicalPerDay(parks), 1e-9)
        assertEquals(ParkedDrain.DEFAULT_TYPICAL, ParkedDrain.typicalPerDay(parks.take(2)), 1e-9)
        assertFalse(ParkedDrain.unusual(night(0.3), 0.4))
        assertTrue(ParkedDrain.unusual(night(1.5), 0.4)) // 3 %/día
        // Corto o con muy poca bajada: no se dice (el % va de 0,1 en 0,1).
        assertFalse(ParkedDrain.unusual(ParkedDrain.Park(0, 2 * h, 70.0, 69.6, 81.9), 0.4))
        assertFalse(ParkedDrain.unusual(ParkedDrain.Park(0, 4 * h, 70.0, 69.6, 81.9), 0.4))
    }

    @Test
    fun summaryPerDayAndMonth() {
        val parks = listOf(ParkedDrain.Park(0, 12 * h, 70.0, 69.8, 81.9), ParkedDrain.Park(20 * h, 32 * h, 69.0, 68.8, 81.9))
        val s = ParkedDrain.summary(parks, 0)
        assertEquals(2, s.count)
        assertEquals(0.4, s.perDayPct(), 1e-9)
        assertEquals(0.4 / 100 * 81.9 * 30, s.kwhPerMonth(), 1e-6)
    }
}
