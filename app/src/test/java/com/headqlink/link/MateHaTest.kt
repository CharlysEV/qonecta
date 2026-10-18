package com.headqlink.link

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
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Config as RoboConfig

/** Leapmotor Mate en Home Assistant: reconocer el coche, pasar sus datos (y unidades) al estado de HeadQLink y nombres cortos. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@RoboConfig(sdk = [35], application = android.app.Application::class)
class MateHaTest {
    private val dev = "leapmotor_mate_123456_leapmotor_mate"
    private val devName = "Leapmotor Mate 123456 (leapmotor_mate)"

    private fun st(id: String, state: String, name: String, unit: String? = null): JSONObject {
        val a = JSONObject().put("friendly_name", name)
        if (unit != null) a.put("unit_of_measurement", unit)
        return JSONObject().put("entity_id", id).put("state", state).put("attributes", a)
    }

    private fun mate(d: String = dev, dn: String = devName, imperial: Boolean = false) = listOf(
        st("sensor.${d}_battery", "64.5", "$dn Battery", "%"),
        st("sensor.${d}_range", if (imperial) "164" else "264", "$dn Range", if (imperial) "mi" else "km"),
        st("sensor.${d}_battery_temp", if (imperial) "77" else "25", "$dn Battery Temp", if (imperial) "°F" else "°C"),
        st("sensor.${d}_inside_temp", if (imperial) "79.7" else "26.5", "$dn Inside Temp", if (imperial) "°F" else "°C"),
        st("sensor.${d}_odometer", if (imperial) "3165.886" else "5095", "$dn Odometer", if (imperial) "mi" else "km"),
        st("sensor.${d}_tyre_fl", if (imperial) "35.1" else "2.42", "$dn Tyre FL", if (imperial) "psi" else "bar"),
        st("sensor.${d}_charge_power", "85.2", "$dn Charge Power", "kW"),
        st("sensor.${d}_charge_time_remaining", "25", "$dn Charge Time Remaining", "min"),
        st("sensor.${d}_state", "parked", "$dn State"),
        st("sensor.${d}_data_timestamp", "2026-10-18T10:28:34+00:00", "$dn Data Timestamp"),
        st("binary_sensor.${d}_charging", "on", "$dn Charging"),
        st("binary_sensor.${d}_plug_connected", "on", "$dn Plug Connected"),
        st("binary_sensor.${d}_door_front_left", "on", "$dn Door Front Left"),
        st("binary_sensor.${d}_trunk", "off", "$dn Trunk"),
        st("lock.${d}_door_lock", "locked", "$dn Door Lock"),
        st("switch.${d}_trunk", "off", "$dn Trunk"),
        st("number.${d}_charge_limit", "90", "$dn Charge Limit", "%"),
        st("button.${d}_preheat_battery", "unknown", "$dn Preheat Battery"),
        st("button.${d}_a_c_off", "unknown", "$dn A/C Off"),
    )

    private fun states(vararg lists: List<JSONObject>) = JSONArray().apply { lists.forEach { l -> l.forEach { put(it) } } }

    private val home = listOf(
        st("light.salon", "on", "Salón"),
        st("sensor.sun_next_rising", "2026-10-19T06:12:00+00:00", "Sun Next rising"),
        st("sensor.battery", "80", "Móvil Battery", "%"),
    )

    @Test
    fun theCarIsFoundAmongTheHouseAndItsElementsByName() {
        val car = MateHa.find(states(home, mate()))
        assertNotNull(car)
        assertEquals(dev, car!!.prefix)
        assertEquals("sensor.${dev}_battery", car.id("sensor.battery"))
        // «trunk» está como binary_sensor y como switch: cada uno con su dominio.
        assertTrue(car.has("binary_sensor.trunk") && car.has("switch.trunk"))
        // «a_c_off» no se confunde con «off» ni «battery_temp» con «battery».
        assertEquals("sensor.${dev}_battery_temp", car.id("sensor.battery_temp"))
        assertEquals("button.${dev}_a_c_off", car.id("button.a_c_off"))
        assertFalse(car.byKey.values.any { it.optString("entity_id") == "sensor.battery" })
        assertNull(MateHa.find(states(home)))
    }

    @Test
    fun withTwoCarsTheOneWithMoreElements() {
        val other = mate("leapmotor_mate_999999_leapmotor_mate", "Leapmotor Mate 999999 (leapmotor_mate)").take(6)
        val car = MateHa.find(states(other, mate()))
        assertEquals(dev, car!!.prefix)
    }

    @Test
    fun americanUnitsBecomeMetricInTheCarStatus() {
        val s = MateHa.status(MateHa.find(states(mate(imperial = true)))!!)
        assertEquals(64.5, s.socBest(), 1e-9)
        assertEquals(264.0, s.rangeKm, 1.0)
        assertEquals(25.0, s.minBatteryTempC, 0.1)
        assertEquals(26.5, s.interiorTempC, 0.1)
        assertEquals(5095.0, s.odometerKm, 1.0)
        assertEquals(2.42, s.tyreBar(LeapStatus.FL), 0.01)
        assertTrue(s.pluggedIn() && s.charging())
        // A 85 kW es carga rápida (Mate no dice si es CA o CC).
        assertTrue(s.dcPlugged())
        assertEquals(25.0, s.chargeRemainMin, 1e-9)
        assertEquals(true, s.locked)
        assertEquals(true, s.doorOpen(LeapStatus.FL))
        assertEquals(false, s.bootOpen)
        assertTrue(s.carTimeMs > 0)
        // En métrico, lo mismo.
        val m = MateHa.status(MateHa.find(states(mate()))!!)
        assertEquals(m.rangeKm, s.rangeKm, 1.0)
        assertEquals(m.tyreBar(LeapStatus.FL), s.tyreBar(LeapStatus.FL), 0.01)
    }

    @Test
    fun theHouseKeepsOnlyHouseThings() {
        val all = states(home, mate())
        val list = (0 until all.length()).map { HomeAssistant.parse(all.getJSONObject(it)) }.toMutableList()
        assertTrue(HomeAssistant.removeMate(list, all))
        assertEquals(listOf("light.salon", "sensor.sun_next_rising", "sensor.battery"), list.map { it.id })
        val onlyHome = states(home)
        val l2 = (0 until onlyHome.length()).map { HomeAssistant.parse(onlyHome.getJSONObject(it)) }.toMutableList()
        assertFalse(HomeAssistant.removeMate(l2, onlyHome))
        assertEquals(3, l2.size)
    }

    /** Estado: la foto con el dato del coche más reciente; a igualdad, la de la nube (trae más cosas). */
    @Test
    fun theStatusShowsTheFreshestOfCloudAndMate() {
        fun snap(iso: String) = CarCloud.Snapshot(CarCloud.State.OK, LeapStatus.parse(JSONObject().put("soc", 50).put("collectTime", iso)),
            0, 0, 70.0, "", 0, false)
        val older = snap("2026-10-18T10:00:00+00:00")
        val newer = snap("2026-10-18T10:05:00+00:00")
        val none = CarCloud.Snapshot.of(CarCloud.State.NO_ACCOUNT)
        assertEquals(newer, MateFeed.freshest(older, newer))
        assertEquals(newer, MateFeed.freshest(newer, older))
        val same = snap("2026-10-18T10:05:00+00:00")
        assertEquals(newer, MateFeed.freshest(newer, same))
        assertEquals(older, MateFeed.freshest(none, older))
        assertEquals(older, MateFeed.freshest(older, null))
        // Sin Mate, la de la nube tal cual (también sin datos); la demostración manda siempre.
        assertEquals(none, MateFeed.freshest(none, null))
        val demo = CarCloud.Snapshot(CarCloud.State.OK, older.status, 0, 0, 70.0, "", 0, true)
        assertEquals(demo, MateFeed.freshest(demo, newer))
    }
}
