package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * «Batería caliente para el cargador», lo puro: qué parada cuenta como rápida, cuántos minutos faltan para llegar a ella y
 * qué toca hacer (decide) en cada caso. Sin Android ni red.
 */
class BatteryHeatTest {
    private val min = 60_000L
    private val now = 1_700_000_000_000L

    /** Ruta de 100 km, un punto cada 10 km y 6 min por tramo (100 km/h). */
    private val km = DoubleArray(11) { it * 10.0 }
    private val segS = DoubleArray(11) { if (it == 0) 0.0 else 360.0 }

    private fun charger(kw: Double, acOnly: Boolean = false, id: String = "dgt:$kw") = RoutePlanner.Charger().apply {
        this.id = id
        name = "Cargador $kw"
        maxKw = kw
        this.acOnly = acOnly
    }

    private fun stop(c: RoutePlanner.Charger, atKm: Double, arrive: Double = 30.0, minutes: Double = 20.0) =
        ChargePlanner.Stop(c, atKm, arrive, 70.0, minutes, 20.0)

    private fun plan(fromKm: Double, vararg stops: ChargePlanner.Stop) = ChargePlanner.Result(
        ChargePlanner.Outcome.PLANNED, stops.toList(), 20.0, km, DoubleArray(11) { it * 1.8 }, fromKm, 60.0, 69.9)

    /** Todo a favor: con esto se manda. */
    private fun good() = BatteryHeat.Input().apply {
        enabled = true
        account = true
        pin = true
        trip = true
        stopKey = "dgt:150"
        stopName = "Zunder"
        stopKw = 150.0
        minutes = 20.0
        arrivePct = 30.0
        outsideC = 6.0
        thermal = 0
        batteryC = 8.0
    }

    private fun why(i: BatteryHeat.Input, st: BatteryHeat.State = BatteryHeat.State(), t: Long = now) =
        BatteryHeat.decide(i, st, t).why

    private fun heating(eta: Long = now + 20 * min, sentAt: Long = now - 5 * min) = BatteryHeat.State().apply {
        key = "dgt:150"
        on = true
        etaMs = eta
        etaNowMs = eta
        lastSendMs = sentAt
    }

    // ------------------------------------------------------------------ paradas y minutos

    @Test
    fun onlyDcChargersOfFortyKwOrMoreAreFast() {
        assertTrue(BatteryHeat.fast(charger(150.0)))
        assertTrue(BatteryHeat.fast(charger(ChargePlanner.FAST_KW)))
        assertFalse(BatteryHeat.fast(charger(22.0)))
        // Solo enchufes de alterna, aunque digan mucha potencia; sin potencia conocida, tampoco.
        assertFalse(BatteryHeat.fast(charger(50.0, acOnly = true)))
        assertFalse(BatteryHeat.fast(charger(0.0)))
        assertFalse(BatteryHeat.fast(null))
    }

    @Test
    fun theNextFastStopSkipsSlowAndPassedOnes() {
        val slow = stop(charger(22.0), 20.0)
        val fast = stop(charger(150.0), 60.0)
        assertEquals(1, BatteryHeat.nextFast(plan(0.0, slow, fast)))
        // Ya pasada (más de 2 km por detrás): no cuenta.
        val passed = stop(charger(100.0, id = "a"), 10.0)
        assertEquals(1, BatteryHeat.nextFast(plan(15.0, passed, fast)))
        // La de «aquí» (llegando o cargando en ella) sigue contando: al llegar no se apaga.
        assertEquals(0, BatteryHeat.nextFast(plan(60.5, fast)))
        assertEquals(-1, BatteryHeat.nextFast(plan(0.0, slow)))
        assertEquals(-1, BatteryHeat.nextFast(null))
    }

    @Test
    fun minutesFromTheTimeOfEachStretch() {
        // De 5 a 15 km: medio tramo y medio tramo, 6 min.
        assertEquals(360.0, BatteryHeat.driveSeconds(km, segS, 5.0, 15.0), 1e-6)
        assertEquals(0.0, BatteryHeat.driveSeconds(km, segS, 30.0, 30.0), 1e-6)
        assertTrue(BatteryHeat.driveSeconds(km, null, 0.0, 10.0).isNaN())
        assertTrue(BatteryHeat.driveSeconds(km, DoubleArray(3), 0.0, 10.0).isNaN())
        val fast = stop(charger(150.0), 40.0)
        assertEquals(24.0, BatteryHeat.minutesTo(km, segS, plan(0.0, fast), 0), 1e-6)
        assertEquals(12.0, BatteryHeat.minutesTo(km, segS, plan(20.0, fast), 0), 1e-6)
        // Con una parada lenta antes: lo que se carga en ella y lo que se tarda en parar.
        val slow = stop(charger(22.0), 20.0, minutes = 10.0)
        assertEquals(24.0 + 10 + ChargePlanner.STOP_OVERHEAD_MIN, BatteryHeat.minutesTo(km, segS, plan(0.0, slow, fast), 1), 1e-6)
        // Llegando: 0.
        assertEquals(0.0, BatteryHeat.minutesTo(km, segS, plan(40.5, fast), 0), 1e-6)
    }

