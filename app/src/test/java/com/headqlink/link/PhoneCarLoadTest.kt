package com.headqlink.link

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.andrerinas.openheadunit.R
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File

/**
 * «Mi coche» con la nube lenta (1-11): las cargas y el consumo del móvil se pintan sin esperar a la lectura de la nube
 * (CarCloud.usage), que puede tardar hasta ~35 s o quedarse esperando el candado de CarCloudSession si hay otra lectura
 * en curso. La prueba hace de nube lenta quedándose ella con ese candado.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class PhoneCarLoadTest {
    @Test
    fun localDataIsPaintedWhileTheCloudIsSlow() {
        val app = RuntimeEnvironment.getApplication()
        Str.init(app)
        assertFalse("la demostración sigue encendida (otra prueba no la apagó)", DemoMode.active())
        // Una cuenta «configurada»: los ficheros están, pero la sesión no se puede abrir (la lectura acabará fallando).
        val dir = File(app.noBackupFilesDir, "carcloud")
        dir.mkdirs()
        for (name in listOf("identity.bin", "session.bin")) File(dir, name).writeBytes(byteArrayOf(1, 2, 3, 4))
        assertTrue(PhoneInsights.hasAccount(app))
        val current = CarCloud::class.java.getDeclaredField("current").apply { isAccessible = true }
        val before = current.get(null)
        clearUsageCache()
        CarCloudSession.invalidate()
        // Un dato del coche: «Mi coche» enseña sus tarjetas (sin él, solo la espera del primer dato).
        CarCloud.publish(app, LeapStatus.parse(JSONObject("""{"signal":{"1204":70}}""")), 0)
        val act = Robolectric.buildActivity(Activity::class.java).setup().get()
        act.setTheme(R.style.Theme_HeadQLink)
        val car = PhoneCar(act)
        act.setContentView(car.view())
        val noCharges = Str.get(R.string.hql_ins_no_charges)
        val noTrips = Str.get(R.string.hql_car_use_none)
        try {
            val worker = synchronized(CarCloudSession::class.java) {
                car.show()
                val w = waitFor("«Mi coche» no llegó a pedir el consumo a la nube") { cloudWaiter() }
                shadowOf(Looper.getMainLooper()).idle()
                // Sin cargas ni viajes guardados: los dos «aún no hay», ya pintados con la nube sin contestar.
                assertTrue("las cargas esperan a la nube", hasText(car.view(), noCharges))
                assertTrue("el consumo espera a la nube", hasText(car.view(), noTrips))
                assertTrue(w.isAlive)
                w
            }
            // La nube contesta (aquí falla: la sesión no se abre) y lo pintado se queda.
            worker.join(10_000)
            assertFalse("el hilo de «Mi coche» no acabó", worker.isAlive)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(hasText(car.view(), noTrips))
        } finally {
            car.hide()
            // El sondeo que arrancó «Mi coche» se para antes de devolver la foto de antes (si no, la pisaría).
            for (t in Thread.getAllStackTraces().keys) if (t.name == "carcloud") t.join(10_000)
            current.set(null, before)
            clearUsageCache()
            dir.deleteRecursively()
        }
    }

    /** El hilo de «Mi coche» esperando el candado de la nube (en CarCloudSession.readWindow), o null. */
    private fun cloudWaiter(): Thread? = Thread.getAllStackTraces().entries.firstOrNull { (t, stack) ->
        t.name == "hql-phone-car" && t.state == Thread.State.BLOCKED && stack.any { it.methodName == "readWindow" }
    }?.key

    private fun <T : Any> waitFor(why: String, what: () -> T?): T {
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until) {
            what()?.let { return it }
            Thread.sleep(20)
        }
        throw AssertionError(why)
    }

    private fun hasText(v: View, text: String): Boolean {
        if (v is TextView && v.text.toString() == text) return true
        if (v is ViewGroup) for (i in 0 until v.childCount) if (hasText(v.getChildAt(i), text)) return true
        return false
    }

    /** Lo leído de la nube para «Consumo» y sus esperas tras un fallo, vacíos: que no pase de una prueba a otra. */
    private fun clearUsageCache() {
        for (name in listOf("usage", "usageFailUntil")) {
            val f = CarCloud::class.java.getDeclaredField(name)
            f.isAccessible = true
            (f.get(null) as MutableMap<*, *>).clear()
        }
    }
}
