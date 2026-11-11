package com.headqlink.link

import android.app.Application
import android.content.Context
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config as RoboConfig
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowLog
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * «Batería caliente para el cargador», el motor: un viaje entero con su parada rápida, el atasco, la parada que sale del
 * plan, «Cancelar», el coche sin la función y los fallos de red. El coche y los avisos son de mentira: nada sale del PC.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@RoboConfig(sdk = [35], application = Application::class)
class BatteryHeatEngineTest {
    private val min = 60_000L
    private val t0 = 1_700_000_000_000L
    private val ctx get() = RuntimeEnvironment.getApplication()
    private lateinit var car: FakeCar
    private lateinit var notice: FakeNotice

    /** El coche de mentira: apunta lo que se le manda; puede fallar. */
    private class FakeCar : BatteryHeat.Car {
        val sent = mutableListOf<Boolean>()
        var fails: Exception? = null
        var confirms = true

        override fun send(on: Boolean): Boolean {
            fails?.let { throw it }
            sent.add(on)
            return confirms
        }
    }

    private class FakeNotice : BatteryHeat.Notice {
        val cards = mutableListOf<Pair<String, Boolean>>()
        val voices = mutableListOf<String>()

        override fun card(ctx: Context, title: String, text: String, cancel: Boolean) {
            cards.add(text.replace(' ', ' ') to cancel)
        }

        override fun voice(ctx: Context, text: String) {
            voices.add(text.replace(' ', ' '))
        }
    }

    /** Un momento del viaje: a cuántos minutos está la parada rápida (null: ya no está en el plan). */
    private fun at(minutes: Double?, outside: Double = 4.0, trip: Boolean = true, dc: Boolean = false, thermal: Int? = 0) =
        BatteryHeat.Input().apply {
            enabled = true
            account = true
            pin = true
            this.trip = trip
            stopKey = if (minutes == null) null else "dgt:zunder-1"
            stopName = "Zunder Écija"
            stopKw = 180.0
            this.minutes = minutes ?: Double.NaN
            arrivePct = 24.0
            outsideC = outside
            tempSource = "el tiempo de ahora"
            this.thermal = thermal
            batteryC = 7.0
            dcCharging = dc
        }

    private fun tick(i: BatteryHeat.Input, t: Long) = BatteryHeat.tick(ctx, i, t, car, true)

    private fun state() = BatteryHeat.State.load(ctx)

    private fun log() = ShadowLog.getLogsForTag("HeadQLink").joinToString("\n") { it.msg }

    @Before
    fun setUp() {
        Str.init(ctx)
        BatteryHeat.resetForTest(ctx)
        car = FakeCar()
        notice = FakeNotice()
        BatteryHeat.notice = notice
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        BatteryHeat.resetForTest(ctx)
    }

    @Test
    fun aWholeTripWithItsFastStop() {
        // 40 y 30 min antes: aún no.
        tick(at(40.0), t0)
        tick(at(30.0), t0 + 10 * min)
        assertTrue(car.sent.isEmpty())
        // 24 min antes: se manda, con su aviso y su «Cancelar», y dicho.
        val step = tick(at(24.0), t0 + 16 * min)
        assertEquals(BatteryHeat.Action.ON, step.action)
        assertEquals(listOf(true), car.sent)
        assertEquals(1, notice.cards.size)
        assertEquals("Warming the battery for the charger at Zunder Écija · arriving in 24 min", notice.cards[0].first)
        assertTrue(notice.cards[0].second)
        assertEquals(listOf("Warming the battery for the charger at Zunder Écija. Arriving in 24 minutes."), notice.voices)
        assertTrue(state().on)
        // Por el camino, sin nada nuevo: nada más.
        for (m in 1..20) tick(at(24.0 - m), t0 + (16 + m) * min)
        assertEquals(listOf(true), car.sent)
        // Llega y enchufa a la carga rápida: ya no hace falta nada (ni se apaga).
        tick(at(0.0, dc = true, thermal = 4), t0 + 41 * min)
        assertEquals(listOf(true), car.sent)
        assertFalse(state().on)
        assertTrue(state().done)
        assertTrue(log().contains("señal 1186 = 4 (sigue activa)"))
        // Cargando y luego saliendo de la parada: para esta, nada más.
        tick(at(0.0), t0 + 70 * min)
        assertEquals(listOf(true), car.sent)
        assertEquals(1, notice.cards.size)
    }

