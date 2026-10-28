package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min

/**
 * Cronómetro 0-50 / 0-100 (28-10): con el GPS a una posición por segundo, interpolando los cruces y llevando la salida a
 * 0 km/h. Se compara con el de antes (la primera posición que pasaba de 3 y la primera que pasaba de 100).
 */
class LaunchTimerTest {
    /** Un arranque de eléctrico: 4,5 m/s² hasta que manda la potencia (≈ 160 kW para 1900 kg), en pasos de 1 ms. */
    private fun speedAt(tMs: Double): Double {
        var v = 0.0
        var t = 0.0
        while (t < tMs) {
            val a = if (v < 0.1) 4.5 else min(4.5, 160_000.0 / (1900.0 * v))
            v += a * 0.001
            t += 1.0
        }
        return v * 3.6
    }

    /** Instante real (ms) en que se llega a kmh. */
    private fun realMs(kmh: Double): Double {
        var t = 0.0
        while (speedAt(t) < kmh) t += 5.0
        return t
    }

    /** Lo que mide cada cronómetro con el GPS cada periodMs, empezando en phaseMs y con la velocidad lagMs retrasada. */
    private fun run(periodMs: Double, phaseMs: Double, lagMs: Double): Triple<Double, Double, Double> {
        val lt = LaunchTimer()
        val t0 = 10_000.0 // el coche sale a los 10 s; antes, parado
        var t = phaseMs
        var oldStart = -1.0
        var old100 = -1.0
        var armedOld = false
        var new50 = -1.0
        var new100 = -1.0
        while (t < t0 + 20_000) {
            val kmh = if (t - lagMs < t0) 0.0 else speedAt(t - lagMs - t0)
            // El de antes.
            if (kmh < 2) armedOld = true
            if (armedOld && oldStart < 0 && kmh > 3) oldStart = t
            if (oldStart >= 0 && old100 < 0 && kmh >= 100) old100 = (t - oldStart) / 1000
            // El de ahora.
            lt.fix(t, kmh)?.let { s ->
                val nums = Regex("""(\d+)[.,](\d)""").findAll(s).map { "${it.groupValues[1]}.${it.groupValues[2]}".toDouble() }.toList()
                if (nums.isNotEmpty()) new50 = nums[0]
                if (nums.size > 1) new100 = nums[1]
            }
            t += periodMs
        }
        return Triple(old100, new100, new50)
    }

    @Test
    fun interpolatedTimesAreMuchCloserToTheRealOnes() {
        val real100 = realMs(100.0) / 1000
        val real50 = realMs(50.0) / 1000
        var oldErr = 0.0
        var newErr = 0.0
        var worstNew = 0.0
        var worst50 = 0.0
        val phases = (0 until 20).map { it * 50.0 }
        for (phase in phases) {
            val (old100, new100, new50) = run(1000.0, phase, 300.0)
            oldErr += abs(old100 - real100)
            newErr += abs(new100 - real100)
            worstNew = maxOf(worstNew, abs(new100 - real100))
            worst50 = maxOf(worst50, abs(new50 - real50))
        }
        println("0-100 real %.2f s · error medio antes %.2f s · ahora %.2f s (peor %.2f s) · 0-50 real %.2f s (peor %.2f s)"
            .format(real100, oldErr / phases.size, newErr / phases.size, worstNew, real50, worst50))
        // Con una décima de redondeo en lo que se enseña, ahora queda a dos décimas como mucho.
        assertTrue(newErr / phases.size < 0.15)
        assertTrue(worstNew < 0.25)
        assertTrue(worst50 < 0.25)
        assertTrue(newErr < oldErr)
    }

    @Test
    fun crossingsAndStart() {
        // De 40 a 60 km/h en un segundo: los 50, a medio camino.
        assertEquals(1500.0, LaunchTimer.cross(1000.0, 40.0, 2000.0, 60.0, 50.0), 1e-9)
        // A 7,2 km/h (2 m/s) y un segundo después a 21,6 (6 m/s): 4 m/s², así que salió medio segundo antes de la primera.
        assertEquals(500.0, LaunchTimer.zeroTime(1000.0, 7.2, 2000.0, 21.6), 1e-6)
        // Una aceleración imposible (un salto del GPS) o casi nula: no se sabe.
        assertTrue(LaunchTimer.zeroTime(1000.0, 5.0, 1100.0, 95.0).isNaN())
        assertTrue(LaunchTimer.zeroTime(1000.0, 5.0, 2000.0, 5.5).isNaN())
        // Sin la segunda: desde la parada, nunca antes de ella.
        assertEquals(1000.0, LaunchTimer.fromStand(1000.0, 0.0, 2000.0, 7.2), 1e-6)
    }

    @Test
    fun onlyFromStandstillAndWithin30Seconds() {
        val lt = LaunchTimer()
        // Ya en marcha sin haberse parado: no cuenta.
        assertNull(lt.fix(0.0, 30.0))
        assertNull(lt.fix(1000.0, 60.0))
        assertNull(lt.fix(2000.0, 101.0))
        // Parado, y luego despacio: pasados 30 s, se olvida.
        lt.fix(3000.0, 0.0)
        lt.fix(4000.0, 10.0)
        assertNull(lt.fix(40_000.0, 101.0))
        // Otra vez desde parado, deprisa: sale.
        lt.fix(41_000.0, 0.0)
        lt.fix(42_000.0, 16.2)
        assertTrue(lt.fix(45_000.0, 60.0)!!.startsWith("0-50 km/h"))
        assertTrue(lt.fix(48_000.0, 101.0)!!.contains("0-100 km/h"))
    }
}
