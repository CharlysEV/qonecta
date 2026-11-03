package com.headqlink.link

import android.app.Application
import android.content.Context
import org.json.JSONArray
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File
import java.util.TimeZone

/**
 * Borrar un viaje guardado (LPMFF #341, Viajes › Recorridos, mantener pulsado): se va del almacén y de todo lo que se
 * calcula con los viajes, no toca los demás, el informe del mes guardado se rehace sin él y el viaje en curso no se borra.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class TripDeleteTest {
    private lateinit var ctx: Context
    private lateinit var dir: File
    private val day = 86_400_000L
    private val tz: TimeZone = TimeZone.getDefault()

    @Before
    fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        dir = File(ctx.getExternalFilesDir(null), "trips")
        dir.deleteRecursively()
        dir.mkdirs()
        File(ctx.filesDir, "monthly-reports.json").delete()
        ParkedDrain.sinceParked = null
    }

    @After
    fun tearDown() {
        ParkedDrain.sinceParked = null
        dir.deleteRecursively()
    }

    /** Un viaje guardado como lo guarda TripLog.save: con recorrido y, si se dan, los datos del coche al salir y al llegar. */
    private fun save(start: Long, km: Double, minutes: Long = 30, soc: Double = Double.NaN, odo: Double = Double.NaN) {
        val o = JSONObject().put("start", start).put("minutes", minutes).put("km", km).put("kwh", km * 0.16).put("climb", 20.0)
            .put("descent", 18.0).put("maxKmh", 90.0)
            .put("track", JSONArray().put(JSONArray().put(40.40).put(-3.70).put(0)).put(JSONArray().put(40.45).put(-3.65).put(600)))
        if (!soc.isNaN()) {
            o.put("cloud", JSONObject().put("socStart", soc).put("socEnd", soc - km / 5).put("odoStart", odo).put("odoEnd", odo + km)
                .put("cap", 65.0).put("charged", false))
        }
        File(dir, TripLog.fileName(start)).writeText(o.toString())
    }

    private fun starts() = TripLog.recent(ctx, Int.MAX_VALUE).map { it.startMs }

    private fun totalKm() = TripStats.totals(TripLog.recent(ctx, Int.MAX_VALUE), Long.MIN_VALUE, Long.MAX_VALUE).km

    @Test
    fun aDeletedTripLeavesTheStoreAndTheTotals() {
        val t0 = System.currentTimeMillis() - 10 * day
        save(t0, 12.0)
        save(t0 + day, 40.0)
        save(t0 + 2 * day, 7.5)
        assertEquals(59.5, totalKm(), 1e-9)
        val before = TripLog.deletions()

        assertTrue(TripLog.delete(ctx, t0 + day))

        assertEquals(listOf(t0 + 2 * day, t0), starts())
        assertEquals(19.5, totalKm(), 1e-9)
        assertFalse(File(dir, TripLog.fileName(t0 + day)).exists())
        assertEquals(before + 1, TripLog.deletions())
    }

    @Test
    fun theOthersAreUntouched() {
        val t0 = System.currentTimeMillis() - 5 * day
        save(t0, 12.0, soc = 80.0, odo = 1000.0)
        save(t0 + day, 40.0, soc = 77.0, odo = 1012.0)
        save(t0 + 2 * day, 7.5, soc = 68.0, odo = 1052.0)
        val keep = listOf(File(dir, TripLog.fileName(t0)), File(dir, TripLog.fileName(t0 + 2 * day))).map { it.readText() }

        assertTrue(TripLog.delete(ctx, t0 + day))

        assertEquals(keep, listOf(File(dir, TripLog.fileName(t0)), File(dir, TripLog.fileName(t0 + 2 * day))).map { it.readText() })
        val left = TripLog.recent(ctx, 10)
        assertEquals(2, left.size)
        assertEquals(7.5, left[0].km, 1e-9)
        assertEquals(68.0, left[0].socStart, 1e-9)
        assertEquals(12.0, left[1].km, 1e-9)
        assertEquals(2, left[1].track.size)
        // El aparcamiento entre los dos que quedan no vale: se condujo por medio (el borrado), y lo dice el cuentakilómetros.
        assertTrue(ParkedDrain.parks(left, 65.0).isEmpty())
    }

    @Test
    fun aTripThatIsNotThereIsNotDeletedAndNothingChanges() {
        val t0 = System.currentTimeMillis() - 3 * day
        save(t0, 12.0)
        val before = TripLog.deletions()
        assertFalse(TripLog.delete(ctx, t0 + 1000))
        assertFalse(TripLog.delete(ctx, 0))
        assertEquals(listOf(t0), starts())
        assertEquals(before, TripLog.deletions())
        // Dos veces el mismo: la segunda ya no está.
        assertTrue(TripLog.delete(ctx, t0))
        assertFalse(TripLog.delete(ctx, t0))
        assertTrue(starts().isEmpty())
    }

    @Test
    fun aFileSavedInAnotherTimeZoneIsFoundByItsStart() {
        val t0 = System.currentTimeMillis() - 3 * day
        save(t0, 12.0)
        // Guardado con otro nombre (otra zona horaria cuando se guardó).
        val other = File(dir, "trip-20260101-000000.json")
        File(dir, TripLog.fileName(t0)).renameTo(other)
        assertTrue(TripLog.delete(ctx, t0))
        assertFalse(other.exists())
    }

    @Test
    fun theTripInProgressIsNotOffered() {
        TripLog.start(ctx)
        try {
            val live = TripLog.liveStartMs()
            assertTrue(live > 0)
            // Su fichero, como al retomar el viaje de la conexión anterior (TripCarry).
            save(live, 25.0)
            save(live - 2 * day, 10.0)
            assertFalse(TripLog.deletable(live))
            assertFalse(TripLog.delete(ctx, live))
            assertTrue(File(dir, TripLog.fileName(live)).exists())
            // Los demás, sí.
            assertTrue(TripLog.deletable(live - 2 * day))
            assertTrue(TripLog.delete(ctx, live - 2 * day))
            assertEquals(listOf(live), starts())
        } finally {
            TripLog.stop()
        }
    }

    @Test
    fun theParkedDrainThatStartedAtItsEndIsForgotten() {
        val t0 = System.currentTimeMillis() - 2 * day
        save(t0, 12.0, minutes = 30)
        ParkedDrain.sinceParked = ParkedDrain.Park(t0 + 30 * 60_000L, t0 + day, 70.0, 69.5, 65.0)
        assertTrue(TripLog.delete(ctx, t0))
        assertNull(ParkedDrain.sinceParked)
    }

    @Test
    fun anotherParkedDrainStays() {
        val t0 = System.currentTimeMillis() - 2 * day
        save(t0, 12.0)
        val p = ParkedDrain.Park(t0 + day, t0 + 2 * day, 70.0, 69.5, 65.0)
        ParkedDrain.sinceParked = p
        assertTrue(TripLog.delete(ctx, t0))
        assertEquals(p, ParkedDrain.sinceParked)
    }

    // ------------------------------------------------------------------ el informe del mes guardado

    @Test
    fun theStoredMonthIsRedoneWithoutItAndRefreshDoesNotBringItBack() {
        // El mes pasado, con viajes de la app (sin cuenta de Leapmotor): guardado con los tres.
        val now = System.currentTimeMillis()
        val cur = MonthlyReport.monthOf(now, tz)
        val prev = MonthlyReport.shift(cur[0], cur[1], -1)
        val from = MonthlyReport.monthStart(prev[0], prev[1], tz) + 3 * day
        save(from, 30.0)
        save(from + day, 120.0)
        save(from + 2 * day, 20.0)
        val stored = MonthlyReport.refresh(ctx)[MonthlyReport.key(prev[0], prev[1])]
        assertNotNull(stored)
        assertEquals(3, stored!!.trips)
        assertEquals(170.0, stored.km, 1e-9)
        assertEquals(120.0, stored.longestKm, 1e-9)

        assertTrue(TripLog.delete(ctx, from + day))

        val after = MonthlyReport.load(ctx)[MonthlyReport.key(prev[0], prev[1])]!!
        assertEquals(2, after.trips)
        assertEquals(50.0, after.km, 1e-9)
        assertEquals(30.0, after.longestKm, 1e-9)
        assertEquals(from, after.longestMs)
        // keep se quedaría con el de más km entre dos meses a medias: el borrado no vuelve.
        val again = MonthlyReport.refresh(ctx)[MonthlyReport.key(prev[0], prev[1])]!!
        assertEquals(2, again.trips)
        assertEquals(50.0, again.km, 1e-9)
    }

    @Test
    fun withoutTripChangesTheAppTripsButNotTheCarOnes() {
        val p = MonthlyReport.Prices(0.20, 0.50, 1.60, 6.5)
        val stored = MonthlyReport.Summary().apply {
            year = 2026; month = 9; complete = true; fromCar = false; trips = 3; km = 170.0; kwh = 27.0; costEur = 5.4
            charges = 4; chargedKwh = 60.0; fastKwh = 10.0; parks = 2; parkedPct = 1.0; longestKm = 120.0
        }
        val fresh = MonthlyReport.Summary().apply {
            year = 2026; month = 9; complete = false; fromCar = false; trips = 2; km = 50.0; kwh = 8.0; parks = 0; parkedPct = 0.0
            longestKm = 30.0; charges = 0
        }
        val app = MonthlyReport.withoutTrip(stored, fresh, p)
        assertEquals(2, app.trips)
        assertEquals(50.0, app.km, 1e-9)
        assertEquals(30.0, app.longestKm, 1e-9)
        assertEquals(0, app.parks)
        // Lo guardado que no sale de los viajes se queda: completo y las cargas (ChargeLog solo guarda las últimas).
        assertTrue(app.complete)
        assertEquals(4, app.charges)
        assertEquals(60.0, app.chargedKwh, 1e-9)
        // El dinero, otra vez: 8 kWh, de ellos 8 en rápida (había 10 kWh de rápida en el mes).
        assertEquals(8 * 0.50, app.costEur, 1e-9)
        assertEquals(50 * 6.5 / 100 * 1.60, app.petrolEur, 1e-9)

        stored.fromCar = true
        val car = MonthlyReport.withoutTrip(stored, fresh, p)
        // El coche sigue contando el viaje en su historial: sus cifras no cambian, solo los aparcamientos (de la app).
        assertEquals(3, car.trips)
        assertEquals(170.0, car.km, 1e-9)
        assertEquals(5.4, car.costEur, 1e-9)
        assertEquals(0, car.parks)
        assertNull(MonthlyReport.withoutTrip(null, fresh, p))
    }
}
