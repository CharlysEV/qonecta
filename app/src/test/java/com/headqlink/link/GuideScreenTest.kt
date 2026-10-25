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
 * La pantalla de la guía del camino con la pantalla completa (tarjeta y lista lado a lado) y partida junto a AA (en una
 * columna), sin la guía en marcha: el estado se le pasa a mano. Con -Ppreview, guia.png y guia_partida.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "es-rES")
class GuideScreenTest {
    private val ctx = RuntimeEnvironment.getApplication().also { Str.init(it) }
    private val kmLat = 1 / 111.2
    private val kmLon = 1 / (111.2 * Math.cos(Math.toRadians(40.0)))

    /** C10: 1920x882 y la barra de 96; partida, AA se queda 1014 y la guía 810. */
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

    private fun place(id: Long, title: String, dLatKm: Double, dLonKm: Double, text: String) =
        RoadGuide.Place(id, title, 40.0 + dLatKm * kmLat, -3.0 + dLonKm * kmLon).also {
            it.views = 900
            it.bytes = 20000
            it.extract = text
            it.detailed = true
        }

    private fun moving() = RoadGuide.State().also {
        it.status = RoadGuide.Status.READY
        it.moving = true
        it.voice = true
        it.lat = 40.0
        it.lon = -3.0
        it.heading = 0.0
        it.current = place(1, "Castillo de Ejemplo", 2.0, 2.0,
            "El castillo de Ejemplo es una fortaleza medieval levantada en el siglo XII sobre un cerro que domina el valle. " +
                "Conserva la muralla, la torre del homenaje y un aljibe. Fue declarado Bien de Interés Cultural en 1949.")
        it.list.add(place(2, "Ermita de Ejemplo", 5.0, -1.5, "Una ermita románica."))
        it.list.add(place(3, "Embalse de Ejemplo", 9.0, 0.5, "Un embalse."))
        it.pool.add(it.current!!)
        it.pool.addAll(it.list)
    }

    private fun build(w: Int, s: RoadGuide.State): Pair<GuideScreen, View> {
        val g = GuideScreen(false)
        val v = g.create(host(w))
        g.onGuide(s)
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, height)
        return g to v
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

    private fun left(v: View, root: View): Int {
        var x = 0
        var c: View? = v
        while (c != null && c !== root) {
            x += c.left
            c = c.parent as? View
        }
        return x
    }

    @Test
    fun fullScreenPutsTheCardAndTheListSideBySide() {
        val (_, v) = build(full, moving())
        val t = texts(v)
        for (s in listOf("Guía del camino", "Voz", "Castillo de Ejemplo", "Más adelante", "Ermita de Ejemplo", "Contar", "Llévame",
                "Textos y fotos: Wikipedia y Wikimedia Commons (CC BY-SA)")) {
            assertTrue(s, t.contains(s))
        }
        assertTrue(t.toString(), t.any { it.startsWith("2,") && it.endsWith(" km · a la derecha") })
        val list = views(v).first { it.text.toString() == "Más adelante" }
        assertTrue(left(list, v) > full / 2)
    }

    @Test
    fun splitScreenStacksThemInOneColumnAndATapMovesAPlaceToTheCard() {
        val (_, v) = build(split, moving())
        val list = views(v).first { it.text.toString() == "Más adelante" }
        assertTrue(left(list, v) < split / 4)
        assertTrue(texts(v).indexOf("Castillo de Ejemplo") < texts(v).indexOf("Ermita de Ejemplo"))
        // Tocar la ermita la sube a la tarjeta (y el castillo pasa a la lista).
        (views(v).first { it.text.toString() == "Ermita de Ejemplo" }.parent.parent as View).performClick()
        val t = texts(v)
        assertTrue(t.toString(), t.indexOf("Ermita de Ejemplo") < t.indexOf("Castillo de Ejemplo"))
        assertTrue(t.toString(), t.contains("Una ermita románica."))
    }

