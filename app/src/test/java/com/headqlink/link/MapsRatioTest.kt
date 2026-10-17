package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Los km de Google Maps frente a los del plan (17-10, coche virtual): el plan contaba 771 km y Maps, con las
 * cuatro paradas añadidas, 835; la primera parada salía a 212 km en Qonecta+ y a 230 en Maps.
 */
class MapsRatioTest {
    @Test
    fun mapsKmScaleWhatIsLeft() {
        assertEquals(1.08, RoutePlanner.mapsRatio(771.0, 835.0, 1.0), 1e-9)
        // Sin paradas, Maps 793 km: un 3 % más.
        assertEquals(1.03, RoutePlanner.mapsRatio(771.0, 793.0, 1.0), 1e-9)
    }

    @Test
    fun smallChangesAreIgnored() {
        assertEquals(1.0, RoutePlanner.mapsRatio(771.0, 780.0, 1.0), 1e-9)
        assertEquals(1.08, RoutePlanner.mapsRatio(700.0, 760.0, 1.08), 1e-9)
    }

    @Test
    fun mapsGoingSomewhereElseIsNotUsed() {
        assertEquals(1.0, RoutePlanner.mapsRatio(771.0, 1100.0, 1.08), 1e-9)
        assertEquals(1.0, RoutePlanner.mapsRatio(771.0, 500.0, 1.08), 1e-9)
    }

    @Test
    fun withoutMapsOrNearTheEndTheLastOneStays() {
        assertEquals(1.08, RoutePlanner.mapsRatio(771.0, -1.0, 1.08), 1e-9)
        assertEquals(1.08, RoutePlanner.mapsRatio(5.0, 9.0, 1.08), 1e-9)
    }

    @Test
    fun theStopIsShownAtMapsDistance() {
        val km = doubleArrayOf(0.0, 212.0, 771.0)
        val cum = doubleArrayOf(0.0, 40.0, 150.0)
        val stop = ChargePlanner.Stop(RoutePlanner.Charger(), 212.0, 30.0, 80.0, 25.0, 30.0)
        val r = ChargePlanner.Result(ChargePlanner.Outcome.PLANNED, listOf(stop), 17.0, km, cum, 0.0, 60.0, 67.1)
        r.trend = 1.1
        r.mapsRatio = 1.08
        assertEquals(229.0, r.kmTo(stop), 0.5)
        assertEquals(229.0, r.kmMark(stop.km), 0.5)
        assertEquals(1.188, r.energyFactor(), 1e-9)
        // Desde el km 100: lo recorrido se queda como está.
        val later = ChargePlanner.Result(ChargePlanner.Outcome.PLANNED, listOf(stop), 17.0, km, cum, 100.0, 60.0, 67.1)
        later.mapsRatio = 1.08
        assertEquals(221.0, later.kmMark(212.0), 0.5)
        assertEquals(50.0, later.kmMark(50.0), 1e-9)
    }

    @Test
    fun theEnergyOfWhatIsLeftGrowsWithMapsKm() {
        val km = doubleArrayOf(0.0, 100.0, 200.0)
        val cum = doubleArrayOf(0.0, 17.0, 34.0)
        val scaled = ChargePlanner.scaled(km, cum, 100.0, 1.08)
        assertEquals(17.0, scaled[1], 1e-9)
        assertEquals(17.0 + 17.0 * 1.08, scaled[2], 1e-9)
    }
}
