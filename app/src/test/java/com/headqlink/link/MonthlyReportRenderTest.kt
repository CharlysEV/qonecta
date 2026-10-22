package com.headqlink.link

import android.app.Application
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
import java.util.TimeZone

/**
 * Lo que se ve de la 0.3.0: el informe del mes (con y sin comparación), su imagen para compartir y la ventana de
 * novedades por apartados. Comprueba los textos y, con -Ppreview, deja las capturas en app/build/preview
 * (informe.png, informe_primer_mes.png, informe_compartir.png, novedades.png).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class, qualifiers = "es-rES-xxhdpi")
class MonthlyReportRenderTest {
    private val ctx = android.view.ContextThemeWrapper(RuntimeEnvironment.getApplication().also { Str.init(it) },
        com.andrerinas.openheadunit.R.style.Theme_HeadQLink)
    private val tz = TimeZone.getDefault()

    private fun texts(v: View): List<String> {
        val out = ArrayList<String>()
        fun walk(x: View) {
            if (x is TextView) out.add(x.text.toString())
            if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i))
        }
        walk(v)
        return out
    }

    private fun october() = MonthlyReport.Summary().apply {
        year = 2026; month = 10; complete = true; fromCar = true; trips = 38; km = 1243.0; kwh = 201.4
        costEur = 52.3; petrolEur = 129.3; charges = 9; chargedKwh = 214.0; fastKwh = 61.0; fastestKw = 152.0
        chargeCostEur = 60.1; parks = 21; parkedPct = 6.8; parkedKwh = 4.8; parkedHours = 402.0
        longestKm = 312.0; longestMs = MonthlyReport.monthStart(2026, 10, tz) + 11 * 86_400_000L + 8 * 3_600_000L
        bestPer100 = 12.8; bestKm = 46.0; bestMs = MonthlyReport.monthStart(2026, 10, tz) + 3 * 86_400_000L
        topDayKm = 340.0; topDayMs = longestMs
    }

    private fun september() = MonthlyReport.Summary().apply {
        year = 2026; month = 9; complete = true; fromCar = true; trips = 31; km = 1102.0; kwh = 187.0; costEur = 49.0
    }

    @Test
    fun theReportSaysTheMonthItsFiguresAndTheChanges() {
        val t = texts(MonthlyReportView.build(ctx, october(), september(), false) { })
        assertTrue(t.toString(), t.contains("Tu octubre con el C10"))
        assertTrue(t.toString(), t.any { it.startsWith("1.243") })
        assertTrue(t.toString(), t.any { it.contains("↑ 13 %") }) // km: 1243 frente a 1102
        assertTrue(t.toString(), t.any { it.endsWith("Cambios frente a septiembre.") })
        assertTrue(t.toString(), t.contains("Perdió 6,8 % (4,8 kWh) en 402 h aparcado."))
        assertTrue(t.toString(), t.contains("★ Viaje más largo"))
        // Sin el mes de antes completo: sin cambios y con el aviso.
        val first = texts(MonthlyReportView.build(ctx, october(), null, false) { })
        assertTrue(first.none { it.contains("↑") || it.contains("↓") })
        assertTrue(first.any { it.contains("Aún sin comparación") })
    }

    @Test
    fun render() {
        org.junit.Assume.assumeTrue(System.getProperty("hql.preview") == "1")
        val dir = File(System.getProperty("hql.preview.dir")!!)
        dir.mkdirs()
        val w = (390 * ctx.resources.displayMetrics.density).toInt()
        save(MonthlyReportView.build(ctx, october(), september(), false) { }, w, 0, File(dir, "informe.png"))
        save(MonthlyReportView.build(ctx, october(), null, false) { }, w, 0, File(dir, "informe_primer_mes.png"))
        save(MonthlyReportView.shareCard(ctx, october()), 1080, 1350, File(dir, "informe_compartir.png"))
        val notes = UpdateCheck.sections(File(System.getProperty("hql.preview.notes") ?: "").let { if (it.isFile) it.readText() else SAMPLE })
        save(ReleaseNotesView.view(ctx, "0.3.0", notes), (340 * ctx.resources.displayMetrics.density).toInt(), 0, File(dir, "novedades.png"))
    }

    private fun save(v: View, w: Int, h: Int, f: File) {
        val pad = (16 * ctx.resources.displayMetrics.density).toInt()
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            if (h > 0) View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY) else View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        v.layout(0, 0, w, v.measuredHeight)
        val framed = h <= 0
        val b = Bitmap.createBitmap(w + if (framed) 2 * pad else 0, v.measuredHeight + if (framed) 2 * pad else 0, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(if (framed) 0xFF0A0E14.toInt() else 0)
        if (framed) c.translate(pad.toFloat(), pad.toFloat())
        v.draw(c)
        FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val SAMPLE = """## Qonecta+ 0.3.0

### Nuevo
- **Informe del mes:** tu mes en cifras.

### Arreglado
- **Modo extendido en el T03:** ya se abre.
"""
    }
}
