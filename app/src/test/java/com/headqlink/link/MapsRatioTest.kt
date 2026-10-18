package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /** 18-10: con paradas, la «Llegada» de la notificación de Maps es la de la próxima (546 km, 51 min): no vale para la ruta. */
    @Test
    fun mapsArrivalTimeOnlyCountsForTheDestination() {
        assertEquals(true, RoutePlanner.googleTimeFits(543.0, 5 * 3600L + 26 * 60L, false))
        assertEquals(false, RoutePlanner.googleTimeFits(546.0, 51 * 60L, false))
        assertEquals(false, RoutePlanner.googleTimeFits(543.0, 5 * 3600L + 26 * 60L, true))
        assertEquals(false, RoutePlanner.googleTimeFits(543.0, -1, false))
    }

    /** El recuadro de Maps con paradas (18-10: «51 min» y «80 km · 1:46»): sus km, en km. */
    @Test
    fun theBoxKmAreRead() {
        fun l(t: String, top: Int) = MapsCard.Line(t, 270, top, 470, top + 30)
        val eta = l("51 min", 670)
        assertEquals(80.0, MapsCard.boxKm(listOf(eta, l("80 km · 1:46", 712)), eta), 1e-9)
        assertEquals(1.7, MapsCard.boxKm(listOf(eta, l("1,7 km · 1:46", 712)), eta), 1e-9)
        assertEquals(0.85, MapsCard.boxKm(listOf(eta, l("850 m · 1:46", 712)), eta), 1e-9)
        val both = l("13 min · 9,0 km", 670)
        assertEquals(9.0, MapsCard.boxKm(listOf(both), both), 1e-9)
        assertTrue(MapsCard.boxKm(listOf(eta), eta).isNaN())
    }

    /**
     * El tramo hasta la próxima parada de Maps: el plan contaba 72 km hasta Ocaña y el recuadro de Maps 80 (18-10): ese
     * tramo ×1,11; lo hecho desde que se leyó se descuenta; lo increíble no se usa.
     */
    @Test
    fun theSegmentToTheNextMapsStop() {
        val wps = doubleArrayOf(72.0, 300.0)
        val near = RoutePlanner.nearSegment(wps, 0.0, 80.0, 0.0)!!
        assertEquals(72.0, near[0], 1e-9)
        assertEquals(1.11, near[1], 0.01)
        // 10 km después: quedan 70 de Maps y 62 del plan.
        assertEquals(70.0 / 62.0, RoutePlanner.nearSegment(wps, 10.0, 80.0, 0.0)!![1], 1e-9)
        // Pasada la primera, la segunda (y el recuadro ya habla de ella).
        assertEquals(300.0, RoutePlanner.nearSegment(wps, 72.5, 230.0, 72.5)!![0], 1e-9)
        assertEquals(null, RoutePlanner.nearSegment(wps, 0.0, 150.0, 0.0))
        assertEquals(null, RoutePlanner.nearSegment(wps, 0.0, Double.NaN, 0.0))
        assertEquals(null, RoutePlanner.nearSegment(wps, 71.0, 1.2, 71.0))
    }

    /** Con el tramo de Maps: los km de la parada, los de Maps; el gasto de ese tramo, con su proporción. */
    @Test
    fun theNearSegmentScalesKmAndEnergy() {
        val km = doubleArrayOf(0.0, 72.0, 300.0)
        val cum = doubleArrayOf(0.0, 12.0, 50.0)
        val stop = ChargePlanner.Stop(RoutePlanner.Charger(), 72.0, 30.0, 80.0, 25.0, 30.0)
        val r = ChargePlanner.Result(ChargePlanner.Outcome.PLANNED, listOf(stop), 17.0, km, cum, 0.0, 60.0, 67.1)
        r.mapsRatio = 1.02
        r.nearRatio = 80.0 / 72.0
        r.nearUntilKm = 72.0
        assertEquals(80.0, r.kmTo(stop), 1e-9)
        assertEquals(80.0 + 228 * 1.02, r.kmMark(300.0), 1e-9)
        val two = ChargePlanner.scaledTwo(km, cum, 0.0, 80.0 / 72.0, 72.0, 1.02)
        assertEquals(12.0 * 80 / 72, two[1], 1e-9)
        assertEquals(12.0 * 80 / 72 + 38 * 1.02, two[2], 1e-9)
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
