package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * Recálculo al salirse de la ruta, simulado segundo a segundo como en el coche (18-10): el planificador mira la posición
 * cada 2 s (RoutePlanner.loop), sigue el avance por el trazado (nearest), mide lo lejos que va (offRouteKm) y el rumbo
 * del coche frente al de la ruta, y recalcula con OffRouteWatch y el tope entre recálculos (REPLAN_GAP_MS). Rutas
 * sintéticas en línea recta, sin lugares reales.
 */
class OffRouteSimTest {
    private val lat0 = 40.0
    private val lon0 = -3.7
    private val mPerDegLat = 111_320.0
    private val mPerDegLon = 111_320.0 * cos(Math.toRadians(lat0))

    /** Un plan en línea recta desde (x, y) metros con rumbo deg (0 norte, 90 este), puntos cada 200 m, length km. */
    private fun plan(x: Double, y: Double, deg: Double, lengthKm: Double = 40.0): RoutePlanner.Plan {
        val n = (lengthKm * 5).toInt() + 1
        val p = RoutePlanner.Plan()
        p.n = n
        p.lat = DoubleArray(n)
        p.lon = DoubleArray(n)
        p.km = DoubleArray(n)
        val r = Math.toRadians(deg)
        for (i in 0 until n) {
            val d = i * 200.0
            p.lat[i] = lat0 + (y + d * cos(r)) / mPerDegLat
            p.lon[i] = lon0 + (x + d * sin(r)) / mPerDegLon
            p.km[i] = d / 1000
        }
        p.totalKm = p.km[n - 1]
        return p
    }

    /** Ruido del GPS repetible (±amp metros). */
    private fun noise(t: Int, amp: Double) = amp * sin(t * 1.7) * cos(t * 0.37)

    /** Lo que da el GPS en el segundo t: posición (m), rumbo real del coche (grados) y velocidad (km/h); null sin GPS. */
    private class Fix(val x: Double, val y: Double, val heading: Double, val kmh: Double)

    /**
     * Simula seconds segundos y devuelve en cuáles recalcula. Al recalcular, el plan nuevo es el que da replan (la
     * carretera por la que va de verdad).
     */
    private fun run(seconds: Int, replan: (Int, Fix) -> RoutePlanner.Plan, gps: (Int) -> Fix?): List<Int> {
        var p = plan(0.0, 0.0, 90.0, 60.0)
        val watch = RoutePlanner.OffRouteWatch()
        var lastReplan = Long.MIN_VALUE / 2
        val out = mutableListOf<Int>()
        for (t in 0..seconds step 2) {
            val f = gps(t) ?: continue
            val lat = lat0 + f.y / mPerDegLat
            val lon = lon0 + f.x / mPerDegLon
            p.progress = RoutePlanner.nearest(p, lat, lon, p.progress)
            val off = RoutePlanner.offRouteKm(p, lat, lon)
            val i = minOf(maxOf(0, p.progress), p.n - 2)
            val headingOff = if (f.kmh >= 10) RoutePlanner.angleDiff(f.heading, RoutePlanner.bearing(p, i, i + 1)) else Double.NaN
            val now = t * 1000L
            if (watch.observe(off, headingOff, now) && now - lastReplan >= RoutePlanner.REPLAN_GAP_MS) {
                lastReplan = now
                out.add(t)
                p = replan(t, f)
            }
        }
        return out
    }

    private fun note(what: String, s: String) {
        System.getenv("OUT")?.let { java.io.File(it).appendText("$what: $s\n") }
    }

    private val straightOn: (Int, Fix) -> RoutePlanner.Plan = { _, f -> plan(f.x, f.y, f.heading) }

    /** Por la ruta 20 min a 120 km/h con el GPS temblando ±80 m: ni un recálculo. */
    @Test
    fun followingTheRouteWithNoisyGpsNeverReplans() {
        val v = 33.3
        val got = run(1200, straightOn) { t -> Fix(v * t, noise(t, 80.0), 90.0, 120.0) }
        note("por la ruta, GPS ±80 m", "$got")
        assertEquals(emptyList<Int>(), got)
    }

    /** En ciudad entre edificios altos, el GPS rebota ±150 m durante 10 min a 30 km/h: ni un recálculo. */
    @Test
    fun badGpsInTownNeverReplans() {
        val v = 8.3
        val got = run(600, straightOn) { t -> Fix(v * t + noise(t + 7, 60.0), noise(t, 150.0), 90.0, 30.0) }
        note("ciudad, GPS ±150 m", "$got")
        assertEquals(emptyList<Int>(), got)
    }

