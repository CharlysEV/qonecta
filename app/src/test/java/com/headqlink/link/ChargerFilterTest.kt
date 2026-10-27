package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Filtro de cargadores de la Ruta: reconocer la red por las etiquetas de OpenStreetMap, potencia mínima y redes. */
class ChargerFilterTest {
    @Test
    fun networksFromOsmTags() {
        assertEquals("tesla", ChargerFilter.classify("Tesla, Inc.", "", "Tesla", "Tesla Supercharger Bailén"))
        assertEquals("tesla", ChargerFilter.classify("", "", "", "Supercharger Alcalá"))
        assertEquals("zunder", ChargerFilter.classify("", "Zunder", "Easycharger S.A.", "Zunder Écija"))
        assertEquals("ionity", ChargerFilter.classify("IONITY", "", "", ""))
        assertEquals("endesa", ChargerFilter.classify("", "", "Endesa X Way", ""))
        assertEquals("iberdrola", ChargerFilter.classify("", "", "Iberdrola | bp pulse", ""))
        assertEquals("moeve", ChargerFilter.classify("", "", "Cepsa", ""))
        assertEquals("bp", ChargerFilter.classify("bp pulse", "", "", ""))
        // «bp» suelto dentro de otra palabra no es bp pulse.
        assertEquals(ChargerFilter.OTHER, ChargerFilter.classify("", "", "Ayuntamiento", "Punto de recarga"))
        assertEquals(ChargerFilter.OTHER, ChargerFilter.classify(null, null, null, null))
    }

    @Test
    fun acceptsByVoltage() {
        // 20-10: 800 V = de continua y de 700 V o más declarados; 400-500 V, por debajo; los de solo alterna, nunca.
        fun charger(kw: Double, volts: Double, ac: Boolean = false) = RoutePlanner.Charger().apply {
            network = ChargerFilter.OTHER
            maxKw = kw
            maxVolts = volts
            acOnly = ac
            kwSource = ChargerFilter.KW_TAGGED
        }
        val none = emptySet<String>()
        val ionity = charger(350.0, 920.0)
        val zunder400 = charger(180.0, 500.0)
        val unknown = charger(50.0, 0.0)
        val ac = charger(22.0, 400.0, ac = true)
        assertTrue(ChargerFilter.accepts(ionity, 0, ChargerFilter.VOLTS_800, none, false))
        assertFalse(ChargerFilter.accepts(zunder400, 0, ChargerFilter.VOLTS_800, none, true))
        assertTrue(ChargerFilter.accepts(zunder400, 0, ChargerFilter.VOLTS_400, none, false))
        assertFalse(ChargerFilter.accepts(ionity, 0, ChargerFilter.VOLTS_400, none, true))
        // Sin voltaje conocido: con «incluir los que no lo dicen», sí; si no, no.
        assertTrue(ChargerFilter.accepts(unknown, 0, ChargerFilter.VOLTS_800, none, true))
        assertFalse(ChargerFilter.accepts(unknown, 0, ChargerFilter.VOLTS_800, none, false))
        assertFalse(ChargerFilter.accepts(ac, 0, ChargerFilter.VOLTS_400, none, true))
        // Cualquiera: todos, también los de alterna.
        assertTrue(ChargerFilter.accepts(ac, 0, ChargerFilter.VOLTS_ANY, none, false))
        // Junto con la potencia: un 800 V de 150 kW no pasa con 200 kW mínimo.
        assertFalse(ChargerFilter.accepts(charger(150.0, 800.0), 200, ChargerFilter.VOLTS_800, none, true))
        assertTrue(ChargerFilter.accepts(charger(300.0, 800.0), 200, ChargerFilter.VOLTS_800, none, false))
        assertEquals(ChargerFilter.VOLTS_ANY, ChargerFilter.snapVolts(650))
        assertEquals(ChargerFilter.VOLTS_800, ChargerFilter.snapVolts(800))
    }

    @Test
    fun acceptsByPowerAndNetwork() {
        val none = emptySet<String>()
        assertTrue(ChargerFilter.accepts(22.0, "other", 0, none))
        assertTrue(ChargerFilter.accepts(0.0, "other", 0, none)) // sin potencia: solo con «cualquiera»
        assertFalse(ChargerFilter.accepts(0.0, "other", 50, none))
        assertFalse(ChargerFilter.accepts(49.0, "zunder", 50, none))
        assertTrue(ChargerFilter.accepts(150.0, "zunder", 50, none))
        val nets = setOf("tesla", "zunder")
        assertTrue(ChargerFilter.accepts(250.0, "tesla", 100, nets))
        assertFalse(ChargerFilter.accepts(300.0, "ionity", 100, nets))
        assertTrue(ChargerFilter.accepts(50.0, "", 0, setOf(ChargerFilter.OTHER)))
    }

    @Test
    fun countsMostFirstOtherLast() {
        val c = ChargerFilter.counts(listOf("other", "zunder", "tesla", "zunder", "other", "other", "ionity", "zunder"))
        assertEquals(listOf("zunder", "tesla", "ionity", "other"), c.map { it.key })
        assertEquals("zunder", c[0].key)
        assertEquals(3, c[0].value)
        assertEquals(ChargerFilter.OTHER, c.last().key)
    }

