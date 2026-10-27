package com.headqlink.link

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Límite de la vía por país: maxspeed, zonas («DE:urban»), «none» de la Autobahn y los estimados de cada país. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class SpeedLimitTest {
    private fun way(vararg kv: String) = JSONObject().apply {
        for (i in kv.indices step 2) put(kv[i], kv[i + 1])
    }

    private fun limit(tags: JSONObject, country: String, forward: Boolean = true) = RoadInfo.limitFor(tags, forward, country).toList()

    @Test
    fun numbersAndUnits() {
        assertEquals(80, RoadInfo.parseLimit("80"))
        assertEquals(50, RoadInfo.parseLimit("50 km/h"))
        assertEquals(48, RoadInfo.parseLimit("30 mph"))
        assertEquals(50, RoadInfo.parseLimit("50;30"))
        assertEquals(-1, RoadInfo.parseLimit(""))
        assertEquals(-1, RoadInfo.parseLimit("sign"))
    }

    @Test
    fun theUnlimitedAutobahnIsNotA120() {
        // Antes: «none» no se entendía y se estimaba 120 (en rojo a 140, que allí es legal).
        assertEquals(listOf(RoadInfo.UNLIMITED, 0), limit(way("highway", "motorway", "maxspeed", "none"), "DE"))
        // Sin etiqueta, en Alemania la autopista se estima sin límite; en Austria, 130; en España, 120.
        assertEquals(listOf(RoadInfo.UNLIMITED, 1), limit(way("highway", "motorway"), "DE"))
        assertEquals(listOf(130, 1), limit(way("highway", "motorway"), "AT"))
        assertEquals(listOf(120, 1), limit(way("highway", "motorway"), "ES"))
    }

    @Test
    fun zonesOfEachCountry() {
        assertEquals(listOf(50, 0), limit(way("highway", "primary", "maxspeed", "DE:urban"), "DE"))
        assertEquals(listOf(100, 0), limit(way("highway", "secondary", "maxspeed", "DE:rural"), "DE"))
        assertEquals(listOf(100, 0), limit(way("highway", "secondary", "maxspeed", "AT:rural"), "DE"))
        assertEquals(listOf(80, 0), limit(way("highway", "secondary", "maxspeed", "CH:rural"), "CH"))
        assertEquals(listOf(120, 0), limit(way("highway", "motorway", "maxspeed", "CH:motorway"), "CH"))
        assertEquals(listOf(90, 0), limit(way("highway", "secondary", "maxspeed", "ES:rural"), "ES"))
        assertEquals(listOf(70, 0), limit(way("highway", "secondary", "maxspeed", "BE-VLG:rural"), "BE"))
        assertEquals(listOf(30, 0), limit(way("highway", "residential", "maxspeed", "BE-BRU:urban"), "BE"))
        // Zonas con número, en sus formas habituales.
        assertEquals(30, RoadInfo.parseLimit("DE:zone30"))
        assertEquals(30, RoadInfo.parseLimit("DE:zone:30"))
        assertEquals(30, RoadInfo.parseLimit("DE:30"))
        assertEquals(20, RoadInfo.parseLimit("ES:zone20"))
    }

    @Test
    fun theZoneCanComeInOtherTags() {
        // En Alemania es habitual la zona en zone:maxspeed o source:maxspeed y no en maxspeed.
        assertEquals(listOf(30, 0), limit(way("highway", "residential", "zone:maxspeed", "DE:30"), "DE"))
        assertEquals(listOf(100, 0), limit(way("highway", "secondary", "source:maxspeed", "DE:rural"), "DE"))
        // maxspeed manda sobre la zona.
        assertEquals(listOf(70, 0), limit(way("highway", "secondary", "maxspeed", "70", "source:maxspeed", "DE:rural"), "DE"))
    }

    @Test
    fun theLimitOfOurDirection() {
        val w = way("highway", "primary", "maxspeed:forward", "80", "maxspeed:backward", "60", "maxspeed", "70")
        assertEquals(listOf(80, 0), limit(w, "DE", forward = true))
        assertEquals(listOf(60, 0), limit(w, "DE", forward = false))
    }

    @Test
    fun variableOrWalkingIsNotShownNorGuessed() {
        assertEquals(listOf(-1, 0), limit(way("highway", "motorway", "maxspeed", "signals"), "DE"))
        assertEquals(listOf(-1, 0), limit(way("highway", "living_street", "maxspeed", "walk"), "DE"))
        // Calle residencial sin etiqueta: en Alemania «a paso» no se estima; en España, 20.
        assertEquals(listOf(-1, 0), limit(way("highway", "living_street"), "DE"))
        assertEquals(listOf(20, 1), limit(way("highway", "living_street"), "ES"))
    }

    @Test
    fun estimatesAreThoseOfTheCountry() {
        // Carretera sin etiqueta: antes, 90 (España) en todas partes.
        assertEquals(listOf(100, 1), limit(way("highway", "secondary"), "DE"))
        assertEquals(listOf(100, 1), limit(way("highway", "secondary"), "AT"))
        assertEquals(listOf(80, 1), limit(way("highway", "secondary"), "CH"))
        assertEquals(listOf(90, 1), limit(way("highway", "secondary"), "ES"))
        // España, como antes: calle de un carril, 30; nacional «trunk», 100; enlace de autopista, 60.
        assertEquals(listOf(30, 1), limit(way("highway", "residential", "lanes", "1"), "ES"))
        assertEquals(listOf(100, 1), limit(way("highway", "trunk"), "ES"))
        assertEquals(listOf(60, 1), limit(way("highway", "motorway_link"), "ES"))
        // Un enlace fuera de España, o un país sin genéricos conocidos: no se supone.
        assertEquals(listOf(-1, 0), limit(way("highway", "motorway_link"), "DE"))
        assertEquals(listOf(-1, 0), limit(way("highway", "secondary"), "PL"))
    }

    @Test
    fun speedingHalo() {
        assertEquals(0, CarKit.speedingHalo(52.0, 50, true))
        assertEquals(CarKit.AMBER, CarKit.speedingHalo(53.0, 50, true))
        assertEquals(CarKit.RED, CarKit.speedingHalo(58.0, 50, true))
        // Sin límite o sin velocidad al día, nunca.
        assertEquals(0, CarKit.speedingHalo(200.0, RoadInfo.UNLIMITED, true))
        assertEquals(0, CarKit.speedingHalo(80.0, 50, false))
    }
}