    @Test
    fun theLogSaysNothingPersonal() {
        tick(at(24.0), t0)
        val l = log()
        assertTrue(l, l.contains("calentar batería: encendida (confirmada por el coche) para una parada rápida de 180 kW a 24 min"))
        assertTrue(l, l.contains("señal 1186 = 0"))
        assertFalse("el nombre del cargador no va al registro", l.contains("Zunder"))
        assertFalse(l.contains("dgt:zunder-1"))
    }

    @Test
    fun notWhenItIsNotCold() {
        tick(at(24.0, outside = 16.0), t0)
        tick(at(20.0, outside = Double.NaN), t0 + 4 * min)
        assertTrue(car.sent.isEmpty())
        assertTrue(notice.cards.isEmpty())
    }

    @Test
    fun aTrafficJamTurnsItOff() {
        tick(at(24.0), t0)
        // Atascado: la parada se aleja en el tiempo; con 20 min o más de retraso, se apaga.
        tick(at(30.0), t0 + 5 * min)
        assertEquals(listOf(true), car.sent)
        tick(at(40.0), t0 + 6 * min)
        assertEquals(listOf(true, false), car.sent)
        assertFalse(state().on)
        assertEquals("Arrival at Zunder Écija is delayed: battery warming stopped.", notice.cards.last().first)
        assertFalse(notice.cards.last().second)
        // Y para esta parada ya no se vuelve a encender, aunque se vuelva a acercar.
        tick(at(20.0), t0 + 30 * min)
        assertEquals(listOf(true, false), car.sent)
        assertEquals(1, notice.voices.size)
    }

    @Test
    fun passingTheTimeWithoutArrivingTurnsItOff() {
        tick(at(24.0), t0)
        // Junto a la parada pero sin enchufar a la rápida: a la hora prevista + 20 min, se apaga.
        tick(at(0.0), t0 + 30 * min)
        assertEquals(listOf(true), car.sent)
        tick(at(0.0), t0 + 45 * min)
        assertEquals(listOf(true, false), car.sent)
    }

    @Test
    fun theStopLeavingThePlanTurnsItOffAfterTwoMinutes() {
        tick(at(24.0), t0)
        // Un recálculo la quita y la vuelve a poner: no se apaga.
        tick(at(null), t0 + 2 * min)
        tick(at(21.0), t0 + 3 * min)
        tick(at(null), t0 + 4 * min)
        tick(at(null), t0 + 5 * min)
        assertEquals(listOf(true), car.sent)
        assertEquals(t0 + 4 * min, state().goneSinceMs)
        // Fuera del plan dos minutos: se apaga.
        tick(at(null), t0 + 6 * min)
        assertEquals(listOf(true, false), car.sent)
        assertEquals("Zunder Écija is no longer in the plan: battery warming stopped.", notice.cards.last().first)
    }

    @Test
    fun anotherFastStopAfterTheFirstOneWasDropped() {
        tick(at(24.0), t0)
        tick(at(null), t0 + 2 * min)
        tick(at(null), t0 + 4 * min)
        assertEquals(listOf(true, false), car.sent)
        val other = at(18.0).apply { stopKey = "dgt:ionity-2"; stopName = "Ionity Carmona" }
        tick(other, t0 + 5 * min)
        assertEquals(listOf(true, false, true), car.sent)
        assertEquals("dgt:ionity-2", state().key)
    }

    @Test
    fun cancelTurnsItOffAtOnceAndForThisStop() {
        tick(at(24.0), t0)
        // «Cancelar» a los 10 s: sin esperar al minuto entre envíos.
        BatteryHeat.cancel(ctx, car, t0 + 10_000)
        assertEquals(listOf(true, false), car.sent)
        assertFalse(state().on)
        assertTrue(state().done)
        tick(at(20.0), t0 + 4 * min)
        assertEquals(listOf(true, false), car.sent)
        assertTrue(log().contains("«Cancelar»"))
    }

