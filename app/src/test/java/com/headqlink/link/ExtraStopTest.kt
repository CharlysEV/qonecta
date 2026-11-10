package com.headqlink.link

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * «Añadir parada de carga» (foro LPMFF, #369): un cargador fijado a mano se visita aunque el plan no lo necesite, el resto
 * del plan se rehace antes y después, se quita volviendo al plan normal, viaja con la ruta al coche (PlannedTrip) y, si no se
 * llega a él, el plan lo dice. Ruta llana de 500 km a 20 kWh/100 km y un C10 de 70 kWh, como ChargePlannerTest.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class ExtraStopTest {
    private val km = DoubleArray(501) { it.toDouble() }
    private val kwh = DoubleArray(501) { it * 0.2 }

    private fun settings() = ChargePlanner.Settings().apply { capacityKwh = 70.0 }

    private fun charger(at: Double, kw: Double, name: String = "km $at") = RoutePlanner.Charger().apply {
        this.name = name
        kmAlong = at
        maxKw = kw
        lat = 60.0 + at / 1000
        lon = 25.0
    }

    private val c100 = charger(100.0, 50.0)
    private val c180 = charger(180.0, 150.0)
    private val c260 = charger(260.0, 150.0)
    private val c350 = charger(350.0, 100.0)
    private val c420 = charger(420.0, 50.0)
    private val all = listOf(c100, c180, c260, c350, c420)

    @Test
    fun anAddedStopIsUsedEvenIfTheTripNeedsNoStops() {
        val base = ChargePlanner.plan(km.copyOf(201), kwh.copyOf(201), 0.0, 90.0, all, settings())
        assertEquals(ChargePlanner.Outcome.NO_STOPS, base.outcome)
        val s = settings().apply { extra = listOf(c100) }
        val r = ChargePlanner.plan(km.copyOf(201), kwh.copyOf(201), 0.0, 90.0, all, s)
        assertEquals(ChargePlanner.Outcome.PLANNED, r.outcome)
        assertEquals(listOf(c100), r.stops.map { it.charger })
        val st = r.stops[0]
        // Llega con 90 - 20/70·100 = 61,4 % y carga de más, hasta el máximo del 80 %.
        assertEquals(90 - 20 / 70.0 * 100, st.arrivePct, 1e-6)
        assertEquals(80.0, st.departPct, 1e-6)
        assertTrue(st.minutes > ChargePlanner.STOP_OVERHEAD_MIN)
        assertTrue(r.unreachable.isEmpty())
        // Con lo cargado se llega con más batería que sin parar.
        assertTrue(r.arrivalPct > base.arrivalPct)
    }

    @Test
    fun anAddedStopEarlierThanNeededRecalculatesTheRest() {
        val base = ChargePlanner.plan(km, kwh, 0.0, 90.0, all, settings())
        assertEquals(listOf(c260), base.stops.map { it.charger })
        val s = settings().apply { extra = listOf(c100) }
        val r = ChargePlanner.plan(km, kwh, 0.0, 90.0, all, s)
        assertEquals(ChargePlanner.Outcome.PLANNED, r.outcome)
        // Primero la añadida; las demás, detrás de ella y en orden.
        assertSame(c100, r.stops[0].charger)
        assertTrue(r.stops.size >= 2)
        for (i in 1 until r.stops.size) assertTrue(r.stops[i].km > r.stops[i - 1].km)
        assertTrue(r.arrivalPct >= 15 - 1e-6)
        // La batería al salir de la añadida es la del cargado, y las siguientes salen de ella.
        assertEquals(r.stops[0].departPct - (r.stops[1].km - 100) * 0.2 / 70 * 100, r.stops[1].arrivePct, 1e-6)
    }

    @Test
    fun removingItGoesBackToTheNormalPlan() {
        val base = ChargePlanner.plan(km, kwh, 0.0, 90.0, all, settings())
        val with = ChargePlanner.plan(km, kwh, 0.0, 90.0, all, settings().apply { extra = listOf(c100) })
        val without = ChargePlanner.plan(km, kwh, 0.0, 90.0, all, settings().apply { extra = emptyList() })
        assertEquals(base.stops.map { it.charger }, without.stops.map { it.charger })
        assertFalse(base.stops.map { it.charger } == with.stops.map { it.charger })
        // La copia de los ajustes conserva las añadidas (alternativas, consejo de velocidad…).
        assertEquals(listOf(c100), settings().apply { extra = listOf(c100) }.copy().extra)
    }

    @Test
    fun anAddedStopThatCannotBeReachedIsLeftOutAndSaidSo() {
        // Solo hay un cargador antes de ella: desde él, a 320 km, no se llega ni con el 80 %.
        val s = settings().apply { extra = listOf(c420) }
        val r = ChargePlanner.plan(km, kwh, 0.0, 40.0, listOf(c100), s)
        assertEquals(listOf(c420), r.unreachable)
        assertFalse(r.stops.any { it.charger === c420 })
    }

    @Test
    fun anAddedStopBehindTheCarOrAtTheEndIsIgnored() {
        val behind = charger(30.0, 50.0)
        val s = settings().apply { extra = listOf(behind) }
        val r = ChargePlanner.plan(km.copyOf(201), kwh.copyOf(201), 50.0, 90.0, all, s)
        assertEquals(ChargePlanner.Outcome.NO_STOPS, r.outcome)
        assertTrue(r.unreachable.isEmpty())
    }

    @Test
    fun theAddedFlagTravelsInPinsAndInThePlannedTrip() {
        val m = ChargerMarks.Mark().apply {
            id = "dgt:1"
            name = "Área de servicio"
            lat = 60.1
            lon = 25.0
            kind = ChargerMarks.FAV
            extra = true
        }
        val plain = ChargerMarks.Mark().apply {
            id = "dgt:2"
            lat = 60.2
            lon = 25.0
            kind = ChargerMarks.FAV
        }
        val back = ChargerMarks.parse(ChargerMarks.toJson(listOf(m, plain)))
        assertEquals(listOf(true, false), back.map { it.extra })

        val t = PlannedTrip().apply {
            dest = RoutePlanner.Place().apply {
                name = "Destino"
                detail = ""
                lat = 61.0
                lon = 25.0
            }
            pins = arrayListOf(m, plain)
            savedAtMs = 1_000
        }
        val got = PlannedTrip.fromJson(t.toJson())
        assertNotNull(got)
        assertEquals(listOf(true, false), got!!.pins.map { it.extra })
        assertEquals("Área de servicio", got.pins[0].name)
    }

    @Test
    fun oldMarksWithoutTheFlagAreNotAddedStops() {
        val json = """[{"i":"dgt:1","n":"x","k":1,"la":60.0,"lo":25.0}]"""
        assertFalse(ChargerMarks.parse(json)[0].extra)
    }

    @Test
    fun theListFindsByNameWithoutAccentsAndSkipsWhatIsBehind() {
        val a = charger(120.0, 150.0, "Área de Servicio Los Pinos").apply { network = "ionity" }
        val b = charger(300.0, 50.0, "Restaurante El Cruce")
        val behind = charger(10.0, 50.0, "Área vieja")
        val list = listOf(b, a, behind)
        // Sin texto: todos los de por delante, por km.
        assertEquals(listOf(a, b), AddStop.entries(list, "", 50.0, null).map { it.charger })
        // Por nombre, sin tildes ni mayúsculas, con varias palabras.
        assertEquals(listOf(a), AddStop.entries(list, "area pinos", 50.0, null).map { it.charger })
        assertEquals(listOf(b), AddStop.entries(list, "CRUCE", 50.0, null).map { it.charger })
        // Por red.
        assertEquals(listOf(a), AddStop.entries(list, "ionity", 50.0, null).map { it.charger })
        assertTrue(AddStop.entries(list, "nada", 50.0, null).isEmpty())
        // La distancia a la ruta sale de quien la sepa.
        assertEquals(2.5, AddStop.entries(list, "cruce", 0.0) { 2.5 }[0].offKm, 1e-9)
    }

    @Test
    fun arrivalAtUsesTheChargeOfThePreviousStop() {
        val s = settings().apply { extra = listOf(c100) }
        val r = ChargePlanner.plan(km, kwh, 0.0, 90.0, all, s)
        // Antes de la primera parada: de la batería de salida; después de ella, de lo que se cargó allí.
        assertEquals(90 - 50 * 0.2 / 70 * 100, AddStop.arrivalAt(r, charger(50.0, 50.0)), 1e-6)
        assertEquals(r.stops[0].departPct - 30 * 0.2 / 70 * 100, AddStop.arrivalAt(r, charger(130.0, 50.0)), 1e-6)
    }
}
