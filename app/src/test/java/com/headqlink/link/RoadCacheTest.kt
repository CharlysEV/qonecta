package com.headqlink.link

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * El límite sale de las carreteras de alrededor (RoadAhead.match) y estas se guardan en el móvil (RoadCache): el 27-10 no
 * llegó ni una consulta del límite a Overpass en toda la tarde y el cartel no salió.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class RoadCacheTest {
    private val day = 86_400_000L

    /** Una vía de oeste a este por el paralelo 40, como la da Overpass («out body geom»). */
    private fun element(id: Long, lat: Double, lon0: Double, lon1: Double, vararg kv: String) = JSONObject().apply {
        put("type", "way")
        put("nodes", JSONArray().put(id * 10).put(id * 10 + 1))
        put("geometry", JSONArray().put(JSONObject().put("lat", lat).put("lon", lon0)).put(JSONObject().put("lat", lat).put("lon", lon1)))
        put("tags", JSONObject().apply { for (i in kv.indices step 2) put(kv[i], kv[i + 1]) })
    }

    private fun roads(vararg els: JSONObject) = RoadAhead(RoadAhead.parse(JSONArray().apply { els.forEach { put(it) } }))

    @Test
    fun theLimitComesFromTheRoadBeingDriven() {
        val r = roads(
            element(1, 40.0, -3.70, -3.68, "highway", "primary", "maxspeed:forward", "70", "maxspeed:backward", "50", "name", "Carretera"),
            element(2, 40.0009, -3.70, -3.68, "highway", "secondary", "maxspeed", "90"),
        )
        // Hacia el este, por la primera (a ~5 m): la del sentido del dibujo.
        val east = r.match(40.00005, -3.69, 90.0)
        assertNotNull(east)
        assertTrue(east!!.forward)
        assertEquals("Carretera", east.way.name)
        assertEquals(listOf(70, 0), RoadInfo.limitFor(east.way.tags, east.forward, "ES").toList())
        // Hacia el oeste, la misma vía al revés: maxspeed:backward.
        val west = r.match(40.00005, -3.69, 270.0)
        assertFalse(west!!.forward)
        assertEquals(listOf(50, 0), RoadInfo.limitFor(west.way.tags, west.forward, "ES").toList())
        // Cruzándola (rumbo norte) o lejos de las dos (una calle que no está entre las CLASSES): no se sabe.
        assertNull(r.match(40.00005, -3.69, 0.0))
        assertNull(r.match(40.0004, -3.69, 90.0))
    }

    @Test
    fun aOneWayRoadIsNotMatchedAgainstItsDirection() {
        val r = roads(element(1, 40.0, -3.70, -3.68, "highway", "motorway", "maxspeed", "120"))
        assertNotNull(r.match(40.0, -3.69, 90.0))
        assertNull(r.match(40.0, -3.69, 270.0))
    }

    @Test
    fun onlyTheNeededTagsAreKeptAndTheyComeBackFromTheFile() {
        val r = roads(element(7, 40.123456, -3.654321, -3.6, "highway", "trunk", "maxspeed", "100", "oneway", "yes",
            "surface", "asphalt", "operator", "x"))
        val w = r.ways()[0]
        assertFalse(w.tags.has("surface"))
        assertFalse(w.tags.has("operator"))
        val back = RoadAhead.fromJson(JSONArray(RoadAhead.toJson(r.ways()).toString()))
        assertEquals(1, back.size)
        val b = back[0]
        assertEquals(listOf(70L, 71L), b.nodes.toList())
        assertEquals(40.123456, b.lat[0], 1e-9)
        assertEquals(-3.654321, b.lon[0], 1e-9)
        assertEquals("100", b.tags.optString("maxspeed"))
        assertEquals(1, b.oneway)
        assertEquals(w.tags.toString(), b.tags.toString())
    }

    @Test
    fun fileNamesKeepTheCentreAndTheTime() {
        val n = RoadCache.name(40.4168, -3.7038, 1_800_000_000_123L)
        assertEquals("r_40416800_-3703800_1800000000.json.gz", n)
        val p = RoadCache.parseName(n)!!
        assertEquals(40.4168, p[0], 1e-9)
        assertEquals(-3.7038, p[1], 1e-9)
        assertEquals(1_800_000_000_000.0, p[2], 0.0)
        assertNull(RoadCache.parseName("chargers-cache-v2.json"))
        assertNull(RoadCache.parseName("r_1_2_3.json.gz.tmp"))
    }

    @Test
    fun theNewestNearbyLoadIsUsed() {
        val now = 100 * day
        val saved = listOf(
            doubleArrayOf(40.0, -3.7, (now - 10 * day).toDouble()),
            doubleArrayOf(40.005, -3.7, (now - 2 * day).toDouble()), // a ~550 m, más nueva
            doubleArrayOf(40.05, -3.7, (now - day).toDouble()), // a ~5,5 km
            doubleArrayOf(40.0, -3.7, (now - 61 * day).toDouble()), // caducada
        )
        assertEquals(1, RoadCache.pick(saved, 40.0, -3.7, now))
        assertEquals(-1, RoadCache.pick(saved, 40.02, -3.7, now))
        assertEquals(-1, RoadCache.pick(emptyList(), 40.0, -3.7, now))
    }

    @Test
    fun oldAndSurplusLoadsAreDeleted() {
        val now = 400 * day
        val names = (0 until RoadCache.MAX_FILES + 2).map { RoadCache.name(40.0 + it * 0.01, -3.7, now - day - it * 1000L) } +
            RoadCache.name(41.0, -3.7, now - 61 * day) + "otro.txt"
        val gone = RoadCache.toDelete(names, now)
        assertEquals(3, gone.size)
        assertTrue(gone.contains(RoadCache.name(41.0, -3.7, now - 61 * day)))
        // Las dos más viejas de las que no han caducado.
        assertTrue(gone.contains(names[RoadCache.MAX_FILES + 1]))
        assertTrue(gone.contains(names[RoadCache.MAX_FILES]))
    }
}
