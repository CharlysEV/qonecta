package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Test

/** La puerta del encoder deja dentro uno más de los frames que retiene (11-11: MediaTek con latency=3 iba a ~24 fps). */
class EncoderGateTest {
    @Test
    fun oneMoreThanTheEncoderHoldsBetweenThreeAndSix() {
        // Sin dato o los que piden 1 frame: como siempre, 3.
        assertEquals(3, VideoEncoder.gateMaxPending(0))
        assertEquals(3, VideoEncoder.gateMaxPending(1))
        assertEquals(3, VideoEncoder.gateMaxPending(2))
        // El MediaTek del realme RMX5061 (latency=3): 4, para que el cuarto empuje al primero sin esperar 200 ms.
        assertEquals(4, VideoEncoder.gateMaxPending(3))
        assertEquals(6, VideoEncoder.gateMaxPending(5))
        // Un valor disparatado no deja la puerta abierta del todo.
        assertEquals(6, VideoEncoder.gateMaxPending(30))
        assertEquals(3, VideoEncoder.gateMaxPending(-4))
    }
}
