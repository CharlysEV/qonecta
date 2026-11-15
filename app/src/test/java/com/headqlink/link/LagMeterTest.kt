package com.headqlink.link

import dev.qdauto.core.wire.TouchPointer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/** Medida del retraso de los toques y de la imagen (LagStat, CarTouchCadence, CarHeartbeatCadence, LagMeter, LagGate). */
class LagMeterTest {
    private val ms = 1_000_000L
    private val down = listOf(TouchPointer.down(0, 100f, 100f))
    private val move = listOf(TouchPointer.move(0, 120f, 100f))
    private val up = listOf(TouchPointer.up(0, 140f, 100f))

    // ---------------------------------------------------------------- LagStat

    @Test
    fun statWithoutSamplesSaysNothing() {
        val s = LagStat()
        assertEquals(0L, s.count)
        assertEquals(0L, s.percentile(95))
        assertEquals("", s.text())
    }

    @Test
    fun statGivesMaxCountAndP95() {
        val s = LagStat()
        for (v in 1L..100L) s.add(v)
        assertEquals(100L, s.max)
        assertEquals(100L, s.count)
        assertEquals(95L, s.percentile(95))
        assertEquals("máx. 100 p95 95 ms (100)", s.text())
    }

    @Test
    fun statP95NeverSaysLessThanItWasAndNeverPassesTheMax() {
        val s = LagStat()
        repeat(90) { s.add(3) }
        repeat(10) { s.add(1_534) }
        // El rango 95 cae en la cubeta 1500-1599: su límite superior, recortado al máximo visto.
        assertEquals(1_534L, s.percentile(95))
        val big = LagStat()
        big.add(15_000)
        assertEquals(15_000L, big.percentile(95))
        // Negativo (relojes): cuenta como 0.
        val neg = LagStat()
        neg.add(-5)
        assertEquals(0L, neg.max)
    }

    @Test
    fun statBucketsCoverEveryRange() {
        assertEquals(0, LagStat.bucketOf(0))
        assertEquals(99, LagStat.bucketOf(99))
        assertEquals(100, LagStat.bucketOf(100))
        assertEquals(189, LagStat.bucketOf(999))
        assertEquals(190, LagStat.bucketOf(1_000))
        assertEquals(279, LagStat.bucketOf(9_999))
        assertEquals(LagStat.BUCKETS - 1, LagStat.bucketOf(10_000))
        assertEquals(109L, LagStat.upperOf(100))
        assertEquals(999L, LagStat.upperOf(189))
        assertEquals(1_099L, LagStat.upperOf(190))
        assertEquals(9_999L, LagStat.upperOf(279))
        for (v in longArrayOf(0, 5, 99, 100, 155, 999, 1_000, 4_321, 9_999)) {
            assertTrue("$v", LagStat.upperOf(LagStat.bucketOf(v)) >= v)
        }
    }

    // ---------------------------------------------------------------- toques del coche

    @Test
    fun steadyDragIsNotABurst() {
        val c = CarTouchCadence()
        var t = 1_000 * ms
        assertNull(c.onTouch(t, down))
        repeat(50) {
            t += 16 * ms
            assertNull(c.onTouch(t, move))
        }
        t += 16 * ms
        assertNull(c.onTouch(t, up))
        c.flush()
        assertEquals(52L, c.messages)
        assertEquals(0, c.bursts)
        assertEquals(0L, c.bunched)
        assertEquals(16L, c.maxGestureGapMs)
        assertEquals("0 ráfagas · hueco máx. en gesto 16 ms · amontonados 0", c.text())
    }

    @Test
    fun heldMovesArriveAsABurstAfterAGap() {
        val c = CarTouchCadence()
        var t = 1_000 * ms
        c.onTouch(t, down)
        repeat(10) {
            t += 16 * ms
            c.onTouch(t, move)
        }
        // El coche se calla 2,3 s con el dedo en movimiento y suelta 14 movimientos de golpe.
        t += 2_300 * ms
        var burst: CarTouchCadence.Burst? = null
        repeat(14) { i ->
            if (i > 0) t += 100_000 // 0,1 ms
            c.onTouch(t, move)?.let { burst = it }
        }
        val b = burst
        assertNotNull(b)
        assertEquals(CarTouchCadence.BURST_MIN, b!!.touches) // se avisa en cuanto hay BURST_MIN
        assertEquals(2_300L, b.gapMs)
        assertEquals("ráfaga de 5 toques tras un hueco de 2300 ms en mitad de un gesto", b.describe())
        t += 16 * ms
        c.onTouch(t, up)
        assertEquals(1, c.bursts)
        assertEquals(1, c.burstsAfterGap)
        assertEquals(14, c.worstBurstTouches)
        assertEquals(2_300L, c.worstBurstGapMs)
        assertEquals(13L, c.bunched)
        assertEquals(2_300L, c.maxGestureGapMs)
        assertEquals(
            "1 ráfaga (1 tras hueco; peor 14 toques tras 2300 ms) · hueco máx. en gesto 2300 ms · amontonados 13",
            c.text(),
        )
    }

