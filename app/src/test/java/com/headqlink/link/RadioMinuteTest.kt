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

    /** 19-10: en qué segundo empieza cada pico, el más largo, las búsquedas de redes Wi-Fi de Android y el audio. */
    @Test
    fun spikesWithTheirTimeScansAndAudio() {
        val r = RadioMinute()
        r.watchScans()
        // Muestras cada 100 ms durante 40 s: picos a los 3 s (300 ms), 13 s (100 ms) y 30 s (sigue abierto al final).
        var t = 0L
        while (t < 40_000) {
            val spike = t in 3_000 until 3_300 || t in 13_000 until 13_100 || t >= 30_000
            r.add(t, if (spike) 120 else 5, 0, 0)
            t += 100
        }
        // Android acaba de buscar redes a los 15 s (el pico de los 13 s, junto a ella) y a los 25 s.
        r.wifiScan(15_000)
        r.wifiScan(25_000)
        repeat(4) { r.audio(true) }
        r.audio(false)
        assertEquals(3, r.spikes())
        assertEquals(1, r.spikesNearScan())
        // El de los 30 s, abierto: 30,0-39,9 s.
        assertEquals(10_000, r.longestSpikeMs())
        val line = r.line(1)
        assertTrue(line, line.contains(" · picos a los 3, 13, 30 s, el más largo 10000 ms"))
        assertTrue(line, line.contains(" · Android buscó redes Wi-Fi 2 veces (a los 15, 25 s), 1 pico junto a una búsqueda"))
        assertTrue(line, line.endsWith(" · audio sonando el 80 %"))
        // Otro minuto sin búsquedas: lo dice.
        r.reset()
        r.add(0, 5, 0, 0)
        assertTrue(r.line(2), r.line(2).endsWith(" · Android no buscó redes Wi-Fi"))
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
