package com.headqlink.link

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Países donde los avisos de radar van apagados de serie (la ley los prohíbe al conductor). */
class CountryTest {
    @Test
    fun radarWarningsAreOffByDefaultOnlyWhereTheyAreForbidden() {
        assertTrue(Country.radarWarningsForbidden("de"))
        assertTrue(Country.radarWarningsForbidden("ch"))
        for (iso in listOf("es", "pt", "fr", "it", "at", "nl", "")) assertFalse(iso, Country.radarWarningsForbidden(iso))
    }
}
