package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** El clima encendido desde el widget (el color del botón): su modo durante media hora, y nada si se apagó. */
class CarOrdersTest {
    @Test
    fun climateShownForHalfAnHour() {
        val t = 1_000_000_000L
        assertEquals(CarCommand.Mode.COLD, CarOrders.climate("COLD,$t", t + 60_000))
        assertEquals(CarCommand.Mode.AUTO, CarOrders.climate("AUTO,$t", t + CarOrders.CLIMATE_SHOW_MS))
        assertNull(CarOrders.climate("HEAT,$t", t + CarOrders.CLIMATE_SHOW_MS + 1))
        assertNull(CarOrders.climate("", t))
        assertNull(CarOrders.climate(null, t))
        assertNull(CarOrders.climate("FOO,$t", t))
    }
}
