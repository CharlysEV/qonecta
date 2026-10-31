package com.headqlink.link

import android.app.Application
import android.content.Context
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Sondeo de la nube (cuándo lee y la espera tras los errores), la foto que ven las pantallas (edad del dato) y el
 * almacén cifrado (con una clave AES-GCM en memoria en lugar de la del Android Keystore).
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class CarCloudTest {
    private fun identity(kp: java.security.KeyPair, cn: String) = LeapTls.Identity(kp.private, arrayOf(TestCerts.selfSigned(kp, cn)))

    // ------------------------------------------------------------------ cuándo lee

    @Test
    fun pollsEvery2MinOr90sWithTheHubOnScreen() {
        assertEquals(120_000L, CarCloud.Policy.delayMs(0, false))
        assertEquals(90_000L, CarCloud.Policy.delayMs(0, true))
    }

    @Test
    fun watchingAChargeReadsEvery90sFastOr5MinSlow() {
        val p = CarCloud.Policy.HUB_MS
        val ac = CarCloud.Policy.WATCH_AC_MS
        // Rápida: como con la sección Coche a la vista.
        assertEquals(90_000L, CarCloud.Policy.delayMs(0, false, 0, false, p, true))
        // Lenta: cada 5 min con la app cerrada; si alguien más lee, a su ritmo.
        assertEquals(300_000L, CarCloud.Policy.delayMs(0, false, 0, false, ac, true))
        assertEquals(120_000L, CarCloud.Policy.delayMs(0, false, 0, false, ac, false))
        assertEquals(90_000L, CarCloud.Policy.delayMs(0, true, 0, false, ac, true))
        // Sin vigilar, lo de siempre; los errores, igual.
        assertEquals(120_000L, CarCloud.Policy.delayMs(0, false, 0, false, 0, false))
        assertEquals(300_000L, CarCloud.Policy.delayMs(2, false, 0, false, ac, true))
    }

    @Test
    fun spacesOutWhenTheCarStopsUploading() {
        // Una lectura sin dato nuevo no cambia nada; desde la segunda, 5 min y luego 15 min (aunque se vea la sección).
        assertEquals(90_000L, CarCloud.Policy.delayMs(0, true, 1))
        assertEquals(300_000L, CarCloud.Policy.delayMs(0, true, 2))
        assertEquals(900_000L, CarCloud.Policy.delayMs(0, false, 3))
        assertEquals("no pasa de 15 min", 900_000L, CarCloud.Policy.delayMs(0, false, 20))
        // Los errores mandan sobre el coche dormido.
        assertEquals(120_000L, CarCloud.Policy.delayMs(1, false, 5))
    }

    @Test
    fun backsOffTwoFiveAndTenMinutesOnErrors() {
        assertEquals(120_000L, CarCloud.Policy.delayMs(1, false))
        assertEquals(300_000L, CarCloud.Policy.delayMs(2, false))
        assertEquals(600_000L, CarCloud.Policy.delayMs(3, false))
        assertEquals("no pasa de 10 min", 600_000L, CarCloud.Policy.delayMs(12, false))
        // Con errores, la sección Coche en pantalla no acelera los reintentos.
        assertEquals(120_000L, CarCloud.Policy.delayMs(1, true))
    }

    // ------------------------------------------------------------------ la foto

    private fun status(json: String) = LeapStatus.parse(JSONObject(json))

    @Test
    fun theAgeIsThatOfTheCarDataWhenItHasIt() {
        val now = 1_800_000_000_000L
        val withTime = CarCloud.Snapshot(CarCloud.State.OK, status("""{"collectTime":${now - 40_000},"signal":{"1204":70}}"""),
            now - 5_000, 300, 69.9, "C10", 0, false)
        assertEquals(40_000L, withTime.ageMs(now))
        assertEquals(70.0, withTime.soc(now, CarCloud.SOC_MAX_AGE_MS), 0.0)
        // Sin hora del coche: la de la lectura.
        val noTime = CarCloud.Snapshot(CarCloud.State.OK, status("""{"signal":{"1204":70}}"""), now - 5_000, 300, 69.9, "C10", 0, false)
        assertEquals(5_000L, noTime.ageMs(now))
        // Demasiado viejo para la Ruta: NaN (se vuelve al % indicado a mano).
        val old = CarCloud.Snapshot(CarCloud.State.OK, status("""{"collectTime":${now - 7 * 3600_000L},"signal":{"1204":70}}"""),
            now, 300, 69.9, "C10", 0, false)
        assertTrue(old.soc(now, CarCloud.SOC_MAX_AGE_MS).isNaN())
        // Un reloj del coche adelantado no da edades negativas.
        val future = CarCloud.Snapshot(CarCloud.State.OK, status("""{"collectTime":${now + 60_000},"signal":{"1204":70}}"""),
            now, 300, 69.9, "C10", 0, false)
        assertEquals(0L, future.ageMs(now))
    }

    @Test
    fun anErrorKeepsTheDataItHad() {
        val now = 1_800_000_000_000L
        val ok = CarCloud.Snapshot(CarCloud.State.OK, status("""{"signal":{"1204":70}}"""), now, 300, 81.9, "C10", 0, false)
        val err = ok.with(CarCloud.State.ERROR, now + 120_000)
        assertEquals(CarCloud.State.ERROR, err.state)
        assertTrue(err.hasData())
        assertEquals(81.9, err.capacityKwh, 0.0)
        assertEquals(now + 120_000, err.nextTryMs)
        assertFalse(CarCloud.Snapshot.of(CarCloud.State.NO_ACCOUNT).hasData())
    }

    // ------------------------------------------------------------------ almacén cifrado

    /** AES-GCM con una clave en memoria: hace de Keystore en el PC. */
    private class MemBox : CarCloudStore.Box {
        private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        var destroyed = false

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

        override fun destroy() {
            destroyed = true
        }
    }

    private fun store(dir: File, box: CarCloudStore.Box): CarCloudStore {
        val ctx: Context = RuntimeEnvironment.getApplication()
        return CarCloudStore(dir, ctx.getSharedPreferences("carcloud_test_" + dir.name, Context.MODE_PRIVATE), box)
    }

    @Test
    fun secretsAreStoredEncryptedAndCanBeWiped() {
        Str.init(RuntimeEnvironment.getApplication())
        val dir = Files.createTempDirectory("carcloud").toFile()
        val box = MemBox()
        val st = store(dir, box)
        assertFalse(st.hasIdentity())
        assertNull(st.identity())
        val id = identity(TestCerts.ec, "guardado")
        st.saveIdentity(id)
        val back = st.identity()!!
        assertArrayEquals(id.key.encoded, back.key.encoded)
        val saved = CarCloudStore.Saved().apply {
            session = LeapApi.Session().apply {
                userId = "1234567"; token = "token-secreto-123"; refreshToken = "rt-secreto"; base64Cert = "AAAA"; signIkm = "ikm"
            }
            vin = "TESTVIN0000000001"
            carType = "C10"
            email = "carla.ejemplo@example.com"
        }
        st.saveSession(saved)
        val s2 = st.session()!!
        assertEquals("token-secreto-123", s2.session.token)
        assertEquals("TESTVIN0000000001", s2.vin)
        // En disco no queda nada en claro: ni el token, ni el VIN, ni el correo, ni la clave.
        for (f in dir.listFiles()!!) {
            val text = String(f.readBytes(), Charsets.ISO_8859_1)
            for (secret in listOf("token-secreto", "rt-secreto", "TESTVIN", "carla.ejemplo", "PRIVATE"))
                assertFalse("$secret en ${f.name}", text.contains(secret))
        }
        // En claro solo lo que no es secreto: el modelo y el correo enmascarado.
        assertEquals("C10", st.carType())
        assertEquals("c***@e***.com", st.maskedEmail())
        // Cerrar sesión deja el certificado (y el deviceId, para volver a entrar como el mismo móvil); borrar datos, nada.
        st.rememberDeviceId("dev-abc")
        st.deleteSession()
        assertFalse(st.hasSession())
        assertTrue(st.hasIdentity())
        assertEquals("dev-abc", st.lastDeviceId())
        st.wipeAll()
        assertFalse(st.hasIdentity())
        assertTrue(box.destroyed)
        assertEquals("", st.maskedEmail())
        assertEquals(null, st.lastDeviceId())
        dir.deleteRecursively()
    }

    @Test
    fun theTokenIsRenewedBeforeItExpires() {
        val now = 1_761_400_000_000L
        // Le queda una hora o menos (o ya caducó): se renueva; más, o sin saberlo, no se toca.
        assertTrue(CarCloudSession.refreshDue(now + 30 * 60_000L, now))
        assertTrue(CarCloudSession.refreshDue(now + CarCloudSession.REFRESH_AHEAD_MS, now))
        assertTrue(CarCloudSession.refreshDue(now - 1, now))
        assertFalse(CarCloudSession.refreshDue(now + 2 * 3_600_000L, now))
        assertFalse(CarCloudSession.refreshDue(0, now))
    }

    @Test
    fun batteryProfiles() {
        assertEquals(69.9, CarCloudStore.capacityFor("", 0.0), 0.0)
        assertEquals(69.9, CarCloudStore.capacityFor(CarCloudStore.PROFILE_C10_LIFE, 0.0), 0.0)
        assertEquals(81.9, CarCloudStore.capacityFor(CarCloudStore.PROFILE_C10_PROMAX, 0.0), 0.0)
        assertEquals(67.1, CarCloudStore.capacityFor(CarCloudStore.PROFILE_CUSTOM, 67.1), 1e-9)
        assertEquals(69.9, CarCloudStore.capacityFor(CarCloudStore.PROFILE_CUSTOM, 0.0), 0.0)
        assertEquals(200.0, CarCloudStore.clampKwh(900.0), 0.0)
        val dir = Files.createTempDirectory("carcloud").toFile()
        val st = store(dir, MemBox())
        st.setProfile(CarCloudStore.PROFILE_CUSTOM, 75.5)
        assertEquals(75.5, st.capacityKwh(), 1e-4)
        st.setProfile(CarCloudStore.PROFILE_C10_PROMAX, 0.0)
        assertEquals(81.9, st.capacityKwh(), 0.0)
        dir.deleteRecursively()
    }

    @Test
    fun reevProfileIsAdoptedWhenTheCarHasATank() {
        assertEquals(28.4, CarCloudStore.capacityFor(CarCloudStore.PROFILE_C10_REEV, 0.0), 0.0)
        val dir = Files.createTempDirectory("carcloud").toFile()
        val st = store(dir, MemBox())
        // Sin elegir (o con una batería de eléctrico puro): pasa al REEV, una vez.
        st.setProfile(CarCloudStore.PROFILE_C10_PROMAX, 0.0)
        assertTrue(st.adoptReev())
        assertEquals(CarCloudStore.PROFILE_C10_REEV, st.profile())
        assertEquals(28.4, st.capacityKwh(), 0.0)
        assertFalse(st.adoptReev())
        // «Otra» a mano se respeta.
        st.setProfile(CarCloudStore.PROFILE_CUSTOM, 30.0)
        assertFalse(st.adoptReev())
        assertEquals(30.0, st.capacityKwh(), 1e-4)
        dir.deleteRecursively()
    }

    @Test
    fun historyIsSealedAndGoesWithTheSession() {
        val dir = Files.createTempDirectory("carcloud").toFile()
        val st = store(dir, MemBox())
        assertNull(st.history())
        st.saveHistory(org.json.JSONObject().put("tripsAt", 5L))
        assertEquals(5L, st.history()!!.getLong("tripsAt"))
        // Cifrado: en el disco no se lee.
        val raw = File(dir, "history.bin").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains("tripsAt"))
        st.deleteSession()
        assertNull(st.history())
        dir.deleteRecursively()
    }

    @Test
    fun historyPolicy() {
        val now = 10_000_000_000L
        // Nunca leído: ya.
        assertTrue(CarCloud.HistoryPolicy.due(now, 0, Double.NaN, Double.NaN, false, 0, 0))
        // Cada 30 min.
        assertFalse(CarCloud.HistoryPolicy.due(now, now - 29 * 60_000L, 100.0, 100.0, true, 0, 0))
        assertTrue(CarCloud.HistoryPolicy.due(now, now - 30 * 60_000L, 100.0, 100.0, true, 0, 0))
        // Tras un viaje (más km y parado), a los 3 min.
        assertFalse(CarCloud.HistoryPolicy.due(now, now - 2 * 60_000L, 107.0, 100.0, true, 0, 0))
        assertTrue(CarCloud.HistoryPolicy.due(now, now - 3 * 60_000L, 107.0, 100.0, true, 0, 0))
        // En marcha, no.
        assertFalse(CarCloud.HistoryPolicy.due(now, now - 10 * 60_000L, 107.0, 100.0, false, 0, 0))
        // El repaso pendiente.
        assertTrue(CarCloud.HistoryPolicy.due(now, now - 10 * 60_000L, 100.0, 100.0, false, now - 1, 0))
        // Tras un fallo, nada hasta que pase la espera.
        assertFalse(CarCloud.HistoryPolicy.due(now, 0, 100.0, 100.0, true, 0, now + 1))
    }

    @Test
    fun emailMasking() {
        assertEquals("c***@c***.es", CarCloudStore.maskEmail("carlos.ejemplo@correo.es"))
        assertEquals("a***@e***.com", CarCloudStore.maskEmail(" A.b@Example.COM "))
        assertEquals("x***", CarCloudStore.maskEmail("xyz"))
        assertEquals("", CarCloudStore.maskEmail(null))
    }

    private fun socSnap(socPct: Int, ageMs: Long, charging: Boolean, plugged: Boolean): CarCloud.Snapshot {
        val now = System.currentTimeMillis()
        val sig = JSONObject()
        sig.put("1204", socPct)
        sig.put("1149", if (charging) 1 else 0)
        sig.put("47", if (plugged || charging) 1 else 0)
        sig.put("1197", 0)
        sig.put("3736", 0)
        val data = JSONObject()
        data.put("collectTime", now - ageMs)
        data.put("signal", sig)
        return CarCloud.Snapshot(CarCloud.State.OK, LeapStatus.parse(data), now, 100, 67.0, "C10", 0, false)
    }

    @Test
    fun socStaleOnlyWhenOldAndUnplugged() {
        val now = System.currentTimeMillis()
        // Fresco: no es viejo.
        assertFalse(CarCloud.socStale(socSnap(100, 5 * 60_000L, false, false), now))
        // Viejo y desenchufado: sí (pudo conducirse sin reportar).
        assertTrue(CarCloud.socStale(socSnap(100, 99 * 60_000L, false, false), now))
        // Viejo pero enchufado: no (parado cargando, el % vale).
        assertFalse(CarCloud.socStale(socSnap(100, 99 * 60_000L, false, true), now))
        // Viejo pero cargando: no.
        assertFalse(CarCloud.socStale(socSnap(90, 99 * 60_000L, true, true), now))
        // Más de 6 h: la nube ya no se cree el %, así que no es «stale», es «sin dato».
        assertFalse(CarCloud.socStale(socSnap(100, 7 * 3600_000L, false, false), now))
        // Sin datos: no.
        assertFalse(CarCloud.socStale(CarCloud.Snapshot.of(CarCloud.State.NO_ACCOUNT), now))
    }
}
