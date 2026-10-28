package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** El dibujo del tiempo del pie del panel (28-10): qué se dibuja por cada código WMO, y la curva de freno. */
class WeatherGlyphTest {
    @Test
    fun kindFollowsTheWmoCodeAndTheDaylight() {
        assertEquals(WeatherGlyph.Kind.NONE, WeatherGlyph.kind(-1, true))
        assertEquals(WeatherGlyph.Kind.SUN, WeatherGlyph.kind(0, true))
        assertEquals(WeatherGlyph.Kind.MOON, WeatherGlyph.kind(0, false))
        assertEquals(WeatherGlyph.Kind.PARTLY, WeatherGlyph.kind(2, true))
        assertEquals(WeatherGlyph.Kind.MOON, WeatherGlyph.kind(2, false))
        assertEquals(WeatherGlyph.Kind.CLOUD, WeatherGlyph.kind(3, true))
        assertEquals(WeatherGlyph.Kind.FOG, WeatherGlyph.kind(45, true))
        assertEquals(WeatherGlyph.Kind.RAIN, WeatherGlyph.kind(61, true))
        assertEquals(WeatherGlyph.Kind.SNOW, WeatherGlyph.kind(71, true))
        assertEquals(WeatherGlyph.Kind.RAIN, WeatherGlyph.kind(80, true))
        assertEquals(WeatherGlyph.Kind.SNOW, WeatherGlyph.kind(85, true))
        assertEquals(WeatherGlyph.Kind.STORM, WeatherGlyph.kind(95, true))
        // Mismo reparto que el emoticono de la pestaña Ruta: ninguno queda sin dibujo.
        for (code in 0..99) assertTrue(WeatherGlyph.kind(code, true) != WeatherGlyph.Kind.NONE)
    }

    @Test
    fun easeStartsFastAndEndsSlow() {
        assertEquals(0f, WeatherGlyph.ease(0f), 1e-6f)
        assertEquals(1f, WeatherGlyph.ease(1f), 1e-6f)
        assertTrue(WeatherGlyph.ease(0.5f) > 0.5f)
        assertTrue(WeatherGlyph.ease(0.9f) > WeatherGlyph.ease(0.8f))
    }
}
