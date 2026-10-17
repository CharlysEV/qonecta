package com.headqlink.link

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** «Localizar»: la posición del coche en el estado (solo bajo demanda), su hemisferio y la distancia en pantalla. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class CarFindTest {
    private fun data(json: String) = JSONObject(json)

    @Test
    fun signedSignalsWin() {
        val p = LeapStatus.position(data("""{"signal":{"3":"40.41","2":"-3.70","3725":"40.41","3724":"3.70"}}"""))!!
        assertEquals(40.41, p.lat, 1e-9)
        assertEquals(-3.70, p.lon, 1e-9)
        assertTrue(p.signed)
    }

    @Test
    fun absoluteValuesTakeThePhonesHemisphere() {
        val p = LeapStatus.position(data("""{"signal":{"3725":40.41,"3724":3.70}}"""))!!
        assertFalse(p.signed)
        val near = p.near(40.0, -3.5)
        assertEquals(40.41, near[0], 1e-9)
        assertEquals(-3.70, near[1], 1e-9)
        // Sin saber dónde está el móvil, tal cual.
        assertEquals(3.70, p.near(Double.NaN, Double.NaN)[1], 1e-9)
    }

    @Test
    fun namedFieldsAndMissingPosition() {
        val p = LeapStatus.position(data("""{"latitude":"41.38","longitude":"2.17"}"""))!!
        assertTrue(p.signed)
        assertEquals(2.17, p.lon, 1e-9)
        assertNull(LeapStatus.position(data("""{"signal":{"1204":80}}""")))
        assertNull(LeapStatus.position(data("""{"signal":{"3":0,"2":0}}""")))
        assertNull(LeapStatus.position(null))
    }

    @Test
    fun distanceOnScreen() {
        assertEquals("350 m", CarOrders.distance(347.0))
        assertEquals("10 m", CarOrders.distance(2.0))
        assertTrue(CarOrders.distance(1234.0).matches(Regex("1[.,]2 km")))
    }
}
