package com.headqlink.link

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Los vídeos y la TV al bajarse del coche (como la radio desde la 0.3.15): si se va la salida de audio (el Bluetooth del
 * coche, unos auriculares), el reproductor se pausa y no sigue por el altavoz del móvil; ▶ lo vuelve a poner.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class VideoOutputGoneTest {
    private val app: Context = RuntimeEnvironment.getApplication().also { Str.init(it) }
    private val looper = shadowOf(Looper.getMainLooper())
    private val players = mutableListOf<ExoPlayer>()
    private var video: VideoPlayer? = null

    /** Se va el Bluetooth del coche (o unos auriculares): Android lo avisa así. */
    private fun outputGone() {
        app.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        looper.idle()
    }

    /** Un reproductor sonando; un fichero que no existe: es de verdad, pero no hace falta que suene nada. */
    private fun playing(p: ExoPlayer): ExoPlayer {
        players += p
        p.setMediaItem(MediaItem.fromUri("file:///nada/canal.ts"))
        p.prepare()
        p.play()
        looper.idle()
        assertTrue(p.playWhenReady)
        return p
    }

    @After
    fun release() {
        video?.release()
        players.forEach { it.release() }
        looper.idle()
    }

    @Test
    fun theTvPausesWhenTheCarAudioGoes() {
        // La TV (IptvScreen): con y sin HTTP Basic.
        val tv = playing(VideoPlayer.newPlayer(app, null, null))
        val withLogin = playing(VideoPlayer.newPlayer(app, "tv.example", "Basic eDp5"))
        outputGone()
        assertFalse(tv.playWhenReady)
        assertFalse(withLogin.playWhenReady)
        // Dentro del coche, ▶ la vuelve a poner.
        tv.play()
        looper.idle()
        assertTrue(tv.playWhenReady)
    }

    @Test
    fun aLocalVideoPausesWhenTheCarAudioGoes() {
        val host = object : CarScreen.Host {
            private val main = Handler(Looper.getMainLooper())
            override fun context() = app
            override fun setContent(v: View) {}
            override fun width() = 1920
            override fun height() = 720
            override fun post(r: Runnable) {
                main.post(r)
            }

            override fun setFullscreen(on: Boolean) {}
        }
        val item = MediaLibrary.Item(Uri.parse("file:///nada/pelicula.mp4"), "Película", "Vídeos", 0, 0, false, "ms",
                null, null)
        val v = VideoPlayer(host, listOf(item)) {}
        video = v
        v.open(0)
        looper.idle()
        assertTrue(v.playWhenReady())
        outputGone()
        // En pausa con su vídeo (no soltado): ▶ sigue donde iba.
        assertTrue(v.playing())
        assertFalse(v.playWhenReady())
    }

    @Test
    fun onlyTheOutputGoingCountsAsOutputGone() {
        assertTrue(VideoPlayer.outputGone(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY))
        assertFalse(VideoPlayer.outputGone(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST))
        assertFalse(VideoPlayer.outputGone(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS))
        assertFalse(VideoPlayer.outputGone(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST))
    }
}
