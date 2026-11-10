package com.headqlink.link

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config as RoboConfig
import org.robolectric.annotation.ConscryptMode
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * «Carga barata», el motor: cuándo programa la ventana, qué manda, cuándo devuelve el plan del coche y qué pasa cuando
 * algo falla. El coche es de mentira (CheapCharge.Car) y la red también (Http.fake): nada sale del PC.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@RoboConfig(sdk = [35], application = Application::class)
class CheapChargeTest {
    private val tz = TimeZone.getTimeZone("Europe/Madrid")
    private var oldTz: TimeZone? = null
    private val min = 60_000L
    private val ctx get() = RuntimeEnvironment.getApplication()
    private lateinit var cfg: Config
    private lateinit var car: FakeCar

    /** Casa inventada (en Finlandia, como las demás pruebas) y el coche a unos 50 m de ella. */
    private val homeLat = 61.5000
    private val homeLon = 23.8000

    /** El plan que tenía el C10 de verdad el 10-11: sin ventana, todo el día, límite 100. */
    private val real = ChargePlan(false, 100, "00:00", "23:59", "1,1,1,1,1,1,1", 1, 0)

    private fun at(s: String): Long =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).apply { timeZone = tz }.parse(s)!!.time

    /** El coche de mentira: apunta lo que se le pide y aplica (o no) lo que se le manda. */
    private class FakeCar(var plan: ChargePlan?) : CheapCharge.Car {
        var pin = true
        var position: DoubleArray? = null
        val sent = mutableListOf<ChargePlan>()
        var planReads = 0
        var positionReads = 0
        /** El coche confirma la orden. */
        var confirms = true
        /** La orden llega al coche (aunque no la confirme). */
        var applies = true
        var sendFails: Exception? = null
        var readFails: Exception? = null
        var positionFails: Exception? = null

        override fun hasPin() = pin

        override fun readPlan(): ChargePlan? {
            planReads++
            readFails?.let { throw it }
            return plan
        }

        override fun sendPlan(p: ChargePlan): Boolean {
            sendFails?.let { throw it }
            sent.add(p)
            if (applies) plan = p
            return confirms
        }

        override fun position(): DoubleArray? {
            positionReads++
            positionFails?.let { throw it }
            return position
        }
    }

    /** Una lectura del coche: % de batería, enchufado por alterna o continua, y la hora del dato. */
    private fun snap(dataAt: Long, soc: Int, ac: Boolean, dc: Boolean = false, charging: Boolean = false): CarCloud.Snapshot {
        val o = JSONObject().put("soc", soc).put("acInputSlowCharge", if (ac) 1 else 0).put("dcInputFastCharge", if (dc) 1 else 0)
            .put("chargeState", if (charging) 1 else 0).put("chargeCompleted", 0).put("collectTime", dataAt)
        return CarCloud.Snapshot(CarCloud.State.OK, LeapStatus.parse(o), dataAt, 100, 69.9, "C10", 0, false)
    }

    /** Una comprobación a una hora, con el coche como esté; comprueba de paso que no manda más de una orden. */
    private fun check(time: String, soc: Int, ac: Boolean = true, dc: Boolean = false, dataAt: String = time) {
        val before = car.sent.size
        CheapCharge.check(ctx, snap(at(dataAt), soc, ac, dc), at(time), car)
        assertTrue("más de un envío en una comprobación", car.sent.size - before <= 1)
    }

    private fun state() = CheapCharge.State.load(ctx)

    private fun notice(): String? {
        val n = Shadows.shadowOf(ctx.getSystemService(NotificationManager::class.java)).getNotification(CheapCharge.NOTIF_ID)
        return n?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.replace(' ', ' ')
    }

    private fun noticeTitle(): String? {
        val n = Shadows.shadowOf(ctx.getSystemService(NotificationManager::class.java)).getNotification(CheapCharge.NOTIF_ID)
        return n?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    }

    @Before
    fun setUp() {
        oldTz = TimeZone.getDefault()
        TimeZone.setDefault(tz)
        Str.init(ctx)
        Money.setForTest("EUR")
        cfg = Config(ctx)
        CheapCharge.resetForTest(ctx)
        PvpcPrices.resetForTest()
        File(ctx.filesDir, "pvpc.json").delete()
        cfg.setCheapCharge(true)
        cfg.setCheapTariff(Tariff.BANDS)
        val home = RoutePlanner.Place().apply { name = "Casa"; lat = homeLat; lon = homeLon }
        cfg.setSavedPlaces(SavedPlaces.setTagged("", home, SavedPlaces.HOME))
        car = FakeCar(real)
        car.position = doubleArrayOf(homeLat + 0.0004, homeLon)
    }

    @After
    fun tearDown() {
        Http.fake = null
        PvpcPrices.resetForTest()
        TimeZone.setDefault(oldTz)
    }

    // ------------------------------------------------------------------ programar

    @Test
    fun pluggedInAtHomeItSendsTheCheapestWindowAndSaysSo() {
        check("2026-11-10 22:00", 50)
        assertEquals(1, car.sent.size)
        val w = car.sent[0]
        // El valle empieza a las 0: 189 min de carga, con el margen hasta las 03:45; el límite, el objetivo.
        assertEquals("""{"chargeEnable":1,"chargesoc":80,"circulation":1,"cycles":"1,1,1,1,1,1,1","endtime":"03:45","recharge":0,"starttime":"00:00"}""",
            w.content())
        val st = state()
        assertEquals(CheapCharge.PLANNED, st.phase)
        assertTrue(st.confirmed)
        assertFalse(st.provisional)
        // El plan original, guardado tal cual para devolverlo.
        assertEquals(real.content(), st.original)
        assertEquals(at("2026-11-11 07:30"), st.departMs)
        assertEquals("Cheap charging scheduled", noticeTitle())
        assertEquals("00:00–03:45 · 80 % · €2.33 (you save €0.74)", notice())
        // Las siguientes comprobaciones no mandan nada más ni vuelven a preguntar dónde está el coche.
        check("2026-11-10 22:15", 50)
        check("2026-11-10 23:45", 50)
        check("2026-11-11 01:00", 62)
        assertEquals(1, car.sent.size)
        assertEquals(1, car.positionReads)
        assertEquals(1, car.planReads)
        val card = CheapCharge.status(ctx, snap(at("2026-11-10 22:15"), 50, true), at("2026-11-10 22:15"), true, false)
        assertEquals("Scheduled 00:00–03:45 · up to 80 %", card.line)
        assertTrue(card.canChargeNow)
    }

    @Test
    fun nothingHappensWhenOffOrWithoutPinOrHomeOrOnAFastChargerOrAtTheTarget() {
        cfg.setCheapCharge(false)
        check("2026-11-10 22:00", 50)
        cfg.setCheapCharge(true)
        car.pin = false
        check("2026-11-10 22:01", 50)
        car.pin = true
        cfg.setSavedPlaces("")
        check("2026-11-10 22:02", 50)
        val home = RoutePlanner.Place().apply { name = "Casa"; lat = homeLat; lon = homeLon }
        cfg.setSavedPlaces(SavedPlaces.setTagged("", home, SavedPlaces.HOME))
        // Continua, sin enchufar, ya en el objetivo, o un dato de hace más de seis horas.
        check("2026-11-10 22:03", 50, ac = false, dc = true)
        check("2026-11-10 22:04", 50, ac = false)
        check("2026-11-10 22:05", 80)
        check("2026-11-10 22:06", 50, dataAt = "2026-11-10 15:00")
        assertEquals(0, car.sent.size)
        assertEquals(0, car.positionReads)
        assertEquals(0, car.planReads)
        assertEquals(CheapCharge.IDLE, state().phase)
        assertNull(notice())
        // Y con todo en su sitio, sí.
        check("2026-11-10 22:07", 50)
        assertEquals(1, car.sent.size)
    }

    @Test
    fun withAFlatRateThereIsNoWindow() {
        cfg.setCheapTariff(Tariff.FLAT)
        check("2026-11-10 22:00", 50)
        assertEquals(0, car.sent.size)
        assertEquals(0, car.positionReads)
        val card = CheapCharge.status(ctx, snap(at("2026-11-10 22:00"), 50, true), at("2026-11-10 22:00"), true, false)
        assertTrue(card.line.startsWith("Flat rate"))
    }

    @Test
    fun awayFromHomeNothingIsScheduledAndThePositionIsAskedOnce() {
        car.position = doubleArrayOf(homeLat + 0.01, homeLon)
        check("2026-11-10 22:00", 50)
        check("2026-11-10 22:15", 50)
        check("2026-11-10 23:30", 52)
        assertEquals(0, car.sent.size)
        assertEquals(1, car.positionReads)
        assertEquals(CheapCharge.HOLD, state().phase)
        assertEquals(CheapCharge.WHY_AWAY, state().why)
        // Desenchufado y, otro día, enchufado en casa: se vuelve a mirar.
        check("2026-11-11 08:00", 60, ac = false)
        assertEquals(CheapCharge.IDLE, state().phase)
        car.position = doubleArrayOf(homeLat, homeLon + 0.0005)
        check("2026-11-11 22:00", 40)
        assertEquals(2, car.positionReads)
        assertEquals(1, car.sent.size)
    }

    @Test
    fun aCarThatGivesNoPositionIsLeftAloneAfterThreeTries() {
        car.position = null
        check("2026-11-10 22:00", 50)
        // Antes de cinco minutos no se insiste.
        check("2026-11-10 22:02", 50)
        assertEquals(1, car.positionReads)
        check("2026-11-10 22:15", 50)
        check("2026-11-10 22:30", 50)
        check("2026-11-10 22:45", 50)
        assertEquals(3, car.positionReads)
        assertEquals(CheapCharge.WHY_NO_POSITION, state().why)
        assertEquals(0, car.sent.size)
    }

    @Test
    fun aWindowSetByTheUserIsRespected() {
        car.plan = ChargePlan(true, 90, "01:00", "06:00", "1,1,1,1,1,0,0", 1, 0)
        check("2026-11-10 22:00", 50)
        check("2026-11-10 22:15", 50)
        assertEquals(0, car.sent.size)
        assertEquals(1, car.planReads)
        assertEquals(CheapCharge.HOLD, state().phase)
        assertEquals(CheapCharge.WHY_RESPECTED, state().why)
        assertNull(notice())
    }

    @Test
    fun withoutAnyPlanInTheCarItStartsFromAPlainOne() {
        car.plan = null
        check("2026-11-10 22:00", 50)
        assertEquals(1, car.sent.size)
        assertEquals("00:00", car.sent[0].start)
        // Al acabar se manda ese plan corriente: sin ventana, todo el día, límite 100.
        check("2026-11-11 08:00", 80, ac = false)
        assertEquals(2, car.sent.size)
        assertEquals("""{"chargeEnable":0,"chargesoc":100,"circulation":1,"cycles":"1,1,1,1,1,1,1","endtime":"23:59","recharge":0,"starttime":"00:00"}""",
            car.sent[1].content())
    }

    @Test
    fun aDisabledPlanWithLooseDaysGetsAWindowForEveryDayAndGoesBackAsItWas() {
        val odd = ChargePlan(false, 90, "23:00", "06:00", "1,1,1,1,1,0,0", 2, 1)
        car.plan = odd
        check("2026-11-10 22:00", 50)
        assertEquals("""{"chargeEnable":1,"chargesoc":80,"circulation":2,"cycles":"1,1,1,1,1,1,1","endtime":"03:45","recharge":1,"starttime":"00:00"}""",
            car.sent[0].content())
        check("2026-11-11 08:00", 80, ac = false)
        assertEquals(odd.content(), car.sent[1].content())
    }

    @Test
    fun ifItDoesNotFitOrNowIsCheapestItChargesNowWithoutWindow() {
        check("2026-11-11 06:00", 50)
        assertEquals(0, car.sent.size)
        assertEquals(0, car.planReads)
        assertEquals(CheapCharge.HOLD, state().phase)
        assertEquals(CheapCharge.WHY_NO_FIT, state().why)
        assertEquals("Cheap charging: charging now", noticeTitle())
        assertEquals("There is not enough time before 07:30: the car charges now, without a window.", notice())
        // No se vuelve a pensar en este enchufado.
        check("2026-11-11 06:15", 52)
        assertEquals(1, car.positionReads)
        // Otro enchufado, ya en valle: lo más barato es ahora.
        check("2026-11-11 09:00", 60, ac = false)
        check("2026-11-12 00:05", 50)
        assertEquals(CheapCharge.WHY_NOW, state().why)
        assertEquals("Now is already the cheapest time before 07:30: the car charges now, without a window.", notice())
        assertEquals(0, car.sent.size)
    }

    // ------------------------------------------------------------------ devolver el plan

    @Test
    fun unpluggedThePlanGoesBackExactlyAsItWas() {
        check("2026-11-10 22:00", 50)
        // Un dato viejo que dice «sin enchufar» (anterior al enchufado) no cuenta.
        check("2026-11-10 22:20", 50, ac = false, dataAt = "2026-11-10 21:50")
        assertEquals(1, car.sent.size)
        check("2026-11-10 23:10", 50, ac = false)
        assertEquals(2, car.sent.size)
        assertEquals(real.content(), car.sent[1].content())
        assertTrue(car.plan!!.same(real))
        val st = state()
        assertEquals(CheapCharge.IDLE, st.phase)
        assertEquals("", st.original)
        // Sin haber cargado nada: el aviso de «programada» se quita y no hay otro.
        assertNull(notice())
        val card = CheapCharge.status(ctx, snap(at("2026-11-10 23:10"), 50, false), at("2026-11-10 23:10"), true, false)
        assertEquals("Waiting for the car to be plugged in at home.", card.line)
        assertEquals("The car's charge plan is back as it was.", card.detail)
        // Y nada más que mandar después.
        check("2026-11-10 23:25", 50, ac = false)
        assertEquals(2, car.sent.size)
    }

    @Test
    fun atTheTargetTheMorningNoticeSaysWhatItCostAndThePlanGoesBackOnlyWhenUnplugged() {
        check("2026-11-10 22:00", 50)
        check("2026-11-11 02:00", 68)
        assertEquals(1, car.sent.size)
        check("2026-11-11 03:15", 80)
        // Enchufado todavía: la ventana (con su límite) se queda; devolver un plan sin límite seguiría cargando.
        assertEquals(1, car.sent.size)
        assertEquals(CheapCharge.PLANNED, state().phase)
        assertTrue(state().done)
        assertEquals("Cheap charging", noticeTitle())
        assertEquals("Charged to 80 %. It cost about €2.33, €0.74 less than charging when plugged in.", notice())
        val card = CheapCharge.status(ctx, snap(at("2026-11-11 03:30"), 80, true), at("2026-11-11 03:30"), true, false)
        assertEquals("The battery is already at the target (80 %).", card.line)
        assertFalse(card.canChargeNow)
        check("2026-11-11 03:30", 80)
        check("2026-11-11 08:00", 80)
        assertEquals(1, car.sent.size)
        // Al desenchufar, el plan vuelve a como estaba y el aviso de la mañana sigue ahí.
        check("2026-11-11 08:10", 79, ac = false)
        assertEquals(2, car.sent.size)
        assertEquals(real.content(), car.sent[1].content())
        assertEquals(CheapCharge.IDLE, state().phase)
        assertFalse(state().done)
        assertEquals("Charged to 80 %. It cost about €2.33, €0.74 less than charging when plugged in.", notice())
    }

    @Test
    fun pastTheDepartureTheNoticeComesEvenIfTheTargetWasNotReachedAndThePlanWaitsForTheUnplug() {
        check("2026-11-10 22:00", 50)
        // El coche dormido: el dato sigue siendo el de las 3 (al 71 %), pero ya son las 07:31.
        check("2026-11-11 07:31", 71, dataAt = "2026-11-11 03:00")
        assertEquals(1, car.sent.size)
        assertEquals(CheapCharge.PLANNED, state().phase)
        // Cargó 21 de los 30 puntos previstos: el coste y el ahorro, en proporción.
        assertEquals("Charged to 71 %. It cost about €1.63, €0.52 less than charging when plugged in.", notice())
        // Sigue enchufado y sin llegar: «Cargar ya» sigue a mano.
        val card = CheapCharge.status(ctx, snap(at("2026-11-11 07:40"), 71, true), at("2026-11-11 07:40"), true, false)
        assertTrue(card.canChargeNow)
        check("2026-11-11 08:30", 71, ac = false)
        assertEquals(2, car.sent.size)
        assertEquals(real.content(), car.sent[1].content())
        assertEquals(CheapCharge.IDLE, state().phase)
    }

    @Test
    fun ifGivingThePlanBackFailsItIsRetriedAtEveryCheckUntilItWorks() {
        check("2026-11-10 22:00", 50)
        car.sendFails = IOException("sin red")
        check("2026-11-11 08:00", 80, ac = false)
        assertEquals(CheapCharge.RESTORE, state().phase)
        assertEquals(CheapCharge.ERR_NET, state().error)
        val card = CheapCharge.status(ctx, snap(at("2026-11-11 08:00"), 80, false), at("2026-11-11 08:00"), true, false)
        assertTrue(card.line.startsWith("Putting the car's charge plan back"))
        assertEquals(2, card.level)
        // A los dos minutos (la app abierta) no se insiste; en la comprobación siguiente, sí.
        check("2026-11-11 08:02", 80, ac = false)
        check("2026-11-11 08:15", 80, ac = false)
        assertEquals(CheapCharge.RESTORE, state().phase)
        assertEquals(1, car.sent.size)
        car.sendFails = null
        check("2026-11-11 08:30", 80, ac = false)
        assertEquals(2, car.sent.size)
        assertEquals(real.content(), car.sent[1].content())
        assertEquals(CheapCharge.IDLE, state().phase)
        // Aunque mientras tanto se vuelva a enchufar: primero se devuelve el plan, luego ya se verá.
    }

    @Test
    fun whileThePlanIsNotBackNothingNewIsPlanned() {
        check("2026-11-10 22:00", 50)
        car.readFails = IOException("sin red")
        check("2026-11-11 08:00", 80, ac = false)
        // Enchufado otra vez esa noche con el plan aún sin devolver: no se guarda la ventana vieja como «original».
        check("2026-11-11 21:00", 45)
        assertEquals(CheapCharge.RESTORE, state().phase)
        assertEquals(real.content(), state().original)
        assertEquals(1, car.sent.size)
        car.readFails = null
        check("2026-11-11 21:15", 45)
        assertEquals(real.content(), car.sent[1].content())
    }

    @Test
    fun anUnconfirmedReturnIsCheckedBeforeSendingAgain() {
        check("2026-11-10 22:00", 50)
        // El coche aplica la orden pero no la confirma a tiempo.
        car.confirms = false
        check("2026-11-11 08:00", 80, ac = false)
        assertEquals(CheapCharge.RESTORE, state().phase)
        assertEquals(2, car.sent.size)
        car.confirms = true
        check("2026-11-11 08:15", 80, ac = false)
        // Leído: ya está como estaba. Nada que mandar.
        assertEquals(2, car.sent.size)
        assertEquals(CheapCharge.IDLE, state().phase)
    }

    @Test
    fun ifTheUserPutAnotherWindowInTheCarItIsLeftAlone() {
        check("2026-11-10 22:00", 50)
        car.plan = ChargePlan(true, 90, "02:00", "06:00", "1,1,1,1,1,1,1", 1, 0)
        check("2026-11-11 08:00", 88, ac = false)
        assertEquals(1, car.sent.size)
        assertEquals(CheapCharge.IDLE, state().phase)
        // Y si el usuario ya lo dejó como estaba, tampoco se manda nada.
        car.plan = real
        check("2026-11-11 22:00", 50)
        assertEquals(2, car.sent.size)
        car.plan = real
        check("2026-11-12 08:00", 80, ac = false)
        assertEquals(2, car.sent.size)
        assertEquals(CheapCharge.IDLE, state().phase)
    }

    @Test
    fun chargeNowGivesThePlanBackSoTheCarCharges() {
        check("2026-11-10 22:00", 50)
        assertTrue(CheapCharge.chargeNow(ctx, car, at("2026-11-10 22:05")))
        assertEquals(2, car.sent.size)
        assertEquals(real.content(), car.sent[1].content())
        assertFalse(car.plan!!.enabled)
        val st = state()
        assertEquals(CheapCharge.HOLD, st.phase)
        assertEquals(CheapCharge.WHY_USER, st.why)
        assertNull(notice())
        // En este enchufado no se vuelve a programar; pulsarlo otra vez no manda nada.
        check("2026-11-10 22:15", 51)
        assertTrue(CheapCharge.chargeNow(ctx, car, at("2026-11-10 22:20")))
        assertEquals(2, car.sent.size)
        // En el siguiente enchufado, sí.
        check("2026-11-11 08:00", 80, ac = false)
        check("2026-11-11 22:00", 55)
        assertEquals(3, car.sent.size)
        assertTrue(car.sent[2].enabled)
    }

    @Test
    fun chargeNowThatFailsKeepsTryingAtTheChecks() {
        check("2026-11-10 22:00", 50)
        car.sendFails = IOException("sin red")
        assertFalse(CheapCharge.chargeNow(ctx, car, at("2026-11-10 22:05")))
        assertEquals(CheapCharge.RESTORE, state().phase)
        car.sendFails = null
        check("2026-11-10 22:15", 50)
        assertEquals(real.content(), car.sent[1].content())
        assertEquals(CheapCharge.HOLD, state().phase)
        assertEquals(CheapCharge.WHY_USER, state().why)
    }

    @Test
    fun turningTheFeatureOffWithAWindowSetGivesThePlanBack() {
        check("2026-11-10 22:00", 50)
        cfg.setCheapCharge(false)
        check("2026-11-10 22:15", 50)
        assertEquals(2, car.sent.size)
        assertEquals(real.content(), car.sent[1].content())
        check("2026-11-10 22:30", 50)
        assertEquals(2, car.sent.size)
    }

    // ------------------------------------------------------------------ cuando mandar la ventana falla

    @Test
    fun aWindowSentButNotConfirmedIsCheckedAndSentAgainIfMissing() {
        // La orden ni llega ni se confirma.
        car.confirms = false
        car.applies = false
        check("2026-11-10 22:00", 50)
        assertEquals(1, car.sent.size)
        var st = state()
        assertEquals(CheapCharge.PLANNED, st.phase)
        assertFalse(st.confirmed)
        // El original ya está guardado, por si el proceso muere aquí.
        assertEquals(real.content(), st.original)
        assertNull(notice())
        val card = CheapCharge.status(ctx, snap(at("2026-11-10 22:01"), 50, true), at("2026-11-10 22:01"), true, false)
        assertEquals("Sending the window 00:00–03:45 to the car…", card.line)
        // A los dos minutos nada; a los quince se lee el plan, no está y se manda otra vez (esta vez llega, sin confirmar).
        check("2026-11-10 22:02", 50)
        assertEquals(1, car.sent.size)
        car.applies = true
        check("2026-11-10 22:15", 50)
        assertEquals(2, car.sent.size)
        assertFalse(state().confirmed)
        // En la siguiente se lee, está puesta: confirmada y avisada, sin mandar más.
        check("2026-11-10 22:30", 50)
        assertEquals(2, car.sent.size)
        st = state()
        assertTrue(st.confirmed)
        assertEquals("00:00–03:45 · 80 % · €2.33 (you save €0.74)", notice())
    }

    @Test
    fun aCarThatNeverTakesTheWindowIsLeftAsItWas() {
        car.sendFails = IOException("sin red")
        check("2026-11-10 22:00", 50)
        assertEquals(CheapCharge.PLANNED, state().phase)
        assertEquals(CheapCharge.ERR_NET, state().error)
        check("2026-11-10 22:15", 50)
        check("2026-11-10 22:30", 50)
        check("2026-11-10 22:45", 50)
        assertEquals(4, state().tries)
        // Cuatro intentos: se deja. El plan del coche sigue siendo el original, así que no hay nada que devolver.
        check("2026-11-10 23:00", 50)
        check("2026-11-10 23:15", 50)
        assertEquals(0, car.sent.size)
        val st = state()
        assertEquals(CheapCharge.HOLD, st.phase)
        assertEquals(CheapCharge.WHY_FAILED, st.why)
        check("2026-11-10 23:30", 50)
        assertEquals(CheapCharge.HOLD, state().phase)
    }

    @Test
    fun aClosedSessionOrAMissingPinIsToldOnTheCard() {
        assertEquals(CheapCharge.ERR_SESSION, CheapCharge.errorOf(LeapApi.SessionExpiredException("caducada")))
        assertEquals(CheapCharge.ERR_PIN, CheapCharge.errorOf(CarCloudSession.NotConfiguredException("sin PIN del coche")))
        assertEquals(CheapCharge.ERR_SESSION, CheapCharge.errorOf(CarCloudSession.NotConfiguredException("sin sesión")))
        assertEquals(CheapCharge.ERR_NET, CheapCharge.errorOf(IOException("x")))
        val s = snap(at("2026-11-10 22:00"), 50, false)
        assertEquals("It needs the car PIN saved in Car data.", CheapCharge.status(ctx, s, at("2026-11-10 22:00"), false, false).line)
        assertTrue(CheapCharge.status(ctx, s, at("2026-11-10 22:00"), true, true).line.startsWith("The Leapmotor session is closed"))
        cfg.setSavedPlaces("")
        assertTrue(CheapCharge.status(ctx, s, at("2026-11-10 22:00"), true, false).line.startsWith("Save «Home»"))
        cfg.setCheapCharge(false)
        assertEquals("Off. Turn it on in Settings.", CheapCharge.status(ctx, s, at("2026-11-10 22:00"), true, false).line)
    }

    // ------------------------------------------------------------------ PVPC: provisional y recálculo

    private val pvpc = doubleArrayOf(181.88, 175.84, 170.44, 164.56, 149.97, 165.46, 173.04, 197.08, 235.49, 204.68, 254.32, 202.85,
        179.5, 179.83, 146.65, 192.94, 225.11, 249.5, 326.87, 326.4, 327.27, 323.16, 240.17, 239.65)

    private fun day(d: String, prices: DoubleArray) = prices.mapIndexed { h, v ->
        """{"value":$v,"datetime":"${d}T${"%02d".format(h)}:00:00.000+01:00"}"""
    }.joinToString(",")

    private fun pvpcAnswer(values: String) =
        """{"included":[{"type":"PVPC","id":"1001","attributes":{"values":[$values]}}]}"""

    @Test
    fun withPvpcBeforeTomorrowIsPublishedTheWindowIsProvisionalAndIsRedoneLater() {
        cfg.setCheapTariff(Tariff.PVPC)
        var requests = 0
        var tomorrow: DoubleArray? = null
        Http.fake = Http.Fake { _, _ ->
            requests++
            pvpcAnswer(day("2026-11-10", pvpc) + (tomorrow?.let { "," + day("2026-11-11", it) } ?: ""))
        }
        check("2026-11-10 19:00", 50)
        assertEquals(1, requests)
        assertEquals(1, car.sent.size)
        // Con los precios de hoy repetidos mañana, lo más barato son las horas de las 3, las 4 y las 5 enteras y el final
        // de la de las 2: de 02:51 a 06:00, y con el margen hasta las 06:30.
        val first = car.sent[0]
        assertEquals("02:51", first.start)
        assertEquals("06:30", first.end)
        var st = state()
        assertTrue(st.provisional)
        assertTrue(notice()!!.contains("Provisional"))
        // Antes de las 20:15 no se pregunta otra vez.
        check("2026-11-10 19:45", 50)
        assertEquals(1, requests)
        // Publicados, y mañana lo barato es de 4 a 7:30: la ventana cambia, se reenvía y el aviso se actualiza.
        tomorrow = pvpc.copyOf().also { for (h in 4..7) it[h] = 10.0 }
        check("2026-11-10 20:30", 50)
        assertEquals(2, requests)
        assertEquals(2, car.sent.size)
        assertEquals("04:00", car.sent[1].start)
        assertEquals("07:30", car.sent[1].end)
        st = state()
        assertFalse(st.provisional)
        assertTrue(st.confirmed)
        assertEquals(real.content(), st.original)
        assertTrue(notice()!!.startsWith("04:00–07:30 · 80 % · "))
        assertFalse(notice()!!.contains("Provisional"))
        // Ya está: ni más peticiones ni más órdenes.
        check("2026-11-10 21:30", 50)
        check("2026-11-11 01:00", 50)
        assertEquals(2, requests)
        assertEquals(2, car.sent.size)
        // Al acabar se devuelve el original, no la ventana provisional.
        check("2026-11-11 07:35", 80)
        assertEquals(2, car.sent.size)
        check("2026-11-11 07:50", 80, ac = false)
        assertEquals(real.content(), car.sent[2].content())
    }

    @Test
    fun ifTomorrowsPricesGiveTheSameWindowNothingIsSentAgain() {
        cfg.setCheapTariff(Tariff.PVPC)
        var both = false
        Http.fake = Http.Fake { _, _ -> pvpcAnswer(day("2026-11-10", pvpc) + if (both) "," + day("2026-11-11", pvpc) else "") }
        check("2026-11-10 19:00", 50)
        assertTrue(state().provisional)
        both = true
        check("2026-11-10 20:30", 50)
        assertEquals(1, car.sent.size)
        assertFalse(state().provisional)
        assertFalse(notice()!!.contains("Provisional"))
    }

    @Test
    fun withoutAnyPvpcPriceItWaitsAndTriesAgain() {
        cfg.setCheapTariff(Tariff.PVPC)
        var fail = true
        Http.fake = Http.Fake { _, _ -> if (fail) throw IOException("sin red") else pvpcAnswer(day("2026-11-10", pvpc) + "," + day("2026-11-11", pvpc)) }
        check("2026-11-10 21:00", 50)
        assertEquals(0, car.sent.size)
        assertEquals(CheapCharge.IDLE, state().phase)
        assertEquals(CheapCharge.ERR_PRICES, state().error)
        val card = CheapCharge.status(ctx, snap(at("2026-11-10 21:00"), 50, true), at("2026-11-10 21:00"), true, false)
        assertTrue(card.line.startsWith("The PVPC prices could not be read"))
        fail = false
        // Red Eléctrica no se pregunta antes de media hora; después, ya con precios, se programa (sin otra lectura de posición).
        check("2026-11-10 21:15", 50)
        assertEquals(0, car.sent.size)
        check("2026-11-10 21:31", 50)
        assertEquals(1, car.sent.size)
        assertEquals(1, car.positionReads)
        assertFalse(state().provisional)
    }

    // ------------------------------------------------------------------ cambiar los ajustes con la ventana puesta

    @Test
    fun changingTheSettingsWithAWindowSetRedoesIt() {
        check("2026-11-10 22:00", 50)
        cfg.setCheapTarget(90)
        CheapCharge.settingsChanged(ctx)
        check("2026-11-10 22:05", 50)
        assertEquals(2, car.sent.size)
        assertEquals(90, car.sent[1].soc)
        assertEquals("00:00", car.sent[1].start)
        // (90 − 50) % de 69,9 kWh a 7,4 kW × 0,9 = 252 min; con el margen, 04:49 → 05:00.
        assertEquals("05:00", car.sent[1].end)
        assertEquals(real.content(), state().original)
        // Con la tarifa plana ya no hay ventana: se devuelve el plan.
        cfg.setCheapTariff(Tariff.FLAT)
        CheapCharge.settingsChanged(ctx)
        check("2026-11-10 22:10", 50)
        assertEquals(3, car.sent.size)
        assertEquals(real.content(), car.sent[2].content())
        assertEquals(CheapCharge.HOLD, state().phase)
    }

    // ------------------------------------------------------------------ la tarjeta y el laboratorio

    @Test
    fun thePriceBarsMarkTheWindowAndTheEstimatedHours() {
        val table = PvpcPrices.parse(pvpcAnswer(day("2026-11-10", pvpc)))
        val bars = PhoneCar.PriceBars(ctx)
        assertTrue(bars.set(table, at("2026-11-10 19:20"), at("2026-11-11 02:51"), at("2026-11-11 06:30"), tz))
        // Desde la hora de ahora (las 19): cinco horas de hoy, publicadas; las de mañana, estimadas con las de hoy.
        assertEquals(0.3264, bars.price[0], 1e-9)
        assertFalse(bars.estimated[4])
        assertTrue(bars.estimated[5])
        assertEquals(0.18188, bars.price[5], 1e-9)
        assertTrue(bars.anyEstimated)
        // La ventana de 02:51 a 06:30 toca las horas de las 2 a las 6.
        assertFalse(bars.chosen[6])
        for (i in 7..11) assertTrue("hora ${i - 5}", bars.chosen[i])
        assertFalse(bars.chosen[12])
        assertEquals(0.14665, bars.min, 1e-9)
        assertEquals(0.32727, bars.max, 1e-9)
        // Sin ventana, ninguna resaltada; sin precios, no hay barrita.
        assertTrue(bars.set(table, at("2026-11-10 19:20"), 0, 0, tz))
        assertFalse(bars.chosen.any { it })
        assertFalse(bars.set(PvpcPrices.Table.EMPTY, at("2026-11-10 19:20"), 0, 0, tz))
    }

    @Test
    fun theLabOrderTellsTheStateWithoutSendingAnything() {
        check("2026-11-10 22:00", 50)
        val reads = car.planReads
        val text = CheapCharge.lab(ctx)
        assertTrue(text, text.startsWith("activada · tarifa bands · salida 07:30 · objetivo 80 % · 7.4 kW · casa guardada"))
        assertTrue(text, text.contains("fase planned"))
        assertTrue(text, text.contains("ventana 00:00–03:45"))
        // Ni una orden ni una lectura más, y nada de dónde está la casa ni el coche.
        assertEquals(1, car.sent.size)
        assertEquals(reads, car.planReads)
        assertFalse(text.contains("61.5"))
    }

    @Test
    fun theDefaultsAreTheAgreedOnes() {
        val fresh = Config(ctx)
        assertEquals(450, fresh.cheapDepartMin())
        assertEquals(80, fresh.cheapTarget())
        assertEquals(7.4, fresh.cheapKw(), 0.0)
        assertTrue(fresh.cheapWeekendValley())
        assertEquals(Tariff.DEFAULT_HOURS, fresh.cheapBandHours())
        assertEquals(0.10, fresh.cheapBandPrice(Tariff.VALLEY), 1e-9)
        assertEquals(0.15, fresh.cheapBandPrice(Tariff.MID), 1e-9)
        assertEquals(0.22, fresh.cheapBandPrice(Tariff.PEAK), 1e-9)
        fresh.setCheapTarget(30)
        assertEquals(50, fresh.cheapTarget())
        fresh.setCheapKw(3.7)
        assertEquals(3.7, fresh.cheapKw(), 0.0)
        fresh.setCheapKw(5.0)
        assertEquals(7.4, fresh.cheapKw(), 0.0)
        fresh.setCheapBandPrice(Tariff.VALLEY, 0.08)
        assertEquals(0.08, fresh.tariff().prices[Tariff.VALLEY], 1e-6)
        assertNotNull(CheapCharge.defaultPlan())
        assertEquals("""{"chargeEnable":0,"chargesoc":100,"circulation":1,"cycles":"1,1,1,1,1,1,1","endtime":"23:59","recharge":0,"starttime":"00:00"}""",
            CheapCharge.defaultPlan().content())
    }
}
