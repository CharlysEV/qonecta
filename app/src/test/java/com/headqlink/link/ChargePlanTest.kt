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

/** El plan de carga del coche (orden 190): lo que se lee se devuelve igual, salvo lo que se quiere cambiar. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class ChargePlanTest {
    private val full = JSONObject(
        """{"chargeEnable":1,"chargesoc":80,"circulation":2,"cycles":"1,1,1,1,1,0,0","endtime":"07:00","recharge":1,"starttime":"23:30"}"""
    )

    @Test
    fun aFullPlanGoesBackExactlyAsItCame() {
        val p = ChargePlan.parse(full)!!
        assertEquals(
            """{"chargeEnable":1,"chargesoc":80,"circulation":2,"cycles":"1,1,1,1,1,0,0","endtime":"07:00","recharge":1,"starttime":"23:30"}""",
            p.content()
        )
        assertTrue(p.same(ChargePlan.parse(JSONObject(p.content()))))
    }

    @Test
    fun noPlanOrNoLimitSendsNothing() {
        assertNull(ChargePlan.parse(null))
        assertNull(ChargePlan.parse(JSONObject()))
        assertNull(ChargePlan.parse(JSONObject("""{"chargeEnable":1,"starttime":"01:00"}""")))
        assertNull(ChargePlan.parse(JSONObject("""{"chargesoc":0}""")))
    }

    /** leapmotor-api, issue #18: un plan solo con hora de inicio no trae días, fin ni «recharge». */
    @Test
    fun aStartOnlyPlanKeepsItsStartAndGetsTheDefaults() {
        val p = ChargePlan.parse(JSONObject("""{"chargeEnable":1,"chargesoc":90,"starttime":"2:15"}"""))!!
        assertTrue(p.enabled)
        assertEquals("02:15", p.start)
        assertEquals("08:00", p.end)
        assertEquals("1,1,1,1,1,1,1", p.cycles)
        assertEquals(0, p.recharge)
    }

    @Test
    fun aNewWindowKeepsTheDaysAndTheRest() {
        val p = ChargePlan.parse(full)!!.window("02:00", "05:30", 85)
        assertEquals(
            """{"chargeEnable":1,"chargesoc":85,"circulation":2,"cycles":"1,1,1,1,1,0,0","endtime":"05:30","recharge":1,"starttime":"02:00"}""",
            p.content()
        )
        val off = p.disabled()
        assertFalse(off.enabled)
        assertEquals(85, off.soc)
        assertTrue(off.content().startsWith("""{"chargeEnable":0,"chargesoc":85,"""))
    }

    @Test
    fun theLimitStaysWithinWhatTheCarAccepts() {
        assertEquals(50, ChargePlan(true, 20, "00:00", "06:00", null, 0, 0).soc)
        assertEquals(100, ChargePlan(true, 140, "00:00", "06:00", null, 0, 0).soc)
    }

    @Test
    fun badTimesAndDaysFallBack() {
        val p = ChargePlan(true, 80, "25:00", "x", "lunes", 0, 0)
        assertEquals("00:00", p.start)
        assertEquals("08:00", p.end)
        assertEquals("1,1,1,1,1,1,1", p.cycles)
        assertEquals("07:05", ChargePlan.time("7:05:00", "x"))
    }
}