    @Test
    fun aTapWhosePressAndReleaseArriveTogetherIsNotABurst() {
        val c = CarTouchCadence()
        val t = 1_000 * ms
        assertNull(c.onTouch(t, down))
        assertNull(c.onTouch(t + 10_000, up))
        assertEquals(0, c.bursts)
        assertEquals(1L, c.bunched)
    }

    @Test
    fun aWholeGestureArrivingAtOnceIsABurstWithoutAMeasuredGap() {
        val c = CarTouchCadence()
        var t = 1_000 * ms
        var burst: CarTouchCadence.Burst? = null
        c.onTouch(t, down)
        repeat(6) {
            t += 50_000
            c.onTouch(t, move)?.let { burst = it }
        }
        assertNotNull(burst)
        assertEquals(-1L, burst!!.gapMs)
        assertEquals("ráfaga de 5 toques al empezar un gesto", burst!!.describe())
        assertEquals(1, c.bursts)
        assertEquals(0, c.burstsAfterGap)
    }

    @Test
    fun aShortGapBeforeTheBurstIsCountedButNotAsHeld() {
        val c = CarTouchCadence()
        var t = 1_000 * ms
        c.onTouch(t, down)
        t += 100 * ms // por debajo de BURST_GAP_MS
        repeat(6) { i ->
            if (i > 0) t += 50_000
            c.onTouch(t, move)
        }
        c.onTouch(t + 16 * ms, up)
        assertEquals(1, c.bursts)
        assertEquals(0, c.burstsAfterGap)
        assertEquals(0, c.worstBurstTouches)
    }

    @Test
    fun aGestureLeftOpenTooLongIsALostReleaseNotAGap() {
        val c = CarTouchCadence()
        val t = 1_000 * ms
        c.onTouch(t, down)
        c.onTouch(t + 25_000 * ms, move)
        assertEquals(0L, c.maxGestureGapMs)
    }

    @Test
    fun theGestureLastsWhileAnyFingerIsDown() {
        val c = CarTouchCadence()
        var t = 1_000 * ms
        c.onTouch(t, listOf(TouchPointer.down(0, 1f, 1f)))
        t += 16 * ms
        c.onTouch(t, listOf(TouchPointer.move(0, 2f, 1f), TouchPointer.down(1, 5f, 5f)))
        t += 16 * ms
        c.onTouch(t, listOf(TouchPointer.up(0, 2f, 1f), TouchPointer.move(1, 6f, 5f)))
        t += 400 * ms
        c.onTouch(t, listOf(TouchPointer.move(1, 7f, 5f)))
        assertEquals(400L, c.maxGestureGapMs)
        t += 16 * ms
        c.onTouch(t, listOf(TouchPointer.up(1, 7f, 5f)))
        // Gesto acabado: el siguiente DOWN, aunque tarde, no es un hueco.
        t += 5_000 * ms
        c.onTouch(t, listOf(TouchPointer.down(0, 1f, 1f)))
        assertEquals(400L, c.maxGestureGapMs)
    }

    // ---------------------------------------------------------------- latidos del coche

    @Test
    fun regularHeartbeatsAreNeitherLateNorBunched() {
        val h = CarHeartbeatCadence()
        var t = 100_000L
        repeat(10) {
            assertEquals(-1L, h.onHeartbeat(t))
            t += 3_000
        }
        assertEquals(0, h.late)
        assertEquals(0, h.bunched)
        assertEquals(3_000L, h.maxIntervalMs)
        assertEquals("latidos máx. 3000 ms (tarde 0, juntos 0)", h.text())
    }

    @Test
    fun aLateHeartbeatFollowedByOneStuckToItWasHeld() {
        val h = CarHeartbeatCadence()
        h.onHeartbeat(100_000)
        h.onHeartbeat(103_000)
        assertEquals(-1L, h.onHeartbeat(107_800)) // 4,8 s: tarde
        assertEquals(1_800L, h.onHeartbeat(107_900)) // 0,1 s después: los dos retenidos
        assertEquals(1, h.late)
        assertEquals(1, h.bunched)
    }

