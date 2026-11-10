package com.headqlink.link

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Volumen de los avisos de voz (foro, 10-11): de 50 a 150 %, 100 de serie; por debajo baja la voz del motor, por encima
 * sube el volumen multimedia mientras habla, sin pasar nunca del máximo del flujo.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class VoiceVolumeTest {
    @Test
    fun clampKeepsTheLimits() {
        assertEquals(50, VoiceVolume.clamp(-3))
        assertEquals(50, VoiceVolume.clamp(0))
        assertEquals(100, VoiceVolume.clamp(100))
        assertEquals(150, VoiceVolume.clamp(9999))
    }

    @Test
    fun theEngineGainNeverPassesOne() {
        assertEquals(0.5f, VoiceVolume.ttsGain(50), 0.001f)
        assertEquals(0.75f, VoiceVolume.ttsGain(75), 0.001f)
        assertEquals(1f, VoiceVolume.ttsGain(100), 0.001f)
        assertEquals(1f, VoiceVolume.ttsGain(150), 0.001f)
    }

    @Test
    fun theStreamIsOnlyRaisedAboveOneHundred() {
        assertEquals(7, VoiceVolume.boostedIndex(50, 7, 15))
        assertEquals(7, VoiceVolume.boostedIndex(100, 7, 15))
        assertEquals(11, VoiceVolume.boostedIndex(125, 7, 15))
        assertEquals(15, VoiceVolume.boostedIndex(150, 7, 15))
        assertEquals(15, VoiceVolume.boostedIndex(5000, 7, 15))
    }

    @Test
    fun theStreamIsNeverLoweredNorPastItsMaximum() {
        assertEquals(15, VoiceVolume.boostedIndex(150, 15, 15))
        assertEquals(15, VoiceVolume.boostedIndex(150, 15, 10))
        assertEquals(0, VoiceVolume.boostedIndex(150, 0, 0))
    }

    @Test
    fun theDefaultIsOneHundredAndTheChoiceIsKept() {
        val cfg = com.headqlink.link.Config(RuntimeEnvironment.getApplication())
        assertEquals(100, cfg.voiceVolume())
        cfg.setVoiceVolume(130)
        assertEquals(130, com.headqlink.link.Config(RuntimeEnvironment.getApplication()).voiceVolume())
        cfg.setVoiceVolume(400)
        assertEquals(150, cfg.voiceVolume())
        cfg.setVoiceVolume(10)
        assertEquals(50, cfg.voiceVolume())
    }
}
