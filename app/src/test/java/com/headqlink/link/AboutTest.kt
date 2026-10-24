package com.headqlink.link

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
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
 * «Acerca de y créditos»: de quién es (CharlysEV) y de qué parte (HeadQLink de ryazrm) arriba, y los créditos de la
 * nube, los datos y las bibliotecas. Con -Ppreview, la captura acerca.png en app/build/preview.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "es-rES-xxhdpi")
class AboutTest {
    private val ctx = ContextThemeWrapper(RuntimeEnvironment.getApplication().also { Str.init(it) },
        com.andrerinas.openheadunit.R.style.Theme_HeadQLink)

    private fun texts(v: View): List<String> {
        val out = ArrayList<String>()
        fun walk(x: View) {
            if (x is TextView) out.add(x.text.toString())
            if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i))
        }
        walk(v)
        return out
    }

    @Test
    fun authorAndBaseOnTopAndEveryCredit() {
        val t = texts(About.view(ctx))
        assertTrue(t.toString(), t.contains("por CharlysEV"))
        assertTrue(t.toString(), t.contains("a partir de HeadQLink, de ryazrm"))
        // Primero el autor y la base, antes que cualquier otro crédito.
        assertTrue(t.indexOf("por CharlysEV") < t.indexOf("a partir de HeadQLink, de ryazrm"))
        for (who in listOf("ryazrm", "André Rinas", "Michael A. Reid", "txurtxil", "markoceri", "ProtossBlaster", "OpenStreetMap",
                "Open-Meteo.com", "DGT", "radio-browser.info", "HaGeZi", "Wikipedia", "LRCLIB", "three.js", "vinishows-1753d1", "Leaflet", "Nestopia")) {
            assertTrue(who, t.any { it.contains(who) })
        }
        assertTrue(About.versionByline(ctx).toString().endsWith("\npor CharlysEV"))
    }

    @Test
    fun theBylineNeverSplitsTheName() {
        val s = "v0.3.2\npor CharlysEV"
        val p = android.text.TextPaint().apply { textSize = 40f }
        fun lay(w: Int) = android.text.StaticLayout.Builder.obtain(s, 0, s.length, p, w).build()
        // Con sitio, palabras enteras; tan estrecho que «CharlysEV» no cabe, se parte y se nota.
        assertTrue(About.wholeWords(lay(2000), s))
        org.junit.Assert.assertFalse(About.wholeWords(lay(p.measureText("Charl").toInt() + 2), s))
    }

    @Test
    fun render() {
        org.junit.Assume.assumeTrue(System.getProperty("hql.preview") == "1")
        val dir = File(System.getProperty("hql.preview.dir")!!)
        dir.mkdirs()
        val v = About.view(ctx)
        val w = (360 * ctx.resources.displayMetrics.density).toInt()
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        v.layout(0, 0, w, v.measuredHeight)
        val b = Bitmap.createBitmap(w, v.measuredHeight, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(0xFF20252B.toInt())
        v.draw(c)
        FileOutputStream(File(dir, "acerca.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
