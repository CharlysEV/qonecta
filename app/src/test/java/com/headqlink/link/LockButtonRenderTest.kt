package com.headqlink.link

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Dibuja en el PC la fila de órdenes de «Mi coche» (a letra normal y a 1,3) y el widget 4x3 con el coche cerrado,
 * abierto, sin saberlo y mandando una orden, para ver el botón de abrir/cerrar sin el móvil:
 *   gradlew :app:testGithubDebugUnitTest --tests "*LockButtonRender*" -Ppreview [-PpreviewSuffix=_despues]
 * PNG en app/build/preview/cierre_*.png. Sin -Ppreview se salta.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class, qualifiers = "es-rES-w411dp-h891dp-port-xxhdpi")
class LockButtonRenderTest {
    /** Cada estado: su nombre, el cierre guardado (null: no se sabe) y la orden que se está mandando (null: ninguna). */
    private val states = listOf(
        Triple("cerrado", true, null),
        Triple("abierto", false, null),
        Triple("desconocido", null, null),
        Triple("enviando", true, "unlock"),
    )

    @Test
    fun renderTheOrdersRowAndTheWidget() {
        assumeTrue(System.getProperty("hql.preview") == "1")
        val dir = File(System.getProperty("hql.preview.dir")!!)
        val suffix = System.getProperty("hql.preview.suffix") ?: ""
        dir.mkdirs()
        Locale.setDefault(Locale.forLanguageTag("es-ES"))
        val app = RuntimeEnvironment.getApplication()
        Str.init(app)
        CarCloudStore.boxForTest = LockButtonTest.MemBox()
        val sending = CarOrders::class.java.getDeclaredField("sending").apply { isAccessible = true }
        try {
            Config(app).putBool(CarOrders.K_ENABLED, true)
            CarCloudStore(app).savePin("1234")
            for (scale in listOf(1.0f, 1.3f)) {
                RuntimeEnvironment.setFontScale(scale)
                val act = Robolectric.buildActivity(Activity::class.java).setup().get()
                act.setTheme(com.andrerinas.openheadunit.R.style.Theme_HeadQLink)
                // Cada estado se dibuja al momento (la fila se repinta sola con el estado de ahora) y se apilan.
                val parts = ArrayList<Bitmap>()
                for ((name, locked, order) in states) {
                    set(app, locked, order, sending)
                    val col = LinearLayout(act)
                    col.orientation = LinearLayout.VERTICAL
                    col.setBackgroundColor(0xFF0B1117.toInt())
                    val pad = (16 * act.resources.displayMetrics.density).toInt()
                    col.setPadding(pad, pad, pad, 0)
                    val title = TextView(act)
                    title.text = name
                    title.setTextColor(PhoneCharts.FAINT)
                    col.addView(title)
                    col.addView(PhoneCarOrders(act).view())
                    act.setContentView(col)
                    shadowOf(Looper.getMainLooper()).idle()
                    parts.add(draw(col))
                }
                val all = Bitmap.createBitmap(1080, parts.sumOf { it.height }, Bitmap.Config.ARGB_8888)
                var y = 0f
                for (p in parts) {
                    Canvas(all).drawBitmap(p, 0f, y, null)
                    y += p.height
                }
                FileOutputStream(File(dir, "cierre_micoche_x${if (scale > 1f) "13" else "10"}$suffix.png")).use {
                    all.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            RuntimeEnvironment.setFontScale(1.0f)
            for ((name, locked, order) in states) {
                set(app, locked, order, sending)
                val b = WidgetShots.render(app, LinkWidgetViews.build(app, WidgetUpdater.glance(app), LinkGlance.Size.WIDE_CAR),
                    WidgetShots.WIDE_W, WidgetShots.PREVIEW_H)
                WidgetShots.save(b, File(dir, "cierre_widget_$name$suffix.png"))
            }
        } finally {
            sending.set(null, null)
            CarOrders.forgetLock(app)
            RuntimeEnvironment.setFontScale(1.0f)
            CarCloudStore.boxForTest = null
        }
    }

    private fun set(app: Application, locked: Boolean?, order: String?, sending: java.lang.reflect.Field) {
        CarOrders.forgetLock(app)
        if (locked != null) CarOrders.noteLock(app, locked, System.currentTimeMillis())
        sending.set(null, order)
    }

    private fun draw(v: View): Bitmap {
        val w = 1080
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        v.layout(0, 0, w, v.measuredHeight)
        val b = Bitmap.createBitmap(w, v.measuredHeight, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        return b
    }
}
