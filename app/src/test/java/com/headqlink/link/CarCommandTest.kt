package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Widget «Coche»: lo que se entiende de una frase dicha y el cuerpo de cada orden (leapmotor-api, docs/api.md). */
class CarCommandTest {
    private fun kind(s: String) = CarCommand.parse(s)?.kind

    @Test
    fun lockAndUnlock() {
        assertEquals(CarCommand.Kind.LOCK, kind("Cierra el coche"))
        assertEquals(CarCommand.Kind.LOCK, kind("bloquea las puertas"))
        assertEquals(CarCommand.Kind.UNLOCK, kind("Abre el coche"))
        assertEquals(CarCommand.Kind.UNLOCK, kind("desbloquéalo"))
    }

    @Test
    fun trunkGoesBeforeDoors() {
        assertEquals(CarCommand.Kind.TRUNK_OPEN, kind("Abre el maletero"))
        assertEquals(CarCommand.Kind.TRUNK_CLOSE, kind("cierra el maletero"))
        assertEquals(CarCommand.Kind.TRUNK_OPEN, kind("el portón"))
    }

    @Test
    fun climateWithTheTemperatureSaid() {
        val c = CarCommand.parse("Pon el clima a 21 grados")!!
        assertEquals(CarCommand.Kind.CLIMATE_ON, c.kind)
        assertEquals(21, c.tempC)
        assertEquals(CarCommand.TEMP_DEFAULT, CarCommand.parse("enciende el aire")!!.tempC)
        assertEquals(26, CarCommand.parse("calienta el coche")!!.tempC)
        assertEquals(19, CarCommand.parse("enfría el coche")!!.tempC)
        // Fuera del margen del coche: al límite.
        assertEquals(CarCommand.TEMP_MAX, CarCommand.parse("clima a 40")!!.tempC)
        assertEquals(CarCommand.Kind.CLIMATE_OFF, kind("Apaga el clima"))
        assertEquals(CarCommand.Kind.CLIMATE_OFF, kind("quita el aire"))
        assertEquals(CarCommand.Kind.DEFROST, kind("desempaña la luna"))
    }

    @Test
    fun findTheCar() {
        assertEquals(CarCommand.Kind.FIND, kind("¿Dónde está el coche?"))
        assertEquals(CarCommand.Kind.FIND, kind("localiza el coche"))
        assertEquals(CarCommand.Kind.FIND, kind("que pite"))
    }

    @Test
    fun nonsenseIsNotACommand() {
        assertNull(CarCommand.parse("qué tiempo hace mañana"))
        assertNull(CarCommand.parse(""))
        assertNull(CarCommand.parse(null))
    }

    @Test
    fun onlyWhatOpensTheCarAsksFirst() {
        assertTrue(CarCommand(CarCommand.Kind.UNLOCK).needsConfirm())
        assertTrue(CarCommand(CarCommand.Kind.TRUNK_OPEN).needsConfirm())
        assertFalse(CarCommand(CarCommand.Kind.LOCK).needsConfirm())
        assertFalse(CarCommand(CarCommand.Kind.CLIMATE_ON).needsConfirm())
        assertFalse(CarCommand(CarCommand.Kind.FIND).needsConfirm())
    }

    @Test
    fun bodiesAndIdsAsDocumented() {
        assertEquals("110", CarCommand(CarCommand.Kind.LOCK).cmdId())
        assertEquals("{\"value\":\"lock\"}", CarCommand(CarCommand.Kind.LOCK).content("C10", Double.NaN))
        assertEquals("{\"value\":\"unlock\"}", CarCommand(CarCommand.Kind.UNLOCK).content("C10", Double.NaN))
        assertEquals("130", CarCommand(CarCommand.Kind.TRUNK_OPEN).cmdId())
        assertEquals("{\"value\":\"true\"}", CarCommand(CarCommand.Kind.TRUNK_OPEN).content("C10", Double.NaN))
        assertEquals("{\"value\":\"false\"}", CarCommand(CarCommand.Kind.TRUNK_CLOSE).content("C10", Double.NaN))
        assertEquals("120", CarCommand(CarCommand.Kind.FIND).cmdId())
        assertEquals("170", CarCommand(CarCommand.Kind.CLIMATE_OFF).cmdId())
        assertEquals("{\"operate\":\"off\"}", CarCommand(CarCommand.Kind.CLIMATE_OFF).content("C10", Double.NaN))
        assertTrue(CarCommand(CarCommand.Kind.CLIMATE_OFF).content("T03", Double.NaN).contains("\"operate\":\"off\""))
        // 21 °C con 30 dentro: enfría; con 10 dentro: calienta.
        assertTrue(CarCommand(CarCommand.Kind.CLIMATE_ON, 21).content("C10", 30.0).contains("\"mode\":\"cold\""))
        assertTrue(CarCommand(CarCommand.Kind.CLIMATE_ON, 21).content("C10", 10.0).contains("\"mode\":\"hot\""))
        assertTrue(CarCommand(CarCommand.Kind.CLIMATE_ON, 21).content("C10", 10.0).contains("\"temperature\":\"21\""))
        assertTrue(CarCommand(CarCommand.Kind.DEFROST).content("C10", Double.NaN).contains("\"wshld\":\"1\""))
    }

    @Test
    fun onlyTheseCommandsCanBeSent() {
        assertEquals(listOf("220", "110", "120", "130", "170"), LeapApi.COMMANDS)
    }
}
