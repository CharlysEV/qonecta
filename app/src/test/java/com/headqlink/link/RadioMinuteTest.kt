package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** El resumen por minuto de la radio: retransmisiones del minuto, rtt, picos (uno por pico) y la pantalla del móvil. */
class RadioMinuteTest {
    @Test
    fun aMinuteWithTwoSpikes() {
        val r = RadioMinute()
        // 100 muestras normales (5 ms), un pico de 3 muestras (80-136 ms) y otro de 1; retransmisiones de 1000 a 1042.
        var retrans = 1000L
        repeat(100) { r.add(5, retrans, 1024) }
        for (rtt in intArrayOf(80, 136, 90)) r.add(rtt, ++retrans, 71 * 1024)
        repeat(50) { r.add(6, retrans, 0) }
        r.add(61, retrans + 39, 2048)
        repeat(10) { r.screen(false) }
        assertEquals(42, r.retrans())
        assertEquals(2, r.spikes())
        assertEquals(5, r.rttPercentile(50))
        assertEquals(136, r.rttPercentile(100))
        val line = r.line(3)
        assertTrue(line, line.startsWith("radio, minuto 3: retrans +42 · rtt 5 ms"))
        assertTrue(line, line.contains("2 picos de más de 50 ms · cola máx. 71 KB · pantalla del móvil apagada"))
    }

    @Test
    fun theNextMinuteStartsFromTheLastTotal() {
        val r = RadioMinute()
        r.add(5, 500, 0)
        r.add(5, 520, 0)
        r.reset()
        r.add(5, 525, 0)
        assertEquals(5, r.retrans())
        r.screen(true)
        r.screen(false)
        assertTrue(r.line(2).endsWith("pantalla del móvil encendida el 50 %"))
    }
}
