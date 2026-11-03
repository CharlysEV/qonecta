package com.headqlink.link

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.Collections

/**
 * El borrador de la pestaña «Ruta» del móvil (PhoneRoute): una ruta calculada en el móvil no toca el viaje en curso (ni
 * la instancia del planificador ni sus estáticos: destino, paradas, ida y vuelta, velocidad elegida, avisos) y la hora de
 * salida elegida llega al tiempo previsto de cada tramo. Los servicios abiertos los contesta la prueba (Http.fake), con
 * coordenadas inventadas en Finlandia (lejos de los registros oficiales de España, Alemania y Suiza).
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class DraftPlanTest {
    private val urls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val day = 86_400_000L
    private val today0 = System.currentTimeMillis().let { it - Math.floorMod(it, day) }

    private fun place(name: String, lat: Double, lon: Double) = RoutePlanner.Place().apply {
        this.name = name
        detail = ""
        this.lat = lat
        this.lon = lon
    }

    @Before
    fun setUp() {
        Str.init(RuntimeEnvironment.getApplication())
        RoutePlanner.setManualDestination(null)
        Http.fake = Http.Fake { url, _ -> answer(url) }
    }

    @After
    fun tearDown() {
        Http.fake = null
        RoutePlanner.chargeAlerts = null
    }

    // ------------------------------------------------------------------ servicios de mentira

    private fun answer(url: String): String {
        urls.add(url)
        return when {
            url.contains("router.project-osrm.org") -> osrm(url)
            url.contains("/v1/elevation") -> JSONObject().put("elevation", JSONArray(List(count(url)) { 100.0 })).toString()
            url.contains("/v1/forecast") -> forecast(count(url))
            url.contains("interpreter") -> """{"elements":[]}"""
            else -> throw IllegalStateException("sin respuesta de mentira para " + url.substringBefore('?'))
        }
    }

    /** Cuántos puntos lleva la consulta (latitude=a,b,c). */
    private fun count(url: String) = url.substringAfter("latitude=").substringBefore('&').split(',').size

    /** El tiempo de 72 h desde las 00:00 UTC de hoy: la temperatura de cada hora es su número (para ver cuál se eligió). */
    private fun forecast(n: Int): String {
        val hours = 72
        val one = JSONObject().put("hourly", JSONObject()
            .put("time", JSONArray(List(hours) { today0 / 1000 + it * 3600L }))
            .put("temperature_2m", JSONArray(List(hours) { it.toDouble() }))
            .put("wind_speed_10m", JSONArray(List(hours) { 0.0 }))
            .put("wind_direction_10m", JSONArray(List(hours) { 0.0 }))
            .put("precipitation", JSONArray(List(hours) { 0.0 }))
            .put("weather_code", JSONArray(List(hours) { 1.0 })))
        return if (n == 1) one.toString() else JSONArray(List(n) { JSONObject(one.toString()) }).toString()
    }

    /** OSRM: en línea recta entre los puntos pedidos, a 100 km/h, con un tramo por cada dos puntos seguidos. */
    private fun osrm(url: String): String {
        val pts = url.substringAfter("/driving/").substringBefore('?').split(';').map { c ->
            val (lo, la) = c.split(',').map { it.toDouble() }
            doubleArrayOf(la, lo)
        }
        val coords = JSONArray()
        val legs = JSONArray()
        var total = 0.0
        coords.put(JSONArray().put(pts[0][1]).put(pts[0][0]))
        for (l in 0 until pts.size - 1) {
            val a = pts[l]
            val b = pts[l + 1]
            val dist = JSONArray()
            val dur = JSONArray()
            var legM = 0.0
            var prev = a
            for (k in 1..60) {
                val q = doubleArrayOf(a[0] + (b[0] - a[0]) * k / 60, a[1] + (b[1] - a[1]) * k / 60)
                val m = RoadInfo.dist(prev[0], prev[1], q[0], q[1])
                dist.put(m)
                dur.put(m / (100 / 3.6))
                legM += m
                coords.put(JSONArray().put(q[1]).put(q[0]))
                prev = q
            }
            total += legM
            legs.put(JSONObject().put("distance", legM).put("annotation", JSONObject().put("distance", dist).put("duration", dur)))
        }
        val route = JSONObject().put("distance", total).put("duration", total / (100 / 3.6))
            .put("geometry", JSONObject().put("coordinates", coords)).put("legs", legs)
        return JSONObject().put("routes", JSONArray().put(route)).toString()
    }

    /** Espera a que el hilo de los cargadores acabe (rehace la lista: sube la versión) antes de quitar la red de mentira. */
    private fun waitChargers(p: RoutePlanner.Plan) {
        val until = System.currentTimeMillis() + 5000
        while (p.chargerVersion < 2 && System.currentTimeMillis() < until) Thread.sleep(20)
    }

    // ------------------------------------------------------------------ puras

    @Test
    fun aDepartureTomorrowAsksForMoreDaysOfForecast() {
        val now = today0 + 10 * 3_600_000L
        assertEquals(2, RoutePlanner.forecastDays(now, 0, 3600.0))
        assertEquals(2, RoutePlanner.forecastDays(now, now + 3_600_000L, 3 * 3600.0))
        // Mañana a las 20:00 con 6 h de viaje: se llega pasado mañana a las 2.
        assertEquals(3, RoutePlanner.forecastDays(now, today0 + day + 20 * 3_600_000L, 6 * 3600.0))
        assertEquals(RoutePlanner.MAX_FORECAST_DAYS, RoutePlanner.forecastDays(now, now + 40 * day, 0.0))
    }

    @Test
    fun theWeatherIsTheOneOfTheHourOfPassingFromTheDeparture() {
        val body = forecast(2)
        val idx = intArrayOf(0, 1)
        val eta = doubleArrayOf(0.0, 2 * 3600.0)
        val depart = today0 / 1000 + 30 * 3600L + 600
        val w = RoutePlanner.pickWeather(body, idx, eta, depart)
        assertEquals(30.0, w[0][2], 1e-9)
        assertEquals(32.0, w[1][2], 1e-9)
        // Una respuesta con un solo punto (un objeto, no una lista) también vale.
        assertEquals(5.0, RoutePlanner.pickWeather(forecast(1), intArrayOf(0), doubleArrayOf(0.0), today0 / 1000 + 5 * 3600L)[0][2], 1e-9)
    }

    // ------------------------------------------------------------------ el borrador

    @Test
    fun theDraftDoesNotTouchTheTripInProgress() {
        val alerts = mutableListOf<String>()
        RoutePlanner.chargeAlerts = RoutePlanner.ChargeAlertListener { title, _, _ -> alerts.add(title) }
        assertNull(RoutePlanner.get())
        val d = RoutePlanner.draft(RuntimeEnvironment.getApplication())
        assertTrue(d.isDraft)
        val origin = place("Salida", 61.0, 25.0)
        val dest = place("Destino", 63.7, 25.0)
        // Salida pasado mañana (hora de pared): el tiempo de cada tramo, a esa hora.
        val depart = today0 + 2 * day + 6 * 60_000L
        val p = d.planDraft(origin, dest, null, false, depart)
        waitChargers(p)
        assertEquals(300.0, p.totalKm, 2.0)
        assertEquals(depart, p.departAtMs)
        assertEquals("la salida (hora 48) llega al tiempo del primer tramo", 48.0, p.temp[0], 1e-9)
        assertEquals("y la hora de llegada, al del destino", 51.0, p.destTemp, 1e-9)
        assertTrue("con más días de previsión", urls.any { it.contains("/v1/forecast") && it.contains("forecast_days=3") })
        // La velocidad elegida en el borrador vale para su plan y no para el viaje en curso.
        d.setDraftSpeedCap(100)
        val cp = d.chargePlan(90.0, 69.9, false, false, false)
        assertNotNull(cp)
        assertEquals(100, cp!!.speedCap)
        assertEquals(0, RoutePlanner.speedCap(p))
        // Nada del viaje en curso ha cambiado.
        assertNull("el borrador no es la instancia del viaje", RoutePlanner.get())
        assertNull(RoutePlanner.manualDestination())
        assertTrue(RoutePlanner.vias().isEmpty())
        assertFalse(RoutePlanner.roundTrip())
        assertFalse(RoutePlanner.testMode())
        assertNull(RoutePlanner.testOrigin())
        assertFalse(RoutePlanner.mapsHasStops())
        assertTrue("sin avisos del plan: $alerts", alerts.isEmpty())
        // El repaso de la pestaña tampoco.
        d.draftTick()
        assertNull(RoutePlanner.get())
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun aRoundTripWithAStopIsPlannedWithoutTouchingTheStatics() {
        val d = RoutePlanner.draft(RuntimeEnvironment.getApplication())
        val origin = place("Salida", 61.0, 25.0)
        val via = place("Parada", 61.5, 25.5)
        val dest = place("Destino", 62.0, 25.0)
        val p = d.planDraft(origin, dest, listOf(via), true, 0)
        waitChargers(p)
        assertTrue(p.multi())
        assertTrue(p.roundTrip)
        assertEquals(3, p.waypoints.size)
        assertEquals("Destino", p.waypoints[p.destIndex].name)
        assertEquals(p.totalKm, p.waypointKm[2], 1e-6)
        assertNull(RoutePlanner.get())
        assertNull(RoutePlanner.manualDestination())
        assertTrue(RoutePlanner.vias().isEmpty())
        assertFalse(RoutePlanner.roundTrip())
    }
}
