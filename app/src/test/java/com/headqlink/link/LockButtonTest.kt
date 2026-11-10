package com.headqlink.link

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.andrerinas.openheadunit.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.security.SecureRandom
import java.util.concurrent.Executor
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * El botón de abrir/cerrar de «Mi coche» y del widget (10-11): con el coche cerrado, un solo botón «Abrir» verde que
 * manda abrir; abierto, «Cerrar» rojo que manda cerrar; sin saberlo, los dos de siempre. Mientras se manda enseña el
 * progreso; si el coche confirma, cambia sin esperar a la nube; si falla, vuelve a como estaba. El coche es de mentira
 * (CarOrders.carForTest): aquí no sale ninguna orden ni ninguna petición a la nube.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class, qualifiers = "es-rES")
class LockButtonTest {
    /** AES-GCM con una clave en memoria: hace de Keystore en el PC (para guardar el PIN de las órdenes). */
    class MemBox : CarCloudStore.Box {
        private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        override fun seal(plain: ByteArray): ByteArray {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            return byteArrayOf(12) + iv + c.doFinal(plain)
        }

        override fun open(sealed: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 1, 12))
            return c.doFinal(sealed, 13, sealed.size - 13)
        }

        override fun destroy() {}
    }

    /** El coche de mentira: apunta lo que se le manda y contesta lo que diga cada prueba. */
    private class FakeCar : CarOrders.Car {
        val sent = ArrayList<CarCommand.Kind>()
        var answer: () -> Boolean = { true }
        /** Lo que diría otra lectura del coche (tras una orden sin confirmar). */
        var locked: Boolean? = null
        var reads = 0

        override fun command(c: CarCommand): Boolean {
            sent.add(c.kind)
            return answer()
        }

        override fun readLocked(): Boolean? {
            reads++
            return locked
        }

        override fun pause(ms: Long) {}
    }

    private val ctx: Application = RuntimeEnvironment.getApplication()
    private val car = FakeCar()
    private val realVerifier = CarOrders.verifier
    /** La comprobación de una orden sin confirmar, retenida hasta que la prueba la suelte. */
    private var pending: Runnable? = null
    private val green = CarOrders.LOCKED_COLOR
    private val red = CarOrders.UNLOCKED_COLOR

    @Before
    fun setUp() {
        Str.init(ctx)
        L.init(ctx)
        CarCloudStore.boxForTest = MemBox()
        Config(ctx).putBool(CarOrders.K_ENABLED, true)
        CarCloudStore(ctx).savePin("1234")
        CarOrders.forgetLock(ctx)
        CarOrders.carForTest = car
        CarOrders.verifier = Executor { pending = it }
    }

    @After
    fun tearDown() {
        CarOrders.carForTest = null
        CarOrders.verifier = realVerifier
        CarOrders.forgetLock(ctx)
        CarCloudStore.boxForTest = null
    }

    private fun now() = System.currentTimeMillis()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    // ------------------------------------------------------------------ la lógica, pura

    @Test
    fun theStateDecidesTheButton() {
        val closed = CarOrders.LockButton(CarOrders.Lock.LOCKED, null)
        assertTrue(closed.single())
        assertEquals("unlock", closed.what())
        assertEquals(R.string.hql_cw_unlock, closed.label())
        // El icono dice cómo está (candado cerrado); la etiqueta, lo que hace.
        assertEquals(R.drawable.hql_ic_cw_lock, closed.icon())
        assertEquals(green, closed.color())
        assertEquals(0x2E4CFF9F, closed.tint())
        assertEquals(R.drawable.hql_w_car_locked, closed.widgetBackground())
        assertEquals(CarCommand.Kind.UNLOCK, CarCommand.fromButton(closed.what()).kind)
        val open = CarOrders.LockButton(CarOrders.Lock.UNLOCKED, null)
        assertTrue(open.single())
        assertEquals("lock", open.what())
        assertEquals(R.string.hql_cw_lock, open.label())
        assertEquals(R.drawable.hql_ic_cw_unlock, open.icon())
        assertEquals(red, open.color())
        assertEquals(0x2EFF6B7A, open.tint())
        assertEquals(R.drawable.hql_w_car_unlocked, open.widgetBackground())
        assertEquals(CarCommand.Kind.LOCK, CarCommand.fromButton(open.what()).kind)
        // Sin saberlo: los dos, y la foto del widget igual que antes de existir el botón único.
        assertFalse(CarOrders.LockButton.UNKNOWN.single())
        assertEquals("", CarOrders.LockButton.UNKNOWN.key())
        // El progreso: en el botón único, con cualquiera de las dos órdenes; con los dos, solo en el de la orden.
        assertTrue(CarOrders.LockButton(CarOrders.Lock.LOCKED, "unlock").busy("unlock"))
        val both = CarOrders.LockButton(CarOrders.Lock.UNKNOWN, "lock")
        assertTrue(both.busy("lock"))
        assertFalse(both.busy("unlock"))
        assertFalse(closed.busy("unlock"))
    }

    @Test
    fun theLastKnownLockIsKeptWhileSomeoneLooksAtTheCar() {
        val t = 1_800_000_000_000L
        assertEquals(CarOrders.Lock.UNKNOWN, CarOrders.lockState("", t))
        assertEquals(CarOrders.Lock.UNKNOWN, CarOrders.lockState(null, t))
        assertEquals(CarOrders.Lock.UNKNOWN, CarOrders.lockState("x,1,2", t))
        // El coche lleva aparcado y cerrado desde hace 3 h (el dato es viejo), pero se acaba de mirar: vale.
        val parked = CarOrders.lockNoted("", true, t - 3 * 3_600_000L, t)
        assertEquals(CarOrders.Lock.LOCKED, CarOrders.lockState(parked, t))
        assertEquals(CarOrders.Lock.LOCKED, CarOrders.lockState(parked, t + CarOrders.LOCK_KNOWN_MS))
        // Horas sin poder mirarlo: ya no se sabe (los dos botones).
        assertEquals(CarOrders.Lock.UNKNOWN, CarOrders.lockState(parked, t + CarOrders.LOCK_KNOWN_MS + 1))
        // Una orden confirmada ahora manda sobre la nube, que aún trae el dato de antes (abierto)…
        val ordered = CarOrders.lockNoted(CarOrders.lockNoted("", false, t - 60_000, t - 30_000), true, t, t)
        assertEquals(CarOrders.Lock.LOCKED, CarOrders.lockState(ordered, t))
        assertEquals(ordered, CarOrders.lockNoted(ordered, false, t - 60_000, t + 90_000))
        // …hasta que el coche sube un dato nuevo (lo han abierto con la llave).
        val later = CarOrders.lockNoted(ordered, false, t + 600_000, t + 620_000)
        assertEquals(CarOrders.Lock.UNLOCKED, CarOrders.lockState(later, t + 620_000))
        // Un dato viejo que dice lo mismo solo apunta que se ha vuelto a mirar (no caduca con el coche dormido).
        val again = CarOrders.lockNoted(ordered, true, t - 5_000, t + CarOrders.LOCK_KNOWN_MS)
        assertEquals(CarOrders.Lock.LOCKED, CarOrders.lockState(again, t + CarOrders.LOCK_KNOWN_MS + 60_000))
    }

    // ------------------------------------------------------------------ «Mi coche»

    private fun row(): Pair<Activity, PhoneCarOrders> {
        val act = Robolectric.buildActivity(Activity::class.java).setup().get()
        act.setTheme(R.style.Theme_HeadQLink)
        val orders = PhoneCarOrders(act)
        act.setContentView(orders.view())
        idle()
        return act to orders
    }

    /** Los botones de la fila: (lo que dice a la accesibilidad, etiqueta, color de la etiqueta, el círculo). */
    private class Button(val description: String, val label: String, val color: Int, val circle: ViewGroup) {
        val busy get() = circle.getChildAt(0) is ProgressBar
        val icon get() = circle.getChildAt(0) as ImageView
    }

    private fun buttons(orders: PhoneCarOrders): List<Button> {
        val r = orders.view() as ViewGroup
        return (0 until r.childCount).map {
            val cell = r.getChildAt(it) as ViewGroup
            val circle = cell.getChildAt(0) as ViewGroup
            val label = cell.getChildAt(1) as TextView
            Button(circle.contentDescription.toString(), label.text.toString(), label.currentTextColor, circle)
        }
    }

    private fun labels(orders: PhoneCarOrders) = buttons(orders).map { it.label }

    /** Toca un botón y devuelve la orden que le llega a la ventanita (CarControlActivity). */
    private fun tap(act: Activity, b: Button): String {
        assertTrue(b.circle.performClick())
        val i = shadowOf(act).nextStartedActivity
        assertEquals(CarControlActivity::class.java.name, i.component!!.className)
        return i.getStringExtra(CarControlActivity.EXTRA_WHAT)!!
    }

    @Test
    fun myCarLockedShowsOneGreenUnlockButton() {
        CarOrders.noteLock(ctx, true, now() - 3 * 3_600_000L)
        val (act, orders) = row()
        assertEquals(listOf("Abrir", "Maletero", "Clima", "Localizar"), labels(orders))
        val b = buttons(orders)[0]
        assertEquals("Coche cerrado. Abrir", b.description)
        assertEquals(green, b.color)
        assertEquals(green, b.icon.imageTintList!!.defaultColor)
        assertEquals(R.drawable.hql_ic_cw_lock, shadowOf(b.icon.drawable).createdFromResId)
        val card = b.circle as com.google.android.material.card.MaterialCardView
        assertEquals(green, card.strokeColor)
        assertEquals(0x2E4CFF9F, card.cardBackgroundColor.defaultColor)
        assertEquals("unlock", tap(act, b))
        // Los demás, sin color.
        assertEquals(PhoneCharts.DIM, buttons(orders)[3].color)
    }

    @Test
    fun myCarUnlockedShowsOneRedLockButton() {
        CarOrders.noteLock(ctx, false, now())
        val (act, orders) = row()
        assertEquals(listOf("Cerrar", "Maletero", "Clima", "Localizar"), labels(orders))
        val b = buttons(orders)[0]
        assertEquals("Coche abierto. Cerrar", b.description)
        assertEquals(red, b.color)
        assertEquals(red, b.icon.imageTintList!!.defaultColor)
        assertEquals(R.drawable.hql_ic_cw_unlock, shadowOf(b.icon.drawable).createdFromResId)
        assertEquals(red, (b.circle as com.google.android.material.card.MaterialCardView).strokeColor)
        assertEquals("lock", tap(act, b))
    }

    @Test
    fun myCarWithoutTheLockStateShowsBothButtonsAsBefore() {
        val (act, orders) = row()
        assertEquals(listOf("Abrir", "Cerrar", "Maletero", "Clima", "Localizar"), labels(orders))
        val b = buttons(orders)
        assertEquals(PhoneCharts.DIM, b[0].color)
        assertEquals(PhoneCharts.DIM, b[1].color)
        assertEquals(PhoneCharts.TEXT, b[0].icon.imageTintList!!.defaultColor)
        assertEquals("unlock", tap(act, b[0]))
        assertEquals("lock", tap(act, b[1]))
        // Con la sesión de Leapmotor cerrada, también los dos, aunque se supiera el cierre.
        CarOrders.noteLock(ctx, true, now())
        orders.refresh()
        assertEquals(4, labels(orders).size)
        CarCloudStore(ctx).markRelogin("renovación rechazada (17)")
        orders.refresh()
        assertEquals(5, labels(orders).size)
        // Y sin las órdenes activadas (la fila apagada).
        Config(ctx).putBool(CarOrders.K_ENABLED, false)
        orders.refresh()
        assertEquals(5, labels(orders).size)
        assertEquals(PhoneCharts.FAINT, buttons(orders)[0].color)
    }

    @Test
    fun aConfirmedOrderFlipsTheButtonWithoutWaitingForTheCloud() {
        CarOrders.noteLock(ctx, true, now() - 60_000)
        val (act, orders) = row()
        val what = tap(act, buttons(orders)[0])
        // Mientras el coche contesta, el botón enseña el progreso y no se puede tocar otra vez.
        car.answer = {
            idle()
            val b = buttons(orders)[0]
            assertTrue(b.busy)
            assertEquals("Abrir", b.label)
            assertFalse(b.circle.isClickable)
            true
        }
        assertTrue(CarOrders.send(ctx, CarCommand.fromButton(what)))
        idle()
        assertEquals(listOf(CarCommand.Kind.UNLOCK), car.sent)
        val b = buttons(orders)[0]
        assertFalse(b.busy)
        assertEquals("Cerrar", b.label)
        assertEquals(red, b.color)
        assertEquals("Coche abierto. Cerrar", b.description)
        // Y el texto de arriba de «Mi coche» dice lo mismo, aunque la foto de la nube siga con lo de antes.
        assertEquals(false, CarOrders.locked(ctx, true))
        // Otra vez: cerrar.
        car.answer = { true }
        assertTrue(CarOrders.send(ctx, CarCommand.fromButton(tap(act, b))))
        idle()
        assertEquals(listOf(CarCommand.Kind.UNLOCK, CarCommand.Kind.LOCK), car.sent)
        assertEquals("Abrir", buttons(orders)[0].label)
        assertEquals(green, buttons(orders)[0].color)
    }

    @Test
    fun aFailedOrderGoesBackToWhatItWas() {
        CarOrders.noteLock(ctx, false, now() - 60_000)
        val (act, orders) = row()
        val what = tap(act, buttons(orders)[0])
        car.answer = {
            idle()
            assertTrue(buttons(orders)[0].busy)
            throw java.io.IOException("sin red")
        }
        try {
            CarOrders.send(ctx, CarCommand.fromButton(what))
            fail("el fallo llega a la ventanita, que lo enseña como siempre")
        } catch (e: java.io.IOException) {
            assertEquals("sin red", e.message)
        }
        idle()
        val b = buttons(orders)[0]
        assertFalse(b.busy)
        assertEquals("Cerrar", b.label)
        assertEquals(red, b.color)
        assertTrue(b.circle.isClickable)
        assertNull(pending)
    }

    @Test
    fun anUnconfirmedOrderIsCheckedWithAnotherReading() {
        CarOrders.noteLock(ctx, false, now() - 60_000)
        val (act, orders) = row()
        car.answer = { false }
        // Sin confirmar: la ventanita dice «enviado» como siempre y el botón sigue con el progreso hasta mirar el coche.
        assertFalse(CarOrders.send(ctx, CarCommand.fromButton(tap(act, buttons(orders)[0]))))
        idle()
        assertTrue(buttons(orders)[0].busy)
        assertEquals("Cerrar", buttons(orders)[0].label)
        assertEquals(0, car.reads)
        // La lectura dice que quedó cerrado: el botón cambia.
        car.locked = true
        pending!!.run()
        idle()
        assertEquals(1, car.reads)
        assertFalse(buttons(orders)[0].busy)
        assertEquals("Abrir", buttons(orders)[0].label)
        // Otra sin confirmar y el coche sigue igual (o no lo dice): el botón vuelve a como estaba.
        assertFalse(CarOrders.send(ctx, CarCommand.fromButton(tap(act, buttons(orders)[0]))))
        car.locked = null
        pending!!.run()
        idle()
        assertFalse(buttons(orders)[0].busy)
        assertEquals("Abrir", buttons(orders)[0].label)
        assertEquals(green, buttons(orders)[0].color)
    }

    @Test
    fun otherOrdersDoNotTouchTheButton() {
        CarOrders.noteLock(ctx, true, now())
        val (_, orders) = row()
        car.answer = {
            idle()
            assertFalse(buttons(orders)[0].busy)
            true
        }
        assertTrue(CarOrders.send(ctx, CarCommand(CarCommand.Kind.TRUNK_OPEN)))
        idle()
        assertEquals("Abrir", buttons(orders)[0].label)
    }

    @Test
    fun theCloudReadingSetsTheButtonAndTheDemoDoesNot() {
        val (_, orders) = row()
        fun snap(locked: Int, demo: Boolean) = CarCloud.Snapshot(CarCloud.State.OK,
            LeapStatus.parse(org.json.JSONObject("""{"signal":{"1298":$locked}}""")), now(), 0, 60.0, "C10", 0, demo)
        CarOrders.observeLock(ctx, snap(1, true))
        idle()
        assertEquals(5, labels(orders).size)
        CarOrders.observeLock(ctx, snap(1, false))
        idle()
        assertEquals(listOf("Abrir", "Maletero", "Clima", "Localizar"), labels(orders))
        CarOrders.observeLock(ctx, snap(0, false))
        idle()
        assertEquals("Cerrar", labels(orders)[0])
        // Sin dato de cierre en la lectura: se queda lo último que se supo.
        CarOrders.observeLock(ctx, CarCloud.Snapshot(CarCloud.State.OK, LeapStatus.parse(org.json.JSONObject("""{"signal":{"1204":70}}""")),
            now(), 0, 60.0, "C10", 0, false))
        idle()
        assertEquals("Cerrar", labels(orders)[0])
        // Otra cuenta u otro coche: se olvida.
        CarOrders.forgetLock(ctx)
        orders.refresh()
        assertEquals(5, labels(orders).size)
    }

    // ------------------------------------------------------------------ el widget

    /** El widget 4x3 de verdad (RemoteViews aplicadas como el launcher), con la foto de ahora. */
    private fun widget(): View =
        LinkWidgetViews.build(ctx, WidgetUpdater.glance(ctx), LinkGlance.Size.WIDE_CAR).apply(ctx, FrameLayout(ctx))

    private fun text(v: View, id: Int) = v.findViewById<TextView>(id).text.toString()

    private fun visible(v: View, id: Int) = v.findViewById<View>(id).visibility == View.VISIBLE

    /** Toca un botón del widget y devuelve la orden de su PendingIntent (la que llegaría a CarControlActivity). */
    private fun tapWidget(v: View, id: Int): String {
        assertTrue(v.findViewById<View>(id).performClick())
        idle()
        val i = shadowOf(ctx).nextStartedActivity
        assertNotNull("el botón no abre nada", i)
        assertEquals(CarControlActivity::class.java.name, i.component!!.className)
        return i.getStringExtra(CarControlActivity.EXTRA_WHAT)!!
    }

    private fun background(v: View, id: Int) = shadowOf(v.findViewById<View>(id).background).createdFromResId

    @Test
    fun theWidgetLockedShowsOneGreenUnlockButton() {
        CarOrders.noteLock(ctx, true, now() - 3 * 3_600_000L)
        val v = widget()
        assertFalse(visible(v, R.id.hql_w_car_unlock))
        assertTrue(visible(v, R.id.hql_w_car_lock))
        assertEquals("Abrir", text(v, R.id.hql_w_car_lock_label))
        assertEquals(green, v.findViewById<TextView>(R.id.hql_w_car_lock_label).currentTextColor)
        assertEquals(R.drawable.hql_w_car_locked, background(v, R.id.hql_w_car_lock))
        assertEquals(R.drawable.hql_ic_cw_lock, shadowOf(v.findViewById<ImageView>(R.id.hql_w_car_lock_icon).drawable).createdFromResId)
        assertEquals("Coche cerrado. Abrir", v.findViewById<View>(R.id.hql_w_car_lock).contentDescription)
        assertTrue(visible(v, R.id.hql_w_car_lock_icon))
        assertFalse(visible(v, R.id.hql_w_car_lock_busy))
        assertEquals("unlock", tapWidget(v, R.id.hql_w_car_lock))
    }

    @Test
    fun theWidgetUnlockedShowsOneRedLockButton() {
        CarOrders.noteLock(ctx, false, now())
        val v = widget()
        assertFalse(visible(v, R.id.hql_w_car_unlock))
        assertEquals("Cerrar", text(v, R.id.hql_w_car_lock_label))
        assertEquals(red, v.findViewById<TextView>(R.id.hql_w_car_lock_label).currentTextColor)
        assertEquals(R.drawable.hql_w_car_unlocked, background(v, R.id.hql_w_car_lock))
        assertEquals(R.drawable.hql_ic_cw_unlock, shadowOf(v.findViewById<ImageView>(R.id.hql_w_car_lock_icon).drawable).createdFromResId)
        assertEquals("Coche abierto. Cerrar", v.findViewById<View>(R.id.hql_w_car_lock).contentDescription)
        assertEquals("lock", tapWidget(v, R.id.hql_w_car_lock))
    }

    @Test
    fun theWidgetWithoutTheLockStateShowsBothButtonsAsBefore() {
        val v = widget()
        assertTrue(visible(v, R.id.hql_w_car_lock))
        assertTrue(visible(v, R.id.hql_w_car_unlock))
        assertEquals("Cerrar", text(v, R.id.hql_w_car_lock_label))
        assertEquals(ctx.getColor(R.color.hql_text), v.findViewById<TextView>(R.id.hql_w_car_lock_label).currentTextColor)
        assertEquals(R.drawable.hql_w_seg_off, background(v, R.id.hql_w_car_lock))
        assertEquals("lock", tapWidget(v, R.id.hql_w_car_lock))
        assertEquals("unlock", tapWidget(v, R.id.hql_w_car_unlock))
        // La foto del widget, igual que antes de existir el botón único (no se repinta de más).
        assertFalse(WidgetUpdater.glance(ctx).stateKey().contains("LOCKED"))
    }

    @Test
    fun theWidgetFollowsTheOrder() {
        CarOrders.noteLock(ctx, true, now() - 60_000)
        val before = WidgetUpdater.glance(ctx).stateKey()
        // Mientras se manda: el progreso en lugar del icono, en el mismo botón «Abrir» verde.
        car.answer = {
            val v = widget()
            assertTrue(visible(v, R.id.hql_w_car_lock_busy))
            assertFalse(visible(v, R.id.hql_w_car_lock_icon))
            assertEquals("Abrir", text(v, R.id.hql_w_car_lock_label))
            assertEquals(R.drawable.hql_w_car_locked, background(v, R.id.hql_w_car_lock))
            true
        }
        assertTrue(CarOrders.send(ctx, CarCommand.fromButton(tapWidget(widget(), R.id.hql_w_car_lock))))
        assertEquals(listOf(CarCommand.Kind.UNLOCK), car.sent)
        // Confirmada: «Cerrar» rojo, y la foto del widget ha cambiado (se repinta).
        var v = widget()
        assertEquals("Cerrar", text(v, R.id.hql_w_car_lock_label))
        assertEquals(R.drawable.hql_w_car_unlocked, background(v, R.id.hql_w_car_lock))
        assertFalse(visible(v, R.id.hql_w_car_lock_busy))
        assertFalse(before == WidgetUpdater.glance(ctx).stateKey())
        // Una que falla: vuelve a como estaba.
        car.answer = { throw java.io.IOException("sin red") }
        try {
            CarOrders.send(ctx, CarCommand.fromButton(tapWidget(v, R.id.hql_w_car_lock)))
            fail("el fallo llega a la ventanita")
        } catch (e: java.io.IOException) {
        }
        v = widget()
        assertEquals("Cerrar", text(v, R.id.hql_w_car_lock_label))
        assertEquals(R.drawable.hql_w_car_unlocked, background(v, R.id.hql_w_car_lock))
        assertFalse(visible(v, R.id.hql_w_car_lock_busy))
        assertTrue(visible(v, R.id.hql_w_car_lock_icon))
    }

    @Test
    fun withBothButtonsOnlyTheOneSentShowsProgress() {
        car.answer = {
            val v = widget()
            assertTrue(visible(v, R.id.hql_w_car_unlock_busy))
            assertFalse(visible(v, R.id.hql_w_car_unlock_icon))
            assertFalse(visible(v, R.id.hql_w_car_lock_busy))
            assertTrue(visible(v, R.id.hql_w_car_lock_icon))
            true
        }
        assertTrue(CarOrders.send(ctx, CarCommand(CarCommand.Kind.UNLOCK)))
        // Confirmada: ya se sabe cómo está, un solo botón.
        val v = widget()
        assertFalse(visible(v, R.id.hql_w_car_unlock))
        assertEquals("Cerrar", text(v, R.id.hql_w_car_lock_label))
    }

    @Test
    fun aStaleLockIsDroppedByTheBackgroundCheck() {
        CarOrders.noteLock(ctx, true, now())
        CarOrders.expireLock(ctx)
        assertTrue(CarOrders.lockButton(ctx).single())
        // Guardado hace más de LOCK_KNOWN_MS sin volver a mirar el coche.
        val old = now() - CarOrders.LOCK_KNOWN_MS - 60_000
        ctx.getSharedPreferences("car_lock", 0).edit().putString("known", "1,$old,$old").commit()
        assertFalse(CarOrders.lockButton(ctx).single())
        CarOrders.expireLock(ctx)
        assertNull(ctx.getSharedPreferences("car_lock", 0).getString("known", null))
    }
}