    @Test
    fun theForecastAtTheStop() {
        val temp = DoubleArray(11) { it.toDouble() }
        assertEquals(4.0, BatteryHeat.valueAt(km, temp, 41.0), 1e-9)
        assertEquals(10.0, BatteryHeat.valueAt(km, temp, 200.0), 1e-9)
        assertTrue(BatteryHeat.valueAt(km, null, 4.0).isNaN())
    }

    // ------------------------------------------------------------------ cuándo se manda

    @Test
    fun withEverythingRightItIsSent() {
        assertEquals(BatteryHeat.Action.ON, BatteryHeat.decide(good(), BatteryHeat.State(), now).action)
        assertEquals(BatteryHeat.W_SEND, why(good()))
    }

    @Test
    fun eachMissingThingSaysWhy() {
        assertEquals(BatteryHeat.W_DISABLED, why(good().apply { enabled = false }))
        assertEquals(BatteryHeat.W_NO_ACCOUNT, why(good().apply { account = false }))
        assertEquals(BatteryHeat.W_NO_PIN, why(good().apply { pin = false }))
        assertEquals(BatteryHeat.W_UNSUPPORTED, why(good().apply { unsupported = true }))
        assertEquals(BatteryHeat.W_NO_TRIP, why(good().apply { trip = false }))
        assertEquals(BatteryHeat.W_NO_FAST_STOP, why(good().apply { stopKey = null }))
        assertEquals(BatteryHeat.W_NO_ETA, why(good().apply { minutes = Double.NaN }))
        assertEquals(BatteryHeat.W_NO_TEMP, why(good().apply { outsideC = Double.NaN }))
        assertEquals(BatteryHeat.W_CHARGING, why(good().apply { dcCharging = true }))
        for (w in listOf(good().apply { enabled = false }, good().apply { pin = false }, good().apply { outsideC = 20.0 })) {
            assertEquals(BatteryHeat.Action.NONE, BatteryHeat.decide(w, BatteryHeat.State(), now).action)
        }
    }

    @Test
    fun aboutTwentyFiveMinutesBeforeArriving() {
        assertEquals(BatteryHeat.W_SEND, why(good().apply { minutes = BatteryHeat.LEAD_MIN }))
        assertEquals(BatteryHeat.W_EARLY, why(good().apply { minutes = BatteryHeat.LEAD_MIN + 0.5 }))
        assertEquals(BatteryHeat.W_SEND, why(good().apply { minutes = BatteryHeat.MIN_LEAD_MIN }))
        assertEquals(BatteryHeat.W_TOO_CLOSE, why(good().apply { minutes = BatteryHeat.MIN_LEAD_MIN - 0.1 }))
    }

    @Test
    fun onlyWhenItIsColdOutside() {
        assertEquals(BatteryHeat.W_SEND, why(good().apply { outsideC = 14.9 }))
        assertEquals(BatteryHeat.W_WARM_OUTSIDE, why(good().apply { outsideC = BatteryHeat.COLD_C }))
        assertEquals(BatteryHeat.W_SEND, why(good().apply { outsideC = -8.0 }))
    }

    @Test
    fun notWithTheBatteryAlreadyWarmOrHeating() {
        // 1186: leapmotor-ha dice 4 = encendido; la pestaña Coche usaba 1. Cualquiera distinto de 0, calentando.
        assertEquals(BatteryHeat.W_ALREADY_HEATING, why(good().apply { thermal = 4 }))
        assertEquals(BatteryHeat.W_ALREADY_HEATING, why(good().apply { thermal = 1 }))
        assertEquals(BatteryHeat.W_SEND, why(good().apply { thermal = null }))
        assertEquals(BatteryHeat.W_BATTERY_WARM, why(good().apply { batteryC = BatteryHeat.WARM_BATTERY_C }))
        assertEquals(BatteryHeat.W_SEND, why(good().apply { batteryC = Double.NaN }))
    }

    @Test
    fun notIfItWouldArriveTooLow() {
        assertEquals(BatteryHeat.W_LOW_ARRIVAL, why(good().apply { arrivePct = 9.0 }))
        assertEquals(BatteryHeat.W_SEND, why(good().apply { arrivePct = BatteryHeat.MIN_ARRIVE_PCT }))
    }

    @Test
    fun onceForEachStopAndAtMostOncePerMinute() {
        val done = BatteryHeat.State().apply {
            key = "dgt:150"
            done = true
        }
        assertEquals(BatteryHeat.W_DONE, why(good(), done))
        // Otra parada: se puede.
        assertEquals(BatteryHeat.W_SEND, why(good().apply { stopKey = "dgt:300" }, done))
        val recent = BatteryHeat.State().apply { lastSendMs = now - 30_000 }
        assertEquals(BatteryHeat.W_GAP, why(good().apply { stopKey = "dgt:300" }, recent))
        assertEquals(BatteryHeat.W_SEND, why(good().apply { stopKey = "dgt:300" }, recent, now + 30_000))
    }