    @Test
    fun stoppedItShowsWalkingTimesAndWithoutDataItSaysWhy() {
        val s = moving().also { it.moving = false }
        val t = texts(build(full, s).second)
        assertTrue(t.toString(), t.contains("Cerca de aquí, a pie"))
        assertTrue(t.toString(), t.any { it.endsWith("min a pie") })
        val waiting = texts(build(split, RoadGuide.State()).second)
        assertTrue(waiting.toString(), waiting.contains("Esperando la posición del GPS…"))
        val offline = texts(build(split, RoadGuide.State().also { it.status = RoadGuide.Status.OFFLINE; it.lat = 40.0; it.lon = -3.0 }).second)
        assertTrue(offline.toString(), offline.contains("Sin conexión con Wikipedia: lo vuelvo a intentar en un minuto."))
    }

    @Test
    fun whileItTalksAStopButtonCutsItOff() {
        val quiet = texts(build(split, moving()).second)
        val (_, v) = build(split, moving().also { it.talking = true })
        val stop = views(v).first { it.text.toString() == "■  Parar" }
        assertTrue(stop.visibility == View.VISIBLE)
        // Callado, no está a la vista.
        assertTrue(quiet.toString(), views(build(split, moving()).second).first { it.text.toString() == "■  Parar" }.visibility == View.GONE)
        stop.performClick()
        assertTrue(stop.visibility == View.GONE)
    }

    @Test
    fun splittingTheScreenWithTheGuideOpenStacksItInOneColumn() {
        val (_, v) = build(full, moving())
        val label = { views(v).first { it.text.toString() == "Más adelante" } }
        assertTrue(left(label(), v) > full / 2)
        // Pantalla partida: el mismo GuideScreen, más estrecho.
        v.measure(View.MeasureSpec.makeMeasureSpec(split, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, split, height)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        v.measure(View.MeasureSpec.makeMeasureSpec(split, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, split, height)
        assertTrue(left(label(), v) < split / 4)
        assertTrue(texts(v).contains("Castillo de Ejemplo"))
    }

    @Test
    fun theTriviaAsksAboutNearbyPlacesAndSaysIfYouGotItRight() {
        val (_, v) = build(full, moving())
        views(v).first { it.text.toString() == "?  Trivial" }.performClick()
        val t = texts(v)
        assertTrue(t.toString(), t.any { it.startsWith("¿") } && t.contains("0 de 0 aciertos"))
        // Se toca la respuesta buena (la de «más cerca» es el castillo; la del siglo, el XII; etc.): se buscan las tres y se
        // prueba la primera; el resultado sale en verde o con la buena.
        val opts = views(v).filter { it.isClickable && it.text.isNotEmpty() && !it.text.startsWith("?") && !it.text.startsWith("‹") &&
            !it.text.startsWith("Siguiente") && it.text.toString() !in listOf("Voz", "Sin voz", "■  Parar", "Contar", "Llévame") }
        assertTrue(opts.toString(), opts.size >= 3)
        opts[0].performClick()
        val after = texts(v)
        assertTrue(after.toString(), after.contains("¡Correcto!") || after.any { it.startsWith("No, era: ") })
        assertTrue(after.toString(), after.any { it.endsWith("de 1 aciertos") })
    }

    @Test
    fun render() {
        org.junit.Assume.assumeTrue(System.getProperty("hql.preview") == "1")
        val dir = File(System.getProperty("hql.preview.dir")!!)
        dir.mkdirs()
        for ((name, w) in listOf("guia" to full, "guia_partida" to split, "guia_trivial" to full)) {
            val v = build(w, moving()).second
            if (name == "guia_trivial") {
                views(v).first { it.text.toString() == "?  Trivial" }.performClick()
                v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                v.layout(0, 0, w, height)
            }
            val b = Bitmap.createBitmap(w, height, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            c.drawColor(CarStyle.BG)
            v.draw(c)
            FileOutputStream(File(dir, "$name.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