    @Test
    fun savedChoices() {
        assertEquals("tesla,zunder", ChargerFilter.formatNetworks(linkedSetOf("zunder", "tesla")))
        assertEquals(setOf("tesla", "zunder"), ChargerFilter.parseNetworks(" tesla, zunder ,"))
        assertTrue(ChargerFilter.parseNetworks("").isEmpty())
        assertEquals(50, ChargerFilter.snapMinKw(60))
        // 20-10: también 200 y 300 kW.
        assertEquals(300, ChargerFilter.snapMinKw(400))
        assertEquals(200, ChargerFilter.snapMinKw(220))
        assertEquals(0, ChargerFilter.snapMinKw(-5))
        assertEquals("Endesa X", ChargerFilter.label("endesa"))
    }

    @Test
    fun germanOperatorsFromTheRegistry() {
        // 27-10: los «Betreiber» tal cual del registro de la Bundesnetzagentur (antes caían todos en «Otras»).
        fun op(s: String) = ChargerFilter.classify("", "", s, "")
        assertEquals("enbw", op("EnBW mobility+ AG und Co.KG"))
        assertEquals("ewego", op("EWE Go HOCHTIEF Ladepartner GmbH & Co. KG"))
        assertEquals("pfalzwerke", op("Pfalzwerke AG"))
        assertEquals("kaufland", op("Kaufland Dienstleistung GmbH & Co. KG"))
        assertEquals("vattenfall", op("Vattenfall Smarter Living GmbH"))
        assertEquals("aldi", op("ALDI SÜD Immobilienverwaltungs-GmbH & Co. oHG"))
        assertEquals("aldi", op("ALDI Nord Deutschland Stiftung & Co. KG"))
        assertEquals("eon", op("E.ON Drive Infrastructure GmbH"))
        assertEquals("mer", op("Mer Germany GmbH"))
        assertEquals("comfortcharge", op("Comfortcharge GmbH"))
        assertEquals("mercedes", op("Mercedes-Benz High-Power Charging Europe GmbH"))
        assertEquals("eviny", op("Bilkraft GmbH"))
        // Aral pulse figura como BP; los de antes siguen igual.
        assertEquals("bp", op("BP Europa SE"))
        assertEquals("shell", op("Shell Deutschland GmbH"))
        assertEquals("lidl", op("Lidl Dienstleistung GmbH & Co. KG"))
        assertEquals("ionity", op("IONITY GmbH"))
        assertEquals("tesla", op("Tesla Germany GmbH"))
        // Un ayuntamiento o una empresa local, en «Otras».
        assertEquals(ChargerFilter.OTHER, op("Stadtwerke Bochum GmbH"))
    }

    @Test
    fun exactFieldOnlyForShortNames() {
        // «Mer» como marca, sí; la palabra «mer» en un nombre francés, no.
        assertEquals("mer", ChargerFilter.classify("Mer", "", "", ""))
        assertEquals(ChargerFilter.OTHER, ChargerFilter.classify("", "", "", "Aire de la Mer"))
        assertEquals("eon", ChargerFilter.classify("E.ON", "", "", ""))
        assertEquals(ChargerFilter.OTHER, ChargerFilter.classify("", "", "", "Parking e on street"))
    }

    @Test
    fun othersAreAlphabeticalAndSkipTheRouteOnes() {
        val rest = ChargerFilter.others(setOf("enbw", "tesla"))
        assertFalse(rest.contains("enbw"))
        assertFalse(rest.contains("tesla"))
        assertEquals(ChargerFilter.NETWORKS.size - 2, rest.size)
        val labels = rest.map { ChargerFilter.label(it).lowercase() }
        assertEquals(labels.sorted(), labels)
        // Cada red conocida tiene su distintivo (color e iniciales).
        for (k in ChargerFilter.NETWORKS.keys) assertTrue(k, ChargerFilter.badge(k) != null)
    }

    @Test
    fun onlyTheNetworksOfYourCountry() {
        // En Alemania: las alemanas y las de toda Europa; no las españolas.
        val de = ChargerFilter.others(emptySet(), "de", emptySet())
        for (k in listOf("enbw", "ewego", "aldi", "tesla", "ionity", "shell", "bp")) assertTrue(k, de.contains(k))
        for (k in listOf("zunder", "iberdrola", "wenea", "plenoil")) assertFalse(k, de.contains(k))
        // En España, al revés.
        val es = ChargerFilter.others(emptySet(), "es", emptySet())
        for (k in listOf("zunder", "iberdrola", "tesla", "ionity")) assertTrue(k, es.contains(k))
        for (k in listOf("enbw", "ewego", "pfalzwerke")) assertFalse(k, es.contains(k))
        // Una elegida de otro país sale igual (para poder quitarla); sin país, todas.
        assertTrue(ChargerFilter.others(emptySet(), "es", setOf("enbw")).contains("enbw"))
        assertEquals(ChargerFilter.NETWORKS.size, ChargerFilter.others(emptySet(), "", null).size)
        // Toda red conocida tiene sus países (si no, saldría en todos sin querer): en un país sin redes, solo las de toda Europa.
        assertEquals(setOf("tesla", "ionity", "shell", "lidl"), ChargerFilter.NETWORKS.keys.filter { ChargerFilter.inCountry(it, "zz") }.toSet())
    }
}
