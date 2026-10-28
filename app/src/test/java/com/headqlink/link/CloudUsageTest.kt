package com.headqlink.link

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Lo que dice la nube de «Consumo» (28-10): el reparto de la energía y los km de una ventana. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = android.app.Application::class)
class CloudUsageTest {
    @Test
    fun theSplitComesAsTextAndGivesThePercentages() {
        val s = CloudUsage.parseSplit(JSONObject("""{"driverEC":"50.12","acEC":"1.1","otherEC":"1.48"}"""))!!
        assertEquals(52.7, s.total(), 1e-9)
        val p = s.percents()!!
        assertEquals(95.1, p[0], 0.05)
        assertEquals(2.1, p[1], 0.05)
        assertEquals(2.8, p[2], 0.05)
        assertNull(CloudUsage.parseSplit(JSONObject("{}")))
        assertNull(CloudUsage.Split(0.0, 0.0, 0.0).percents())
    }

    @Test
    fun kmOfTheWindow() {
        assertEquals(283.4, CloudUsage.parseKm(JSONObject("""{"totalAccumulatedMileage":"283.4","totalmileage":5501}""")), 1e-9)
        assertTrue(CloudUsage.parseKm(JSONObject("{}")).isNaN())
        assertTrue(CloudUsage.parseKm(JSONObject("""{"totalAccumulatedMileage":-3}""")).isNaN())
    }
}
