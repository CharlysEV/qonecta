package com.headqlink.link

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * La pantalla del karaoke sin sesiones multimedia: parado (la letra entera con la línea de ahora), en marcha (solo la de
 * ahora y la siguiente), sin letra y en la pantalla partida. Con -Ppreview, karaoke.png, karaoke_marcha.png y
 * karaoke_partida.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "es-rES")
class KaraokeScreenTest {
    private val ctx = RuntimeEnvironment.getApplication().also { Str.init(it) }
    private val full = 1920 - 96
    private val split = 810
    private val height = 882

    private fun host(w: Int) = object : CarScreen.Host {
        override fun context() = ctx
        override fun setContent(v: View) {}
        override fun width() = w
        override fun height() = height
        override fun post(r: Runnable) = r.run()
        override fun setFullscreen(on: Boolean) {}
    }

    private val song = Lyrics.Result().also {
        it.synced = Lyrics.parseLrc("""
            [00:01.00] Por la carretera voy cantando
            [00:05.00] con el sol de frente y sin prisa
            [00:09.00] la batería llena y la música alta
            [00:13.00]
            [00:16.00] que se acabe el camino nunca
            [00:20.00] que la próxima parada sea la playa
        """.trimIndent())
    }

    private fun build(w: Int, r: Lyrics.Result?, pos: Long, moving: Boolean): View {
        val k = KaraokeScreen(false)
        val v = k.create(host(w))
        k.showForTest("Canción de ejemplo", "Grupo de ejemplo", r, pos, moving)
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, height)
        return v
    }

    private fun views(v: View): List<TextView> {
        val out = ArrayList<TextView>()
        fun walk(x: View) {
            if (x is TextView) out.add(x)
            if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i))
        }
        walk(v)
        return out
    }

    private fun texts(v: View) = views(v).map { it.text.toString() }

    @Test
    fun stoppedTheWholeLyricsShowWithTheCurrentLineHighlighted() {
        val v = build(full, song, 9_500, false)
        val t = texts(v)
        for (s in listOf("Canción de ejemplo", "Grupo de ejemplo", "Por la carretera voy cantando", "la batería llena y la música alta",
                "♪", "que la próxima parada sea la playa", "Letras: LRCLIB (lrclib.net)")) {
            assertTrue(s, t.contains(s))
        }
        val cur = views(v).first { it.text.toString() == "la batería llena y la música alta" }
        val other = views(v).first { it.text.toString() == "Por la carretera voy cantando" }
        assertTrue(cur.textSize > other.textSize && cur.alpha > other.alpha)
    }

    @Test
    fun whileDrivingOnlyTheCurrentAndTheNextLineShow() {
        val t = texts(build(split, song, 5_200, true))
        assertTrue(t.toString(), t.contains("con el sol de frente y sin prisa"))
        assertTrue(t.toString(), t.contains("la batería llena y la música alta"))
        assertTrue(t.toString(), !t.contains("Por la carretera voy cantando") && !t.contains("que la próxima parada sea la playa"))
    }

    @Test
    fun withoutLyricsItSaysWhy() {
        assertTrue(texts(build(split, Lyrics.Result(), 0, false)).contains("No encuentro la letra de esta canción."))
        assertTrue(texts(build(split, null, 0, false)).contains("Buscando la letra…"))
        val plain = Lyrics.Result().also { it.plain = "Una letra\nsin tiempos" }
        val t = texts(build(full, plain, 0, false))
        assertTrue(t.toString(), t.contains("Letra sin sincronizar") && t.contains("Una letra\nsin tiempos"))
        val inst = Lyrics.Result().also { it.instrumental = true }
        assertTrue(texts(build(full, inst, 0, false)).contains("Instrumental: sin letra."))
    }

    @Test
    fun theVoiceLevelsShowWithMediumByDefaultAndATapChangesIt() {
        val v = build(split, song, 9_500, false)
        val pills = views(v).filter { it.text.toString() in listOf("Normal", "Suave", "Media", "Sin voz") }
        assertTrue(pills.map { it.text.toString() }.toString(), pills.size == 4 && texts(v).contains("Voz"))
        val medium = pills.first { it.text.toString() == "Media" }
        assertTrue(medium.currentTextColor == CarStyle.ON_ACCENT)
        val off = pills.first { it.text.toString() == "Sin voz" }
        off.performClick()
        assertTrue(off.currentTextColor == CarStyle.ON_ACCENT && medium.currentTextColor == CarStyle.TEXT)
        assertTrue(Config(ctx).karaokeVocal() == 3)
        Config(ctx).setKaraokeVocal(2)
    }

    @Test
    fun render() {
        org.junit.Assume.assumeTrue(System.getProperty("hql.preview") == "1")
        val dir = File(System.getProperty("hql.preview.dir")!!)
        dir.mkdirs()
        for ((name, w, moving) in listOf(Triple("karaoke", full, false), Triple("karaoke_marcha", full, true), Triple("karaoke_partida", split, false))) {
            val v = build(w, song, 9_500, moving)
            val b = Bitmap.createBitmap(w, height, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            c.drawColor(CarStyle.BG)
            v.draw(c)
            FileOutputStream(File(dir, "$name.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
