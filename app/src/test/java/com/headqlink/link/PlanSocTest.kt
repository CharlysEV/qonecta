package com.headqlink.link

import android.app.Application
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * El % del plan de carga: unas décimas arriba y abajo (la pestaña Ruta y el bucle pedían el plan con dos % distintos, el
 * crudo de la nube y el estimado desde esa lectura) no lo rehacen ni lo hacen avisar; un punto entero, sí. Y la pestaña
 * y el bucle sacan el % de la misma fuente (RoutePlanner.battery), también con el dato de la nube viejo. Coordenadas
 * inventadas.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class PlanSocTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        Str.init(app)
        RoutePlanner.setManualDestination(null)
    }

    @After
    fun tearDown() {
        RoutePlanner.setManualDestination(null)
    }

    /** Una ruta de 300 km hacia el norte con cuatro cargadores iguales. */
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

    private fun RoutePlanner.at(soc: Double) = chargePlan(soc, 69.9, true, false, false)!!

    @Test
    fun tenthsOfAPercentDoNotRedoThePlan() {
        val r = RoutePlanner.draft(app)
        r.adopt(plan())
        val first = r.at(60.0)
        // Como antes cada tick: el crudo de la nube (60) y el estimado del bucle (59,6), uno tras otro.
        assertSame("59,6 tras 60: el mismo plan", first, r.at(59.6))
        assertSame("y otra vez 60: el mismo", first, r.at(60.0))
        assertSame(first, r.at(59.3))
        // Un punto entero (también bajando poco a poco): se rehace, con el % nuevo.
        val moved = r.at(59.0)
        assertNotSame("un punto menos: plan nuevo", first, moved)
        assertEquals("con el % nuevo", 59.0, moved.socNow, 1e-9)
        assertSame("y de ahí, otra vez unas décimas: el mismo", moved, r.at(59.4))
        assertNotSame("subir un punto también lo rehace", moved, r.at(60.2))
    }

    @Test
    fun thePercentChosenByHandStillMovesThePlanByOnePoint() {
        // El borrador del móvil (% elegido a mano, de uno en uno): cada cambio se ve.
        val r = RoutePlanner.draft(app)
        r.adopt(plan())
        val a = r.chargePlan(60.0, 69.9, false, false, false)!!
        val b = r.chargePlan(61.0, 69.9, false, false, false)!!
        assertNotSame(a, b)
        assertEquals(61.0, b.socNow, 1e-9)
    }

    private fun snap(socPct: Int, ageMs: Long): CarCloud.Snapshot {
        val now = System.currentTimeMillis()
        val sig = JSONObject().put("1204", socPct).put("1149", 0).put("47", 0).put("1197", 0).put("3736", 0)
        val data = JSONObject().put("collectTime", now - ageMs).put("signal", sig)
        return CarCloud.Snapshot(CarCloud.State.OK, LeapStatus.parse(data), now, 100, 67.0, "C10", 0, false)
    }

    @Test
    fun theTabAndTheLoopTakeTheSamePercent() {
        val r = RoutePlanner.draft(app)
        // Dato al día: el real del coche (sin gastar nada desde la lectura, el mismo %).
        val fresh = r.battery(snap(70, 5 * 60_000L))
        assertEquals(70.0, fresh[0], 1e-6)
        assertEquals(67.0, fresh[1], 1e-6)
        assertEquals("real", 1.0, fresh[2], 0.0)
        assertEquals("no viejo", 0.0, fresh[3], 0.0)
        // Viejo y desenchufado: el indicado, partiendo del de la nube (como lo enseña la pestaña), no el real.
        val stale = r.battery(snap(64, 99 * 60_000L))
        assertEquals(64.0, stale[0], 1e-6)
        assertEquals(67.0, stale[1], 1e-6)
        assertEquals("indicado", 0.0, stale[2], 0.0)
        assertEquals("viejo", 1.0, stale[3], 0.0)
    }
}