    @Test
    fun anIrregularButSpacedHeartbeatIsOnlyLate() {
        val h = CarHeartbeatCadence()
        h.onHeartbeat(100_000)
        assertEquals(-1L, h.onHeartbeat(114_800)) // 14,8 s, visto en sesiones sanas del C10
        assertEquals(-1L, h.onHeartbeat(117_800))
        assertEquals(1, h.late)
        assertEquals(0, h.bunched)
        // Reloj del sistema hacia atrás: no es un intervalo.
        assertEquals(-1L, h.onHeartbeat(50_000))
        assertEquals(-1L, h.onHeartbeat(50_100))
        assertEquals(14_800L, h.maxIntervalMs)
        assertEquals("", CarHeartbeatCadence().also { it.onHeartbeat(1) }.text())
    }

    // ---------------------------------------------------------------- LagMeter y LagGate

    @Test
    fun aDelayOfOneSecondLeavesAnAlarmAndTheGateLetsOneEveryThirtySeconds() {
        val m = LagMeter(LagGate())
        m.record(LagMeter.Stage.AA, 999)
        assertNull(m.takeAlarm(0))
        m.record(LagMeter.Stage.AA, 1_500)
        val first = m.takeAlarm(10_000)
        assertNotNull(first)
        assertEquals(LagMeter.Stage.AA, first!!.first.stage)
        assertEquals(1_500L, first.first.ms)
        assertEquals(0, first.second)
        assertNull(m.takeAlarm(10_100)) // ya recogido
        m.record(LagMeter.Stage.PANTALLA, 2_000)
        assertNull(m.takeAlarm(20_000)) // a los 10 s del anterior: se calla
        m.record(LagMeter.Stage.IMAGEN, 1_200)
        val next = m.takeAlarm(40_000)
        assertNotNull(next)
        assertEquals(LagMeter.Stage.IMAGEN, next!!.first.stage)
        assertEquals(1, next.second) // el de la pantalla se calló
    }

    @Test
    fun theWorstPendingDelayIsTheOneReported() {
        val m = LagMeter(LagGate())
        m.record(LagMeter.Stage.AA, 1_200)
        m.record(LagMeter.Stage.PANTALLA, 3_000)
        m.record(LagMeter.Stage.IMAGEN, 1_100)
        assertEquals(3_000L, m.takeAlarm(0)!!.first.ms)
    }

    @Test
    fun theGateIsSharedBySessions() {
        val gate = LagGate()
        val a = LagMeter(gate)
        val b = LagMeter(gate)
        a.record(LagMeter.Stage.AA, 1_500)
        b.record(LagMeter.Stage.AA, 1_500)
        assertNotNull(a.takeAlarm(1_000))
        assertNull(b.takeAlarm(2_000))
        assertEquals(0, LagGate().tryPass(0))
    }

    @Test
    fun aCarBurstAfterALongGapIsACarAlarmAndTheEventWaitIsMeasured() {
        val m = LagMeter(LagGate())
        var t = 1_000 * ms
        m.onCarTouch(t, t + 2 * ms, down)
        repeat(3) {
            t += 16 * ms
            m.onCarTouch(t, t + ms, move)
        }
        t += 2_300 * ms
        repeat(6) { i ->
            if (i > 0) t += 100_000
            m.onCarTouch(t, t + ms, move)
        }
        val a = m.takeAlarm(5_000)!!.first
        assertEquals(LagMeter.Stage.COCHE, a.stage)
        assertEquals(2_300L, a.ms)
        assertEquals(
            "LAG coche→móvil 2300 ms · ráfaga de 5 toques tras un hueco de 2300 ms en mitad de un gesto · enlace cable USB",
            a.line("enlace cable USB", 0),
        )
        assertEquals(10L, m.stat(LagMeter.Stage.EVENTOS).count)
        assertEquals(2L, m.stat(LagMeter.Stage.EVENTOS).max)
    }

    @Test
    fun aShortBurstGapIsNotAnAlarm() {
        val m = LagMeter(LagGate())
        var t = 1_000 * ms
        m.onCarTouch(t, t, down)
        t += 400 * ms
        repeat(6) { i ->
            if (i > 0) t += 100_000
            m.onCarTouch(t, t, move)
        }
        assertNull(m.takeAlarm(0))
        assertEquals(1, m.touches.bursts)
    }

    @Test
    fun aHeldHeartbeatIsACarAlarm() {
        val m = LagMeter(LagGate())
        m.onCarHeartbeat(100_000)
        m.onCarHeartbeat(103_000)
        m.onCarHeartbeat(108_000)
        m.onCarHeartbeat(108_050)
        val a = m.takeAlarm(0)!!.first
        assertEquals(LagMeter.Stage.COCHE, a.stage)
        assertEquals(2_000L, a.ms)
        assertTrue(a.detail!!, a.detail!!.startsWith("latido retenido"))
    }

