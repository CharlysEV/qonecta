package com.headqlink.link

import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Precios ad hoc de Alemania (AdHocPrices, LPMFF sayaz #238): leer una cuadrícula de Ladekönig, el precio de ahora con
 * franjas (hora alemana), días y festivos, emparejar cada cargador con su estación (sin cruzar redes), el filtro con
 * tope y lo que costaría una parada.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class AdHocPricesTest {
    /** Como una cuadrícula real de Berlín (recortada): Tesla con franjas, Ionity al lado, EnBW con €/min y Lidl por días. */
    private val tile = """
        {"v":1,"tile":"210_53","generated":"2026-10-30T08:46:35+00:00","stations":[
          {"id":"tesla:86f7","s":"tesla","la":52.51939,"lo":13.41673,"g":[{"n":12,"c":"dc","kw":250.0,"k":["ccs"],"r":[0]}],
           "o":"Tesla, Inc.","a":"Grunerstr. 20, 10179 Berlin","r":[{"p":[{"t":"kwh","v":0.55},
             {"t":"kwh","v":0.35,"w":{"s":"2026-09-23T22:00:00Z","in":[{"t":[["00:00","04:00"]]}]}},
             {"t":"kwh","v":0.35,"w":{"s":"2026-09-23T22:00:00Z","in":[{"t":[["04:00","09:00"]]}]}},
             {"t":"kwh","v":0.62,"w":{"s":"2026-09-23T22:00:00Z","in":[{"t":[["09:00","21:00"]]}]}},
             {"t":"kwh","v":0.35,"w":{"s":"2026-09-23T22:00:00Z","in":[{"t":[["21:00","00:00"]]}]}}],"u":"2026-09-24T01:20:29Z"}]},
          {"id":"eco_movement:ion1","s":"eco_movement","la":52.51979,"lo":13.41673,"g":[{"n":6,"c":"dc","kw":350.0,"r":[0]}],
           "o":"IONITY GmbH","r":[{"p":[{"t":"kwh","v":0.69}]}]},
          {"id":"enbw:1","s":"enbw","la":52.53,"lo":13.40,"g":[{"n":4,"c":"dc","kw":300.0,"r":[0]},{"n":2,"c":"ac","kw":22.0,"r":[1]}],
           "o":"EnBW mobility+ AG & Co. KG","r":[{"p":[{"t":"kwh","v":0.79},{"t":"min","v":0.24,"f":30}]},{"p":[{"t":"kwh","v":0.59}]}]},
          {"id":"vaylens:lidl","s":"vaylens","la":52.54,"lo":13.38,"g":[{"n":2,"c":"dc","kw":150.0,"r":[0]}],"o":"Lidl Dienstleistung",
           "r":[{"p":[{"t":"kwh","v":0.39,"w":{"in":[{"d":[0,1,2,3,4]}]}},{"t":"kwh","v":0.49,"w":{"in":[{"d":[5,6]}]}},
             {"t":"kwh","v":0.10,"w":{"in":[{"sp":true}]}},{"t":"base","v":0.5}]}]},
          {"id":"x:chf","s":"x","la":52.55,"lo":13.37,"g":[{"n":1,"c":"dc","kw":50.0,"r":[0]}],"o":"Irgendwer","r":[{"cur":"CHF","p":[{"t":"kwh","v":0.3}]}]},
          {"id":"vw:elli","s":"vw_group_charging","la":52.56,"lo":13.36,"g":[{"n":4,"c":"dc","kw":150.0}],"o":"Elli"}
        ]}
    """.trimIndent()

    @After
    fun clear() = AdHocPrices.clearForTest()

    /** Hora alemana (sin zona en el texto): viernes 30-10-2026 (ya en horario de invierno). */
    private fun at(local: String) = AdHocPrices.berlin(AdHocPrices.time(local, 0))

    private fun station(id: String) = AdHocPrices.parseTile(tile).first { it.id == id }

    @Test
    fun readsOnlyStationsWithPricesAndTheirNetworks() {
        val list = AdHocPrices.parseTile(tile)
        // La de Elli no tiene tarifa: fuera.
        assertEquals(listOf("tesla:86f7", "eco_movement:ion1", "enbw:1", "vaylens:lidl", "x:chf"), list.map { it.id })
        assertEquals("tesla", station("tesla:86f7").network)
        assertEquals("ionity", station("eco_movement:ion1").network)
        assertEquals("enbw", station("enbw:1").network)
        assertEquals(setOf("210_53", "190_30"), AdHocPrices.parseMetaTiles("""{"v":1,"tiles":["210_53","190_30"],"sources":[]}"""))
    }

    @Test
    fun timeWindowsAreGermanTime() {
        val tesla = station("tesla:86f7")
        // De día, la franja cara; de noche y de madrugada, la barata (gana la de franja sobre los 0,55 de base).
        assertEquals(0.62, AdHocPrices.stationPrice(tesla, true, at("2026-10-30T10:00:00"))!!.kwh, 1e-9)
        assertEquals(0.35, AdHocPrices.stationPrice(tesla, true, at("2026-10-30T22:30:00"))!!.kwh, 1e-9)
        assertEquals(0.35, AdHocPrices.stationPrice(tesla, true, at("2026-10-30T03:00:00"))!!.kwh, 1e-9)
        // En verano (UTC+2) también cuenta la hora de allí: 09:30 en Berlín son las 07:30 UTC.
        assertEquals(0.62, AdHocPrices.stationPrice(tesla, true, AdHocPrices.berlin(AdHocPrices.time("2027-07-15T07:30:00Z", 0)))!!.kwh, 1e-9)
        // Antes de que empezaran las franjas, solo la base.
        assertEquals(0.55, AdHocPrices.stationPrice(tesla, true, at("2026-09-01T10:00:00"))!!.kwh, 1e-9)
        // Solo hay puntos de continua: en alterna, nada.
        assertNull(AdHocPrices.stationPrice(tesla, false, at("2026-10-30T10:00:00")))
    }

    @Test
    fun daysHolidaysSessionFeesAndCurrency() {
        val lidl = station("vaylens:lidl")
        val friday = AdHocPrices.stationPrice(lidl, true, at("2026-10-30T12:00:00"))!!
        assertEquals(0.39, friday.kwh, 1e-9)
        assertEquals(0.5, friday.session, 1e-9)
        // El sábado, el del fin de semana; el de festivos no se puede saber y no cuenta.
        assertEquals(0.49, AdHocPrices.stationPrice(lidl, true, at("2026-10-31T12:00:00"))!!.kwh, 1e-9)
        // EnBW: continua con €/min desde el minuto 30; alterna, otra tarifa.
        val dc = AdHocPrices.stationPrice(station("enbw:1"), true, at("2026-10-30T12:00:00"))!!
        assertEquals(0.79, dc.kwh, 1e-9)
        assertEquals(0.24, dc.minute, 1e-9)
        assertEquals(30, dc.minuteFrom)
        assertTrue(dc.session.isNaN())
        assertEquals(0.59, AdHocPrices.stationPrice(station("enbw:1"), false, at("2026-10-30T12:00:00"))!!.kwh, 1e-9)
        // En francos suizos: no es un precio en euros.
        assertNull(AdHocPrices.stationPrice(station("x:chf"), true, at("2026-10-30T12:00:00")))
    }

    @Test
    fun eachChargerGetsItsOwnNetworkNeverTheNeighbour() {
        val list = AdHocPrices.parseTile(tile)
        val noon = at("2026-10-30T12:00:00")
        // Supercharger e Ionity a 45 m: cada uno el suyo.
        assertEquals(0.62, AdHocPrices.best(list, 52.5194, 13.4167, "tesla", true, noon)!!.kwh, 1e-9)
        assertEquals(0.69, AdHocPrices.best(list, 52.5198, 13.4167, "ionity", true, noon)!!.kwh, 1e-9)
        // De la misma red, aunque las coordenadas difieran 200 m.
        assertEquals(0.69, AdHocPrices.best(list, 52.5216, 13.4167, "ionity", true, noon)!!.kwh, 1e-9)
        // Sin red conocida: la más cercana con precio, solo muy cerca.
        assertEquals(0.62, AdHocPrices.best(list, 52.51945, 13.41673, ChargerFilter.OTHER, true, noon)!!.kwh, 1e-9)
        assertNull(AdHocPrices.best(list, 52.5216, 13.4167, ChargerFilter.OTHER, true, noon))
        // Otra red conocida sin estación allí: nada (no se le pone el precio del Tesla de al lado).
        assertNull(AdHocPrices.best(list, 52.5194, 13.4167, "fastned", true, noon))
    }

    @Test
    fun attachMarksTheAreaAndThePrice() {
        AdHocPrices.putForTest("210_53", tile)
        val berlin = RoutePlanner.Charger().apply { lat = 52.53; lon = 13.40; network = "enbw" }
        val unknown = RoutePlanner.Charger().apply { lat = 52.50; lon = 13.30; network = ChargerFilter.OTHER }
        val madrid = RoutePlanner.Charger().apply { lat = 40.42; lon = -3.70; network = "enbw" }
        AdHocPrices.attach(listOf(berlin, unknown, madrid), AdHocPrices.time("2026-10-30T12:00:00", 0))
        assertTrue(berlin.adHocArea)
        assertEquals(0.79, berlin.adHocKwh, 1e-9)
        assertEquals(0.24, berlin.adHocMin, 1e-9)
        assertTrue(unknown.adHocArea)
        assertTrue(unknown.adHocKwh.isNaN())
        assertFalse(madrid.adHocArea)
        assertTrue(madrid.adHocKwh.isNaN())
    }

    @Test
    fun theCapOnlyAppliesWhereThereArePrices() {
        assertTrue(ChargerFilter.adHocOk(true, 0.55, 60, false))
        assertTrue(ChargerFilter.adHocOk(true, 0.60, 60, false))
        assertFalse(ChargerFilter.adHocOk(true, 0.62, 60, true))
        // Sin precio publicado: como los que no dicen sus datos.
        assertTrue(ChargerFilter.adHocOk(true, Double.NaN, 60, true))
        assertFalse(ChargerFilter.adHocOk(true, Double.NaN, 60, false))
        // Fuera de Alemania o sin tope, siempre.
        assertTrue(ChargerFilter.adHocOk(false, Double.NaN, 60, false))
        assertTrue(ChargerFilter.adHocOk(true, 0.95, 0, false))
        assertEquals(0, ChargerFilter.snapAdHoc(55))
        assertEquals(60, ChargerFilter.snapAdHoc(60))
    }

    @Test
    fun whatAStopWouldCost() {
        val c = RoutePlanner.Charger().apply { adHocKwh = 0.79; adHocMin = 0.24; adHocMinFrom = 30 }
        // 40 kWh en la batería (con un 5 % que se pierde cargando) en 25 min: sin €/min.
        assertEquals(40 * 1.05 * 0.79, RouteTab.stopCost(c, 40.0, 25.0), 1e-9)
        // En 40 min: 10 min de bloqueo.
        assertEquals(40 * 1.05 * 0.79 + 10 * 0.24, RouteTab.stopCost(c, 40.0, 40.0), 1e-9)
        c.adHocSession = 0.5
        assertEquals(40 * 1.05 * 0.79 + 0.5, RouteTab.stopCost(c, 40.0, 20.0), 1e-9)
        assertTrue(RouteTab.stopCost(RoutePlanner.Charger(), 40.0, 20.0).isNaN())
    }

    @Test
    fun clocksAndDates() {
        assertEquals(450, AdHocPrices.clock("07:30"))
        assertEquals(1440, AdHocPrices.clock("24:00"))
        assertEquals(-1, AdHocPrices.clock("25:00"))
        assertEquals(1_790_200_800_000L, AdHocPrices.time("2026-09-23T22:00:00Z", 0))
        assertEquals(AdHocPrices.time("2026-09-23T22:00:00Z", 0), AdHocPrices.time("2026-09-24T00:00:00+02:00", 0))
        // Sin zona: hora alemana (en septiembre, UTC+2).
        assertEquals(AdHocPrices.time("2026-09-23T22:00:00Z", 0), AdHocPrices.time("2026-09-24T00:00:00", 0))
        assertEquals(7L, AdHocPrices.time("no es una fecha", 7L))
        assertNotNull(AdHocPrices.tilesAround(52.52, 13.40, 5.0).firstOrNull { it == "210_53" })
    }
}
