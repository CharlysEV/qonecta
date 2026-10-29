package com.headqlink.link

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RoboConfig
import org.robolectric.annotation.ConscryptMode

/** Botones de Home Assistant fijados en el panel lateral del coche (29-10, LPMFF): hasta 3, en su orden. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@RoboConfig(sdk = [35], application = android.app.Application::class)
class HaPinnedTest {
    private fun e(id: String, name: String, dc: String = "") = HomeAssistant.parse(
        JSONObject("""{"entity_id":"$id","state":"closed","attributes":{"friendly_name":"$name","device_class":"$dc"}}"""))

    private val garage = e("cover.garaje", "Garaje", "garage")
    private val light = e("light.entrada", "Entrada")
    private val gate = e("cover.portal", "Portal", "gate")
    private val lock = e("lock.puerta", "Puerta")

    @Test
    fun pinningAddsAtTheEndAndTappingAgainUnpins() {
        var pins = HomeAssistant.togglePin(emptyList(), garage, 3)!!
        pins = HomeAssistant.togglePin(pins, light, 3)!!
        assertEquals(listOf("cover.garaje", "light.entrada"), pins.map { it.id })
        pins = HomeAssistant.togglePin(pins, garage, 3)!!
        assertEquals(listOf("light.entrada"), pins.map { it.id })
    }

    @Test
    fun atMostThree() {
        val full = listOf(garage, light, gate)
        assertNull(HomeAssistant.togglePin(full, lock, HomeAssistant.MAX_PINNED))
        // Quitar uno con el tope lleno sí se puede.
        assertEquals(2, HomeAssistant.togglePin(full, light, HomeAssistant.MAX_PINNED)!!.size)
    }

    @Test
    fun aButtonThatIsNoLongerChosenIsUnpinned() {
        val keep = HomeAssistant.keepChosen(listOf(garage, light, gate), listOf("light.entrada", "cover.portal"))
        assertEquals(listOf("light.entrada", "cover.portal"), keep.map { it.id })
    }

    @Test
    fun savedPinsKeepWhatTheCarNeedsToDrawThem() {
        val back = HomeAssistant.pinnedFrom(HomeAssistant.pinnedJson(listOf(garage, light)))
        assertEquals(listOf("cover.garaje", "light.entrada"), back.map { it.id })
        assertEquals("Garaje", back[0].name)
        assertEquals("cover", back[0].domain)
        assertEquals("garage", back[0].deviceClass)
        // El dibujo del garaje, sin leer la casa.
        assertEquals(HomeScreen.glyph(garage), HomeScreen.glyph(back[0]))
        assertTrue(HomeAssistant.pinnedFrom("").isEmpty())
        assertTrue(HomeAssistant.pinnedFrom("{roto").isEmpty())
    }
}
