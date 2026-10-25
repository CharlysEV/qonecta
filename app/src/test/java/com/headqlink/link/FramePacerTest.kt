package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ritmo del relay GL: como mucho los fps del vídeo, también con dos fuentes a la vez. */
class FramePacerTest {
    private val ms = 1_000_000L

    /**
     * Simula el relay con un reloj de 1 ms durante [seconds]: cada llegada deja un frame nuevo (el último pisa al
     * anterior) y se dibuja en cuanto el ritmo lo permite. Devuelve cuántos se dibujaron.
     */
    private fun simulate(pacer: FramePacer, grid: Boolean, seconds: Int, arrivalsMs: List<Long>): Int {
        val arrivals = arrivalsMs.toHashSet()
        var hasNew = false
        var drawn = 0
        for (t in 0L until seconds * 1000L) {
            if (t in arrivals) hasNew = true
            val now = 1_000_000_000L + t * ms
            if (hasNew && pacer.waitNs(now, grid) <= 0) {
                pacer.onDraw(now, grid)
                hasNew = false
                drawn++
            }
        }
        return drawn
    }

    private fun every(periodMs: Double, offsetMs: Double, seconds: Int): List<Long> =
        generateSequence(offsetMs) { it + periodMs }.takeWhile { it < seconds * 1000 }.map { it.toLong() }.toList()

    @Test
    fun aaAt30IsDrawnFrameByFrame() {
        val drawn = simulate(FramePacer(30), grid = false, seconds = 10, arrivalsMs = every(1000.0 / 30, 0.0, 10))
        assertTrue("dibujados $drawn", drawn in 298..301)
    }

    @Test
    fun twoSourcesAt30DoNotMake60() {
        // AA a 30 y nuestra capa a 30, desfasadas medio periodo: antes salían ~60 dibujos por segundo.
        val arrivals = every(1000.0 / 30, 0.0, 10) + every(1000.0 / 30, 1000.0 / 60, 10)
        val drawn = simulate(FramePacer(30), grid = false, seconds = 10, arrivalsMs = arrivals)
        assertTrue("dibujados $drawn", drawn in 290..301)
    }

    @Test
    fun jitteryAaIsNotThinnedOut() {
        // AA a 30 con ±8 ms de irregularidad: se dibujan todos (la tolerancia es medio periodo).
        val arrivals = every(1000.0 / 30, 0.0, 10).mapIndexed { i, t -> t + if (i % 2 == 0) 8 else -8 }.filter { it >= 0 }
        val drawn = simulate(FramePacer(30), grid = false, seconds = 10, arrivalsMs = arrivals)
        assertTrue("dibujados $drawn de ${arrivals.size}", drawn >= arrivals.size - 2)
    }

    @Test
    fun gridBelowTheSourceRateCapsIt() {
        // Tope térmico: 24 o 20 fps con AA a 30, en rejilla.
        val aa = every(1000.0 / 30, 0.0, 10)
        val p24 = FramePacer(24)
        p24.setFps(24, true)
        val d24 = simulate(p24, grid = true, seconds = 10, arrivalsMs = aa)
        assertTrue("dibujados $d24", d24 in 230..241)
        val p20 = FramePacer(30)
        p20.setFps(20, true)
        val d20 = simulate(p20, grid = true, seconds = 10, arrivalsMs = aa)
        assertTrue("dibujados $d20", d20 in 190..201)
    }

    @Test
    fun aGapLeavesNoBurstCredit() {
        val p = FramePacer(30)
        val t0 = 5_000_000_000L
        p.onDraw(t0, false)
        // Dos segundos sin frames y luego una ráfaga: solo el primero sale en el acto.
        val t1 = t0 + 2_000 * ms
        assertTrue(p.waitNs(t1, false) <= 0)
        p.onDraw(t1, false)
        assertTrue(p.waitNs(t1 + 1 * ms, false) > 0)
        assertEquals(0L, FramePacer(0).waitNs(t1, false))
    }

    @Test
    fun theExtendedModeKeepsAtLeastThirtyFramesASecondButNeverMoreThanAsked() {
        // El C10 por USB pide 60: el flujo constante va a 30 (como AA en modo Auto); a 24 (térmica), a 24.
        assertEquals(1_000_000_000L / 30, GlFrameRelay.keepAlivePeriodNs(60))
        assertEquals(1_000_000_000L / 24, GlFrameRelay.keepAlivePeriodNs(24))
        assertEquals(1_000_000_000L / 30, GlFrameRelay.keepAlivePeriodNs(0))
    }