    @Test
    fun theAlarmLineCarriesTheSuppressedCount() {
        val a = LagMeter.Alarm(LagMeter.Stage.AA, 1_500, null)
        assertEquals("LAG entrega a Android Auto 1500 ms · S3 · +2 avisos callados desde el anterior", a.line("S3", 2))
        assertEquals("LAG entrega a Android Auto 1500 ms", a.line("", 0))
    }

    @Test
    fun imageAgeIsFromTheDrawTimeToTheEndOfTheWrite() {
        assertEquals(50L, LagMeter.imageAgeMs(10_000_000_000L, 9_950_000L))
        assertEquals(-1L, LagMeter.imageAgeMs(10_000_000_000L, 10_100_000L)) // dibujado después: otro reloj
        assertEquals(-1L, LagMeter.imageAgeMs(10_000_000_000L, 0))
        assertEquals(-1L, LagMeter.imageAgeMs(0, 5))
        assertEquals(-1L, LagMeter.imageAgeMs(100_000_000_000L, 1_000L)) // más de 60 s: no era la hora de dibujo
        val m = LagMeter(LagGate())
        m.onImageWritten(10_000_000_000L, 9_950_000L)
        m.onImageWritten(10_000_000_000L, 0)
        assertEquals(1L, m.stat(LagMeter.Stage.IMAGEN).count)
        assertEquals(1L, m.unreadableImages)
    }

    @Test
    fun freezeTextSaysWhetherThePhoneFroze() {
        val m = LagMeter(LagGate())
        assertEquals("móvil sin congelarse", m.freezeText(1_000))
        m.noteFreeze(10_000, 640)
        assertEquals("móvil congelado 640 ms hace 2000 ms", m.freezeText(12_000))
        assertEquals("móvil sin congelarse", m.freezeText(10_000 + LagMeter.FREEZE_WINDOW_MS + 1))
    }

    @Test
    fun summaryIsEmptyWithNothingMeasuredAndListsEveryMeasuredPart() {
        assertEquals("", LagMeter(LagGate()).summary("cable USB"))
        val m = LagMeter(LagGate())
        var t = 1_000 * ms
        m.onCarTouch(t, t + ms, down)
        t += 16 * ms
        m.onCarTouch(t, t + ms, up)
        m.onCarHeartbeat(100_000)
        m.onCarHeartbeat(103_000)
        m.record(LagMeter.Stage.AA, 12)
        m.record(LagMeter.Stage.AA, 1_300)
        m.onImageWritten(10_000_000_000L, 9_978_000L)
        m.takeAlarm(0)
        val s = m.summary("cable USB")
        assertEquals(
            "retraso (cable USB): coche→móvil 0 ráfagas · hueco máx. en gesto 16 ms · amontonados 0 · " +
                "latidos máx. 3000 ms (tarde 0, juntos 0) | dentro del móvil eventos de la sesión máx. 1 p95 1 ms (2) · " +
                "entrega a Android Auto máx. 1300 p95 1300 ms (2) | imagen máx. 22 p95 22 ms (1) | avisos LAG 1",
            s,
        )
        // Sin toques ni imagen (solo latidos): las demás partes con «—».
        val quiet = LagMeter(LagGate())
        quiet.onCarHeartbeat(100_000)
        quiet.onCarHeartbeat(103_000)
        assertEquals(
            "retraso: coche→móvil latidos máx. 3000 ms (tarde 0, juntos 0) | dentro del móvil — | imagen — | avisos LAG 0",
            quiet.summary(""),
        )
    }

    // ---------------------------------------------------------------- TouchLag

    @Test
    fun touchLagCarriesTheArrivalOnlyOnItsThreadAndOnlyDuringTheCall() {
        val m = LagMeter(LagGate())
        assertNull(TouchLag.current())
        TouchLag.begin(m, System.nanoTime())
        val stamp = TouchLag.current()
        assertNotNull(stamp)
        val other = AtomicReference<TouchLag.Stamp?>(stamp)
        val th = Thread { other.set(TouchLag.current()) }
        th.start()
        th.join()
        assertNull(other.get())
        TouchLag.end()
        assertNull(TouchLag.current())
        stamp!!.done(LagMeter.Stage.PANTALLA)
        assertEquals(1L, m.stat(LagMeter.Stage.PANTALLA).count)
    }
}