    @Test
    fun aFailedCancelIsRetried() {
        tick(at(24.0), t0)
        car.fails = UnknownHostException("appgateway")
        BatteryHeat.cancel(ctx, car, t0 + 10_000)
        assertTrue(state().on)
        assertTrue(state().cancelled)
        car.fails = null
        // Aunque la parada siga valiendo, la siguiente vuelta (pasado el minuto) vuelve a apagar.
        tick(at(23.0), t0 + 30_000)
        assertEquals(listOf(true), car.sent)
        tick(at(22.0), t0 + 80_000)
        assertEquals(listOf(true, false), car.sent)
        assertFalse(state().on)
    }

    @Test
    fun turningTheSettingOffWhileHeatingTurnsItOff() {
        tick(at(24.0), t0)
        tick(at(20.0).apply { enabled = false }, t0 + 2 * min)
        assertEquals(listOf(true, false), car.sent)
    }

    @Test
    fun atMostOneSendPerMinute() {
        tick(at(24.0), t0)
        // Se acaba el viaje a los 20 s: el apagado espera al minuto.
        tick(at(20.0, trip = false), t0 + 20_000)
        assertEquals(listOf(true), car.sent)
        tick(at(20.0, trip = false), t0 + 61_000)
        assertEquals(listOf(true, false), car.sent)
    }

    @Test
    fun aCarWithoutTheFunctionTurnsItOffForThatCar() {
        car.fails = LeapApi.ApiException(200, 40, "No such permission")
        tick(at(24.0), t0)
        assertTrue(car.sent.isEmpty())
        assertEquals("?", state().unsupportedCar)
        assertEquals(listOf("Your car does not allow warming the battery remotely: the feature stays off for it." to false),
            notice.cards)
        assertTrue(notice.voices.isEmpty())
        // Lo que dice input() para ese coche: no se vuelve a intentar.
        assertTrue(BatteryHeat.input(ctx, null, null, null, 4.0, t0).unsupported)
        assertEquals(BatteryHeat.W_UNSUPPORTED, tick(at(20.0).apply { unsupported = true }, t0 + 5 * min).why)
        assertEquals(1, notice.cards.size)
    }

    @Test
    fun aNetworkFailureGivesUpForThatStopQuietly() {
        car.fails = UnknownHostException("appgateway")
        tick(at(24.0), t0)
        assertFalse(state().on)
        assertTrue(state().done)
        assertEquals("Could not warm the battery for Zunder Écija (no answer from Leapmotor).", notice.cards.single().first)
        assertTrue(notice.voices.isEmpty())
        car.fails = null
        tick(at(20.0), t0 + 4 * min)
        assertTrue(car.sent.isEmpty())
    }

    @Test
    fun aReadTimeoutMayHaveTurnedItOnSoItIsTurnedOffLater() {
        car.fails = SocketTimeoutException("Read timed out")
        tick(at(24.0), t0)
        assertTrue(state().on)
        assertTrue(notice.cards.isEmpty())
        car.fails = null
        tick(at(20.0, trip = false), t0 + 2 * min)
        assertEquals(listOf(false), car.sent)
    }

    @Test
    fun turningOffGivesUpAfterThreeTries() {
        tick(at(24.0), t0)
        car.fails = UnknownHostException("appgateway")
        for (i in 1..BatteryHeat.OFF_TRIES) tick(at(20.0, trip = false), t0 + i * 2 * min)
        assertFalse(state().on)
        assertTrue(state().done)
        car.fails = null
        tick(at(20.0, trip = false), t0 + 20 * min)
        assertEquals(listOf(true), car.sent)
    }

    @Test
    fun theCarDisconnectingFarFromTheStopTurnsItOff() {
        tick(at(24.0), t0)
        BatteryHeat.tripStopped(ctx, car, t0 + 5 * min)
        assertEquals(listOf(true, false), car.sent)
    }