    /** Un salto del GPS de 4 s a 3 km (rebote en edificios): no recalcula. */
    @Test
    fun aGpsJumpIsIgnored() {
        val v = 27.8
        val got = run(200, straightOn) { t -> Fix(v * t, if (t in 80..83) 3000.0 else noise(t, 30.0), 90.0, 100.0) }
        note("salto del GPS de 4 s a 3 km", "$got")
        assertEquals(emptyList<Int>(), got)
    }

    /** Túnel de 1 min sin GPS y sale por la ruta: no recalcula. */
    @Test
    fun aTunnelIsIgnored() {
        val v = 27.8
        val got = run(240, straightOn) { t -> if (t in 60..120) null else Fix(v * t, noise(t, 30.0), 90.0, 100.0) }
        note("túnel de 1 min", "$got")
        assertEquals(emptyList<Int>(), got)
    }

    /** Salida equivocada a 100 km/h, abriéndose 25° de la ruta a los 60 s: recalcula en menos de 20 s. */
    @Test
    fun wrongMotorwayExit() {
        val v = 27.8
        val turn = 60
        val a = Math.toRadians(25.0)
        val got = run(300, straightOn) { t ->
            if (t <= turn) Fix(v * t, noise(t, 30.0), 90.0, 100.0)
            else {
                val d = v * (t - turn)
                Fix(v * turn + d * cos(a), -d * sin(a) + noise(t, 30.0), 115.0, 100.0)
            }
        }
        note("salida equivocada 25° a 100 km/h (sale en 60 s)", "recalcula en $got → ${got.firstOrNull()?.minus(turn)} s después")
        assertEquals(1, got.size)
        assertTrue(got[0] - turn <= 20)
    }

    /** En ciudad, gira en un cruce que no era (90°) a 50 km/h: recalcula en menos de 15 s. */
    @Test
    fun wrongTurnInTown() {
        val v = 13.9
        val turn = 40
        val got = run(200, straightOn) { t ->
            if (t <= turn) Fix(v * t, noise(t, 30.0), 90.0, 50.0) else Fix(v * turn, -v * (t - turn) + noise(t, 30.0), 180.0, 50.0)
        }
        note("giro equivocado 90° a 50 km/h (gira en 40 s)", "recalcula en $got → ${got.firstOrNull()?.minus(turn)} s después")
        assertEquals(1, got.size)
        assertTrue(got[0] - turn <= 15)
    }

    /** Se equivoca, recalcula y a los 20 s se vuelve a equivocar: el segundo recálculo, en menos de 25 s. */
    @Test
    fun twoWrongTurnsInARow() {
        val v = 13.9
        val first = 40
        var second = -1
        val got = run(300, straightOn) { t ->
            when {
                t <= first -> Fix(v * t, 0.0, 90.0, 50.0)
                second < 0 || t <= second -> Fix(v * first, -v * (t - first), 180.0, 50.0)
                else -> Fix(v * first - v * (t - second), -v * (second - first), 270.0, 50.0)
            }.also { if (second < 0 && t > first && it.y < -400) second = t + 20 }
        }
        note("dos giros equivocados seguidos (50 km/h)", "recalcula en $got (segundo giro en $second s)")
        assertEquals(2, got.size)
        assertTrue(got[1] - second <= 25)
    }

    /**
     * Área de servicio: sale de la autovía a una vía paralela a 150 m, para 5 min y vuelve. Recalcular al entrar es
     * correcto (ya no va por la ruta); al volver, la ruta nueva ya es la autovía: un recálculo como mucho.
     */
    @Test
    fun aServiceAreaReplansAtMostOnce() {
        val v = 27.8
        val got = run(700, { _, f -> plan(f.x, 0.0, 90.0) }) { t ->
            when {
                t < 100 -> Fix(v * t, 0.0, 90.0, 100.0)
                t < 110 -> Fix(v * 100 + 10.0 * (t - 100), -15.0 * (t - 100), 146.0, 60.0)
                t < 410 -> Fix(v * 100 + 100.0, -150.0 + noise(t, 10.0), 146.0, 0.0)
                t < 420 -> Fix(v * 100 + 100.0 + 10.0 * (t - 410), -150.0 + 15.0 * (t - 410), 34.0, 60.0)
                else -> Fix(v * 100 + 200.0 + v * (t - 420), 0.0, 90.0, 100.0)
            }
        }
        note("área de servicio a 150 m, 5 min parado", "$got")
        assertTrue(got.size <= 1)
    }
}
