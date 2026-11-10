package com.headqlink.link

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.andrerinas.openheadunit.R
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * La cuenta de Leapmotor en las pestañas del móvil (10-11): con la sesión cerrada, «Mi coche» y «Tu coche ahora» (Viajes)
 * se quedaban para siempre en «Un momento…» y «Leyendo los datos del coche…». Ahora dicen que la sesión está cerrada y
 * ofrecen «Volver a entrar» (abre CarCloudActivity); sin cuenta, «Conectar la cuenta»; y solo esperando de verdad, la
 * espera, sin botón.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class PhoneCloudStateTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val expired = CarCloud.Snapshot.of(CarCloud.State.EXPIRED)
    private val waiting = CarCloud.Snapshot.of(CarCloud.State.WAITING)

    private fun activity(): Activity {
        Str.init(app)
        val act = Robolectric.buildActivity(Activity::class.java).setup().get()
        act.setTheme(R.style.Theme_HeadQLink)
        return act
    }

    @Test
    fun whatToShowForEachState() {
        assertEquals(PhoneUi.Cloud.NO_ACCOUNT, PhoneUi.cloud(false, CarCloud.Snapshot.of(CarCloud.State.NO_ACCOUNT), false))
        // Sin cuenta manda sobre todo lo demás (también apagada en el móvil con la sesión caducada).
        assertEquals(PhoneUi.Cloud.NO_ACCOUNT, PhoneUi.cloud(false, expired, true))
        assertEquals(PhoneUi.Cloud.CLOSED, PhoneUi.cloud(true, expired, false))
        // Aún sin leer, pero la sesión guardada ya pide volver a entrar (CarCloudStore.reloginReason).
        assertEquals(PhoneUi.Cloud.CLOSED, PhoneUi.cloud(true, waiting, true))
        assertEquals(PhoneUi.Cloud.WAITING, PhoneUi.cloud(true, waiting, false))
        assertEquals(PhoneUi.Cloud.WAITING, PhoneUi.cloud(true, null, false))
        assertEquals(PhoneUi.Cloud.WAITING, PhoneUi.cloud(true, CarCloud.Snapshot.of(CarCloud.State.ERROR), false))
    }

    @Test
    fun tripsSaysTheSessionIsClosedAndOffersToSignInAgain() {
        val act = activity()
        val v = trips(act, account = true, cloud = expired)
        assertTrue(hasText(v, Str.get(R.string.hql_cloud_closed_title)))
        assertTrue(hasText(v, Str.get(R.string.hql_cloud_closed_body)))
        assertFalse("sigue diciendo que lee", hasText(v, Str.get(R.string.hql_ins_cloud_wait)))
        opensTheAccount(act, v, Str.get(R.string.hql_cloud_reenter))
    }

    @Test
    fun tripsWithoutAnAccountOffersToConnectIt() {
        val act = activity()
        val v = trips(act, account = false, cloud = CarCloud.Snapshot.of(CarCloud.State.NO_ACCOUNT))
        assertTrue(hasText(v, Str.get(R.string.hql_cloud_none_title)))
        assertTrue(hasText(v, Str.get(R.string.hql_ins_no_cloud)))
        opensTheAccount(act, v, Str.get(R.string.hql_cloud_connect))
    }

    @Test
    fun tripsStillWaitsWhileTheFirstReadIsOnItsWay() {
        val act = activity()
        val v = trips(act, account = true, cloud = waiting)
        assertTrue(hasText(v, Str.get(R.string.hql_ins_cloud_wait)))
        assertNull(button(v, Str.get(R.string.hql_cloud_reenter)))
        assertNull(button(v, Str.get(R.string.hql_cloud_connect)))
    }

    @Test
    fun myCarSaysTheSessionIsClosedAndOffersToSignInAgain() {
        val act = activity()
        assertFalse("la demostración sigue encendida (otra prueba no la apagó)", DemoMode.active())
        // Una cuenta «configurada» (los ficheros están) cuya sesión la nube ha dado por caducada.
        val dir = File(app.noBackupFilesDir, "carcloud")
        dir.mkdirs()
        for (name in listOf("identity.bin", "session.bin")) File(dir, name).writeBytes(byteArrayOf(1, 2, 3, 4))
        val current = CarCloud::class.java.getDeclaredField("current").apply { isAccessible = true }
        val before = current.get(null)
        try {
            assertTrue(PhoneInsights.hasAccount(app))
            current.set(null, expired)
            val v = myCar(act)
            assertTrue(hasText(v, Str.get(R.string.hql_cloud_closed_title)))
            assertFalse("sigue en «Un momento…»", hasText(v, Str.get(R.string.hql_cloud_waiting_body)))
            opensTheAccount(act, v, Str.get(R.string.hql_cloud_reenter))
            // Con la sesión viva y sin dato todavía, la espera de siempre y ningún botón.
            current.set(null, waiting)
            val w = myCar(act)
            assertTrue(hasText(w, Str.get(R.string.hql_cloud_waiting_body)))
            assertNull(button(w, Str.get(R.string.hql_cloud_reenter)))
        } finally {
            current.set(null, before)
            dir.deleteRecursively()
        }
    }

    @Test
    fun myCarWithoutAnAccountOffersToConnectIt() {
        val act = activity()
        assertFalse(PhoneInsights.hasAccount(app))
        val v = myCar(act)
        assertTrue(hasText(v, Str.get(R.string.hql_cloud_none_title)))
        assertTrue(hasText(v, Str.get(R.string.hql_car_intro)))
        opensTheAccount(act, v, Str.get(R.string.hql_cloud_connect))
    }

    @Test
    fun anEmptyTextBecomesAShortTitleAndItsExplanation() {
        assertArrayEquals(arrayOf("Aún no hay recorridos", "Aquí se guardan los viajes hechos con Qonecta+."),
            PhoneUi.split("Aún no hay recorridos: aquí se guardan los viajes hechos con Qonecta+."))
        assertArrayEquals(arrayOf("Aún no hay datos", "Hace falta dejar el coche al menos 2 h."),
            PhoneUi.split("Aún no hay datos. Hace falta dejar el coche al menos 2 h."))
        // Los dos puntos del francés (con espacio delante) y los del chino.
        assertArrayEquals(arrayOf("Aucun parcours", "Les trajets sont enregistrés ici."), PhoneUi.split("Aucun parcours : les trajets sont enregistrés ici."))
        assertArrayEquals(arrayOf("暂无数据", "需要停车至少 2 小时。"), PhoneUi.split("暂无数据。需要停车至少 2 小时。"))
        // Sin dos puntos ni punto y seguido, todo es título.
        assertArrayEquals(arrayOf("Sin viajes en este periodo.", ""), PhoneUi.split("Sin viajes en este periodo."))
    }

    /** «Viajes» pintada con lo que haya de la nube (como PhoneScreensRenderTest: build, sin leer ficheros). */
    private fun trips(act: Activity, account: Boolean, cloud: CarCloud.Snapshot): View {
        val pi = PhoneInsights(act)
        val d = PhoneInsights.Data()
        d.account = account
        d.cloud = cloud
        val m = PhoneInsights::class.java.getDeclaredMethod("build", PhoneInsights.Data::class.java)
        m.isAccessible = true
        m.invoke(pi, d)
        act.setContentView(pi.view())
        return pi.view()
    }

    /** «Mi coche» pintada con la foto de la nube de ahora, sin arrancar la lectura (show). */
    private fun myCar(act: Activity): View {
        val car = PhoneCar(act)
        val m = PhoneCar::class.java.getDeclaredMethod("build")
        m.isAccessible = true
        m.invoke(car)
        act.setContentView(car.view())
        return car.view()
    }

    /** El botón está y, al tocarlo, abre «Datos del coche (cuenta Leapmotor)». */
    private fun opensTheAccount(act: Activity, v: View, label: String) {
        val b = button(v, label)
        assertNotNull("falta el botón «$label»", b)
        b!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val started = shadowOf(act).nextStartedActivity
        assertNotNull("«$label» no abre nada", started)
        assertEquals(CarCloudActivity::class.java.name, started.component!!.className)
    }

    private fun button(v: View, label: String): Button? {
        if (v is Button && v.text.toString() == label) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) button(v.getChildAt(i), label)?.let { return it }
        return null
    }

    private fun hasText(v: View, text: String): Boolean {
        if (v is TextView && v.text.toString() == text) return true
        if (v is ViewGroup) for (i in 0 until v.childCount) if (hasText(v.getChildAt(i), text)) return true
        return false
    }
}
