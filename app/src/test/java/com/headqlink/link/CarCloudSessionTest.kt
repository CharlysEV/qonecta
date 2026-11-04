package com.headqlink.link

import android.app.AlarmManager
import android.app.Application
import android.widget.FrameLayout
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * La sesión de Leapmotor de punta a punta, con una nube de mentira y una clave en memoria en lugar del Keystore (4-11,
 * LPMFF: el widget daba error al abrir y la cuenta salía cerrada): el token se renueva antes de caducar (y queda un
 * despertador para la siguiente), un rechazo de la nube cierra la sesión una sola vez y sin bucles, un fallo del servidor
 * no la cierra, lo guardado que no se puede abrir pide volver a entrar sin borrar nada, y el widget lo dice claro.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class, qualifiers = "es-rES")
class CarCloudSessionTest {
    private val ctx: Application = RuntimeEnvironment.getApplication()

    /** AES-GCM con una clave en memoria: hace de Keystore en el PC. */
    private class MemBox : CarCloudStore.Box {
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

    /** La nube de mentira: login, coches y estado bien; la renovación, lo que diga cada prueba. */
    private class Cloud : LeapApi.Transport {
        val paths = ArrayList<String>()
        var loginToken = ""
        var refresh: () -> LeapApi.Response = { LeapApi.Response(500, "") }

        override fun post(identity: LeapTls.Identity, path: String, headers: Map<String, String>, body: String): LeapApi.Response {
            paths.add(path)
            return when {
                path == LeapApi.PATH_LOGIN -> ok(loginData(loginToken))
                path == LeapApi.PATH_REFRESH -> refresh()
                path == LeapApi.PATH_VEHICLES -> ok("""{"bindcars":[{"vin":"TESTVIN0000000001","carType":"C10"}]}""")
                else -> ok("{}")
            }
        }

        fun count(path: String) = paths.count { it == path }
    }

    private lateinit var cloud: Cloud

    @Before
    fun setUp() {
        Str.init(ctx)
        L.init(ctx)
        CarCloudStore.boxForTest = MemBox()
        cloud = Cloud()
        CarCloudSession.setTransportForTest(cloud)
        CarCloudStore(ctx).saveIdentity(LeapTls.Identity(TestCerts.ec.private, arrayOf(TestCerts.selfSigned(TestCerts.ec, "cliente"))))
    }

    @After
    fun tearDown() {
        CarCloudSession.setTransportForTest(null)
        CarCloudStore.boxForTest = null
    }

    /** Entra con un token que caduca dentro de expInMs (negativo: ya caducado) y deja la nube sin peticiones apuntadas. */
    private fun signIn(expInMs: Long) {
        cloud.loginToken = jwt(System.currentTimeMillis() + expInMs)
        CarCloudSession.login(ctx, "demo@example.com", "p@ss w0rd")
        cloud.paths.clear()
    }

    private fun store() = CarCloudStore(ctx)

    private fun alarms() = shadowOf(ctx.getSystemService(AlarmManager::class.java))

    @Test
    fun theTokenIsRenewedBeforeItExpiresAndTheNextRenewalHasAnAlarm() {
        signIn(50 * 60_000L)
        val token2 = jwt(System.currentTimeMillis() + 2 * 3_600_000L)
        cloud.refresh = { ok("""{"token":"$token2","refreshToken":"rt-2"}""") }
        // Le queda menos de una hora: la comprobación de fondo lo renueva sin esperar a que la nube lo rechace.
        CarCloudSession.keepAlive(ctx)
        assertEquals(1, cloud.count(LeapApi.PATH_REFRESH))
        assertEquals(token2, store().session()!!.session.token)
        // El despertador, para cuando al nuevo le quede una hora (no antes: ninguna renovación de más).
        val at = alarms().nextScheduledAlarm!!.triggerAtMs
        val want = LeapCrypto.tokenExpiryMs(token2) - CarCloudSession.REFRESH_AHEAD_MS + 60_000
        assertEquals(want.toDouble(), at.toDouble(), 2_000.0)
        // Con el token nuevo no toca: ni la comprobación ni una lectura lo renuevan otra vez.
        CarCloudSession.keepAlive(ctx)
        CarCloudSession.vehicles(ctx)
        assertEquals(1, cloud.count(LeapApi.PATH_REFRESH))
        assertNull(store().reloginReason())
    }

