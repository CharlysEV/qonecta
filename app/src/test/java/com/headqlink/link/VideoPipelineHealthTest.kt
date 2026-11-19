package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Salud del encoder al reutilizar el vídeo entre sesiones (qdauto §6): «último frame» sin sesión sigue sano. */
class VideoPipelineHealthTest {
    private val s = 1_000_000_000L
    private val t0 = 100 * s

    @Test
    fun recentOutputIsHealthyWithOrWithoutGate() {
        assertTrue(VideoPipeline.encoderResponsive(t0 + s, t0, t0, false))
        assertTrue(VideoPipeline.encoderResponsive(t0 + s, t0, t0, true))
    }

    @Test
    fun ungatedSourceWithoutOutputIsUnhealthy() {
        // Patrón o app: la fuente dibuja siempre, así que 2 s sin salida es un encoder muerto.
        assertFalse(VideoPipeline.encoderResponsive(t0 + 3 * s, t0, t0 - s, false))
    }

    @Test
    fun gatedEncoderIdleWithoutSessionStaysHealthy() {
        // Último frame dado en t0, su salida y las repeticiones hasta t0+1 s; 30 s después, sin sesión, sigue sano.
        assertTrue(VideoPipeline.encoderResponsive(t0 + 30 * s, t0 + s, t0, true))
        // Ni siquiera se le dio nunca un frame (0): no hay nada que reprocharle.
        assertTrue(VideoPipeline.encoderResponsive(t0 + 30 * s, t0 + s, 0L, true))
    }

    @Test
    fun gatedEncoderThatSwallowsAFrameIsUnhealthy() {
        // Se le dio un frame en t0+5 s (tras su última salida en t0+1 s) y no devolvió nada.
        assertTrue(VideoPipeline.encoderResponsive(t0 + 6 * s, t0 + s, t0 + 5 * s, true)) // aún dentro del plazo
        assertFalse(VideoPipeline.encoderResponsive(t0 + 8 * s, t0 + s, t0 + 5 * s, true))
    }

    @Test
    fun gateIsWiderOverWifi() {
        // El C10 pide 30 fps por Wi-Fi: 64 KB para aguantar sus microcortes; por cable (60) o sin VIDEO_ARGS, 32 KB.
        assertEquals(64 * 1024, VideoPipeline.gateOutqFor(30))
        assertEquals(32 * 1024, VideoPipeline.gateOutqFor(60))
        assertEquals(32 * 1024, VideoPipeline.gateOutqFor(0))
    }

    @Test
    fun cableUsesShortGopWithoutIntraRefreshWhileWifiKeepsIntraRefresh() {
        // Cable (C10 pide 60 fps e intervalo 2): sin intra-refresh y GOP de 8 s (acotado 6-10 s; evita tanto los 56 s
        // de pixelación de IR como las 54 ráfagas de IDR de gop=4 s).
        assertEquals(0, VideoPipeline.intraRefreshFor(60, 60, false))
        assertEquals(8, VideoPipeline.gopSecFor(60, 2, false))
        assertEquals(6, VideoPipeline.gopSecFor(60, 1, false))
        assertEquals(10, VideoPipeline.gopSecFor(60, 5, false))
        assertEquals(8, VideoPipeline.gopSecFor(60, 0, false))
        // Wi-Fi (30 fps): mantiene intra-refresh y GOP largo para no meter ráfagas por radio.
        assertEquals(30, VideoPipeline.intraRefreshFor(30, 30, false))
        assertEquals(30, VideoPipeline.gopSecFor(30, 3, false))
        assertEquals(0, VideoPipeline.intraRefreshFor(30, 30, true))
        assertEquals(10, VideoPipeline.gopSecFor(30, 3, true))
    }
}
