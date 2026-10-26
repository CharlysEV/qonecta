package com.headqlink.link

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** El fantasma: tu mejor vez en el mismo camino. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class GhostTest {
    /** Un recorrido recto desde (40, -3.7): hacia el norte (o el este) cada 30 m, a speed m/s. */
    private fun line(km: Double, speed: Double, east: Boolean = false): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        var m = 0.0
        while (m <= km * 1000) {
            val dLat = if (east) 0.0 else m / 111_320.0
            val dLon = if (east) m / (111_320.0 * Math.cos(Math.toRadians(40.0))) else 0.0
            out.add(doubleArrayOf(40.0 + dLat, -3.7 + dLon, m / speed))
            m += 30
        }
        return out
    }

    private fun trip(start: Long, km: Double, speed: Double, east: Boolean = false) = TripLog.Trip().apply {
        startMs = start
        this.km = km
        val pts = line(km, speed, east)
        track = pts.map { it }.toTypedArray()
        minutes = Math.round(pts.last()[2] / 60)
    }

    @Test
    fun comparesWithTheFastestTimeToHereOnTheSameRoad() {
        val day = 86_400_000L
        val past = listOf(trip(1 * day, 5.0, 20.0), trip(2 * day, 5.0, 25.0), trip(3 * day, 5.0, 25.0, east = true))
        // Hoy, a 20 m/s, a los 2,5 km: 125 s; la mejor vez llegó aquí en 100 s.
        val r = Ghost.compare(past, line(2.5, 20.0), 10 * day)!!
        assertEquals(25.0, r.deltaSec, 2.0)
        assertEquals(2 * day, r.bestStartMs)
        // El viaje hacia el este salió del mismo sitio pero va por otro camino: no cuenta.
        assertEquals(2, r.runs)
        // Más rápido que nunca: por delante.
        assertTrue(Ghost.compare(past, line(2.5, 30.0), 10 * day)!!.deltaSec < -10)
    }

    @Test
    fun nothingBeforeTheFirstKilometreOrOnANewRoad() {
        val past = listOf(trip(86_400_000L, 5.0, 20.0))
        assertNull(Ghost.compare(past, line(0.6, 20.0), 0))
        assertNull(Ghost.compare(past, line(3.0, 20.0, east = true), 0))
        assertNull(Ghost.compare(emptyList(), line(3.0, 20.0), 0))
    }

    @Test
    fun theRouteRecordIsTheFastestOfARepeatedRoute() {
        val trips = listOf(trip(1, 5.0, 20.0), trip(2, 5.0, 25.0), trip(3, 5.0, 25.0, east = true))
        // Norte: dos veces, la más rápida es la 1; este: una sola vez, sin récord.
        assertEquals(setOf(1), Ghost.routeRecords(trips))
    }
}
