package com.headqlink.link

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import androidx.media3.common.Player
import com.bumptech.glide.request.target.CustomTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * El logo de una emisora en Android Auto (foro #341): centrado en un lienzo cuadrado, sin agrandarse más de 2× ni ocupar
 * más del 60 % del lado, sobre el color de su borde o el oscuro de la app (el claro si el logo es oscuro). Con -Ppreview
 * deja los lienzos en app/build/preview (logo_*.png) para verlos.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class PlaybackLogoTest {
    private val app = RuntimeEnvironment.getApplication().also { Str.init(it) }
    private val side = PlaybackMetadata.FRAME_PX

    @Test
    fun theLogoGoesCenteredAndSmall() {
        // Pequeño: el doble como mucho.
        assertEquals(listOf(192, 192, 128, 128), PlaybackMetadata.frameRect(64, 64, side).toList())
        assertEquals(listOf(240, 240, 32, 32), PlaybackMetadata.frameRect(16, 16, side).toList())
        // Mediano: el doble mientras no pase del 60 % del lado (307 px de 512).
        assertEquals(listOf(106, 106, 300, 300), PlaybackMetadata.frameRect(150, 150, side).toList())
        assertEquals(listOf(102, 102, 307, 307), PlaybackMetadata.frameRect(200, 200, side).toList())
        // Grande o apaisado: el lado largo, al 60 %; sin deformar.
        assertEquals(listOf(102, 102, 307, 307), PlaybackMetadata.frameRect(512, 512, side).toList())
        assertEquals(listOf(102, 179, 307, 154), PlaybackMetadata.frameRect(400, 200, side).toList())
        assertEquals(listOf(192, 224, 128, 64), PlaybackMetadata.frameRect(64, 32, side).toList())
        // Nunca vacío.
        assertEquals(listOf(255, 255, 2, 2), PlaybackMetadata.frameRect(0, 0, side).toList())
        for (w in listOf(1, 24, 64, 100, 150, 300, 512, 1000)) for (h in listOf(1, 32, 128, 512)) {
            val r = PlaybackMetadata.frameRect(w, h, side)
            assertTrue("$w×$h", r[2] <= Math.round(w * PlaybackMetadata.FRAME_GROW) && r[3] <= Math.round(h * PlaybackMetadata.FRAME_GROW))
            assertTrue("$w×$h", maxOf(r[2], r[3]) <= Math.round(side * PlaybackMetadata.FRAME_SHARE))
            // Centrado: el mismo margen a cada lado (±1 px).
            assertTrue("$w×$h", Math.abs(side - r[0] - r[2] - r[0]) <= 1 && Math.abs(side - r[1] - r[3] - r[1]) <= 1)
        }
    }

    @Test
    fun theBackgroundComesFromTheLogo() {
        // Sobre blanco (un JPG): blanco, y el lienzo parece el logo entero.
        assertEquals(Color.WHITE, PlaybackMetadata.frameBackground(pixels(logo(64, 64, Color.WHITE, Color.RED)), 64, 64))
        // Transparente y claro o de colores: el oscuro de la app.
        assertEquals(PlaybackMetadata.FRAME_DARK, PlaybackMetadata.frameBackground(pixels(logo(64, 64, Color.TRANSPARENT, Color.YELLOW)), 64, 64))
        // Transparente y oscuro (letras negras): el claro, que sobre el oscuro no se vería.
        assertEquals(PlaybackMetadata.FRAME_LIGHT, PlaybackMetadata.frameBackground(pixels(logo(64, 64, Color.TRANSPARENT, Color.BLACK)), 64, 64))
        // Un borde opaco variado (una foto): el oscuro.
        val stripes = IntArray(64 * 64) { if ((it % 64) / 8 % 2 == 0) Color.BLACK else Color.WHITE }
        assertEquals(PlaybackMetadata.FRAME_DARK, PlaybackMetadata.frameBackground(stripes, 64, 64))
        assertEquals(PlaybackMetadata.FRAME_DARK, PlaybackMetadata.frameBackground(IntArray(0), 0, 0))
    }

    @Test
    fun theCanvasKeepsTheLogoSharpAndItsTransparency() {
        val art = PlaybackMetadata.framed(logo(64, 64, Color.TRANSPARENT, Color.YELLOW))
        assertEquals(side, art.width)
        assertEquals(side, art.height)
        // Fuera del logo, el fondo (opaco); lo transparente del logo, también fondo; su centro, el logo.
        assertEquals(PlaybackMetadata.FRAME_DARK, art.getPixel(10, 10))
        assertEquals(PlaybackMetadata.FRAME_DARK, art.getPixel(side - 10, side / 2))
        assertEquals(PlaybackMetadata.FRAME_DARK, art.getPixel(194, 194))
        assertEquals(Color.YELLOW, art.getPixel(side / 2, side / 2))
        // Un logo opaco de 64 px: 128 px (el doble) y no más, de 192 a 320; fuera de su cuadro, fondo.
        val stripes = Bitmap.createBitmap(IntArray(64 * 64) { if ((it % 64) / 8 % 2 == 0) Color.BLACK else Color.WHITE },
            64, 64, Bitmap.Config.ARGB_8888)
        val opaque = PlaybackMetadata.framed(stripes)
        assertEquals(Color.BLACK, opaque.getPixel(194, side / 2))
        assertEquals(Color.WHITE, opaque.getPixel(317, side / 2))
        for ((x, y) in listOf(189 to 256, 322 to 256, 256 to 189, 256 to 322)) {
            assertEquals("$x,$y", PlaybackMetadata.FRAME_DARK, opaque.getPixel(x, y))
        }
        preview("logo_transparente", art)
        preview("logo_jpg_blanco", PlaybackMetadata.framed(logo(150, 100, Color.WHITE, Color.rgb(200, 0, 40))))
        preview("logo_negro", PlaybackMetadata.framed(logo(300, 300, Color.TRANSPARENT, Color.BLACK)))
    }

    @Test
    fun onlyTheStationLogoGoesInTheCanvas() {
        val player = mock<Player> {
            on { playWhenReady } doReturn true
            on { playbackState } doReturn Player.STATE_READY
        }
        val session = MediaSessionCompat(app, "prueba")
        val metadata = PlaybackMetadata(app, player, session)
        try {
            // Una canción del móvil: su portada, entera (512 de lado como mucho).
            metadata.setNowPlaying("Canción", "Grupo", "Álbum", "file:///missing/cover.png", "", 180_000, false)
            target(metadata).onResourceReady(Bitmap.createBitmap(1024, 512, Bitmap.Config.ARGB_8888), null)
            val cover = art(session)
            assertEquals(listOf(512, 256), listOf(cover.width, cover.height))
            // Una emisora: su logo, en el lienzo.
            metadata.setNowPlaying("Radio Uno", "Radio", "Radio Uno", "file:///missing/logo.png", "Radio Uno", 0, true)
            target(metadata).onResourceReady(logo(100, 60, Color.TRANSPARENT, Color.CYAN), null)
            val framed = art(session)
            assertEquals(listOf(side, side), listOf(framed.width, framed.height))
            assertEquals(Color.CYAN, framed.getPixel(side / 2, side / 2))
            assertEquals(PlaybackMetadata.FRAME_DARK, framed.getPixel(4, 4))
        } finally {
            metadata.close()
            session.release()
        }
    }

    /** Un logo de mentira: el fondo (fill) y un disco del color del logo en el centro. */
    private fun logo(w: Int, h: Int, fill: Int, ink: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        b.eraseColor(fill)
        Canvas(b).drawCircle(w / 2f, h / 2f, minOf(w, h) * 0.35f, Paint().apply { color = ink })
        return b
    }

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun preview(name: String, b: Bitmap) {
        if (System.getProperty("hql.preview") != "1") return
        val dir = File(System.getProperty("hql.preview.dir")!!).apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun target(metadata: PlaybackMetadata): CustomTarget<Bitmap> =
        PlaybackMetadata::class.java.getDeclaredField("image").apply { isAccessible = true }.get(metadata) as CustomTarget<Bitmap>

    // Robolectric no comunica el Binder de MediaSession con su controlador: lo que AndroidX guarda.
    private fun art(session: MediaSessionCompat): Bitmap {
        val impl = MediaSessionCompat::class.java.getDeclaredField("mImpl").apply { isAccessible = true }.get(session)
        var type: Class<*>? = impl.javaClass
        while (type != null) {
            try {
                val md = type.getDeclaredField("mMetadata").apply { isAccessible = true }.get(impl) as MediaMetadataCompat
                return md.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART)
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            }
        }
        error("Missing media session field mMetadata")
    }
}
