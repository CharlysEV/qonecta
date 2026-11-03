package com.headqlink.link

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
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

/**
 * Al bajarse del coche (revisión 3-11): si se va la salida de audio (el Bluetooth del coche), la radio se pausa y no sigue
 * por el altavoz del móvil; al acabar la sesión con el coche se para del todo en cualquier modo y su sesión ya no se queda
 * con las teclas; y «Seguir con la radio al conectar» se acuerda de lo que sonaba aunque la pausara la salida de audio.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class RadioCarGoneTest {
    private val app: Context = RuntimeEnvironment.getApplication().also { Str.init(it) }
    private val looper = shadowOf(Looper.getMainLooper())
    private val prefs = app.getSharedPreferences("hql_radio_resume", Context.MODE_PRIVATE)

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

    /** Lo que hace CarUi.stop al cerrarse la interfaz propia (modo extendido) con «Seguir con la radio al conectar». */
    private fun extendedEnds() {
        RadioPlayer.saveForResume(app)
        RadioPlayer.stop()
        looper.idle()
    }

    private fun saved() = RadioPlayer.resumeFrom(prefs.getString("state", null))

    @Before
    fun clean() {
        RadioPlayer.stop()
        RadioSession.sourceAtStart(app)
        prefs.edit().clear().commit()
    }

    @After
    fun release() {
        RadioPlayer.stop()
        RadioSession.browserDown()
        looper.idle()
    }

    @Test
    fun losingTheCarAudioPausesTheRadioInsteadOfTheSpeaker() {
        playing()
        outputGone()
        // En pausa (no por el altavoz), con su emisora: dentro del coche, ▶ la vuelve a poner.
        assertFalse(RadioPlayer.isPlaying())
        assertEquals("Radio Uno", RadioPlayer.current()!!.name)
        // Una pausa por la salida de audio no es «otra cosa se ha quedado el sonido»: sigue teniendo la música.
        assertTrue(RadioSession.owner())
        RadioPlayer.playFromControls(app)
        looper.idle()
        assertTrue(RadioPlayer.isPlaying())
    }

    @Test
    fun theResumeRemembersWhatThePausedOutputWasPlaying() {
        playing()
        outputGone()
        // El Bluetooth se va antes que el enlace: al cerrarse la interfaz propia sonaba, y sigue en la próxima conexión.
        extendedEnds()
        assertNull(RadioPlayer.current())
        assertFalse(RadioPlayer.isPlaying())
        val r = saved()
        assertNotNull(r)
        assertEquals("Radio Uno", r!!.list[r.index].name)
        assertEquals(2, r.list.size)
    }

    @Test
    fun aPauseByTheUserIsNotResumed() {
        playing()
        RadioPlayer.pauseFromControls()
        extendedEnds()
        assertNull(saved())
        // Pausada por la salida de audio y después por el usuario (⏸ en el coche): tampoco.
        prefs.edit().clear().commit()
        playing()
        outputGone()
        RadioPlayer.pauseFromControls()
        extendedEnds()
        assertNull(saved())
        // Sonando al cerrarse, como siempre: sí.
        playing("Radio Tres")
        extendedEnds()
        assertEquals("Radio Tres", saved()!!.list[0].name)
        // Puro.
        assertTrue(RadioPlayer.soundedAtClose(true, false))
        assertTrue(RadioPlayer.soundedAtClose(false, true))
        assertFalse(RadioPlayer.soundedAtClose(false, false))
    }

    @Test
    fun theEndOfTheCarSessionStopsTheRadioInAutoMode() {
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Android Auto conectado a la radio (RadioBrowserService): en modo Auto puede quedarse minutos esperando al coche.
        RadioSession.browserUp(app)
        val session = RadioSession.get(app)
        // Lo guardado de una sesión anterior del modo extendido no se toca.
        prefs.edit().putString("state", RadioPlayer.resumeJson(listOf(station("Radio Vieja")), 0, 0)).commit()
        playing()
        outputGone()
        assertTrue(session.isActive)
        assertEquals(1, shadowOf(nm).allNotifications.size)
        // Se acaba la sesión con el coche sin la interfaz propia (AaPassthroughSource.stop).
        assertFalse(CarUi.running())
        RadioPlayer.carGone()
        looper.idle()
        assertNull(RadioPlayer.current())
        assertFalse(RadioPlayer.isPlaying())
        // Ni la música ni las teclas: su sesión, inactiva (Android Auto la sigue teniendo) y sin notificación.
        assertFalse(RadioSession.owner())
        assertFalse(session.isActive)
        assertEquals(0, shadowOf(nm).allNotifications.size)
        // Olvidada: un «▶» de Android Auto o del coche no la vuelve a poner.
        assertNull(RadioPlayer.lastStopped())
        RadioPlayer.playFromControls(app)
        assertNull(RadioPlayer.current())
        assertEquals("Radio Vieja", saved()!!.list[0].name)
        // Sin Android Auto, la sesión se libera.
        RadioSession.browserDown()
        assertFalse(RadioSession.isToken(session.sessionToken.token))
    }

    @Test
    fun aRadioPlayingWhenTheCarGoesIsStoppedToo() {
        playing()
        RadioPlayer.carGone()
        looper.idle()
        assertNull(RadioPlayer.current())
        assertFalse(RadioSession.owner())
    }
}
