package com.headqlink.link

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Viajes que no se guardan: el coche aparcado con el GPS bailando (28-10, LPMFF: 2,4 km en 18 min sin moverse). */
class TripParkedTest {
    /** Puntos alrededor de (lat, lon) a los metros dados al norte. */
    private fun around(vararg northM: Double): List<DoubleArray> =
        northM.map { m -> doubleArrayOf(40.40 + m / 111_320.0, -3.70, 0.0) }

    @Test
    fun aCarThatNeverLeftTheSpotIsNotATrip() {
        assertTrue(TripLog.parkedOnly(around(0.0, 40.0, -60.0, 120.0, 30.0), 9.0))
    }

    @Test
    fun aRealTripIsKept() {
        // Se alejó 1,5 km.
        assertFalse(TripLog.parkedOnly(around(0.0, 400.0, 900.0, 1500.0), 50.0))
        // Maniobras en un aparcamiento grande, pero pasó de 20 km/h: se guarda.
        assertFalse(TripLog.parkedOnly(around(0.0, 80.0, 150.0), 32.0))
        // Una vuelta a la manzana que vuelve a la salida: se alejó más de 200 m.
        assertFalse(TripLog.parkedOnly(around(0.0, 250.0, 0.0), 15.0))
    }

    @Test
    fun withoutPositionsItCannotTell() {
        assertFalse(TripLog.parkedOnly(emptyList(), 0.0))
    }
}
