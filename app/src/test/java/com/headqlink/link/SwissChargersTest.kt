package com.headqlink.link

import android.app.Application
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * Cargadores de Suiza (Oficina Federal de Energía, OICP): los rápidos agrupados por sitio con sus puntos, el estado en
 * directo de cada punto, el plan sin los que están fuera de servicio y las redes suizas del filtro (LPMFF, 27-10).
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class SwissChargersTest {
    @Before
    fun texts() {
        Str.init(RuntimeEnvironment.getApplication())
    }

    private fun evse(id: String, lat: Double, lon: Double, type: String, kw: String, plugs: String, name: String = "MOVE - La Côte",
                     access: String = "Paying publicly accessible", volts: String = "null") = """
        {"Accessibility": "$access", "Address": {"City": "Bursins", "Street": null},
         "ChargingFacilities": [{"Amperage": "560", "Voltage": $volts, "power": $kw, "powertype": "$type"}],
         "ChargingStationNames": [{"lang": "en", "value": "$name EN"}, {"lang": "de", "value": "$name"}],
         "EvseID": "$id", "GeoCoordinates": {"Google": "$lat $lon"}, "Plugs": [$plugs], "OpeningTimes": null}"""

    /** Dos operadores: el nombre del operador va detrás de sus puntos, como en el fichero de verdad. */
    private val data = """{"EVSEData": [
        {"EVSEDataRecord": [
            ${evse("CH*CCC*E1", 46.446593, 6.297621, "DC", "\"224.0\"", "\"CCS Combo 2 Plug (Cable Attached)\"")},
            ${evse("CH*CCC*E2", 46.446601, 6.297630, "DC", "224.0", "\"CCS Combo 2 Plug (Cable Attached)\", \"CHAdeMO\"", volts = "\"920\"")},
            ${evse("CH*CCC*E3", 46.446595, 6.297625, "AC_3_PHASE", "\"22.0\"", "\"Type 2 Outlet\"")},
            ${evse("CH*CCC*T1", 46.5, 6.5, "DC", "\"50\"", "\"CCS Combo 2 Plug (Cable Attached)\"", access = "Test Station")}
         ], "OperatorID": "CH*CCC", "OperatorName": "Move"},
        {"OperatorName": "IONITY", "OperatorID": "CH*ION", "EVSEDataRecord": [
            ${evse("CH*ION*E1", 46.8, 7.1, "DC", "\"350\"", "\"CCS Combo 2 Plug (Cable Attached)\"", name = "IONITY Gruyère",
        access = "Restricted access", volts = "\"400\"")}
         ]}
    ]}"""

    private val status = """{"EVSEStatuses": [
        {"EVSEStatusRecord": [{"EvseID": "CH*CCC*E1", "EVSEStatus": "Occupied"}, {"EvseID": "CH*CCC*E2", "EVSEStatus": "Available"},
            {"EvseID": "CH*CCC*E3", "EVSEStatus": "Available"}], "OperatorID": "CH*CCC", "OperatorName": "Move"},
        {"EVSEStatusRecord": [{"EvseID": "CH*ION*E1", "EVSEStatus": "OutOfService"}, {"EvseID": "CH*XXX*E9", "EVSEStatus": "EvseNotFound"}]}
    ]}"""

    @Test
    fun fastChargersAreGroupedBySiteWithTheirPoints() {
        val all = SwissChargers.parse(StringReader(data), "de")
        assertEquals(2, all.size)
        val move = all[0]
        assertEquals("Move", move.operator)
        assertEquals("MOVE - La Côte", move.name) // en el idioma pedido
        assertEquals("Bursins", move.town)
        assertEquals(224.0, move.dcKw, 0.01)
        // Solo los de continua: el de alterna del mismo sitio y el de pruebas no cuentan.
        assertArrayEquals(arrayOf("CH*CCC*E1", "CH*CCC*E2"), move.evses)
        assertEquals(2, move.points)
        assertTrue(move.ccs && move.chademo)
        assertEquals(920.0, move.dcVolts, 0.01)
        // «Restricted access» no quita a IONITY; su «400 V» no se cree (los de verdad son de 920).
        val ionity = all[1]
        assertEquals("IONITY", ionity.operator)
        assertEquals(0.0, ionity.dcVolts, 0.01)
        val c = ionity.also { it.src = "ch" }.toCharger()
        assertEquals("ch:CH*ION*E1", c.id)
        assertEquals("ionity", c.network)
        assertEquals(920.0, c.maxVolts, 0.01)
        assertEquals(RoutePlanner.Charger.SOURCE_DGT, c.source)
        // En inglés, el nombre inglés.
        assertEquals("MOVE - La Côte EN", SwissChargers.parse(StringReader(data), "en")[0].name)
    }

    @Test
    fun aSingleNameWithoutAList() {
        // 40 puntos del fichero de verdad traen el nombre como un objeto suelto, no como lista.
        val one = """{"EVSEData": [{"OperatorName": "Lombard", "EVSEDataRecord": [{"EvseID": "CH*LOM*1",
            "ChargingStationNames": {"value": "Lombard Borne P1", "lang": "en"}, "GeoCoordinates": {"Google": "46.5 6.6"},
            "ChargingFacilities": {"power": 50, "powertype": "DC"}, "Plugs": "CCS Combo 2 Plug (Cable Attached)"}]}]}"""
        val all = SwissChargers.parse(StringReader(one), "de")
        assertEquals(1, all.size)
        assertEquals("Lombard Borne P1", all[0].name)
        assertEquals(50.0, all[0].dcKw, 0.01)
        assertTrue(all[0].ccs)
    }

    @Test
    fun liveStatusPerSite() {
        val st = SwissChargers.parseStatus(StringReader(status), null)
        assertEquals(SwissChargers.BUSY, st["CH*CCC*E1"])
        assertEquals(SwissChargers.FREE, st["CH*CCC*E2"])
        assertEquals(SwissChargers.OUT, st["CH*ION*E1"])
        assertEquals(SwissChargers.UNKNOWN, st["CH*XXX*E9"])
        // Solo los que se piden.
        assertEquals(setOf("CH*CCC*E1"), SwissChargers.parseStatus(StringReader(status), setOf("CH*CCC*E1")).keys)
        // {libres, ocupados, fuera, sin estado}
        assertArrayEquals(intArrayOf(1, 1, 0, 1), SwissChargers.count(arrayOf("CH*CCC*E1", "CH*CCC*E2", "CH*NUEVO"), st))
        assertNull(SwissChargers.count(arrayOf("CH*CCC*E1"), null))
        assertNull(SwissChargers.count(emptyArray(), st))
    }

    @Test
    fun theScreenAndThePlanUseTheLiveStatus() {
        val all = SwissChargers.parse(StringReader(data), "de")
        SwissChargers.setForTest(all)
        val before = SwissChargers.version
        SwissChargers.setStatus(SwissChargers.parseStatus(StringReader(status), null), System.currentTimeMillis())
        // IONITY, con su único punto fuera de servicio: el plan no lo usa y la lista de la ruta se rehace.
        assertTrue(SwissChargers.allOut(all[1]))
        assertFalse(SwissChargers.allOut(all[0]))
        assertTrue(SwissChargers.version != before)
        // La pantalla lo busca por el id del cargador.
        assertArrayEquals(intArrayOf(1, 1, 0, 0), SwissChargers.live("ch:CH*CCC*E1"))
        assertNull(SwissChargers.live("dgt:123"))
        // El mismo estado otra vez no rehace nada.
        val v = SwissChargers.version
        SwissChargers.setStatus(SwissChargers.parseStatus(StringReader(status), null), System.currentTimeMillis())
        assertEquals(v, SwissChargers.version)
        // Un estado viejo no se enseña.
        SwissChargers.setStatus(SwissChargers.parseStatus(StringReader(status), null),
            System.currentTimeMillis() - SwissChargers.STATUS_FRESH_MS - 1000)
        assertNull(SwissChargers.live("ch:CH*CCC*E1"))
        assertFalse(SwissChargers.allOut(all[1]))
        // Y en la cuadrícula, junto a los oficiales de los otros países.
        val tile = ChargerCache.tileOf(all[0].lat, all[0].lon)
        assertEquals("ch", OfficialChargers.sourceOf(tile))
        assertTrue(OfficialChargers.inTile(tile)!!.contains(all[0]))
    }

    @Test
    fun savedLinesKeepThePoints() {
        val s = SwissChargers.parse(StringReader(data), "de")[0]
        val back = SwissChargers.fromLine(SwissChargers.toLine(s))!!
        assertArrayEquals(s.evses, back.evses)
        assertEquals("ch", back.src)
        assertEquals(s.name, back.name)
        assertEquals(s.dcKw, back.dcKw, 0.01)
        // Una línea de la DGT (sin puntos) también se lee.
        assertNotNull(SwissChargers.fromLine(DgtChargers.toLine(s)))
    }

    @Test
    fun swissNetworksInTheFilter() {
        assertEquals("energie360", ChargerFilter.classify("", "", "Energie 360 Grad AG", ""))
        assertEquals("mcharge", ChargerFilter.classify("", "", "M-Charge", ""))
        assertEquals("move", ChargerFilter.classify("", "", "Move", ""))
        assertEquals(ChargerFilter.OTHER, ChargerFilter.classify("", "", "", "Remove the cable")) // «move» solo entero
        assertEquals("ecarup", ChargerFilter.classify("", "", "eCarUp", ""))
        assertEquals("swisscharge", ChargerFilter.classify("", "", "swisscharge.ch AG", ""))
        assertEquals("powerup", ChargerFilter.classify("", "", "Power Up", ""))
        assertEquals("aviavolt", ChargerFilter.classify("", "", "AVIA VOLT Suisse AG", ""))
        assertEquals("ewz", ChargerFilter.classify("", "", "Elektrizitätswerk der Stadt Zürich", ""))
        assertEquals("gofast", ChargerFilter.classify("", "", "GoFast", ""))
        assertTrue(ChargerFilter.inCountry("move", "ch"))
        assertFalse(ChargerFilter.inCountry("move", "es"))
    }

    @Test
    fun theRouteKnowsWhenItGoesThroughSwitzerland() {
        assertTrue(SwissChargers.nearSwitzerland(doubleArrayOf(48.1, 47.0), doubleArrayOf(11.5, 8.3)))
        assertFalse(SwissChargers.nearSwitzerland(doubleArrayOf(40.4, 41.4), doubleArrayOf(-3.7, 2.2)))
    }

    /** Con el fichero entero (HQL_CH_JSON = el JSON descargado): se lee todo y salen miles de puntos rápidos. */
    @Test
    fun theWholeRegister() {
        val path = System.getenv("HQL_CH_JSON")
        assumeTrue(path != null && File(path).exists())
        val t0 = System.currentTimeMillis()
        val all = InputStreamReader(File(path!!).inputStream(), Charsets.UTF_8).use { SwissChargers.parse(it, "de") }
        val points = all.sumOf { it.evses.size }
        println("Suiza: ${all.size} sitios rápidos, $points puntos, en ${System.currentTimeMillis() - t0} ms")
        assertTrue(all.size > 1000)
        assertTrue(points > 4000)
        val nets = all.map { it.also { s -> s.src = "ch" }.toCharger().network }.groupingBy { it }.eachCount()
        println("Suiza: redes ${nets.entries.sortedByDescending { it.value }.take(12)}")
        assertTrue((nets["mcharge"] ?: 0) > 50)
        val statusPath = System.getenv("HQL_CH_STATUS")
        if (statusPath != null && File(statusPath).exists()) {
            val st = InputStreamReader(File(statusPath).inputStream(), Charsets.UTF_8).use { SwissChargers.parseStatus(it, null) }
            val withStatus = all.count { SwissChargers.count(it.evses, st)?.let { c -> c[SwissChargers.UNKNOWN] < it.evses.size } == true }
            println("Suiza: estado de ${st.size} puntos; $withStatus sitios con estado")
            assertTrue(withStatus > all.size / 2)
        }
    }
}