    // ------------------------------------------------------------------ cuándo se apaga

    @Test
    fun whileHeatingNothingChangesNothingIsSent() {
        assertEquals(BatteryHeat.W_HEATING, why(good(), heating()))
        assertEquals(BatteryHeat.Action.NONE, BatteryHeat.decide(good(), heating(), now).action)
        // Aunque fuera ya no haga frío o la batería ya esté caliente: ya está pedido.
        assertEquals(BatteryHeat.W_HEATING, why(good().apply { outsideC = 18.0; thermal = 4; batteryC = 30.0 }, heating()))
    }

    @Test
    fun aBigDelayOrPassingTheTimeTurnsItOff() {
        val st = heating(eta = now + 20 * min)
        // 19 min más tarde de lo previsto: sigue; 21, se apaga.
        assertEquals(BatteryHeat.W_HEATING, why(good().apply { minutes = 39.0 }, st))
        assertEquals(BatteryHeat.W_DELAY, why(good().apply { minutes = 41.0 }, st))
        assertEquals(BatteryHeat.Action.OFF, BatteryHeat.decide(good().apply { minutes = 41.0 }, st, now).action)
        // La hora prevista + 20 min sin llegar, aunque ya no se sepa cuánto falta.
        assertEquals(BatteryHeat.W_LATE, why(good().apply { minutes = Double.NaN }, st, now + 41 * min))
        assertEquals(BatteryHeat.W_HEATING, why(good().apply { minutes = Double.NaN }, st, now + 39 * min))
    }

    @Test
    fun theStopLeavingThePlanTurnsItOffAfterAWhile() {
        val st = heating()
        assertEquals(BatteryHeat.W_GONE_WAIT, why(good().apply { stopKey = null }, st))
        assertEquals(BatteryHeat.W_GONE_WAIT, why(good().apply { stopKey = "dgt:300" }, st))
        st.goneSinceMs = now - BatteryHeat.GONE_GRACE_MS + 1000
        assertEquals(BatteryHeat.W_GONE_WAIT, why(good().apply { stopKey = null }, st))
        st.goneSinceMs = now - BatteryHeat.GONE_GRACE_MS
        assertEquals(BatteryHeat.W_GONE, why(good().apply { stopKey = null }, st))
    }

    @Test
    fun theTripEndingTurnsItOffUnlessArrivingAtTheStop() {
        assertEquals(BatteryHeat.W_NO_TRIP, why(good().apply { trip = false }, heating()))
        val arriving = heating().apply { etaNowMs = now + 3 * min }
        assertEquals(BatteryHeat.W_ARRIVED, why(good().apply { trip = false }, arriving))
        assertEquals(BatteryHeat.Action.NONE, BatteryHeat.decide(good().apply { trip = false }, arriving, now).action)
    }

    @Test
    fun pluggedToFastChargingNothingMoreToDo() {
        assertEquals(BatteryHeat.W_PLUGGED, why(good().apply { dcCharging = true; trip = false }, heating()))
        assertEquals(BatteryHeat.Action.NONE, BatteryHeat.decide(good().apply { dcCharging = true }, heating(), now).action)
    }

    @Test
    fun turningTheSettingOffOrCancellingTurnsItOff() {
        assertEquals(BatteryHeat.W_DISABLED, why(good().apply { enabled = false }, heating()))
        assertEquals(BatteryHeat.W_USER, why(good(), heating().apply { cancelled = true }))
    }

    @Test
    fun theOffAlsoWaitsAMinute() {
        val st = heating(sentAt = now - 30_000)
        assertEquals(BatteryHeat.W_GAP, why(good().apply { trip = false }, st))
        assertEquals(BatteryHeat.W_NO_TRIP, why(good().apply { trip = false }, st, now + 30_000))
    }

    // ------------------------------------------------------------------ respuestas de la nube

    @Test
    fun noSuchPermissionMeansTheCarHasNoSuchFunction() {
        assertTrue(BatteryHeat.unsupported(LeapApi.ApiException(200, 40, "No such permission")))
        assertTrue(BatteryHeat.unsupported(LeapApi.ApiException(200, 7, "no such permission")))
        assertTrue(BatteryHeat.unsupported(IOException("envuelta", LeapApi.ApiException(200, 40, ""))))
        assertFalse(BatteryHeat.unsupported(LeapApi.ApiException(200, 500, "vehicle offline")))
        assertFalse(BatteryHeat.unsupported(UnknownHostException("appgateway")))
    }

    @Test
    fun onlyAReadTimeoutMayHaveReachedTheCar() {
        assertTrue(BatteryHeat.maybeSent(SocketTimeoutException("Read timed out")))
        assertFalse(BatteryHeat.maybeSent(SocketTimeoutException("failed to connect to appgateway")))
        assertFalse(BatteryHeat.maybeSent(UnknownHostException("appgateway")))
        assertFalse(BatteryHeat.maybeSent(LeapApi.ApiException(200, 500, "vehicle offline")))
    }
}
