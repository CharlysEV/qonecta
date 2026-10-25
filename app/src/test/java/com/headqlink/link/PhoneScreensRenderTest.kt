package com.headqlink.link

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.time.Duration
import java.util.Locale

/**
 * Dibuja en el PC las pantallas del móvil (como CarPanelRenderTest con las del coche), para revisar el diseño sin el
 * móvil: gradlew :app:testGithubDebugUnitTest --tests "*PhoneScreensRender*" -Ppreview. PNG de 1080x2340 en
 * app/build/preview/movil_*.png. Sin -Ppreview se salta. Una pantalla que no arranque en Robolectric se salta sola.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class, qualifiers = "es-rES-w411dp-h891dp-port-xxhdpi")
class PhoneScreensRenderTest {
    @Test
    fun renderPhoneScreens() {
        assumeTrue(System.getProperty("hql.preview") == "1")
        val dir = File(System.getProperty("hql.preview.dir")!!)
        dir.mkdirs()
        Locale.setDefault(Locale.forLanguageTag("es-ES"))
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        Str.init(app)
        // Con la configuración inicial hecha, Inicio no salta al asistente.
        Config(app).setSetupDone(true)
        val screens: List<Pair<String, Class<out Activity>>> = listOf(
            "movil_inicio" to HomeActivity::class.java,
            "movil_comprobacion" to ChecklistActivity::class.java,
            "movil_diagnostico" to LogActivity::class.java,
            "movil_cuenta" to CarCloudActivity::class.java,
            "movil_configuracion" to SetupActivity::class.java,
            "movil_casa" to HomeAssistantActivity::class.java,
        )
        for ((name, cls) in screens) {
            try {
                val act = Robolectric.buildActivity(cls).setup().get()
                act.setTheme(com.andrerinas.openheadunit.R.style.Theme_HeadQLink)
                idle(800)
                save(act.window.decorView, File(dir, "$name.png"))
                println("PhoneScreensRender: $name OK")
            } catch (e: Throwable) {
                println("PhoneScreensRender: $name no se pudo dibujar: $e")
            }
        }
        // Primera instalación, paso 2: «Tu coche» (C10, ProMax, REEV o T03) encima de qué ver en el coche.
        try {
            Config(app).setSetupDone(false)
            val state = android.os.Bundle()
            state.putInt(SetupActivity.EXTRA_STEP, 1)
            state.putBoolean("responsibility_accepted", true)
            val act = Robolectric.buildActivity(SetupActivity::class.java).create(state).start().postCreate(state).resume()
                .visible().get()
            idle(800)
            save(act.window.decorView, File(dir, "movil_primera_coche.png"))
            println("PhoneScreensRender: movil_primera_coche OK")
        } catch (e: Throwable) {
            println("PhoneScreensRender: movil_primera_coche no se pudo dibujar: $e")
        } finally {
            Config(app).setSetupDone(true)
        }
        // «Acerca de y créditos», con «Invítame a un café».
        try {
            val act = Robolectric.buildActivity(Activity::class.java).setup().get()
            act.setTheme(com.andrerinas.openheadunit.R.style.Theme_HeadQLink)
            val sv = android.widget.ScrollView(act)
            sv.setBackgroundColor(0xFF111821.toInt())
            sv.addView(About.view(act))
            act.setContentView(sv)
            idle(300)
            save(sv, File(dir, "movil_acerca.png"))
            println("PhoneScreensRender: movil_acerca OK")
        } catch (e: Throwable) {
            println("PhoneScreensRender: movil_acerca no se pudo dibujar: $e")
        }
        // Viajes con una carga rápida en marcha: la tarjeta «Cargando ahora» arriba.
        try {
            val act = Robolectric.buildActivity(Activity::class.java).setup().get()
            act.setTheme(com.andrerinas.openheadunit.R.style.Theme_HeadQLink)
            val pi = PhoneInsights(act)
            val d = PhoneInsights.Data()
            d.account = true
            val now = System.currentTimeMillis()
            val s = ChargeSession(now - 8 * 60_000L, 34.0, true, null, 81.9, 180.0, 800.0)
            val pts = listOf(34.0 to 120.0, 38.9 to 135.0, 44.4 to 130.0, 49.7 to 110.0, 54.2 to 85.0)
            for ((i, p) in pts.withIndex()) {
                val t = now - (pts.size - 1 - i) * 2 * 60_000L
                s.observe(t, p.first, p.second, true, false, Double.NaN, t)
            }
            d.live = s
            val m = PhoneInsights::class.java.getDeclaredMethod("build", PhoneInsights.Data::class.java)
            m.isAccessible = true
            m.invoke(pi, d)
            act.setContentView(pi.view())
            idle(300)
            save(pi.view(), File(dir, "movil_viajes_cargando.png"))
            println("PhoneScreensRender: movil_viajes_cargando OK")
        } catch (e: Throwable) {
            println("PhoneScreensRender: movil_viajes_cargando no se pudo dibujar: $e")
            e.printStackTrace()
        }
    }

    private fun idle(ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(50L, left)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
            left -= step
        }
    }

    private fun save(v: View, f: File) {
        val w = 1080
        val h = 2340
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
