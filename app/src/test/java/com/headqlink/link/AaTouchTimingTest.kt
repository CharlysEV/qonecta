package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Config as RoboConfig

/** Toques hacia Android Auto: el soltar no llega antes de MIN_TAP_MS del pulsar, y nada se desordena (18-10). */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@RoboConfig(sdk = [35], application = android.app.Application::class)
class AaTouchTimingTest {
    private val min = AaPassthroughSource.MIN_TAP_MS

    @Test
    fun aGluedTapIsHeldAtLeastTheMinimum() {
        // Pulsar y soltar a la vez (llegaron juntos por un pico de la radio): el soltar, MIN_TAP_MS después.
        assertEquals(1000 + min, AaPassthroughSource.touchSendAt(1000, 1000, true, 1000))
        // Un toque normal (60 ms): a su hora.
        assertEquals(1060L, AaPassthroughSource.touchSendAt(1060, 1000, true, 1000))
    }

    @Test
    fun whatComesAfterADelayedReleaseWaitsForIt() {
        // Un DOWN nuevo que llega mientras espera el soltar del anterior va detrás de él.
        assertEquals(1000 + min, AaPassthroughSource.touchSendAt(1010, 1000 + min, false, 0))
        // Un movimiento, a su hora si no hay nada pendiente.
        assertEquals(2000L, AaPassthroughSource.touchSendAt(2000, 1000, false, 0))
    }
}
