package com.headqlink.link

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import androidx.media.MediaBrowserServiceCompat
import androidx.media.MediaSessionManager
import androidx.media3.common.Player
import java.lang.reflect.Proxy
import java.time.Duration
import com.bumptech.glide.request.target.CustomTarget
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config as RoboConfig
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowProcess

/**
 * La radio en Android Auto (foro #297) y su logo en los controles (#286): qué se publica (canción y emisora), los
 * estados, el árbol de favoritos y recientes, que un «▶» sin emisora no la pone a sonar, y cuándo tiene la radio la música
 * de Qonecta+ (su sesión activa, la del enlace con Android Auto apartada y las teclas para ella).
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@RoboConfig(sdk = [35], application = android.app.Application::class)
class RadioAaTest {
    private val app: Context = RuntimeEnvironment.getApplication().also { Str.init(it) }

    private fun station(name: String, url: String) = RadioPlayer.Station().apply {
        this.name = name
        this.url = url
        logo = ""
        group = "pop"
    }

    @Before
    fun clean() {
        // El uid de la app, fijo (Robolectric lo elige al azar): los de las otras apps de las pruebas no coinciden.
        ShadowProcess.setUid(10_100)
        RadioPlayer.stop()
        RadioSession.sourceAtStart(app)
    }

    @After
    fun release() {
        RadioPlayer.stop()
        RadioSession.browserDown()
        RadioSession.link(null)
    }

    @Test
    fun theSongGoesFirstAndTheStationBelow() {
        assertEquals(listOf("Artista - Canción", "Radio Uno"),
            RadioSession.texts(false, "Radio Uno", "Artista - Canción", "Radio", "Mi música").toList())
        // Sin canción (o con lo mismo que la emisora, o solo guiones): la emisora y «Radio».
        for (icy in listOf("", "  ", " - ", "Radio Uno", "radio uno ")) {
            assertEquals(icy, listOf("Radio Uno", "Radio"), RadioSession.texts(false, "Radio Uno", icy, "Radio", "Mi música").toList())
        }
        // Una canción del móvil: título y artista (o «Mi música»).
        assertEquals(listOf("Canción", "Grupo"), RadioSession.texts(true, "Canción", "Grupo", "Radio", "Mi música").toList())
        assertEquals(listOf("Canción", "Mi música"), RadioSession.texts(true, "Canción", "", "Radio", "Mi música").toList())
    }

    @Test
    fun theKaraokeSplitsWhatTheStationSays() {
        assertEquals(listOf("Artista", "Canción"), RadioSession.song("Artista - Canción", "Radio Uno")!!.toList())
        assertEquals(listOf("", "Solo el título"), RadioSession.song("Solo el título", "Radio Uno")!!.toList())
        assertNull(RadioSession.song("Radio Uno", "Radio Uno"))
        assertNull(RadioSession.song("", "Radio Uno"))
        assertNull(RadioSession.song("-", "Radio Uno"))
        // Por la radio, la letra sin tiempos.
        val synced = Lyrics.Result().also { it.synced = Lyrics.parseLrc("[00:01.00] uno\n[00:03.00] dos") }
        assertEquals("uno\ndos", KaraokeScreen.unsynced(synced).plain)
        assertTrue(KaraokeScreen.unsynced(synced).synced.isEmpty())
    }

    @Test
    fun playerStatesMapToTheControls() {
        val none = Player.PLAYBACK_SUPPRESSION_REASON_NONE
        assertEquals(PlaybackStateCompat.STATE_PLAYING, PlaybackMetadata.stateOf(Player.STATE_READY, true, none, false))
        assertEquals(PlaybackStateCompat.STATE_BUFFERING, PlaybackMetadata.stateOf(Player.STATE_BUFFERING, true, none, false))
        assertEquals(PlaybackStateCompat.STATE_PAUSED, PlaybackMetadata.stateOf(Player.STATE_READY, false, none, false))
        assertEquals(PlaybackStateCompat.STATE_PAUSED, PlaybackMetadata.stateOf(Player.STATE_BUFFERING, true,
            Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS, false))
        assertEquals(PlaybackStateCompat.STATE_STOPPED, PlaybackMetadata.stateOf(Player.STATE_IDLE, true, none, false))
        assertEquals(PlaybackStateCompat.STATE_ERROR, PlaybackMetadata.stateOf(Player.STATE_IDLE, true, none, true))
        assertEquals(PlaybackStateCompat.STATE_STOPPED, PlaybackMetadata.stateOf(Player.STATE_ENDED, true, none, true))
        val all = PlaybackMetadata.actions(true, true)
        for (a in listOf(PlaybackStateCompat.ACTION_PLAY, PlaybackStateCompat.ACTION_PAUSE, PlaybackStateCompat.ACTION_STOP,
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)) {
            assertTrue(all and a != 0L)
        }
        assertEquals(0L, PlaybackMetadata.actions(false, false) and PlaybackStateCompat.ACTION_SKIP_TO_NEXT)
        // Parada: elegir emisora siempre; «▶» solo con una parada a mano.
        assertEquals(PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID, RadioSession.idleActions(false))
        assertTrue(RadioSession.idleActions(true) and PlaybackStateCompat.ACTION_PLAY != 0L)
    }

    @Test
    fun theLogoFitsWithoutGrowing() {
        assertEquals(listOf(512, 256), PlaybackMetadata.fit(1024, 512, 512).toList())
        assertEquals(listOf(512, 512), PlaybackMetadata.fit(600, 600, 512).toList())
        assertEquals(listOf(32, 32), PlaybackMetadata.fit(32, 32, 512).toList())
        assertEquals(listOf(171, 512), PlaybackMetadata.fit(300, 900, 512).toList())
        assertEquals(listOf(1, 1), PlaybackMetadata.fit(0, 0, 512).toList())
    }

    @Test
    fun androidAutoSeesFavoritesAndRecents() {
        val root = RadioLibrary.folders("Favoritos", "Recientes")
        assertEquals(listOf(RadioLibrary.FAVS, RadioLibrary.RECENT), root.map { it.mediaId })
        assertTrue(root.all { it.isBrowsable && !it.isPlayable })
        val favs = listOf(station("Radio Uno", "https://uno.example/stream"), station("Radio Dos", "https://dos.example/stream"))
        val items = RadioLibrary.items(RadioLibrary.FAVS, favs)
        assertEquals(listOf("Radio Uno", "Radio Dos"), items.map { it.description.title.toString() })
        assertTrue(items.all { it.isPlayable && !it.isBrowsable })
        // El id lleva la lista y un resumen de la URL (no la URL).
        val id = items[1].mediaId!!
        assertTrue(id.startsWith("fav:") && !id.contains("example"))
        assertEquals(RadioLibrary.FAVS, RadioLibrary.listOf(id))
        assertEquals(1, RadioLibrary.indexOf(favs, id))
        assertEquals(-1, RadioLibrary.indexOf(favs.take(1), id))
        assertNull(RadioLibrary.listOf("otra:123"))
        assertNull(RadioLibrary.listOf(null))
        // Las carpetas, con sus nombres; quien no es de confianza, nada.
        assertEquals(listOf("Favorites", "Recent"), RadioLibrary.children(app, RadioLibrary.ROOT, true).map { it.description.title.toString() })
        assertTrue(RadioLibrary.children(app, RadioLibrary.ROOT, false).isEmpty())
        assertTrue(RadioLibrary.trusted("com.google.android.projection.gearhead", 10_200, 10_100))
        assertTrue(RadioLibrary.trusted("com.headqlink.app", 10_100, 10_100))
        assertFalse(RadioLibrary.trusted("com.ejemplo.otra", 10_300, 10_100))
        // Quien pide una carpeta: de confianza y dueño de verdad del paquete que dice.
        val aa = "com.google.android.projection.gearhead"
        assertTrue(RadioLibrary.mayList(aa, 10_200, 10_100, arrayOf(aa), 35))
        assertFalse(RadioLibrary.mayList(aa, 10_300, 10_100, arrayOf("com.ejemplo.otra"), 35))
        assertFalse(RadioLibrary.mayList("com.ejemplo.otra", 10_300, 10_100, arrayOf("com.ejemplo.otra"), 35))
        assertFalse(RadioLibrary.mayList(aa, 10_200, 10_100, null, 35))
        // Android 8.1 o anterior: por el camino del sistema no se sabe quién es, pero ya pasó por onGetRoot.
        val legacy = MediaSessionManager.RemoteUserInfo.LEGACY_CONTROLLER
        assertTrue(RadioLibrary.mayList(legacy, -1, 10_100, null, 27))
        assertFalse(RadioLibrary.mayList(legacy, -1, 10_100, null, 28))
        val extras = RadioLibrary.rootExtras()
        assertFalse(extras.getBoolean("android.media.browse.SEARCH_SUPPORTED", true))
    }

    @Test
    fun recentsGoFirstWithoutRepeats() {
        val old = JSONArray()
        for (i in 1..4) old.put(Favorites.item("Radio $i", "https://r$i.example/s", "", "", null))
        val out = RadioLibrary.withRecent(old, station("Radio 3", "https://r3.example/s"), 3)
        assertEquals(listOf("Radio 3", "Radio 1", "Radio 2"), RadioLibrary.stations(out).map { it.name })
        // Sin URL, fuera.
        val broken = JSONArray().put(Favorites.item("Sin URL", "", "", "", null)).put(Favorites.item("Buena", "https://b.example", "", "", null))
        assertEquals(listOf("Buena"), RadioLibrary.stations(broken).map { it.name })
        // Lo que suena se apunta; las canciones del móvil, no.
        RadioLibrary.addRecent(app, station("Radio Nueva", "https://nueva.example/s"))
        RadioLibrary.addRecent(app, station("Canción", "content://media/1").apply { local = true })
        assertEquals("Radio Nueva", RadioLibrary.recents(app).first().name)
        assertTrue(RadioLibrary.recents(app).none { it.local || it.name == "Canción" })
    }

    @Test
    fun theSharedSessionShowsSongStationAndLogo() {
        var state = Player.STATE_READY
        val player = mock<Player> {
            on { playWhenReady } doReturn true
            on { currentPosition } doReturn 42_000L
        }
        whenever(player.playbackState).thenAnswer { state }
        val session = MediaSessionCompat(app, "prueba")
        val metadata = PlaybackMetadata(app, player, session)
        try {
            metadata.setNowPlaying("Artista - Canción", "Radio Uno", "Radio Uno", "file:///missing/logo.png", "Radio Uno", 0, true)
            val md = publishedMetadata(session)
            assertEquals("Artista - Canción", md.getString(MediaMetadataCompat.METADATA_KEY_TITLE))
            assertEquals("Radio Uno", md.getString(MediaMetadataCompat.METADATA_KEY_ARTIST))
            assertEquals("Radio Uno", md.getString(PlaybackMetadata.KEY_STATION))
            // El logo no va como dirección http: solo como imagen, de 512 px como mucho.
            assertNull(md.getString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI))
            target(metadata).onResourceReady(Bitmap.createBitmap(1024, 512, Bitmap.Config.ARGB_8888), null)
            val art = publishedMetadata(session).getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART)
            assertEquals(512, art.width)
            assertEquals(256, art.height)
            val st = publishedState(session)
            assertEquals(PlaybackStateCompat.STATE_PLAYING, st.state)
            // En directo: sin posición (la del flujo no es la de la canción).
            assertEquals(PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, st.position)
            assertTrue(st.actions and PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID != 0L)
            // La sesión compartida no la activa PlaybackMetadata: lo decide RadioSession (si la radio tiene la música).
            assertFalse(session.isActive)
            // La emisora no suena: Android Auto enseña por qué.
            state = Player.STATE_IDLE
            metadata.setError("No se puede reproducir")
            assertEquals(PlaybackStateCompat.STATE_ERROR, publishedState(session).state)
            assertEquals("No se puede reproducir", publishedState(session).errorMessage.toString())
            assertFalse(session.isActive)
        } finally {
            metadata.close()
        }
        // Al cerrar, la sesión compartida sigue viva (Android Auto la tiene): se puede seguir usando.
        session.setMetadata(null)
        assertFalse(session.isActive)
        session.release()
    }

    @Test
    fun playWithoutAStationNeverStartsTheRadio() {
        RadioSession.browserUp(app)
        val session = RadioSession.get(app)
        assertTrue(RadioSession.isToken(session.sessionToken.token))
        val st = publishedState(session)
        assertEquals(PlaybackStateCompat.STATE_NONE, st.state)
        assertEquals(PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID, st.actions)
        // Android Auto al conectar: preparar, ▶ y ⏭ sin emisora no ponen nada.
        val orders = sessionField(session, "mCallback") as MediaSessionCompat.Callback
        orders.onPrepare()
        orders.onPlay()
        orders.onSkipToNext()
        orders.onPlayFromMediaId("fav:ffff", null)
        // Ni sus teclas (▶ o ⏯ que el propio Android Auto manda a la sesión del enlace, o las del volante).
        assertTrue(RadioSession.linkKey(KeyEvent.KEYCODE_MEDIA_PLAY, RadioSession.ANDROID_AUTO))
        assertTrue(RadioSession.linkKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, RadioSession.ANDROID_AUTO))
        assertFalse(RadioSession.linkKey(KeyEvent.KEYCODE_MEDIA_PLAY, null))
        assertNull(RadioPlayer.current())
        assertFalse(RadioPlayer.isPlaying())
        assertEquals(PlaybackStateCompat.STATE_NONE, publishedState(session).state)
        // Android Auto conectado a la radio no la hace activa: sin la música, no la ve entre las sesiones que suenan.
        assertFalse(session.isActive)
        assertFalse(RadioSession.owner())
    }

    @Test
    fun stoppingByHandRemembersTheStationOnlyForThisTrip() {
        RadioSession.browserUp(app)
        val session = RadioSession.get(app)
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        RadioPlayer.previewOnly(station("Radio Uno", "https://uno.example/stream"))
        // Con emisora, la notificación multimedia del móvil.
        assertEquals(1, shadowOf(nm).allNotifications.size)
        RadioPlayer.stopByUser()
        assertEquals(0, shadowOf(nm).allNotifications.size)
        assertEquals("Radio Uno", RadioPlayer.lastStopped()!!.name)
        val st = publishedState(session)
        assertEquals(PlaybackStateCompat.STATE_STOPPED, st.state)
        assertTrue(st.actions and PlaybackStateCompat.ACTION_PLAY != 0L)
        assertEquals("Radio Uno", publishedMetadata(session).getString(MediaMetadataCompat.METADATA_KEY_TITLE))
        // Fin de la interfaz propia o de Android Auto: olvidada; un «▶» ya no la pone.
        RadioPlayer.stop()
        assertNull(RadioPlayer.lastStopped())
        assertEquals(PlaybackStateCompat.STATE_NONE, publishedState(session).state)
        assertNull(sessionField(session, "mMetadata"))
        // Sin Android Auto ni radio, la sesión se libera.
        RadioSession.browserDown()
        assertFalse(RadioSession.isToken(session.sessionToken.token))
    }

    @Test
    fun theServiceGivesListsOnlyToAndroidAutoAndNothingToResume() {
        val controller = Robolectric.buildService(RadioBrowserService::class.java).create()
        val service = controller.get()
        try {
            assertNotNull(service.sessionToken)
            assertEquals(RadioLibrary.ROOT, service.onGetRoot("com.google.android.projection.gearhead", 10_200, null)!!.rootId)
            // Los demás ni entran: sin raíz no reciben la sesión.
            assertNull(service.onGetRoot("com.ejemplo.otra", 10_300, null))
            val recent = Bundle().apply { putBoolean(MediaBrowserServiceCompat.BrowserRoot.EXTRA_RECENT, true) }
            assertNull(service.onGetRoot("com.android.systemui", 10_400, recent))
        } finally {
            controller.destroy()
        }
        assertNull(RadioPlayer.current())
    }

    @Test
    fun anUntrustedAppCannotReadTheRecents() {
        RadioLibrary.addRecent(app, station("Radio Nueva", "https://nueva.example/s"))
        val aa = "com.google.android.projection.gearhead"
        val other = "com.ejemplo.otra"
        shadowOf(app.packageManager).setPackagesForUid(10_200, aa)
        shadowOf(app.packageManager).setPackagesForUid(10_300, other)
        val controller = Robolectric.buildService(RadioBrowserService::class.java).create()
        try {
            val service = controller.get()
            // Otra app se conecta y se suscribe a «recent»: no entra, y no le llega nada.
            val stranger = Client(service)
            stranger.connect(other, 10_300)
            assertEquals(false, stranger.connected)
            stranger.subscribe(RadioLibrary.RECENT)
            assertFalse(stranger.loaded.containsKey(RadioLibrary.RECENT))
            // Aunque llegara a registrarse (la mensajería de AndroidX, sin onGetRoot), «recent» y «fav» le llegan vacías,
            // también si dice ser Android Auto con su propio uid.
            val sneaky = Client(service)
            sneaky.register(other, 10_300)
            sneaky.subscribe(RadioLibrary.RECENT)
            sneaky.subscribe(RadioLibrary.FAVS)
            assertEquals(emptyList<String>(), sneaky.loaded[RadioLibrary.RECENT]!!.map { it.mediaId })
            assertEquals(emptyList<String>(), sneaky.loaded[RadioLibrary.FAVS]!!.map { it.mediaId })
            val impostor = Client(service)
            impostor.register(aa, 10_300)
            impostor.subscribe(RadioLibrary.RECENT)
            assertTrue(impostor.loaded[RadioLibrary.RECENT]!!.isEmpty())
            // Android Auto sí: entra y ve las recientes.
            val car = Client(service)
            car.connect(aa, 10_200)
            assertEquals(true, car.connected)
            car.subscribe(RadioLibrary.ROOT)
            car.subscribe(RadioLibrary.RECENT)
            assertEquals(listOf(RadioLibrary.FAVS, RadioLibrary.RECENT), car.loaded[RadioLibrary.ROOT]!!.map { it.mediaId })
            assertEquals("Radio Nueva", car.loaded[RadioLibrary.RECENT]!!.first().description.title.toString())
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun qonectaIsAlwaysAnAndroidAutoSource() {
        val pm = app.packageManager
        val cn = ComponentName(app, RadioBrowserService::class.java)
        // Habilitado de serie: Android Auto lee sus fuentes al empezar su sesión, y uno habilitado a mitad no lo ve.
        assertTrue(pm.getServiceInfo(cn, PackageManager.MATCH_DISABLED_COMPONENTS).enabled)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, pm.getComponentEnabledSetting(cn))
        // Que suene o se pare la radio no lo toca.
        RadioSession.radio(true, true, false, false)
        RadioPlayer.stop()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(2))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, pm.getComponentEnabledSetting(cn))
        // Pruebas: «radioaa fuente no» lo quita (como en 0.3.13) y al arrancar la app vuelve.
        RadioSession.labSource(false)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, pm.getComponentEnabledSetting(cn))
        assertTrue(RadioSession.labStatus().startsWith("fuente no"))
        RadioSession.sourceAtStart(app)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, pm.getComponentEnabledSetting(cn))
        // Lo que se cuenta como sonar.
        assertTrue(RadioSession.sounds(true, true, Player.STATE_READY))
        assertTrue(RadioSession.sounds(true, true, Player.STATE_BUFFERING))
        assertFalse(RadioSession.sounds(true, false, Player.STATE_READY))
        assertFalse(RadioSession.sounds(true, true, Player.STATE_IDLE))
        assertFalse(RadioSession.sounds(true, true, Player.STATE_ENDED))
        assertFalse(RadioSession.sounds(false, true, Player.STATE_READY))
    }

    @Test
    fun theRadioHasTheMusicFromPlayingUntilSomethingElseSounds() {
        val looper = shadowOf(Looper.getMainLooper())
        val link = FakeLink()
        RadioSession.link(link)
        // Android Auto conectado a la radio, sin que suene: su sesión, inactiva; la del enlace, como siempre.
        val session = RadioSession.get(app).also { RadioSession.browserUp(app) }
        assertFalse(session.isActive)
        assertTrue(RadioSession.linkActive(true))
        assertFalse(RadioSession.linkActive(false))
        // Suena: la tiene; su sesión, activa, y la del enlace se aparta.
        RadioSession.radio(true, true, false, false)
        assertTrue(RadioSession.owner())
        assertTrue(session.isActive)
        assertFalse(RadioSession.linkActive(true))
        assertEquals(1, link.changes)
        // En pausa o fallando: la sigue teniendo (Android Auto la enseña en pausa y ▶ la vuelve a poner).
        RadioSession.radio(false, true, false, false)
        assertTrue(session.isActive)
        // Mientras suena, ni la música de Android Auto (su foco: la radio no se lo quita) ni la TV se la quitan.
        RadioSession.radio(true, true, false, false)
        RadioSession.aaSounds(true)
        RadioSession.otherSounds()
        looper.idle()
        assertTrue(RadioSession.owner())
        // En pausa y Android Auto pone su música (Spotify): la suelta; la del enlace vuelve.
        RadioSession.radio(false, true, false, false)
        RadioSession.aaSounds(true)
        looper.idle()
        assertFalse(RadioSession.owner())
        assertFalse(session.isActive)
        assertTrue(RadioSession.linkActive(true))
        assertEquals(2, link.changes)
        // Otra app se queda el sonido (la radio, callada): la suelta enseguida.
        RadioSession.radio(true, true, false, false)
        RadioSession.radio(false, true, true, false)
        assertFalse(RadioSession.owner())
        // Parada a mano (⏹): la sigue teniendo, parada (▶ la vuelve a poner en este viaje); parada del todo, no.
        RadioSession.radio(true, true, false, false)
        RadioSession.radio(false, false, false, true)
        assertTrue(RadioSession.owner())
        assertTrue(session.isActive)
        RadioSession.radio(false, false, false, false)
        assertFalse(RadioSession.owner())
        // Android Auto empieza o acaba una sesión con la radio en pausa: la suelta; sonando, no.
        RadioSession.radio(true, true, false, false)
        RadioSession.aaSessionChanged()
        looper.idle()
        assertTrue(RadioSession.owner())
        RadioSession.radio(false, true, false, false)
        RadioSession.aaSessionChanged()
        looper.idle()
        assertFalse(RadioSession.owner())
        // Sin Android Auto, una radio que tiene la música no suelta su sesión; al soltarla, sí.
        RadioSession.radio(true, true, false, false)
        RadioSession.browserDown()
        assertTrue(RadioSession.isToken(session.sessionToken.token))
        RadioSession.radio(false, false, false, false)
        assertFalse(RadioSession.isToken(session.sessionToken.token))
        // Pruebas: «radioaa enlace apartado», la del enlace inactiva siempre.
        RadioSession.labLinkApart(true)
        assertFalse(RadioSession.linkActive(true))
        RadioSession.labLinkApart(false)
        assertTrue(RadioSession.linkActive(true))
        // Puro.
        assertTrue(RadioSession.owns(false, true, true, false, false))
        assertTrue(RadioSession.owns(true, false, true, false, false))
        assertFalse(RadioSession.owns(false, false, true, false, false))
        assertFalse(RadioSession.owns(true, false, true, true, false))
        assertTrue(RadioSession.owns(true, false, false, false, true))
        assertFalse(RadioSession.owns(true, false, false, false, false))
        assertTrue(RadioSession.linkActive(true, false, false))
        assertFalse(RadioSession.linkActive(true, true, false))
        assertFalse(RadioSession.linkActive(true, false, true))
        assertFalse(RadioSession.linkActive(false, false, false))
    }

    @Test
    fun theTvTakesTheMusicFromThePausedRadioAndHasItsOwnSession() {
        RadioSession.get(app)
        RadioSession.radio(true, true, false, false)
        RadioSession.radio(false, true, false, false)
        assertTrue(RadioSession.owner())
        val player = mock<Player> {
            on { playWhenReady } doReturn true
            on { playbackState } doReturn Player.STATE_READY
        }
        val tv = PlaybackMetadata(app, player, "TV", {}, {}, null, null)
        try {
            val own = PlaybackMetadata::class.java.getDeclaredField("session").apply { isAccessible = true }.get(tv) as MediaSessionCompat
            // La TV suena con su sesión (activa: los controles del móvil y el coche por Bluetooth) y la radio suelta la
            // música: Android Auto no la enseña mientras suena la TV.
            assertTrue(own.isActive)
            assertFalse(RadioSession.owner())
            assertFalse(RadioSession.get(app).isActive)
        } finally {
            tv.close()
        }
    }

    @Test
    fun keysGoToTheRadioOnlyWhileItHasTheMusic() {
        val link = FakeLink()
        RadioSession.link(link)
        val session = RadioSession.get(app)
        val orders = sessionField(session, "mCallback") as MediaSessionCompat.Callback
        fun press(code: Int) = Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, code))
        // Sin la música en la radio: las del volante o los auriculares, a Android Auto como siempre, también si Android
        // las manda a la sesión de la radio.
        assertFalse(RadioSession.linkKey(KeyEvent.KEYCODE_MEDIA_NEXT, null))
        assertTrue(orders.onMediaButtonEvent(press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)))
        assertEquals(listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE), link.keys)
        // Con la música en la radio: a la radio (sin emisora, ⏭ no pone nada), y la sesión de la radio se las queda.
        RadioSession.radio(true, true, false, false)
        assertTrue(RadioSession.linkKey(KeyEvent.KEYCODE_MEDIA_NEXT, null))
        assertFalse(orders.onMediaButtonEvent(press(KeyEvent.KEYCODE_MEDIA_NEXT)))
        assertEquals(1, link.keys.size)
        assertNull(RadioPlayer.current())
        // Las que no son de la radio (el asistente), a Android Auto; las que manda el propio Android Auto, nunca de vuelta.
        assertFalse(RadioSession.linkKey(KeyEvent.KEYCODE_VOICE_ASSIST, null))
        RadioSession.radio(false, false, false, false)
        assertTrue(RadioSession.linkKey(KeyEvent.KEYCODE_VOICE_ASSIST, RadioSession.ANDROID_AUTO))
        // Android Auto sin conectar: la radio se queda la tecla (sin emisora, nada).
        link.connected = false
        assertFalse(orders.onMediaButtonEvent(press(KeyEvent.KEYCODE_MEDIA_PLAY)))
        assertNull(RadioPlayer.current())
        // Puro.
        assertEquals(RadioSession.KEY_PLAY, RadioSession.keyOrder(KeyEvent.KEYCODE_MEDIA_PLAY, true))
        assertEquals(RadioSession.KEY_PAUSE, RadioSession.keyOrder(KeyEvent.KEYCODE_MEDIA_PAUSE, false))
        assertEquals(RadioSession.KEY_PAUSE, RadioSession.keyOrder(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, true))
        assertEquals(RadioSession.KEY_PLAY, RadioSession.keyOrder(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, false))
        assertEquals(RadioSession.KEY_PLAY, RadioSession.keyOrder(KeyEvent.KEYCODE_HEADSETHOOK, false))
        assertEquals(RadioSession.KEY_NEXT, RadioSession.keyOrder(KeyEvent.KEYCODE_MEDIA_NEXT, false))
        assertEquals(RadioSession.KEY_PREVIOUS, RadioSession.keyOrder(KeyEvent.KEYCODE_MEDIA_PREVIOUS, false))
        assertEquals(RadioSession.KEY_STOP, RadioSession.keyOrder(KeyEvent.KEYCODE_MEDIA_STOP, false))
        assertEquals(RadioSession.KEY_NONE, RadioSession.keyOrder(KeyEvent.KEYCODE_VOICE_ASSIST, false))
        assertEquals(RadioSession.ROUTE_AA, RadioSession.keyRoute(false, false, RadioSession.KEY_PLAY))
        assertEquals(RadioSession.ROUTE_RADIO, RadioSession.keyRoute(true, false, RadioSession.KEY_PLAY))
        assertEquals(RadioSession.ROUTE_AA, RadioSession.keyRoute(true, false, RadioSession.KEY_NONE))
        assertEquals(RadioSession.ROUTE_RADIO, RadioSession.keyRoute(false, true, RadioSession.KEY_NEXT))
        assertEquals(RadioSession.ROUTE_DROP, RadioSession.keyRoute(true, true, RadioSession.KEY_NONE))
    }

    /** La sesión del enlace con Android Auto, de mentira: cuenta los cambios y apunta las teclas que le llegan. */
    private class FakeLink : RadioSession.Link {
        var changes = 0
        var connected = true
        val keys = mutableListOf<Int>()

        override fun linkChanged() {
            changes++
        }

        override fun keyToAndroidAuto(keyCode: Int): Boolean {
            if (!connected) return false
            keys.add(keyCode)
            return true
        }
    }

    @Test
    fun theTvTakingTheSoundIsNotAPause() {
        var paused = 0
        val radio = MediaFocus(app) { paused++ }
        val tv = MediaFocus(app) { }
        try {
            assertTrue(radio.acquire())
            assertFalse(MediaFocus.takenFrom(radio))
            assertTrue(tv.acquire())
            assertEquals(1, paused)
            assertTrue(MediaFocus.takenFrom(radio))
            assertFalse(MediaFocus.takenFrom(tv))
        } finally {
            tv.close()
            radio.close()
        }
        assertFalse(MediaFocus.takenFrom(radio))
    }

    /** Una app que se conecta a la radio por dentro de AndroidX (ServiceBinderImpl), como lo haría por Binder. */
    private class Client(service: MediaBrowserServiceCompat) {
        var connected: Boolean? = null
        val loaded = mutableMapOf<String, List<MediaBrowserCompat.MediaItem>>()
        private val binder = Binder()
        private val type = Class.forName("androidx.media.MediaBrowserServiceCompat\$ServiceCallbacks")
        private val impl: Any
        private val callbacks: Any

        init {
            // En AndroidX Media 1.7 lo tiene el servicio; en la 1.6, su Handler.
            impl = try {
                MediaBrowserServiceCompat::class.java.getDeclaredField("mServiceBinderImpl").apply { isAccessible = true }.get(service)!!
            } catch (_: NoSuchFieldException) {
                val handler = MediaBrowserServiceCompat::class.java.getDeclaredField("mHandler").apply { isAccessible = true }.get(service)!!
                handler.javaClass.getDeclaredField("mServiceBinderImpl").apply { isAccessible = true }.get(handler)!!
            }
            callbacks = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, m, args ->
                when (m.name) {
                    "asBinder" -> binder
                    "onConnect" -> { connected = true; null }
                    "onConnectFailed" -> { connected = false; null }
                    "onLoadChildren" -> {
                        @Suppress("UNCHECKED_CAST")
                        loaded[args!![0] as String] = (args[1] as List<MediaBrowserCompat.MediaItem>?).orEmpty()
                        null
                    }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args!![0]
                    "toString" -> "cliente de prueba"
                    else -> null
                }
            }
        }

        fun connect(pkg: String, uid: Int) =
            call("connect", arrayOf<Class<*>>(String::class.java, INT, INT, Bundle::class.java, type), pkg, 1, uid, null, callbacks)

        fun register(pkg: String, uid: Int) =
            call("registerCallbacks", arrayOf<Class<*>>(type, String::class.java, INT, INT, Bundle::class.java), callbacks, pkg, 1, uid, null)

        fun subscribe(id: String) =
            call("addSubscription", arrayOf<Class<*>>(String::class.java, IBinder::class.java, Bundle::class.java, type), id, Binder(), null, callbacks)

        private fun call(name: String, types: Array<Class<*>>, vararg args: Any?) {
            impl.javaClass.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(impl, *args)
            shadowOf(Looper.getMainLooper()).idle()
        }

        private companion object {
            val INT: Class<*> = Int::class.javaPrimitiveType!!
        }
    }

    // Robolectric no comunica el Binder de MediaSession con su controlador: se leen los valores que AndroidX guarda.
    private fun publishedMetadata(session: MediaSessionCompat) = sessionField(session, "mMetadata") as MediaMetadataCompat
    private fun publishedState(session: MediaSessionCompat) = sessionField(session, "mPlaybackState") as PlaybackStateCompat

    private fun sessionField(session: MediaSessionCompat, name: String): Any? {
        val impl = MediaSessionCompat::class.java.getDeclaredField("mImpl").apply { isAccessible = true }.get(session)
        var type: Class<*>? = impl.javaClass
        while (type != null) {
            try {
                return type.getDeclaredField(name).apply { isAccessible = true }.get(impl)
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            }
        }
        error("Missing media session field $name")
    }

    @Suppress("UNCHECKED_CAST")
    private fun target(metadata: PlaybackMetadata): CustomTarget<Bitmap> =
        PlaybackMetadata::class.java.getDeclaredField("image").apply { isAccessible = true }.get(metadata) as CustomTarget<Bitmap>
}