    @Test
    fun theCarDisconnectingAtTheStopLeavesItOn() {
        tick(at(24.0), t0)
        tick(at(3.0), t0 + 21 * min)
        BatteryHeat.tripStopped(ctx, car, t0 + 22 * min)
        assertEquals(listOf(true), car.sent)
        assertFalse(state().on)
    }

    @Test
    fun withoutSendingTheRouteThreadOnlyDecides() {
        val s = BatteryHeat.tick(ctx, at(24.0), t0, car, false)
        assertEquals(BatteryHeat.Action.ON, s.action)
        assertTrue(car.sent.isEmpty())
        assertFalse(state().on)
    }

    @Test
    fun theInputFromTheRoutePlan() {
        val p = RoutePlanner.Plan().apply {
            n = 11
            km = DoubleArray(11) { it * 10.0 }
            segS = DoubleArray(11) { if (it == 0) 0.0 else 360.0 }
            temp = DoubleArray(11) { 3.0 + it }
        }
        val slow = RoutePlanner.Charger().apply { id = "osm:1"; name = "Lento"; maxKw = 22.0 }
        val fast = RoutePlanner.Charger().apply { id = "dgt:9"; name = "Rápido"; maxKw = 150.0 }
        val cp = ChargePlanner.Result(ChargePlanner.Outcome.PLANNED,
            listOf(ChargePlanner.Stop(slow, 20.0, 50.0, 60.0, 10.0, 5.0), ChargePlanner.Stop(fast, 40.0, 22.0, 70.0, 20.0, 30.0)),
            20.0, p.km, DoubleArray(11) { it * 1.8 }, 0.0, 60.0, 69.9)
        val o = JSONObject().put("soc", 60).put("batteryThermalRequest", 4).put("minBatteryTemp", 6).put("dcInputFastCharge", 0)
            .put("collectTime", t0 - 2 * min)
        val cs = CarCloud.Snapshot(CarCloud.State.OK, LeapStatus.parse(o), t0 - 2 * min, 100, 69.9, "C10", 0, false)
        val i = BatteryHeat.input(ctx, p, cp, cs, Double.NaN, t0)
        assertTrue(i.trip)
        assertEquals("dgt:9", i.stopKey)
        assertEquals("Rápido", i.stopName)
        assertEquals(150.0, i.stopKw, 1e-9)
        // 24 min conduciendo más los 10 + 3 de la parada lenta de antes.
        assertEquals(24.0 + 10 + ChargePlanner.STOP_OVERHEAD_MIN, i.minutes, 1e-6)
        assertEquals(22.0, i.arrivePct, 1e-9)
        // Sin el tiempo de ahora: la previsión en la parada (km 40).
        assertEquals(7.0, i.outsideC, 1e-9)
        assertEquals("la previsión en la parada", i.tempSource)
        assertEquals(4, i.thermal)
        assertEquals(6.0, i.batteryC, 1e-9)
        // Con el tiempo de ahora, ese.
        assertEquals(12.5, BatteryHeat.input(ctx, p, cp, cs, 12.5, t0).outsideC, 1e-9)
        // Sin cuenta de Leapmotor ni PIN (este PC), y el ajuste apagado de serie.
        assertFalse(i.account)
        assertFalse(i.pin)
        assertFalse(i.enabled)
        // Un dato del coche de hace más de 10 min no vale para la señal 1186.
        assertNull(BatteryHeat.input(ctx, p, cp, cs, 5.0, t0 + 20 * min).thermal)
        // Llegado al destino: sin viaje.
        p.arrivedAtMs = t0
        assertFalse(BatteryHeat.input(ctx, p, cp, cs, 5.0, t0).trip)
        assertNull(BatteryHeat.input(ctx, null, null, null, 5.0, t0).stopKey)
    }

    @Test
    fun theSettingIsOffByDefault() {
        assertFalse(com.headqlink.link.Config(ctx).batteryHeat())
        assertEquals(Str.get(com.andrerinas.openheadunit.R.string.hql_bh_set_needs), BatteryHeat.missing(ctx))
    }
}