    @Test
    fun aRejectedRenewalClosesTheSessionOnceAndNeverLoops() {
        signIn(-3 * 3_600_000L)
        cloud.refresh = { LeapApi.Response(200, """{"code":17,"message":"refresh token invalid"}""") }
        CarCloudSession.keepAlive(ctx)
        assertEquals(1, cloud.count(LeapApi.PATH_REFRESH))
        val why = store().reloginReason()
        assertNotNull(why)
        assertTrue(why, why!!.contains("17"))
        // La sesión guardada no se borra (el correo sigue para volver a entrar) y el despertador se quita.
        assertTrue(store().hasSession())
        assertEquals("demo@example.com", CarCloudSession.email(ctx))
        assertNull(alarms().nextScheduledAlarm)
        // Ni la comprobación de fondo, ni una orden del widget, ni una lectura vuelven a pedir nada a la nube.
        repeat(3) { CarCloudSession.keepAlive(ctx) }
        store().savePin("1234")
        try {
            CarCloudSession.command(ctx, CarCommand(CarCommand.Kind.UNLOCK))
            fail("con la sesión cerrada no se manda la orden")
        } catch (e: LeapApi.SessionExpiredException) {
        }
        try {
            CarCloudSession.readStatus(ctx)
            fail("ni se lee el estado")
        } catch (e: LeapApi.SessionExpiredException) {
        }
        assertEquals(emptyList<String>(), cloud.paths.filter { it != LeapApi.PATH_REFRESH })
        assertEquals(1, cloud.count(LeapApi.PATH_REFRESH))
        // Volver a entrar lo arregla todo.
        signIn(2 * 3_600_000L)
        assertNull(store().reloginReason())
        CarCloudSession.vehicles(ctx)
        assertEquals(listOf(LeapApi.PATH_VEHICLES), cloud.paths)
    }

    @Test
    fun aRejectedRenewalWithTheTokenStillValidKeepsItAndWaits() {
        signIn(30 * 60_000L)
        cloud.refresh = { LeapApi.Response(200, """{"code":17,"message":"refresh token invalid"}""") }
        CarCloudSession.keepAlive(ctx)
        // El token vale aún media hora: se sigue con él, sin cerrar la sesión ni reintentar en cada lectura (cada 90 s).
        assertNull(store().reloginReason())
        repeat(5) { CarCloudSession.vehicles(ctx) }
        assertEquals(1, cloud.count(LeapApi.PATH_REFRESH))
        assertEquals(5, cloud.count(LeapApi.PATH_VEHICLES))
        // Y el despertador, para dentro de 15 min (no antes).
        val at = alarms().nextScheduledAlarm!!.triggerAtMs
        assertTrue(at >= System.currentTimeMillis() + CarCloudSession.REFRESH_RETRY_MS - 5_000)
    }

    @Test
    fun aServerFailureOnRenewalIsNotARejection() {
        signIn(-10 * 60_000L)
        // Un 502 con una página de mantenimiento: antes se tomaba como «sesión caducada».
        cloud.refresh = { LeapApi.Response(502, "<html>Bad gateway</html>") }
        CarCloudSession.keepAlive(ctx)
        assertEquals(1, cloud.count(LeapApi.PATH_REFRESH))
        assertNull(store().reloginReason())
        // Sin bucle: la siguiente comprobación no vuelve a pedirlo antes de 15 min.
        CarCloudSession.keepAlive(ctx)
        assertEquals(1, cloud.count(LeapApi.PATH_REFRESH))
    }

    @Test
    fun anUnreadableSessionAsksToSignInAgainWithoutCrashingOrDeleting() {
        signIn(2 * 3_600_000L)
        // Otra clave en el «Keystore» (la de antes ya no abre lo guardado), como tras restaurar o actualizar el móvil.
        CarCloudStore.boxForTest = MemBox()
        CarCloudSession.setTransportForTest(cloud)
        try {
            CarCloudSession.readStatus(ctx)
            fail("con la sesión ilegible hay que volver a entrar")
        } catch (e: LeapApi.SessionExpiredException) {
            assertTrue(e.message, e.message!!.contains("ilegible"))
        }
        assertTrue(store().reloginReason()!!.contains("AEADBadTagException"))
        assertTrue(store().hasSession())
        assertEquals(emptyList<String>(), cloud.paths)
        // La comprobación de fondo tampoco revienta.
        CarCloudSession.keepAlive(ctx)
    }

