package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fin de la ruta: cuándo se da por llegado, lo previsto para lo hecho y qué dato del coche sirve para las cuentas. */
class RouteEndTest {
    @Test
    fun closeAlongTheRouteIsAlwaysArrival() {
        assertTrue(RoutePlanner.arrived(0.25, 0.2, false, false))
    }

    @Test
    fun parkingAFewHundredMetresAwayCountsOnceStoppedOrWhenMapsEnds() {
        // Un parking a 200 m en línea recta, con 600 m de ruta por delante (no la sigue hasta el final).
        assertFalse(RoutePlanner.arrived(0.6, 0.2, false, false))
        assertTrue(RoutePlanner.arrived(0.6, 0.2, true, false))
        assertTrue(RoutePlanner.arrived(0.6, 0.2, false, true))
        // A 600 m en línea recta, no.
        assertFalse(RoutePlanner.arrived(0.8, 0.6, true, true))
    }

    @Test
    fun theStartOfARoundTripIsNotItsEnd() {
        // Ida y vuelta: la salida está junto al final, pero queda toda la ruta.
        assertFalse(RoutePlanner.arrived(40.0, 0.05, true, false))
    }

    @Test
    fun theForecastCountsOnlyWhatWasDriven() {
        val cum = doubleArrayOf(0.0, 1.0, 2.5, 4.0, 5.0)
        assertEquals(5.0, RoutePlanner.predictedKwh(cum, 0, 4), 1e-9)
        // Terminada en el punto 2 (aparcado antes): lo previsto hasta ahí.
        assertEquals(2.5, RoutePlanner.predictedKwh(cum, 0, 2), 1e-9)
        assertEquals(1.5, RoutePlanner.predictedKwh(cum, 1, 2), 1e-9)
        assertEquals(0.0, RoutePlanner.predictedKwh(cum, 3, 2), 1e-9)
        assertEquals(5.0, RoutePlanner.predictedKwh(cum, -3, 99), 1e-9)
        assertTrue(RoutePlanner.predictedKwh(DoubleArray(0), 0, 1).isNaN())
    }

    @Test
    fun onlyCarDataFromAroundTheArrivalCloses() {
        val arrived = 1_000_000_000L
        assertTrue(RoutePlanner.freshEnough(arrived + 30_000, arrived))
        assertTrue(RoutePlanner.freshEnough(arrived - 90_000, arrived))
        assertFalse(RoutePlanner.freshEnough(arrived - 5 * 60_000, arrived))
        assertFalse(RoutePlanner.freshEnough(0, arrived))
    }
}
