package com.headqlink.link

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Carga en directo: «ya puedes seguir» una vez, carga lenta (sin confundir la mitad de los 400 V), fin, hora de lista e historial. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class ChargeSessionTest {
    private val min = 60_000L

    private fun charger(net: String, kw: Double, volts: Double) = RoutePlanner.Charger().apply {
        name = "Zunder Épila"
        network = net
        maxKw = kw
        maxVolts = volts
        lat = 41.54
        lon = -1.24
    }

    /** C10 ProMax de 81,9 kWh (800 V, 180 kW de pico) en un cargador. */
    private fun session(c: RoutePlanner.Charger?, soc0: Double = 30.0) = ChargeSession(0, soc0, true, c, 81.9, 180.0, 800.0)

    /**
     * 18-10: el coche al 100 % y enchufado en casa, con una ruta puesta, decía «ya puedes seguir» por voz cada 5 s. Sin
     * aviso si al enchufar ya tenía lo que pide el plan, y sin empezar una carga con un dato de la nube antiguo.
     */
    @Test
    fun noReadyAlertIfItWasAlreadyEnoughWhenPluggedIn() {
        val s = session(null, soc0 = 100.0)
        s.setTarget(27.8, 22.0, "Decathlon")
        assertTrue(s.observe(0, 100.0, 0.0, true, false, 0.0, 0).isEmpty())
        assertTrue(s.readyFired) // en la pantalla sí: «ya puedes seguir»
        assertTrue(RoutePlanner.freshForCharge(40 * min, 20 * min))
        assertFalse(RoutePlanner.freshForCharge(120 * min, 20 * min))
        assertFalse(RoutePlanner.freshForCharge(120 * min, 0))
    }

    @Test
    fun readyFiresOnceWhenTheTargetIsReached() {
        val s = session(charger("zunder", 250.0, 920.0))
        s.setTarget(60.0, 12.0, "Zunder Lleida")
        assertEquals(62.0, s.targetPct, 1e-9) // con el margen
        assertTrue(s.observe(0, 30.0, 125.0, true, false, 40.0, 0).isEmpty())
        assertTrue(s.observe(10 * min, 50.0, 128.0, true, false, 30.0, 10 * min).isEmpty())
        assertEquals(listOf(ChargeSession.Event.READY), s.observe(18 * min, 62.0, 100.0, true, false, 20.0, 18 * min))
        assertTrue(s.observe(19 * min, 63.0, 98.0, true, false, 19.0, 19 * min).isEmpty())
        assertTrue(s.readyFired)
    }

    @Test
    fun theReadyTimeFollowsTheRealPaceAlongTheCarCurve() {
        val s = session(charger("zunder", 250.0, 920.0))
        s.setTarget(60.0, 12.0, "Lleida")
        s.observe(0, 30.0, 100.0, true, false, Double.NaN, 0)
        s.observe(5 * min, 40.0, 100.0, true, false, Double.NaN, 5 * min)
        // 2 %/min ≈ 98 kW de verdad frente a los ~152 de la curva al 40 %: tarda 152/98 veces lo que diría el modelo.
        val rate = s.rateKw()
        assertEquals(2 * 81.9 / 100 * 60, rate, 0.01)
        val model = ChargePlanner.minutes(40.0, 62.0, 250.0, 180.0, 81.9)
        assertEquals(model * ChargePlanner.carPowerKw(40.0, 180.0) / rate, s.minutesTo(62.0), 0.01)
        assertEquals(5 * min + s.minutesTo(62.0) * 60_000, s.readyAtMs(), 1.0)
        // Sin objetivo (sin ruta): lo que dice el coche.
        val home = session(null)
        home.observe(0, 30.0, 7.0, true, false, 300.0, 0)
        assertEquals(300.0 * 60_000, home.readyAtMs(), 1.0)
    }

    @Test
    fun slowChargingIsDetectedButNotTheExpectedHalfOfA500VoltCharger() {
        // Supercharger (470 V) con el coche de 800 V: se esperan ~90 kW (la mitad); 60 kW no es lento.
        val sc = session(charger("tesla", 250.0, 470.0), 20.0)
        assertTrue(sc.lowVolts())
        assertEquals(90.0, sc.expectedKw(25.0), 1e-9)
        for (m in 0..6) assertFalse(sc.observe(m * min, 20.0 + m, 60.0, true, false, Double.NaN, m * min).contains(ChargeSession.Event.SLOW))
        // En uno de 920 V se esperan 180: 45 kW dos veces seguidas (pasados 3 min) sí es lento, y avisa una vez.
        val hpc = session(charger("zunder", 250.0, 920.0), 20.0)
        val events = mutableListOf<ChargeSession.Event>()
        for (m in 0..8) events += hpc.observe(m * min, 20.0 + m * 0.5, 45.0, true, false, Double.NaN, m * min)
        assertEquals(1, events.count { it == ChargeSession.Event.SLOW })
        assertEquals(45.0, hpc.slowKw, 1e-9)
        assertEquals(180.0, hpc.slowExpectedKw, 1e-9)
    }

    @Test
    fun itEndsWhenUnpluggedOrWithoutData() {
        val s = session(null)
        s.observe(0, 30.0, 50.0, true, false, Double.NaN, 0)
        s.observe(10 * min, 45.0, 50.0, true, false, Double.NaN, 10 * min)
        assertEquals(listOf(ChargeSession.Event.ENDED), s.observe(11 * min, 45.0, 0.0, false, false, Double.NaN, 11 * min))
        assertEquals(45.0, s.endSoc, 1e-9)
        assertEquals(10 * min, s.endMs)
        assertTrue(s.observe(12 * min, 46.0, 0.0, false, false, Double.NaN, 12 * min).isEmpty())
        // Sin datos nuevos en 30 min.
        val q = session(null)
        q.observe(0, 30.0, 50.0, true, false, Double.NaN, 0)
        assertTrue(q.observe(0, 30.0, 50.0, true, false, Double.NaN, 29 * min).isEmpty())
        assertEquals(listOf(ChargeSession.Event.ENDED), q.observe(0, 30.0, 50.0, true, false, Double.NaN, 31 * min))
    }

    @Test
    fun theSessionSurvivesTheHistory() {
        val s = session(charger("zunder", 250.0, 920.0))
        s.setTarget(60.0, 12.0, "Lleida")
        s.observe(0, 30.0, 120.0, true, false, Double.NaN, 0)
        s.observe(5 * min, 40.0, 121.0, true, false, Double.NaN, 5 * min)
        s.observe(6 * min, 41.0, Double.NaN, true, false, Double.NaN, 6 * min)
        s.observe(7 * min, 41.0, 0.0, false, false, Double.NaN, 7 * min)
        val back = ChargeSession.fromJson(JSONObject(s.toJson().toString()))
        assertEquals(3, back.samples.size)
        assertTrue(back.samples[2].kw.isNaN())
        assertEquals("Zunder Épila", back.charger!!.name)
        assertEquals(920.0, back.charger!!.maxVolts, 1e-9)
        assertEquals(62.0, back.targetPct, 1e-9)
        assertEquals(41.0, back.endSoc, 1e-9)
        // El historial guarda las últimas MAX y aguanta un fichero roto.
        var json = ""
        repeat(205) { json = ChargeLog.append(json, s, 200) }
        assertEquals(200, ChargeLog.read(json).size)
        assertTrue(ChargeLog.read("roto").isEmpty())
    }

    /** Carga lenta en casa (CA, 7 kW, 81,9 kWh): lecturas cada 15 min, como la comprobación de fondo. */
    private fun home(soc0: Double = 40.0) = ChargeSession(0, soc0, false, null, 81.9, 180.0, 800.0)

    @Test
    fun aSlowHomeChargeLastsHoursAndItsPaceIsPercentPerHour() {
        val s = home()
        // 7 kW en 81,9 kWh: ~8,5 % a la hora; 8 h de carga, sin cortarse a las 3 h como una rápida.
        var soc = 40.0
        var t = 0L
        while (t <= 6 * 60 * min) {
            assertTrue("a los ${t / min} min", s.observe(t, soc, 7.0, true, false, Double.NaN, t).isEmpty())
            t += 15 * min
            soc += 8.5 / 4
        }
        assertFalse(s.ended)
        assertEquals(8.5, s.pctPerHour(), 0.3)
        // Sin objetivo ni lo que dice el coche: llena al ritmo de ahora.
        val l = s.last()!!
        val hours = (s.readyAtMs() - l.atMs) / 3_600_000.0
        assertEquals((100 - l.soc) / 8.5, hours, 0.2)
        // Sin datos en 3 h: terminada; con datos, a las 16 h.
        val q = home()
        q.observe(0, 40.0, 7.0, true, false, Double.NaN, 0)
        assertTrue(q.observe(0, 40.0, 7.0, true, false, Double.NaN, 2 * 60 * min).isEmpty())
        assertEquals(listOf(ChargeSession.Event.ENDED), q.observe(0, 40.0, 7.0, true, false, Double.NaN, 3 * 60 * min + min))
        // Una lenta nunca avisa de «carga lenta» (lo es por ser de alterna).
        assertFalse(s.slowFired)
    }

    @Test
    fun seenLateSurvivesTheJson() {
        val s = home()
        s.seenLate = true
        s.observe(0, 40.0, 7.0, true, false, Double.NaN, 0)
        assertTrue(ChargeSession.fromJson(s.toJson()).seenLate)
        assertFalse(ChargeSession.fromJson(home().toJson()).seenLate)
    }

    @Test
    fun theSlowChartAxis() {
        val h = 3_600_000L
        // Al ritmo de ahora; sin él, lo que dice el coche; sin nada, una hora; como mucho 16 h.
        assertEquals(4 * h, ChargeCurve.SlowAxis.endMs(0, 60.0, 100.0, 10.0, Double.NaN))
        assertEquals(90 * min, ChargeCurve.SlowAxis.endMs(0, 60.0, 100.0, Double.NaN, 90.0))
        assertEquals(h, ChargeCurve.SlowAxis.endMs(0, 60.0, 100.0, Double.NaN, Double.NaN))
        assertEquals(16 * h, ChargeCurve.SlowAxis.endMs(0, 10.0, 100.0, 1.0, Double.NaN))
        assertEquals(0L, ChargeCurve.SlowAxis.endMs(0, 100.0, 100.0, 10.0, Double.NaN))
        assertEquals(h, ChargeCurve.SlowAxis.stepMs(5 * h))
        assertEquals(2 * h, ChargeCurve.SlowAxis.stepMs(9 * h))
        assertEquals(4 * h, ChargeCurve.SlowAxis.stepMs(15 * h))
        // La primera marca, en punto.
        val t0 = 1_760_000_000_000L + 17 * min
        val first = ChargeCurve.SlowAxis.firstTick(t0, h)
        assertTrue(first >= t0 && first - t0 < h)
        assertEquals(0L, (first + java.util.TimeZone.getDefault().getOffset(first)) % h)
    }

    @Test
    fun aChargeStartsOnlyWithFreshDataAndCharging() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        fun snap(charging: Boolean, ageMin: Long): CarCloud.Snapshot {
            val at = java.time.Instant.ofEpochMilli(System.currentTimeMillis() - ageMin * min).toString()
            val d = JSONObject().put("soc", 55).put("chargeState", if (charging) 1 else 0).put("acInputSlowCharge", 1)
                .put("collectTime", at)
            return CarCloud.Snapshot(CarCloud.State.OK, LeapStatus.parse(d), System.currentTimeMillis(), 0, 81.9, "", 0, false)
        }
        assertTrue(ChargeWatch.mayStart(ctx, snap(true, 2)))
        assertFalse(ChargeWatch.mayStart(ctx, snap(false, 2)))
        // El dato de un coche dormido (de hace más de 30 min) no empieza nada.
        assertFalse(ChargeWatch.mayStart(ctx, snap(true, 45)))
        val s = ChargeWatch.newSession(snap(true, 2), null)
        assertFalse(s.dc)
        assertEquals(55.0, s.startSoc, 1e-9)
    }

    @Test
    fun theBarGoesToThePlanOrTo80FastAnd100Slow() {
        // Con plan: su %.
        val a = session(null)
        a.targetPct = 64.0
        assertEquals(64.0, ChargeWatch.barTarget(a), 1e-9)
        // Sin plan: rápida al 80 % y, pasado el 80, al 100; lenta al 100.
        val b = session(null)
        assertEquals(80.0, ChargeWatch.barTarget(b), 1e-9)
        b.observe(10 * min, 85.0, 60.0, true, false, Double.NaN, 10 * min)
        assertEquals(100.0, ChargeWatch.barTarget(b), 1e-9)
        assertEquals(100.0, ChargeWatch.barTarget(home()), 1e-9)
        // Lecturas de la nube: la rápida cada 90 s; la lenta cada 5 min.
        assertEquals(90_000L, ChargeWatch.watchMs(b))
        assertEquals(300_000L, ChargeWatch.watchMs(home()))
        assertEquals(0L, ChargeWatch.watchMs(null))
        // Sin plan, la notificación dice a qué hora llega hasta donde va la barra.
        Str.init(org.robolectric.RuntimeEnvironment.getApplication())
        val c = session(null, 50.0)
        c.observe(System.currentTimeMillis(), 50.0, 120.0, true, false, Double.NaN, System.currentTimeMillis())
        assertTrue(ChargeWatch.`when`(c), ChargeWatch.`when`(c).startsWith("80 % ≈ "))
    }

    @Test
    fun theTimeToEachPercent() {
        // Lenta: al 100 % lo que dice el coche; a otro %, al ritmo de ahora.
        val s = home()
        s.observe(0, 40.0, 7.0, true, false, 300.0, 0)
        s.observe(60 * min, 48.5, 7.0, true, false, 240.0, 60 * min)
        assertEquals(240.0, s.minutesUntil(100.0), 1e-9)
        assertEquals(60.0, s.minutesUntil(57.0), 1.0)
        assertEquals(0.0, s.minutesUntil(48.0), 1e-9)
        assertEquals(60 * min + 240 * min.toDouble(), s.atMs(100.0), 1.0)
        // Rápida: por la curva, más rato del 80 al 100 que del 60 al 80.
        val f = session(null, 60.0)
        f.observe(0, 60.0, 150.0, true, false, Double.NaN, 0)
        val to80 = f.minutesUntil(80.0)
        val to100 = f.minutesUntil(100.0) - to80
        assertTrue("$to80 / $to100", to80 > 0 && to100 > to80)
    }
}
