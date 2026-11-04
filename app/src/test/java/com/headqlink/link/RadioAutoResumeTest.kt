package com.headqlink.link

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Looper
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.time.Duration
import java.util.function.BooleanSupplier

/**
 * «Seguir con la radio al conectar» en el modo «Auto» (sin la interfaz propia): lo que sonaba al perder el coche (o lo
 * pausó solo la salida de audio) vuelve una vez en la conexión siguiente, ya con Android Auto en el coche; una pausa
 * del usuario, el ajuste apagado u otra música sonando, no. En «Auto extendido», como antes (CarUi).
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class RadioAutoResumeTest {
    private val app: Context = RuntimeEnvironment.getApplication().also { Str.init(it) }
    private val looper = shadowOf(Looper.getMainLooper())
    private val prefs = app.getSharedPreferences("hql_radio_resume", Context.MODE_PRIVATE)
    private val cfg = com.headqlink.link.Config(app)
    private var inCar = true
    private val aaInCarBefore = RadioPlayer.aaInCar

    private fun station(name: String) = RadioPlayer.Station().apply {
        this.name = name
        // Un fichero que no existe: el reproductor es de verdad, pero no hace falta que suene nada.
        url = "file:///nada/${name.replace(' ', '_')}.mp3"
        logo = ""
        group = "pop"
    }

    private fun playing(name: String = "Radio Uno") {
        RadioPlayer.play(app, listOf(station(name), station("Radio Dos")), 0)
        looper.idle()
        assertTrue(RadioPlayer.isPlaying())
    }

    /** Se va el Bluetooth del coche (o unos auriculares): Android lo avisa así. */
    private fun outputGone() {
        app.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        looper.idle()
    }

    /** Se acaba la sesión con el coche en «Auto» (AaPassthroughSource.stop y LinkService.carLostNow). */
    private fun carGone() {
        RadioPlayer.cancelCarBack()
        RadioPlayer.carGone(app)
        looper.idle()
    }

    /** Vuelve el coche (LinkService.carConnectedNow) y pasa el tiempo. */
    private fun carBack(mode: String = com.headqlink.link.Config.MODE_AA, waitMs: Long = RadioPlayer.AUTO_RESUME_AFTER_MS) {
        RadioPlayer.carBack(app, mode)
        idle(waitMs)
    }

    private fun idle(ms: Long) {
        looper.idleFor(Duration.ofMillis(ms))
    }

    private fun saved() = RadioPlayer.resumeFrom(prefs.getString("state", null))

    @Before
    fun clean() {
        RadioPlayer.stop()
        RadioPlayer.cancelCarBack()
        RadioSession.sourceAtStart(app)
        prefs.edit().clear().commit()
        app.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().clear().commit()
        inCar = true
        RadioPlayer.aaInCar = BooleanSupplier { inCar }
    }

    @After
    fun release() {
        RadioPlayer.cancelCarBack()
        RadioPlayer.stop()
        RadioPlayer.aaInCar = aaInCarBefore
        looper.idle()
    }

    @Test
    fun whatWasPlayingWhenTheCarWentComesBackOnce() {
        playing()
        // El Bluetooth se va antes que el enlace: en pausa por la salida de audio, cuenta como que sonaba.
        outputGone()
        carGone()
        assertNull(RadioPlayer.current())
        assertEquals("Radio Uno", saved()!!.list[0].name)
        // Recién conectado, aún no: se espera a que Android Auto esté en el coche y el audio salga por él.
        carBack(waitMs = RadioPlayer.AUTO_RESUME_AFTER_MS - 1_000)
        assertNull(RadioPlayer.current())
        idle(1_000)
        assertEquals("Radio Uno", RadioPlayer.current()!!.name)
        assertTrue(RadioPlayer.isPlaying())
        // Toma la música como al ponerla a mano: Android Auto enseña la emisora.
        assertTrue(RadioSession.owner())
        // Con ⏮ ⏭ por su lista.
        RadioPlayer.skipFromControls(app, 1)
        assertEquals("Radio Dos", RadioPlayer.current()!!.name)
        // Una vez: ya no queda nada guardado y otra conexión sin perder el coche sonando no la vuelve a poner.
        assertNull(saved())
        RadioPlayer.stop()
        carBack()
        idle(RadioPlayer.AUTO_RESUME_GIVE_UP_MS)
        assertNull(RadioPlayer.current())
    }

    @Test
    fun itCarriesOnFromTripToTrip() {
        playing()
        carGone()
        carBack()
        assertEquals("Radio Uno", RadioPlayer.current()!!.name)
        // Sigue sonando al perder el coche otra vez: vuelve en la siguiente.
        carGone()
        assertNull(RadioPlayer.current())
        carBack()
        assertEquals("Radio Uno", RadioPlayer.current()!!.name)
    }

    @Test
    fun aPauseOrAStopByTheUserIsNotResumed() {
        playing()
        RadioPlayer.pauseFromControls()
        carGone()
        assertNull(saved())
        carBack()
        assertNull(RadioPlayer.current())
        // Pausada por la salida de audio y después por el usuario: tampoco.
        playing()
        outputGone()
        RadioPlayer.pauseFromControls()
        carGone()
        assertNull(saved())
        // Parada a mano (⏹), con algo guardado de antes: se olvida.
        prefs.edit().putString("state", RadioPlayer.resumeJson(listOf(station("Radio Vieja")), 0, 0)).commit()
        playing()
        RadioPlayer.stopByUser()
        carGone()
        assertNull(saved())
        carBack()
        assertNull(RadioPlayer.current())
    }

    @Test
    fun withTheSettingOffNothingComesBack() {
        cfg.putBool(com.headqlink.link.Config.RADIO_RESUME, false)
        playing()
        carGone()
        assertNull(saved())
        carBack()
        assertNull(RadioPlayer.current())
        // Apagado mientras se espera a Android Auto: tampoco, y se olvida.
        cfg.putBool(com.headqlink.link.Config.RADIO_RESUME, true)
        playing()
        carGone()
        RadioPlayer.carBack(app, com.headqlink.link.Config.MODE_AA)
        cfg.putBool(com.headqlink.link.Config.RADIO_RESUME, false)
        idle(RadioPlayer.AUTO_RESUME_AFTER_MS)
        assertNull(RadioPlayer.current())
        assertNull(saved())
    }

    @Test
    fun theExtendedModeStaysAsBefore() {
        prefs.edit().putString("state", RadioPlayer.resumeJson(listOf(station("Radio Uno")), 0, 0)).commit()
        // En «Auto extendido» no sigue al conectar (lo hace CarUi al abrirse) y lo guardado no se toca.
        carBack(com.headqlink.link.Config.MODE_AA_EXT)
        idle(RadioPlayer.AUTO_RESUME_GIVE_UP_MS)
        assertNull(RadioPlayer.current())
        assertNotNull(saved())
        // CarUi: sigue al abrirse, y lo guardado se queda hasta que se cierre (CarUi.stop lo vuelve a guardar o lo olvida).
        RadioPlayer.resumeSaved(app)
        looper.idle()
        assertEquals("Radio Uno", RadioPlayer.current()!!.name)
        assertNotNull(saved())
        RadioPlayer.saveForResume(app)
        RadioPlayer.stop()
        assertEquals("Radio Uno", saved()!!.list[0].name)
    }

    @Test
    fun itWaitsForAndroidAutoInTheCar() {
        playing()
        carGone()
        inCar = false
        carBack()
        assertNull(RadioPlayer.current())
        idle(RadioPlayer.AUTO_RESUME_RETRY_MS * 3)
        assertNull(RadioPlayer.current())
        assertNotNull(saved())
        inCar = true
        idle(RadioPlayer.AUTO_RESUME_RETRY_MS)
        assertEquals("Radio Uno", RadioPlayer.current()!!.name)
        // Si no llega en un minuto, se olvida.
        carGone()
        inCar = false
        carBack()
        idle(RadioPlayer.AUTO_RESUME_GIVE_UP_MS + RadioPlayer.AUTO_RESUME_RETRY_MS)
        assertNull(saved())
        inCar = true
        idle(RadioPlayer.AUTO_RESUME_GIVE_UP_MS)
        assertNull(RadioPlayer.current())
    }

    @Test
    fun otherMusicPlayingIsNotInterrupted() {
        playing()
        carGone()
        // Spotify (o la música que Android Auto retoma al conectar) suena ya: la radio no se la quita, y se olvida.
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        shadowOf(am).setActivePlaybackConfigurationsFor(listOf(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()))
        carBack()
        assertNull(RadioPlayer.current())
        assertNull(saved())
        shadowOf(am).setActivePlaybackConfigurationsFor(emptyList())
    }

    @Test
    fun aStationChosenMeanwhileIsKept() {
        playing()
        carGone()
        RadioPlayer.carBack(app, com.headqlink.link.Config.MODE_AA)
        // El usuario pone otra emisora en Android Auto antes de que siga: esa se queda.
        idle(2_000)
        playing("Radio Tres")
        idle(RadioPlayer.AUTO_RESUME_AFTER_MS)
        assertEquals("Radio Tres", RadioPlayer.current()!!.name)
        assertNull(saved())
    }

    @Test
    fun aShortConnectionKeepsItForTheNextOne() {
        playing()
        carGone()
        // El coche se va antes de que siga: lo guardado espera a la próxima conexión.
        carBack(waitMs = 5_000)
        carGone()
        assertNotNull(saved())
        carBack()
        assertEquals("Radio Uno", RadioPlayer.current()!!.name)
    }

    @Test
    fun decision() {
        val r = RadioPlayer.AUTO_RESUME
        val w = RadioPlayer.AUTO_WAIT
        val f = RadioPlayer.AUTO_FORGET
        assertEquals(r, RadioPlayer.autoResume(true, false, false, false, true, false))
        // Ajuste apagado, emisora ya puesta u otra música: se olvida.
        assertEquals(f, RadioPlayer.autoResume(false, false, false, false, true, false))
        assertEquals(f, RadioPlayer.autoResume(true, true, false, false, true, false))
        assertEquals(f, RadioPlayer.autoResume(true, false, false, true, true, false))
        // Hablando el navegador (su audio puede contar como música) o sin Android Auto en el coche: se espera...
        assertEquals(w, RadioPlayer.autoResume(true, false, true, true, true, false))
        assertEquals(w, RadioPlayer.autoResume(true, false, false, false, false, false))
        // ... hasta que es tarde.
        assertEquals(f, RadioPlayer.autoResume(true, false, true, false, true, true))
        assertEquals(f, RadioPlayer.autoResume(true, false, false, false, false, true))
        // Con todo listo, sigue aunque haya tardado.
        assertEquals(r, RadioPlayer.autoResume(true, false, false, false, true, true))
    }
}
