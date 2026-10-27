package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

/**
 * La carretera por delante sin ruta (RoadAhead, 18-10): sigue la autovía y no la salida ni la vía de servicio paralela;
 * con ella, el radar de la autovía se ve y el de la vía de servicio, no.
 */
class RoadAheadTest {
    private val lat0 = 40.0
    private val lon0 = -3.7
    private val ky = 111_320.0
    private val kx = 111_320.0 * cos(Math.toRadians(lat0))

    private fun way(nodes: LongArray, xy: List<DoubleArray>, ref: String, link: Boolean, oneway: Int) = RoadAhead.Way(
        nodes, DoubleArray(xy.size) { lat0 + xy[it][1] / ky }, DoubleArray(xy.size) { lon0 + xy[it][0] / kx }, ref, "", link, oneway, null)

    /**
     * Autovía A-9 hacia el norte en dos vías (0–1000 m y 1000–3000 m, curva a la derecha en la segunda), una salida en el
     * nodo de los 1000 m que se abre a la izquierda y una vía de servicio paralela a 40 m.
     */
    private fun roads(): RoadAhead {
        val a1 = way(longArrayOf(1, 2, 3), listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 500.0), doubleArrayOf(0.0, 1000.0)), "A-9", false, 1)
        val curve = (0..10).map { i ->
            val a = Math.toRadians(i * 6.0)
            doubleArrayOf(1500 - 1500 * cos(a), 1000 + 1500 * Math.sin(a))
        }
        val a2 = way(LongArray(curve.size) { if (it == 0) 3L else 100L + it }, curve, "A-9", false, 1)
        val exit = way(longArrayOf(3, 50, 51), listOf(doubleArrayOf(0.0, 1000.0), doubleArrayOf(-60.0, 1300.0), doubleArrayOf(-200.0, 1600.0)), "", true, 1)
        val service = way(longArrayOf(70, 71), listOf(doubleArrayOf(40.0, 0.0), doubleArrayOf(40.0, 3000.0)), "", false, 0)
        return RoadAhead(listOf(a1, a2, exit, service))
    }

    private fun ll(x: Double, y: Double) = doubleArrayOf(lat0 + y / ky, lon0 + x / kx)

    @Test
    fun itFollowsTheMotorwayPastTheJunction() {
        val car = ll(0.0, 200.0)
        val t = roads().ahead(car[0], car[1], 0.0, 1600.0)
        assertNotNull(t)
        // Hasta 1,6 km como poco (acaba en el punto siguiente de la vía).
        assertTrue("km ${t!!.km.last()}", t.km.last() >= 1.6 && t.km.last() < 2.0)
        // Al final va por la curva de la autovía (a la derecha), no por la salida (a la izquierda).
        val endX = (t.lon.last() - lon0) * kx
        assertTrue("x final $endX", endX > 5)
    }

    @Test
    fun theMotorwayCameraPastTheBendIsSeenAndTheServiceRoadOneIsNot() {
        val car = ll(0.0, 200.0)
        val t = roads().ahead(car[0], car[1], 0.0, 1600.0)
        // Radar en la curva de la autovía (a unos 700 m por ella) y otro en la vía de servicio (paralela a 40 m).
        val a = Math.toRadians(6.0)
        val onCurve = ll(1500 - 1495 * cos(a), 1000 + 1495 * Math.sin(a))
        val onService = ll(40.0, 600.0)
        val cams = listOf(doubleArrayOf(onCurve[0], onCurve[1], 100.0, Double.NaN, 1.0), doubleArrayOf(onService[0], onService[1], 50.0, Double.NaN, 2.0))
        val got = RoadInfo.nearestAhead(cams, car[0], car[1], 0.0, true, t)
        assertNotNull(got)
        assertEquals(0, got!![1].toInt())
        assertEquals(957.0, got[0], 20.0)
    }

    /** Contra el sentido de una vía de un sentido, o lejos de todas: no se sabe por dónde va (null: por el rumbo). */
    @Test
    fun wrongWayOrNoRoadGivesNothing() {
        val r = roads()
        val car = ll(0.0, 200.0)
        assertNull(r.ahead(car[0], car[1], 180.0, 1600.0))
        val far = ll(500.0, 200.0)
        assertNull(r.ahead(far[0], far[1], 0.0, 1600.0))
    }
}
