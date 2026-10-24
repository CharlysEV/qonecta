package com.headqlink.link

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File
import java.io.InputStreamReader
import java.io.StringReader

/**
 * Cargadores de Alemania (Ladesäulenregister de la Bundesnetzagentur): el CSV con sus líneas de aviso y sus comillas,
 * los rápidos agrupados por sitio, el enlace más nuevo de la página, cuándo se mira y la cuadrícula junto a la DGT.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class BnetzaChargersTest {
    @Before
    fun texts() {
        Str.init(RuntimeEnvironment.getApplication())
    }

    private val header = "Ladeeinrichtungs-ID;Betreiber;Anzeigename (Karte);Status;Art der Ladeeinrichtung;Anzahl Ladepunkte;" +
        "Nennleistung Ladeeinrichtung [kW];Inbetriebnahmedatum;Straße;Hausnummer;Adresszusatz;Postleitzahl;Ort;" +
        "Kreis/kreisfreie Stadt;Bundesland;Breitengrad;Längengrad;Standortbezeichnung;Informationen zum Parkraum;Bezahlsysteme;" +
        "Öffnungszeiten;Öffnungszeiten: Wochentage;Öffnungszeiten: Tageszeiten;" +
        (1..6).joinToString(";") { "Steckertypen$it;Nennleistung Stecker$it;EVSE-ID$it;Public Key$it" }

    private fun row(id: String, op: String, name: String, status: String, art: String, n: Int, kw: String, town: String,
                    lat: String, lon: String, plugs: List<Pair<String, String>>): String {
        val p = (0 until 6).joinToString(";") { i -> if (i < plugs.size) "\"${plugs[i].first}\";${plugs[i].second};;" else ";;;" }
        return "$id;$op;$name;$status;$art;$n;$kw;01.01.2024;Straße;1;;12345;$town;Kreis;Land;$lat;$lon;;;" +
            "\"RFID-Karte;Onlinezahlungsverfahren\";247;\"Montag; Dienstag\";\"00:00-23:59;\n00:00-23:59\";$p"
    }

    private val csv = "﻿Ladesäulenregister Bundesnetzagentur;;;\n;;;\nHinweis: ;;;\nLetzte Aktualisierung vom: 01.09.2026;;;\n;;;\n" +
        "Allgemeine Informationen;;;;1. Ladepunkt;;;\n" + header + "\n" +
        // Dos equipos del mismo hub (mismo sitio y operador): uno solo, con los puntos sumados y la potencia mayor.
        row("1", "EnBW mobility+ AG und Co.KG ", "", "In Betrieb", "Schnellladeeinrichtung", 2, "300", "Amstetten", "48,578533", "9,87484",
            listOf("DC Fahrzeugkupplung Typ Combo 2 (CCS)" to "300", "DC Fahrzeugkupplung Typ Combo 2 (CCS)" to "300")) + "\n" +
        row("2", "EnBW mobility+ AG und Co.KG", "", "In Betrieb", "Schnellladeeinrichtung", 2, "150", "Amstetten", "48,578533", "9,87484",
            listOf("DC Fahrzeugkupplung Typ Combo 2 (CCS); DC CHAdeMO" to "150", "AC Typ 2 Steckdose" to "22")) + "\n" +
        // Alterna: fuera (la alterna sigue saliendo de OpenStreetMap).
        row("3", "Stadtwerke X GmbH", "Rathaus", "In Betrieb", "Normalladeeinrichtung", 2, "22", "Ulm", "48,40", "9,99",
            listOf("AC Typ 2 Steckdose" to "22", "AC Typ 2 Steckdose" to "22")) + "\n" +
        // En mantenimiento: fuera.
        row("4", "Tesla Germany GmbH", "Supercharger Ulm", "In Wartung", "Schnellladeeinrichtung", 8, "250", "Ulm", "48,41", "9,98",
            listOf("DC Fahrzeugkupplung Typ Combo 2 (CCS)" to "250")) + "\n" +
        row("5", "Tesla Germany GmbH", "Supercharger Ulm Nord", "In Betrieb", "Schnellladeeinrichtung", 8, "250", "Ulm", "48,42", "9,97",
            listOf("DC Fahrzeugkupplung Typ Combo 2 (CCS)" to "250")) + "\n"

    @Test
    fun fastSitesAreReadGroupedAndSlowOrInMaintenanceLeftOut() {
        val sites = BnetzaChargers.parse(StringReader(csv))
        assertEquals(2, sites.size)
        val hub = sites[0]
        assertEquals(48.578533, hub.lat, 1e-6)
        assertEquals(300.0, hub.dcKw, 0.0)
        assertEquals(22.0, hub.acKw, 0.0)
        assertEquals(4, hub.points)
        assertTrue(hub.ccs && hub.chademo && hub.type2)
        assertEquals("Amstetten", hub.town)
        val tesla = sites[1]
        assertEquals("Supercharger Ulm Nord", tesla.name)
        assertEquals(250.0, tesla.dcKw, 0.0)
        // Como cargador de la ruta: su red por el operador, sin voltaje oficial (el típico de su red) y el id con «de:».
        val c = tesla.also { it.src = "de" }.toCharger()
        assertEquals("tesla", c.network)
        assertTrue(c.id.startsWith("de:") && c.maxVolts in 1.0..699.0)
        val e = hub.also { it.src = "de" }.toCharger()
        assertEquals("EnBW mobility+ · Amstetten", e.name)
    }

    @Test
    fun theCsvKeepsQuotedSemicolonsAndLineBreaksInsideAField() {
        val r = BnetzaChargers.record(StringReader("a;\"b;c\";\"d\ne\";\"f \"\"g\"\"\"\nnext"))
        assertEquals(listOf("a", "b;c", "d\ne", "f \"g\""), r)
    }

    @Test
    fun theNewestCsvOfThePageIsPicked() {
        val html = """<a href="https://data.bundesnetzagentur.de/Bundesnetzagentur/DE/Fachthemen/ElektrizitaetundGas/E-Mobilitaet/Ladesaeulenregister_BNetzA_2026-08-01.csv">alt</a>
            <a href="https://data.bundesnetzagentur.de/Bundesnetzagentur/DE/Fachthemen/ElektrizitaetundGas/E-Mobilitaet/Ladesaeulenregister_BNetzA_2026-09-01.csv">neu</a>
            <a href="https://data.bundesnetzagentur.de/Bundesnetzagentur/DE/Fachthemen/ElektrizitaetundGas/E-Mobilitaet/Ladesaeulenregister_BNetzA_2026-09-30.xlsx">xlsx</a>"""
        assertTrue(BnetzaChargers.newestCsv(html)!!.endsWith("Ladesaeulenregister_BNetzA_2026-09-01.csv"))
        assertNull(BnetzaChargers.newestCsv("<html>nada</html>"))
    }

    @Test
    fun thePageIsCheckedOnlyWhenWantedAndDue() {
        val day = 86_400_000L
        val now = 100 * day
        // Sin rutas por Alemania, nunca.
        assertFalse(BnetzaChargers.due(false, false, 0, 0, now))
        // Sin datos, enseguida (como mucho una vez por hora); con datos, una vez por semana.
        assertTrue(BnetzaChargers.due(true, false, 0, 0, now))
        assertFalse(BnetzaChargers.due(true, false, now - 600_000, 0, now))
        assertFalse(BnetzaChargers.due(true, true, now - 3 * day, 0, now))
        assertTrue(BnetzaChargers.due(true, true, now - 8 * day, 0, now))
        // Tras un fallo, unas horas de espera.
        assertFalse(BnetzaChargers.due(true, true, now - 8 * day, now - 3_600_000L, now))
        assertTrue(BnetzaChargers.nearGermany(doubleArrayOf(40.4, 48.1), doubleArrayOf(-3.7, 11.6)))
        assertFalse(BnetzaChargers.nearGermany(doubleArrayOf(40.4), doubleArrayOf(-3.7)))
    }

    @Test
    fun officialChargersJoinBothRegistersByTile() {
        val es = DgtChargers.Site().apply { id = "e1"; lat = 40.4; lon = -3.7; dcKw = 150.0; sockets = ChargerFilter.SOCKET_DC }
        val de = DgtChargers.Site().apply { id = "d1"; lat = 48.42; lon = 9.97; dcKw = 250.0; sockets = ChargerFilter.SOCKET_DC }
        DgtChargers.setForTest(listOf(es))
        BnetzaChargers.setForTest(listOf(de))
        assertEquals("dgt", OfficialChargers.sourceOf(ChargerCache.tileOf(40.4, -3.7)))
        assertEquals("de", OfficialChargers.sourceOf(ChargerCache.tileOf(48.42, 9.97)))
        assertNull(OfficialChargers.sourceOf(ChargerCache.tileOf(48.85, 2.35)))
        assertEquals("d1", OfficialChargers.inTile(ChargerCache.tileOf(48.42, 9.97))!![0].id)
        assertEquals("EWE Go", DgtChargers.shortOperator("EWE Go GmbH"))
        assertEquals("Allego", DgtChargers.shortOperator("Allego GmbH"))
        assertEquals("Iberdrola", DgtChargers.shortOperator("IBERDROLA, S.A."))
    }

    /** Con el registro entero (HQL_BNETZA_CSV = el CSV descargado): se lee todo y salen miles de sitios rápidos. */
    @Test
    fun theWholeRegister() {
        val path = System.getenv("HQL_BNETZA_CSV")
        assumeTrue(path != null && File(path).exists())
        val t0 = System.currentTimeMillis()
        val all = InputStreamReader(File(path!!).inputStream(), Charsets.UTF_8).use { BnetzaChargers.parse(it) }
        val big = all.filter { it.dcKw >= 150 }
        println("Bundesnetzagentur: ${all.size} sitios rápidos en ${System.currentTimeMillis() - t0} ms; ${big.size} de 150 kW o más")
        assertTrue(all.size > 10000)
        val chargers = all.map { it.also { s -> s.src = "de" }.toCharger() }
        assertTrue(chargers.count { it.network == "tesla" } > 100)
    }
}
