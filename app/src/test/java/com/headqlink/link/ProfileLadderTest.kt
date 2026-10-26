package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Los perfiles, coherentes con sus nombres (25-10): de Máximo a Muy bajo, cada uno pide al móvil y da al coche lo mismo
 * o menos que el anterior. Antes, Máximo iba a 30 fps sin tocar la pantalla (menos que Muy alto), Muy alto y Alto iban a
 * 60 fps por Wi-Fi, Básico pasaba a ser Medio en Auto extendido y Medio forzaba el codificador.
 */
class ProfileLadderTest {
    private val carW = 1920
    private val carH = 882

    /** Píxeles por segundo que codifica el móvil por cable (60 fps pedidos), con el tope de contenido de Coche a 30. */
    private fun pixelRate(p: VideoProfile, carFps: Int = 60): Long {
        val size = p.videoSize(carW, carH)
        val fps = p.fpsFor(carFps)
        val shown = if (p.capsContentTo30()) minOf(fps, 30) else fps
        return size[0].toLong() * size[1] * shown
    }

    @Test
    fun eachStepAsksTheSameOrLess() {
        val ladder = listOf(
            VideoProfile.of(VideoProfile.BEST),
            VideoProfile.of(VideoProfile.MAX),
            VideoProfile.of(VideoProfile.HIGH),
            VideoProfile.of(VideoProfile.CAR),
            VideoProfile.of(VideoProfile.MEDIUM),
            VideoProfile.basicReencoded(),
            VideoProfile.of(VideoProfile.LOW),
        )
        for (i in 1 until ladder.size) {
            assertTrue("${ladder[i].id} no pide más que ${ladder[i - 1].id}", pixelRate(ladder[i]) <= pixelRate(ladder[i - 1]))
        }
        // Máximo, Muy alto y Alto: 60 fps por cable, todo el rato.
        for (p in ladder.take(3)) assertEquals(p.id, 60, p.fpsFor(60))
    }

    @Test
    fun theTopThreeDifferInSharpnessLatencyAndHeat() {
        val best = VideoProfile.of(VideoProfile.BEST)
        val max = VideoProfile.of(VideoProfile.MAX)
        val high = VideoProfile.of(VideoProfile.HIGH)
        // Máximo por cable, el más nítido (QP más bajo); por Wi-Fi y los demás, el de siempre.
        assertTrue(best.qpFloorFor(60) < max.qpFloorFor(60))
        assertEquals(PFrameSizeController.QP_FLOOR, best.qpFloorFor(30))
        assertEquals(PFrameSizeController.QP_FLOOR, high.qpFloorFor(60))
        // Máximo y Muy alto, con el codificador al máximo; Alto, sin forzarlo.
        assertTrue(best.boost && max.boost)
        assertFalse(high.boost)
    }

    @Test
    fun theLightOnesDoNotForceThePhone() {
        // Medio, Básico (recodificando, como en Auto extendido) y Muy bajo: sin forzar el codificador.
        assertFalse(VideoProfile.of(VideoProfile.MEDIUM).boost)
        val basic = VideoProfile.basicReencoded()
        assertFalse(basic.boost)
        assertEquals(VideoProfile.BASIC, basic.id)
        assertEquals(30, basic.fpsFor(60))
        assertTrue(basic.startBitrate(5_080_320, 60, 1280) < VideoProfile.of(VideoProfile.CAR).startBitrate(5_080_320, 30, 1920))
        assertFalse(VideoProfile.of(VideoProfile.LOW).boost)
    }

    @Test
    fun byWifiNothingGoesAboveWhatTheRadioTakes() {
        // Por Wi-Fi (el coche pide 30): Máximo, Muy alto y Alto a 30 fps y como mucho 8 Mbit/s.
        for (id in listOf(VideoProfile.BEST, VideoProfile.MAX, VideoProfile.HIGH)) {
            val p = VideoProfile.of(id)
            assertEquals(id, 30, p.fpsFor(30))
            assertEquals(id, VideoProfile.BEST_WIFI_MAX_BPS, p.maxBitrateFor(30))
        }
    }
}
