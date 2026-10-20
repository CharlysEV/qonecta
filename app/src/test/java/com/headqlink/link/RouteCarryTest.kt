package com.headqlink.link

import android.app.Application
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * El viaje no se pierde (20-10): lo recorrido pasa al plan siguiente de la misma ruta (recálculo o reconexión), con una
 * línea donde empieza cada uno, y se guarda en el móvil por si la app se reinicia.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class RouteCarryTest {
    /** Un plan recto de n puntos cada km, con la altitud subiendo 10 m por km. */
    private fun plan(n: Int, progress: Int): RoutePlanner.Plan = RoutePlanner.Plan().apply {
        this.n = n
        km = DoubleArray(n) { it.toDouble() }
        elev = DoubleArray(n) { 600.0 + it * 10 }
        totalKm = (n - 1).toDouble()
        this.progress = progress
        destination = "Destino"
    }

    @Test
    fun loRecorridoPasaAlPlanSiguienteConSuLinea() {
        // Primer plan de 16 km: se recalcula en el km 4.
        val a = plan(17, 4)
        val c1 = RoutePlanner.carryFrom(a, "Destino")
        assertEquals(4.0, c1.kmBase, 1e-9)
        assertArrayEquals(doubleArrayOf(0.0, 1.0, 2.0, 3.0, 4.0), c1.histKm, 1e-9)
        val b = plan(13, 0)
        RoutePlanner.attach(b, c1)
        assertEquals(4.0, b.kmBase, 1e-9)
        assertArrayEquals(doubleArrayOf(4.0), b.marks, 1e-9)
        // El segundo, recalculado a sus 3 km: el viaje va por el km 7, con dos líneas.
        b.progress = 3
        val c2 = RoutePlanner.carryFrom(b, "Destino")
        assertEquals(7.0, c2.kmBase, 1e-9)
        assertEquals(7.0, c2.histKm.last(), 1e-9)
        assertEquals(630.0, c2.histElev.last(), 1e-9)
        val c = plan(10, 0)
        RoutePlanner.attach(c, c2)
        assertArrayEquals(doubleArrayOf(4.0, 7.0), c.marks, 1e-9)
    }

    @Test
    fun sinRecorridoNoHayLinea() {
        val a = plan(10, 0)
        val b = plan(10, 0)
        RoutePlanner.attach(b, RoutePlanner.carryFrom(a, "Destino"))
        assertEquals(0.0, b.kmBase, 1e-9)
        assertEquals(0, b.marks.size)
    }

    @Test
    fun elRelieveSeAdelgaza() {
        val k = DoubleArray(1000) { it * 0.1 }
        val e = DoubleArray(1000) { 600.0 + it }
        val t = RoutePlanner.thin(k, e, RoutePlanner.MAX_HIST)
        assertEquals(RoutePlanner.MAX_HIST, t[0].size)
        assertEquals(0.0, t[0].first(), 1e-9)
        assertEquals(99.9, t[0].last(), 1e-9)
        assertEquals(1599.0, t[1].last(), 1e-9)
    }

    @Test
    fun seGuardaYSeRecuperaEnElMovil() {
        val s = TripMemory.Saved().apply {
            dest = RoutePlanner.Place().apply { name = "Destino"; detail = "Calle Ejemplo 1"; lat = 41.5; lon = -1.5 }
            mapsTotalM = 16_000
            atMs = 1_000L
            kmBase = 7.25
            marks = doubleArrayOf(4.0, 7.25)
            histKm = doubleArrayOf(0.0, 3.5, 7.25)
            histElev = doubleArrayOf(600.0, 640.0, 672.5)
        }
        val back = TripMemory.fromJson(TripMemory.toJson(s))
        assertNotNull(back)
        assertEquals(7.25, back!!.kmBase, 1e-9)
        assertArrayEquals(doubleArrayOf(4.0, 7.25), back.marks, 1e-9)
        assertArrayEquals(doubleArrayOf(0.0, 3.5, 7.25), back.histKm, 1e-9)
        assertArrayEquals(doubleArrayOf(600.0, 640.0, 672.5), back.histElev, 1e-9)
        // Un viaje guardado de antes (sin estos campos) se lee igual.
        val old = TripMemory.fromJson("{\"dest\":{\"name\":\"Destino\",\"detail\":\"\",\"lat\":41.5,\"lon\":-1.5},\"mapsTotalM\":16000,\"at\":1}")
        assertNotNull(old)
        assertEquals(0.0, old!!.kmBase, 1e-9)
        assertTrue(old.marks.isEmpty())
    }
}
