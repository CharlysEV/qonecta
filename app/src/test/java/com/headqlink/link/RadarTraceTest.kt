package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * Radares por la ruta (18-10): con el trazado de la ruta, el radar de la propia carretera pasada una curva se ve a
 * ~700 m, y el de una vía paralela, no. Sin trazado, como antes (por el rumbo).
 */
class RadarTraceTest {
    private val lat0 = 40.0
    private val lon0 = -3.7
    private val ky = 111_320.0
    private val kx = 111_320.0 * cos(Math.toRadians(lat0))

    private fun ll(x: Double, y: Double) = doubleArrayOf(lat0 + y / ky, lon0 + x / kx)

    /** Recta hacia el norte 300 m y luego una curva a la derecha de radio 500 m (90°), un punto cada 25 m. */
    private fun curve(): RoadInfo.Trace {
        val pts = mutableListOf<DoubleArray>()
        for (i in 0..12) pts.add(doubleArrayOf(0.0, i * 25.0))
        val r = 500.0
        for (i in 1..31) {
            val a = Math.toRadians(i * 90.0 / 31)
            pts.add(doubleArrayOf(r - r * cos(a), 300 + r * sin(a)))
        }
        val lat = DoubleArray(pts.size)
        val lon = DoubleArray(pts.size)
        val km = DoubleArray(pts.size)
        var acc = 0.0
        pts.forEachIndexed { i, p ->
            val g = ll(p[0], p[1])
            lat[i] = g[0]
            lon[i] = g[1]
            if (i > 0) acc += Math.hypot(p[0] - pts[i - 1][0], p[1] - pts[i - 1][1])
            km[i] = acc / 1000
        }
        return RoadInfo.Trace(lat, lon, km)
    }

    /** Un radar a 8 m a la derecha de la curva, a unos 650 m por la carretera; otro en una vía paralela a 70 m. */
    @Test
    fun theCameraPastTheBendIsSeenAndTheParallelRoadOneIsNot() {
        val t = curve()
        val a = Math.toRadians(40.0)
        val r = 500.0
        val onRoad = ll(r - (r - 8) * cos(a), 300 + (r - 8) * sin(a))
        val parallel = ll(70.0, 250.0)
        val cams = listOf(doubleArrayOf(onRoad[0], onRoad[1], 80.0, Double.NaN, 1.0), doubleArrayOf(parallel[0], parallel[1], 80.0, 0.0, 2.0))
        val car = ll(0.0, 0.0)
        // Por la ruta: el de la curva, a ~650 m (300 de recta y 349 de curva); el paralelo, nunca.
        val got = RoadInfo.nearestAhead(cams, car[0], car[1], 0.0, false, t)
        assertNotNull(got)
        assertEquals(0, got!![1].toInt())
        assertEquals(649.0, got[0], 15.0)
        // Por el rumbo (sin ruta), el de la curva queda fuera de la línea de la marcha a esa distancia.
        val byHeading = RoadInfo.nearestAhead(cams.subList(0, 1), car[0], car[1], 0.0, false, null)
        assertNull(byHeading)
    }

    /** Sin sentido conocido y claramente a la izquierda (la calzada de enfrente): no es para nosotros. */
    @Test
    fun aCameraOnTheOtherCarriagewayIsNot() {
        val t = curve()
        val left = ll(-20.0, 200.0)
        val car = ll(0.0, 0.0)
        assertNull(RoadInfo.nearestAhead(listOf(doubleArrayOf(left[0], left[1], 80.0, Double.NaN, 1.0)), car[0], car[1], 0.0, false, t))
        // Con sentido contrario, tampoco aunque esté junto a la carretera.
        val right = ll(6.0, 200.0)
        assertNull(RoadInfo.nearestAhead(listOf(doubleArrayOf(right[0], right[1], 80.0, 180.0, 1.0)), car[0], car[1], 0.0, false, t))
    }

    /** Fuera de la ruta (a más de 40 m del trazado): por el rumbo, como sin ruta. */
    @Test
    fun offTheRouteItGoesByHeading() {
        val t = curve()
        val car = ll(200.0, 0.0)
        val cam = ll(200.0, 400.0)
        val got = RoadInfo.nearestAhead(listOf(doubleArrayOf(cam[0], cam[1], 50.0, Double.NaN, 1.0)), car[0], car[1], 0.0, false, t)
        assertNotNull(got)
        assertEquals(400.0, got!![0], 5.0)
    }
}
