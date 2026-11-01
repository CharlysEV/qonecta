package com.headqlink.link

import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media3.common.Player
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

/**
 * La radio en Android Auto (foro #297) y su logo en los controles (#286): qué se publica (canción y emisora), los
 * estados, el árbol de favoritos y recientes, y que un «▶» sin emisora no la pone a sonar.
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
        RadioPlayer.stop()
    }

    @After
    fun release() {
        RadioPlayer.stop()
        RadioSession.browserDown()
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
        assertEquals(listOf("Favorites", "Recent"), RadioLibrary.children(app, RadioLibrary.ROOT).map { it.description.title.toString() })
        assertTrue(RadioLibrary.children(app, RadioLibrary.EMPTY_ROOT).isEmpty())
        assertTrue(RadioLibrary.trusted("com.google.android.projection.gearhead", 10_200, 10_100))
        assertTrue(RadioLibrary.trusted("com.headqlink.app", 10_100, 10_100))
        assertFalse(RadioLibrary.trusted("com.ejemplo.otra", 10_300, 10_100))
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
            assertTrue(session.isActive)
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
        assertNull(RadioPlayer.current())
        assertFalse(RadioPlayer.isPlaying())
        assertEquals(PlaybackStateCompat.STATE_NONE, publishedState(session).state)
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
            assertEquals(RadioLibrary.EMPTY_ROOT, service.onGetRoot("com.ejemplo.otra", 10_300, null)!!.rootId)
            val recent = Bundle().apply { putBoolean(MediaBrowserServiceCompat.BrowserRoot.EXTRA_RECENT, true) }
            assertNull(service.onGetRoot("com.android.systemui", 10_400, recent))
        } finally {
            controller.destroy()
        }
        assertNull(RadioPlayer.current())
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
