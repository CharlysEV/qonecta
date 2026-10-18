package com.headqlink.link

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** El viaje en curso guardado para retomarlo tras un reinicio de la app a mitad (18-10: se perdía el destino con paradas). */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class TripMemoryTest {
    private fun place(name: String, lat: Double, lon: Double) = RoutePlanner.Place().apply {
        this.name = name
        this.detail = "Calle Ejemplo 1"
        this.lat = lat
        this.lon = lon
    }

    private fun trip(totalM: Int = 250_000, atMs: Long = 1_000_000L) = TripMemory.Saved().apply {
        dest = place("Destino", 41.5, -1.5)
        vias += place("Parada 1", 40.8, -2.4)
        vias += place("Parada 2", 41.1, -2.0)
        round = true
        guessed = true
        guessChecked = true
        mapsTotalM = totalM
        this.atMs = atMs
    }

    @Test
    fun jsonIdaYVuelta() {
        val back = TripMemory.fromJson(TripMemory.toJson(trip()))
        assertNotNull(back)
        back!!
        assertEquals("Destino", back.dest.name)
        assertEquals("Calle Ejemplo 1", back.dest.detail)
        assertEquals(41.5, back.dest.lat, 1e-9)
        assertEquals(-1.5, back.dest.lon, 1e-9)
        assertEquals(listOf("Parada 1", "Parada 2"), back.vias.map { it.name })
        assertEquals(40.8, back.vias[0].lat, 1e-9)
        assertTrue(back.round)
        assertTrue(back.guessed)
        assertTrue(back.guessChecked)
        assertEquals(250_000, back.mapsTotalM)
        assertEquals(1_000_000L, back.atMs)
    }

    @Test
    fun jsonVacioORotoEsNull() {
        assertNull(TripMemory.fromJson(null))
        assertNull(TripMemory.fromJson(""))
        assertNull(TripMemory.fromJson("{no es json"))
        assertNull(TripMemory.fromJson("{\"vias\":[]}"))
    }

    @Test
    fun retomaSoloLaMismaRutaDeMaps() {
        val t = trip()
        val now = t.atMs + 3_600_000L
        // La misma ruta (la misma longitud, o casi: Maps la recalcula un poco).
        assertTrue(TripMemory.matches(t, now, 250_000))
        assertTrue(TripMemory.matches(t, now, 251_500))
        // Otra ruta: otro viaje.
        assertFalse(TripMemory.matches(t, now, 120_000))
        // Maps sin longitud, o el viaje se guardó sin Maps navegando.
        assertFalse(TripMemory.matches(t, now, -1))
        assertFalse(TripMemory.matches(trip(totalM = -1), now, 250_000))
        assertFalse(TripMemory.matches(null, now, 250_000))
    }

    @Test
    fun caducaALas12Horas() {
        val t = trip()
        assertTrue(TripMemory.matches(t, t.atMs + TripMemory.MAX_AGE_MS, 250_000))
        assertFalse(TripMemory.matches(t, t.atMs + TripMemory.MAX_AGE_MS + 1, 250_000))
        // El reloj del móvil para atrás: no se fía.
        assertFalse(TripMemory.matches(t, t.atMs - 1, 250_000))
    }

    @Test
    fun guardaLeeYBorraEnElMovil() {
        val ctx = RuntimeEnvironment.getApplication() as Context
        assertNull(TripMemory.load(ctx))
        TripMemory.save(ctx, trip())
        assertEquals("Destino", TripMemory.load(ctx)!!.dest.name)
        TripMemory.clear(ctx)
        assertNull(TripMemory.load(ctx))
    }
}
