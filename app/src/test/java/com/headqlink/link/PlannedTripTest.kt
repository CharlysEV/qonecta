package com.headqlink.link

import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

/**
 * La ruta planificada en el móvil para el próximo viaje (PlannedTrip): se guarda y se lee entera, caduca a las 48 h o al
 * usarse, la respuesta «Sí»/«No» de la tarjeta del coche se respeta, al aplicarla el viaje en curso pasa a ser el del
 * móvil (con su velocidad) y las paradas fijadas a mano en el móvil vuelven en el plan del coche, y se dice si el plan de
 * ahora ya no es el del móvil. Coordenadas inventadas.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class PlannedTripTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private fun place(name: String, lat: Double, lon: Double) = RoutePlanner.Place().apply {
        this.name = name
        detail = "$name, calle 1"
        this.lat = lat
        this.lon = lon
    }

    @Before
    fun setUp() {
        Str.init(app)
        PlannedTrip.clear(app)
        RoutePlanner.setManualDestination(null)
    }

    @After
    fun tearDown() {
        PlannedTrip.clear(app)
        RoutePlanner.setManualDestination(null)
    }

    private fun trip(now: Long = System.currentTimeMillis()) = PlannedTrip().apply {
        dest = place("Destino", 63.7, 25.0)
        vias = mutableListOf(place("Parada", 62.0, 25.1))
        round = true
        pins = mutableListOf(ChargerMarks.Mark().apply {
            id = "dgt:a"
            name = "Cargador A"
            lat = 62.08
            lon = 25.0
            kind = ChargerMarks.FAV
        })
        speedCap = 110
        departAtMs = now + 3_600_000L
        savedAtMs = now
        stops = mutableListOf(PlannedTrip.Stop().apply {
            name = "Cargador A"
            key = "dgt:a"
            lat = 62.08
            lon = 25.0
        })
        socPct = 74.0
        arrivalPct = 21.0
        totalKm = 300.0
        route = mutableListOf(doubleArrayOf(62.08, 25.0, 120.0), doubleArrayOf(63.7, 25.0, 300.0))
    }

    // ------------------------------------------------------------------ guardar, caducar y responder

    @Test
    fun itIsSavedAndReadWhole() {
        val t = PlannedTrip.fromJson(trip().toJson())!!
        assertEquals("Destino", t.dest.name)
        assertEquals("Destino, calle 1", t.dest.detail)
        assertEquals(63.7, t.dest.lat, 1e-9)
        assertEquals(listOf("Parada"), t.vias.map { it.name })
        assertTrue(t.round)
        assertEquals(1, t.pins.size)
        assertEquals("dgt:a", t.pins[0].id)
        assertEquals(ChargerMarks.FAV, t.pins[0].kind)
        assertEquals(110, t.speedCap)
        assertEquals(listOf("dgt:a"), t.stops.map { it.key })
        assertEquals(74.0, t.socPct, 1e-9)
        assertEquals(2, t.route.size)
        assertEquals(120.0, t.route[0][2], 1e-9)
        assertNull(PlannedTrip.fromJson(""))
        assertNull(PlannedTrip.fromJson("{roto"))
    }

    @Test
    fun itExpiresAfter48HoursAndIsForgotten() {
        val now = System.currentTimeMillis()
        assertFalse(trip(now).expired(now + 47 * 3_600_000L))
        assertTrue(trip(now).expired(now + 49 * 3_600_000L))
        // Guardada hace 49 h: no se ofrece y se borra.
        PlannedTrip.save(app, trip(now - 49 * 3_600_000L))
        assertNull(PlannedTrip.peek(app))
        assertEquals("", Config(app).getString(PlannedTrip.K))
    }

    @Test
    fun yesGivesItAndNoDiscardsItAndBothUseItOnce() {
        PlannedTrip.save(app, trip())
        assertNotNull("mirar no la gasta", PlannedTrip.peek(app))
        assertNotNull(PlannedTrip.peek(app))
        assertNull("con «No», nada que aplicar", PlannedTrip.answer(app, false))
        assertNull("y ya no está", PlannedTrip.peek(app))
        PlannedTrip.save(app, trip())
        assertEquals("Destino", PlannedTrip.answer(app, true)!!.dest.name)
        assertNull(PlannedTrip.peek(app))
        assertNull("sin ruta guardada, nada", PlannedTrip.answer(app, true))
    }

    @Test
    fun savingItRemovesTheSharedDestination() {
        SharedDest.save(app, place("Compartido", 40.0, -3.0))
        PlannedTrip.save(app, trip())
        assertNull(SharedDest.take(app))
    }

    // ------------------------------------------------------------------ aplicar al conectar

    @Test
    fun applyingItMakesItTheTripOfNowWithItsSpeed() {
        val t = trip()
        RoutePlanner.applyPlanned(app, t)
        assertEquals("Destino", RoutePlanner.manualDestination().name)
        assertEquals(listOf("Parada"), RoutePlanner.vias().map { it.name })
        assertTrue(RoutePlanner.roundTrip())
        val p = RoutePlanner.Plan().apply { destination = "Destino" }
        assertEquals("la velocidad elegida en el móvil, para ese destino", 110, RoutePlanner.speedCap(p))
        assertEquals(0, RoutePlanner.speedCap(RoutePlanner.Plan().apply { destination = "Otro" }))
        // Otro viaje la quita.
        RoutePlanner.setManualDestination(null)
        assertEquals(0, RoutePlanner.speedCap(p))
    }

    /** Una ruta de 300 km hacia el norte con cuatro cargadores iguales (a, b, c, d), como la reconstruiría cada planificador. */
    private fun plan(): RoutePlanner.Plan {
        val n = 301
        return RoutePlanner.Plan().apply {
            destination = "Destino"
            this.n = n
            lat = DoubleArray(n) { 61.0 + it * 0.009 }
            lon = DoubleArray(n) { 25.0 }
            km = DoubleArray(n) { it.toDouble() }
            kwhCum = DoubleArray(n) { it * 0.18 }
            gravCum = DoubleArray(n)
            totalKm = 300.0
            destLat = lat[n - 1]
            destLon = 25.0
            chargers = listOf(120.0, 140.0, 160.0, 200.0).mapIndexed { i, k ->
                RoutePlanner.Charger().apply {
                    id = "dgt:" + "abcd"[i]
                    name = "Cargador " + "ABCD"[i]
                    this.lat = 61.0 + k * 0.009
                    this.lon = 25.0
                    kmAlong = k
                    maxKw = 150.0
                    kwSource = ChargerFilter.KW_TAGGED
                    maxVolts = 920.0
                }
            }
        }
    }

    private fun firstStop(r: RoutePlanner) = r.chargePlan(60.0, 69.9, false, false, false)!!.stops[0].charger.id

    @Test
    fun theStopsPinnedOnThePhoneComeBackInTheCarPlan() {
        // En el móvil: el plan elige una parada y se cambia por otra («Cambiar» › «Usar esta»).
        val phone = RoutePlanner.draft(app)
        phone.adopt(plan())
        val chosen = firstStop(phone)
        val alt = phone.stopAlternatives(0).first { !it.current && it.works() }
        assertNotEquals(chosen, alt.charger.id)
        phone.pinStop(phone.chargePlan(60.0, 69.9, false, false, false)!!.stops[0].charger, alt.charger)
        assertEquals(alt.charger.id, firstStop(phone))
        val t = trip().apply { pins = phone.pinsNow().toMutableList() }
        val saved = PlannedTrip.fromJson(t.toJson())!!
        // En el coche, otro planificador con la misma ruta rehecha (cargadores nuevos con los mismos ids).
        val car = RoutePlanner.draft(app)
        car.adopt(plan())
        assertEquals("sin las del móvil, el plan elige la suya", chosen, firstStop(car))
        val car2 = RoutePlanner.draft(app)
        val p2 = plan()
        car2.restorePins(saved.pins, RoutePlanner.Place().apply {
            lat = p2.destLat
            lon = p2.destLon
        })
        car2.adopt(p2)
        assertEquals("con las del móvil, la elegida a mano", alt.charger.id, firstStop(car2))
        assertTrue(car2.isPinned(p2.chargers.first { it.id == alt.charger.id }))
    }

    // ------------------------------------------------------------------ antes y ahora

    @Test
    fun itSaysWhenThePlanOfNowIsNotTheOneOfThePhone() {
        val p = plan()
        val r = RoutePlanner.draft(app)
        r.adopt(p)
        val now = r.chargePlan(60.0, 69.9, false, false, false)!!
        val now0 = System.currentTimeMillis()
        val same = trip(now0).apply { stops = PlannedTrip.stopsOf(now).toMutableList() }
        assertNull("el mismo plan: nada que decir", PlannedTrip.diff(same, now, now0))
        val none = trip(now0).apply { stops = mutableListOf() }
        assertEquals("When you planned it: no charging stops; now: 1 charging stop.", PlannedTrip.diff(none, now, now0))
        val yesterday = trip(now0 - 24 * 3_600_000L).apply { stops = mutableListOf() }
        assertEquals("Yesterday: no charging stops; today: 1 charging stop.", PlannedTrip.diff(yesterday, now, now0))
        val other = trip(now0).apply {
            stops = mutableListOf(PlannedTrip.Stop().apply {
                name = "Otro"
                key = "dgt:x"
                lat = 61.5
                lon = 25.3
            })
        }
        assertEquals("Now it stops at " + now.stops[0].charger.name + ".", PlannedTrip.diff(other, now, now0))
    }
}
