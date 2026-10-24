package com.andrerinas.openheadunit.decoder.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Karaoke vocal reduction for the media sink (48 kHz interleaved stereo PCM16), on the mixer clock.
 *
 * Lead vocals are usually mixed dead centre: identical in both channels. Splitting each frame into
 * mid (L+R)/2 and side (L-R)/2, the voice lives in the mid; side keeps wide instruments and reverb.
 * The mid is turned down by [amount], except below ~200 Hz, which is restored through a low-pass so
 * kick and bass (also centred) survive: mid' = (1 - k) * mid + k * lowpass(mid). At k = 1 a centred
 * 1 kHz voice drops about 30 dB; at 0.5, 6 dB; at 0.75, 12 dB. Mono recordings keep only their bass.
 *
 * [amount] is set from the karaoke screen (any thread) and glides over ~50 ms so changing level or
 * closing the screen never clicks. At 0 nothing is touched.
 */
class VocalReducer(sampleRate: Int = AudioMixer.OUTPUT_SAMPLE_RATE, cutoffHz: Double = 200.0) {
    companion object {
        /** Requested reduction, 0 (off) to 1 (full). Written by the karaoke screen. */
        @Volatile
        @JvmStatic
        var amount: Float = 0f

        /** The karaoke levels: Normal, Soft (-6 dB), Medium (-12 dB) and No voice. */
        @JvmField
        val LEVELS = floatArrayOf(0f, 0.5f, 0.75f, 1f)

        /** Glide length in frames (50 ms at 48 kHz). */
        private const val GLIDE_FRAMES = 2400
    }

    // Biquad low-pass (RBJ cookbook, Butterworth Q), transposed direct form II, on the mid signal.
    private val b0: Double
    private val b1: Double
    private val b2: Double
    private val a1: Double
    private val a2: Double
    private var z1 = 0.0
    private var z2 = 0.0
    private var current = 0f

    init {
        val w0 = 2 * PI * cutoffHz / sampleRate
        val alpha = sin(w0) / (2 * (1 / sqrt(2.0)))
        val c = cos(w0)
        val a0 = 1 + alpha
        b0 = (1 - c) / 2 / a0
        b1 = (1 - c) / a0
        b2 = (1 - c) / 2 / a0
        a1 = -2 * c / a0
        a2 = (1 - alpha) / a0
    }

    /** Processes [shorts] interleaved stereo samples of [buffer] in place. */
    fun process(buffer: ShortArray, shorts: Int = buffer.size) {
        val target = amount.coerceIn(0f, 1f)
        if (target == 0f && current == 0f) {
            // Idle: clear the filter so the next start does not replay an old tail.
            z1 = 0.0
            z2 = 0.0
            return
        }
        val step = 1f / GLIDE_FRAMES
        var i = 0
        while (i + 1 < shorts) {
            if (current < target) current = minOf(target, current + step)
            else if (current > target) current = maxOf(target, current - step)
            val l = buffer[i].toDouble()
            val r = buffer[i + 1].toDouble()
            val mid = (l + r) * 0.5
            val side = (l - r) * 0.5
            val low = b0 * mid + z1
            z1 = b1 * mid - a1 * low + z2
            z2 = b2 * mid - a2 * low
            val k = current.toDouble()
            val m = (1 - k) * mid + k * low
            buffer[i] = (m + side).toInt().coerceIn(-32768, 32767).toShort()
            buffer[i + 1] = (m - side).toInt().coerceIn(-32768, 32767).toShort()
            i += 2
        }
    }
}
