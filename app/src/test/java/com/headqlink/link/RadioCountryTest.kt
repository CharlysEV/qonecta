package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Test

/** País de las emisoras populares: región del sistema, idioma de la app, red móvil y, si nada, España. */
class RadioCountryTest {
    @Test
    fun theSystemRegionComesFirst() {
        // Móvil en alemán de Austria con la app en español sin país y la red alemana: Austria.
        assertEquals("AT", RadioScreen.pickCountry("AT", "", "de"))
    }

    @Test
    fun withoutRegionTheMobileNetworkDecides() {
        // Idioma sin país (la app puesta en «alemán» a mano): antes salía España; ahora, la red móvil.
        assertEquals("DE", RadioScreen.pickCountry("", "", "de"))
        assertEquals("CH", RadioScreen.pickCountry(null, null, "ch"))
    }

    @Test
    fun nothingKnownIsSpain() {
        assertEquals("ES", RadioScreen.pickCountry("", null, ""))
        // Regiones que no son países de dos letras (419 = Latinoamérica) no valen.
        assertEquals("MX", RadioScreen.pickCountry("419", "", "mx"))
    }

    @Test
    fun flagsAreRegionalIndicators() {
        assertEquals("🇩🇪", RadioScreen.flag("de"))
        assertEquals("🇦🇹", RadioScreen.flag("AT"))
        assertEquals("", RadioScreen.flag(""))
    }
}
