package com.headqlink.link

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.andrerinas.openheadunit.R
import org.junit.Assert.assertEquals
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
 * La novedad estrella de la 0.2.71 (precisión de la ubicación de Google): sus textos según el estado y, con -Ppreview,
 * el cartel pintado (star_activada.png, star_apagada.png) en app/build/preview.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class, qualifiers = "es-rES-xxhdpi")
class StarFeatureTest {
    private val ctx: Application = RuntimeEnvironment.getApplication().also { Str.init(it) }

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
    fun textsFollowTheState() {
        val on = texts(StarFeature.view(ctx, 1))
        assertEquals("★ NOVEDAD ESTRELLA ★", on[0])
        assertEquals("Menos cortes por Wi-Fi", on[1])
        assertTrue(on.contains("Ahora la tienes activada"))
        assertTrue(on.any { it.startsWith("El GPS sigue funcionando igual") })
        assertTrue(texts(StarFeature.view(ctx, 0)).contains("Ya la tienes apagada ✓"))
        // Sin saber (sin Google o con la ubicación apagada): sin línea de estado.
        val unknown = texts(StarFeature.view(ctx, -1))
        assertTrue(unknown.none { it == ctx.getString(R.string.hql_star_state_on) || it == ctx.getString(R.string.hql_star_state_off) })
    }

    @Test
    fun render() {
        org.junit.Assume.assumeTrue(System.getProperty("hql.preview") == "1")
        val dir = File(System.getProperty("hql.preview.dir")!!)
        dir.mkdirs()
        for ((state, name) in listOf(1 to "star_activada", 0 to "star_apagada")) {
            val v = StarFeature.view(ctx, state)
            val w = (340 * ctx.resources.displayMetrics.density).toInt()
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            v.layout(0, 0, w, v.measuredHeight)
            val b = Bitmap.createBitmap(w, v.measuredHeight, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            c.drawColor(0xFF20252B.toInt())
            v.draw(c)
            FileOutputStream(File(dir, "$name.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
