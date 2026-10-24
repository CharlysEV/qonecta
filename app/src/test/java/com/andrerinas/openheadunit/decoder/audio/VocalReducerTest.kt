package com.andrerinas.openheadunit.decoder.audio

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/** Karaoke vocal reduction: centred voice down, wide instruments and bass kept, no change at Normal. */
class VocalReducerTest {
    @After
    fun reset() {
        VocalReducer.amount = 0f
    }

    /** One second of stereo at 48 kHz: [hz] in the centre (L = R) or on the sides (L = -R). */
    private fun tone(hz: Double, centre: Boolean, seconds: Double = 1.0): ShortArray {
        val frames = (48000 * seconds).toInt()
        val out = ShortArray(frames * 2)
        for (f in 0 until frames) {
            val v = (8000 * sin(2 * PI * hz * f / 48000)).toInt().toShort()
            out[2 * f] = v
            out[2 * f + 1] = if (centre) v else (-v).toShort()
        }
        return out
    }

    /** Output level over input level after the glide and the filter settle (the last half). */
    private fun ratio(hz: Double, centre: Boolean, amount: Float): Double {
        VocalReducer.amount = amount
        val r = VocalReducer()
        val input = tone(hz, centre)
        val output = input.copyOf()
        // In mixer-sized cycles: 10 ms = 960 shorts.
        var i = 0
        while (i < output.size) {
            val n = minOf(960, output.size - i)
            val chunk = output.copyOfRange(i, i + n)
            r.process(chunk)
            chunk.copyInto(output, i)
            i += n
        }
        return rms(output, output.size / 2) / rms(input, input.size / 2)
    }

    private fun rms(a: ShortArray, from: Int): Double {
        var sum = 0.0
        for (k in from until a.size) sum += a[k].toDouble() * a[k]
        return sqrt(sum / (a.size - from))
    }

    @Test
    fun normalLeavesTheMusicUntouched() {
        val input = tone(1000.0, true)
        val output = input.copyOf()
        VocalReducer().process(output)
        assertArrayEquals(input, output)
    }

    @Test
    fun noVoiceRemovesTheCentreButKeepsSidesAndBass() {
        // Centred voice at 1 kHz: about -28 dB.
        assertTrue(ratio(1000.0, true, 1f) < 0.05)
        // Wide instruments (only on the sides): untouched.
        assertEquals(1.0, ratio(1000.0, false, 1f), 0.01)
        // Bass in the centre at 60 Hz: kept.
        assertTrue(ratio(60.0, true, 1f) > 0.9)
    }

    @Test
    fun softAndMediumLowerTheVoiceSixAndTwelveDecibels() {
        assertEquals(0.5, ratio(1000.0, true, VocalReducer.LEVELS[1]), 0.05)
        assertEquals(0.25, ratio(1000.0, true, VocalReducer.LEVELS[2]), 0.05)
        assertEquals(1.0, ratio(1000.0, true, VocalReducer.LEVELS[0]), 0.001)
    }
}