    @Test
    fun aKeystoreHiccupIsRetriedNotTakenAsLost() {
        signIn(2 * 3_600_000L)
        val good = CarCloudStore.boxForTest!!
        CarCloudStore.boxForTest = object : CarCloudStore.Box {
            override fun seal(plain: ByteArray) = good.seal(plain)
            override fun open(sealed: ByteArray): ByteArray = throw java.security.KeyStoreException("ocupado")
            override fun destroy() {}
        }
        CarCloudSession.setTransportForTest(cloud)
        try {
            CarCloudSession.vehicles(ctx)
            fail("sin Keystore no hay sesión ahora")
        } catch (e: LeapApi.SessionExpiredException) {
            fail("un fallo pasajero del Keystore no cierra la sesión")
        } catch (e: java.io.IOException) {
        }
        assertNull(store().reloginReason())
        CarCloudStore.boxForTest = good
        assertEquals(1, CarCloudSession.vehicles(ctx).size)
    }

    @Test
    fun theWidgetSaysClearlyThatTheSessionIsClosed() {
        Config(ctx).putBool(CarOrders.K_ENABLED, true)
        store().savePin("1234")
        val closed = "Sesión de Leapmotor cerrada: abre Qonecta+ y vuelve a entrar"
        fun status(): String = LinkWidgetViews.build(ctx, WidgetShots.glance(ctx, "apagado"), LinkGlance.Size.WIDE_CAR)
            .apply(ctx, FrameLayout(ctx)).findViewById<TextView>(R.id.hql_w_car_status).text.toString()
        assertEquals("Coche · Listo", status())
        store().markRelogin("renovación rechazada (17)")
        assertEquals("Coche · $closed", status())
        // Y una orden que falla por eso dice qué hacer, no «SessionExpiredException: …».
        val text = CarControlActivity.failureText(CarCommand(CarCommand.Kind.UNLOCK), LeapApi.SessionExpiredException("x"))
        assertTrue(text, text.endsWith(closed))
        assertFalse(text, text.contains("Exception"))
    }

    @Test
    fun theAlarmNeverFiresInALoop() {
        val now = 1_791_297_600_000L
        // Cuando quede una hora (y un minuto de margen)…
        assertEquals(now + 3_600_000L + 60_000, CarCloudSession.alarmAtMs(now + 2 * 3_600_000L, now))
        // …pero nunca antes de 15 min, aunque el token ya tocara o hubiera caducado.
        assertEquals(now + CarCloudSession.REFRESH_RETRY_MS, CarCloudSession.alarmAtMs(now + 10 * 60_000L, now))
        assertEquals(now + CarCloudSession.REFRESH_RETRY_MS, CarCloudSession.alarmAtMs(now - 3_600_000L, now))
        assertEquals(0, CarCloudSession.alarmAtMs(0, now))
    }

    companion object {
        private fun ok(data: String) = LeapApi.Response(200, """{"code":0,"message":"ok","data":$data}""")

        /** Un token como los de la nube: el deviceId en user_name y la caducidad en exp (segundos). */
        private fun jwt(expMs: Long) = "h." + Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"user_name":"a,b,dev-xyz,d","exp":${expMs / 1000}}""".toByteArray()) + ".s"

        private fun loginData(token: String): String {
            val pw = LeapCrypto.accountP12Password("1234567", "u-ABCDEFGH-0001").toCharArray()
            val p12 = Base64.getEncoder().encodeToString(
                TestCerts.pkcs12(TestCerts.rsa.private, arrayOf(TestCerts.selfSigned(TestCerts.rsa, "cuenta")), pw))
            return """{"id":1234567,"token":"$token","refreshToken":"rt-1","signIkm":"ikm-demo","signSalt":"salt-demo",
                |"signInfo":"info-demo","uid":"u-ABCDEFGH-0001","base64Cert":"$p12"}""".trimMargin()
        }
    }
}
