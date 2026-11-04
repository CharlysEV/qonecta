package com.headqlink.link

import android.app.Application
import android.app.NotificationManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.IOException

/**
 * «Cerrar el coche al alejarme» (AutoLock): el minuto tras perder el Bluetooth del coche, lo que decide con la lectura
 * de la nube y que manda cerrar una sola vez. Con un coche de mentira: ni órdenes ni lecturas de verdad.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class AutoLockTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val t0 = 1_780_000_000_000L
    private val minute = AutoLock.WAIT_MS

    /** El coche de mentira: lo que lee (en orden; IOException si no queda), lo que contesta a cerrar y lo que se pidió. */
    private class FakeCar(var ready: Boolean = true, vararg reads: LeapStatus?) : AutoLock.Car {
        val queue = ArrayDeque(reads.toList())
        var lockAnswer: () -> Boolean = { true }
        var readsDone = 0
        var locksSent = 0
        override fun ready() = ready
        override fun read(): LeapStatus {
            readsDone++
            return queue.removeFirstOrNull() ?: throw IOException("sin red")
        }
        override fun lock(): Boolean {
            locksSent++
            return lockAnswer()
        }
        override fun pause(ms: Long) {}
    }

    private class Notes : AutoLock.Notifier {
        val got = mutableListOf<String>()
        override fun locked() { got += "locked" }
        override fun stillOpen(open: List<Int>) { got += "open:$open" }
        override fun unknown() { got += "unknown" }
        override fun lockFailed(why: String) { got += "failed" }
    }

    /** Estado de la nube: cierre (1298), puertas (1277-1280), maletero (1281) y la hora del dato. */
    private fun status(locked: Int? = 0, doors: List<Int?> = listOf(0, 0, 0, 0), boot: Int? = 0, at: Long? = t0,
                       on: Int? = null): LeapStatus {
        val sig = JSONObject()
        locked?.let { sig.put("1298", it) }
        listOf("1277", "1278", "1279", "1280").forEachIndexed { i, k -> doors[i]?.let { sig.put(k, it) } }
        boot?.let { sig.put("1281", it) }
        on?.let { sig.put("1258", it) }
        val data = JSONObject().put("signal", sig)
        at?.let { data.put("collectTime", it) }
        return LeapStatus.parse(data)
    }

    @Before
    fun setUp() {
        Str.init(app)
        AutoLock.resetForTest(app)
        com.headqlink.link.Config(app).putBool(AutoLock.K_ENABLED, true)
    }

    private fun run(car: FakeCar, notes: AutoLock.Notifier, at: Long, retry: Boolean = false) =
        AutoLock.run(app, car, notes, at, retry)

    @Test
    fun bluetoothBackWithinTheMinuteDoesNothing() {
        AutoLock.markGone(app, t0)
        AutoLock.onCarBluetooth(app, true, t0 + 30_000)
        val car = FakeCar(true, status())
        val notes = Notes()
        assertEquals(AutoLock.Verdict.NOTHING, run(car, notes, t0 + minute + 1_000))
        assertEquals(0, car.readsDone)
        assertEquals(0, car.locksSent)
        assertTrue(notes.got.isEmpty())
    }

    @Test
    fun beforeTheMinuteItWaits() {
        AutoLock.markGone(app, t0)
        val car = FakeCar(true, status())
        assertEquals(AutoLock.Verdict.WAIT, run(car, Notes(), t0 + 20_000))
        assertEquals(0, car.readsDone)
        assertEquals(40_000, AutoLock.remainingMs(app, t0 + 20_000))
    }

    @Test
    fun openDoorWarnsAndDoesNotLock() {
        AutoLock.markGone(app, t0)
        val car = FakeCar(true, status(doors = listOf(1, 0, 0, 0)))
        val notes = Notes()
        assertEquals(AutoLock.Verdict.WARN_OPEN, run(car, notes, t0 + minute))
        assertEquals(0, car.locksSent)
        assertEquals(listOf("open:[0]"), notes.got)
    }

    @Test
    fun openBootWarnsToo() {
        AutoLock.markGone(app, t0)
        val car = FakeCar(true, status(doors = listOf(0, 0, 0, 1), boot = 1))
        val notes = Notes()
        assertEquals(AutoLock.Verdict.WARN_OPEN, run(car, notes, t0 + minute))
        assertEquals(0, car.locksSent)
        assertEquals(listOf("open:[3, ${AutoLock.BOOT}]"), notes.got)
    }

    @Test
    fun cloudErrorWarnsAndDoesNotLock() {
        AutoLock.markGone(app, t0)
        val car = FakeCar(true) // la lectura falla
        val notes = Notes()
        assertEquals(AutoLock.Verdict.WARN_UNKNOWN, run(car, notes, t0 + minute))
        assertEquals(1, car.readsDone)
        assertEquals(0, car.locksSent)
        assertEquals(listOf("unknown"), notes.got)
    }

    @Test
    fun oldOrUndatedOrIncompleteDataWarnsAndDoesNotLock() {
        val cases = listOf(
            status(at = t0 - AutoLock.FRESH_BEFORE_MS - 60_000), // de antes de alejarse
            status(at = null), // sin hora del dato
            status(locked = null), // sin el cierre
            status(doors = listOf(null, null, null, null)), // sin las puertas
        )
        for (s in cases) {
            AutoLock.resetForTest(app)
            AutoLock.markGone(app, t0)
            val car = FakeCar(true, s)
            val notes = Notes()
            assertEquals(AutoLock.Verdict.WARN_UNKNOWN, run(car, notes, t0 + minute))
            assertEquals(0, car.locksSent)
            assertEquals(listOf("unknown"), notes.got)
        }
    }

    @Test
    fun alreadyLockedDoesNothing() {
        AutoLock.markGone(app, t0)
        val car = FakeCar(true, status(locked = 1))
        val notes = Notes()
        assertEquals(AutoLock.Verdict.NOTHING, run(car, notes, t0 + minute))
        assertEquals(0, car.locksSent)
        assertTrue(notes.got.isEmpty())
    }

    @Test
    fun carSwitchedOnDoesNothing() {
        AutoLock.markGone(app, t0)
        val car = FakeCar(true, status(on = 1))
        assertEquals(AutoLock.Verdict.NOTHING, run(car, Notes(), t0 + minute))
        assertEquals(0, car.locksSent)
    }

    @Test
    fun allClosedLocksOnceAndNotifiesWithUnlock() {
        AutoLock.markGone(app, t0)
        val car = FakeCar(true, status(at = t0 - 60_000), status())
        val notes = Notes()
        assertEquals(AutoLock.Verdict.LOCK, run(car, notes, t0 + minute))
        assertEquals(1, car.locksSent)
        assertEquals(listOf("locked"), notes.got)
        // El trabajo repetido (o tardío) no vuelve a mandar nada en el mismo alejamiento.
        assertEquals(AutoLock.Verdict.NOTHING, run(car, notes, t0 + minute + 30_000))
        assertEquals(1, car.locksSent)
        assertEquals(1, car.readsDone)
    }

    @Test
    fun theRealNoticeHasTheUnlockButton() {
        AutoLock.markGone(app, t0)
        val car = FakeCar(true, status())
        AutoLock.run(app, car, AutoLockJob.Notices(app), t0 + minute, false)
        val nm = app.getSystemService(NotificationManager::class.java)
        val n = shadowOf(nm).allNotifications.single()
        assertEquals(Str.get(com.andrerinas.openheadunit.R.string.hql_autolock_done), shadowOf(n).contentTitle)
        assertEquals(listOf(Str.get(com.andrerinas.openheadunit.R.string.hql_autolock_unlock)), n.actions.map { it.title.toString() })
    }

    @Test
    fun failedLockOffersRetryAndRetryLocks() {
        AutoLock.markGone(app, t0)
        val car = FakeCar(true, status(), status())
        car.lockAnswer = { throw IOException("sin red") }
        AutoLock.run(app, car, AutoLockJob.Notices(app), t0 + minute, false)
        val nm = app.getSystemService(NotificationManager::class.java)
        val n = shadowOf(nm).allNotifications.single()
        assertEquals(Str.get(com.andrerinas.openheadunit.R.string.hql_autolock_failed_title), shadowOf(n).contentTitle)
        assertEquals(listOf(Str.get(com.andrerinas.openheadunit.R.string.hql_autolock_retry)), n.actions.map { it.title.toString() })
        // «Reintentar»: vuelve a mirar el coche y cierra.
        car.lockAnswer = { true }
        val notes = Notes()
        assertEquals(AutoLock.Verdict.LOCK, run(car, notes, t0 + 3 * minute, retry = true))
        assertEquals(2, car.locksSent)
        assertEquals(listOf("locked"), notes.got)
    }

    @Test
    fun unconfirmedLockIsCheckedWithAnotherReading() {
        AutoLock.markGone(app, t0)
        val car = FakeCar(true, status(), status(locked = 1, at = t0 + minute + 20_000))
        car.lockAnswer = { false }
        val notes = Notes()
        assertEquals(AutoLock.Verdict.LOCK, run(car, notes, t0 + minute))
        assertEquals(2, car.readsDone)
        assertEquals(listOf("locked"), notes.got)
        // Sin confirmar y sin constar cerrado: «No he podido cerrar el coche».
        AutoLock.resetForTest(app)
        AutoLock.markGone(app, t0)
        val car2 = FakeCar(true, status(), status())
        car2.lockAnswer = { false }
        val notes2 = Notes()
        run(car2, notes2, t0 + minute)
        assertEquals(listOf("failed"), notes2.got)
    }

    @Test
    fun settingOffDoesNothing() {
        com.headqlink.link.Config(app).putBool(AutoLock.K_ENABLED, false)
        AutoLock.markGone(app, t0)
        val car = FakeCar(true, status())
        val notes = Notes()
        assertEquals(AutoLock.Verdict.NOTHING, run(car, notes, t0 + minute))
        assertEquals(0, car.readsDone)
        assertTrue(notes.got.isEmpty())
        // Apagado, perder el Bluetooth no apunta nada.
        AutoLock.resetForTest(app)
        AutoLock.onCarBluetooth(app, false, t0)
        assertEquals(0, AutoLock.remainingMs(app, t0))
    }

    @Test
    fun withoutAccountDoesNothing() {
        AutoLock.markGone(app, t0)
        val car = FakeCar(false, status())
        val notes = Notes()
        assertEquals(AutoLock.Verdict.NOTHING, run(car, notes, t0 + minute))
        assertEquals(0, car.readsDone)
        assertEquals(0, car.locksSent)
        assertTrue(notes.got.isEmpty())
        // Sin cuenta de verdad (sin certificado ni sesión), perder el Bluetooth tampoco apunta nada.
        AutoLock.resetForTest(app)
        AutoLock.onCarBluetooth(app, false, t0)
        assertEquals(0, AutoLock.remainingMs(app, t0))
    }

    @Test
    fun tooLateWarnsWithoutReading() {
        AutoLock.markGone(app, t0)
        val car = FakeCar(true, status())
        val notes = Notes()
        assertEquals(AutoLock.Verdict.WARN_UNKNOWN, run(car, notes, t0 + AutoLock.LATE_MS + 1_000))
        assertEquals(0, car.readsDone)
        assertEquals(listOf("unknown"), notes.got)
    }
}