    @Test
    fun burstToleranceKeepsJittery60At60() {
        // AA a 60 a rachas (como en casa con Máximo): tres frames en 6 ms y un hueco de 44 ms. Con medio periodo se pierde
        // uno de cada tres; con uno entero, ninguno.
        val arrivals = every(50.0, 0.0, 10).flatMap { listOf(it, it + 3, it + 6) }
        val half = simulate(FramePacer(60), grid = false, seconds = 10, arrivalsMs = arrivals)
        val full = simulate(FramePacer(60).apply { setBurst(true) }, grid = false, seconds = 10, arrivalsMs = arrivals)
        assertTrue("medio $half, entero $full de ${arrivals.size}", full > half)
        assertTrue("entero $full de ${arrivals.size}", full >= arrivals.size * 97 / 100)
    }

    @Test
    fun burstToleranceNeverExceedsTheRate() {
        // AA a 90: con un periodo de tolerancia, como mucho 60 de media (más uno).
        val drawn = simulate(FramePacer(60).apply { setBurst(true) }, grid = false, seconds = 10, arrivalsMs = every(1000.0 / 90, 0.0, 10))
        assertTrue("dibujados $drawn", drawn in 590..601)
    }

    /**
     * Como simulate, con el relleno del relay (keepAlive: un dibujo repetido si no hay nada nuevo en gapMs desde el
     * último). Devuelve las horas (ms) a las que salió cada frame de verdad (los repetidos no cuentan).
     */
    private fun realFrameTimes(pacer: FramePacer, grid: Boolean, gapMs: Long, seconds: Int, arrivalsMs: List<Long>): List<Long> {
        val arrivals = arrivalsMs.toHashSet()
        var hasNew = false
        var real = false
        var lastDraw = -1_000L
        val out = ArrayList<Long>()
        for (t in 0L until seconds * 1000L) {
            if (t in arrivals) {
                hasNew = true
                real = true
            }
            if (!hasNew && t - lastDraw >= gapMs) hasNew = true
            val now = 1_000_000_000L + t * ms
            if (hasNew && pacer.waitNs(now, grid) <= 0) {
                pacer.onDraw(now, grid)
                if (real) out.add(t)
                hasNew = false
                real = false
                lastDraw = t
            }
        }
        return out
    }

    @Test
    fun aMovieAt25GoesOutEvery40msNotInAGridOf30() {
        // Un vídeo de 25 fps (un frame cada 40 ms) con nuestra pantalla delante. Antes: rejilla de 30 y relleno a un
        // periodo (33 ms): el frame repetido ocupaba el tic y el del vídeo esperaba al siguiente (salidas a 27-60 ms).
        val movie = every(40.0, 3.0, 10)
        val old = realFrameTimes(FramePacer(30).apply { setFps(30, true) }, true, 33, 10, movie).zipWithNext { a, b -> b - a }
        assertTrue("antes, intervalos irregulares: $old", old.count { it !in 38..42 } > 20)
        // Ahora: el ritmo del vídeo (VideoPipeline.movieCap: 25 con la sesión a 30), sin rejilla y relleno a tres periodos.
        val cap = VideoPipeline.movieCap(25f, 30)
        assertEquals(25, cap)
        val gaps = realFrameTimes(FramePacer(cap), false, 120, 10, movie).zipWithNext { a, b -> b - a }
        assertTrue("intervalos $gaps", gaps.all { it in 39..41 })
        assertTrue(gaps.size >= 245)
    }

    @Test
    fun theMovieCapIsAMultipleOfTheVideoRate() {
        assertEquals(25, VideoPipeline.movieCap(25f, 30))
        assertEquals(50, VideoPipeline.movieCap(25f, 60))
        assertEquals(24, VideoPipeline.movieCap(23.976f, 30))
        assertEquals(48, VideoPipeline.movieCap(23.976f, 60))
        assertEquals(30, VideoPipeline.movieCap(29.97f, 30))
        assertEquals(50, VideoPipeline.movieCap(50f, 60))
        // Más rápido que el tope, o sin saber sus fps: el tope.
        assertEquals(30, VideoPipeline.movieCap(50f, 30))
        assertEquals(30, VideoPipeline.movieCap(0f, 30))
        assertEquals(30, VideoPipeline.movieCap(Float.NaN, 30))
        // Con tope térmico de 20, un vídeo de 25 va a 20.
        assertEquals(20, VideoPipeline.movieCap(25f, 20))
    }

    @Test
    fun discardedCountsFramesThatArrivedAndWereNotSent() {
        // 25 frames de la interfaz, 30 dibujos de los que 5 fueron sin cambios: ninguno perdido.
        assertEquals(0, GlFrameRelay.discarded(25, 30, 5))
        assertEquals(4, GlFrameRelay.discarded(25, 24, 3))
        assertEquals(0, GlFrameRelay.discarded(0, 10, 10))
    }
}
