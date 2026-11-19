package com.headqlink.link

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Iconos vectoriales multicapa del panel extendido: se dibujan sin fallos en cualquier tamaño,
 * modo claro/oscuro, activo/inactivo y alerta, y sus tamaños se ajustan a la altura del botón.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = Application::class)
class PanelIconsTest {
    @Test
    fun everyPanelIconRendersAcrossSizesAndStates() {
        for (id in PanelIcons.ALL_IDS) {
            val tile = PanelIcons.forNav(id)
            for (sz in intArrayOf(32, 48, 96)) {
                val bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
                val cv = Canvas(bmp)
                tile.setBounds(0, 0, sz, sz)
                tile.setDarkSurface(true)
                tile.setSelected(false)
                tile.draw(cv)
                assertTrue("$id a $sz px no ha pintado píxeles visibles", hasVisiblePixels(bmp))

                tile.setSelected(true)
                tile.setDarkSurface(false)
                if (id == "car") tile.setAlert(true)
                bmp.eraseColor(0)
                tile.draw(cv)
                assertTrue("$id activo a $sz px no ha pintado píxeles visibles", hasVisiblePixels(bmp))
            }
        }
        // Coche en alerta cambia su paleta a ámbar/naranja.
        val normal = PanelIcons.palette("car", false, true)
        val alert = PanelIcons.palette("car", true, true)
        assertNotEquals(normal[0], alert[0])
        assertNotEquals(normal[1], alert[1])
    }

    @Test
    fun activePillAndHaBadgeRenderCleanlyInDarkAndLight() {
        for (dark in booleanArrayOf(true, false)) {
            for (right in booleanArrayOf(false, true)) {
                val pill = PanelIcons.activePill(dark, right)
                val bmp = Bitmap.createBitmap(280, 72, Bitmap.Config.ARGB_8888)
                pill.setBounds(0, 0, 280, 72)
                pill.draw(Canvas(bmp))
                assertTrue(hasVisiblePixels(bmp))
            }
            val badge = PanelIcons.haBadge(dark)
            val bmp = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
            badge.setBounds(0, 0, 48, 48)
            badge.draw(Canvas(bmp))
            assertTrue(hasVisiblePixels(bmp))
        }
    }

    @Test
    fun iconSizeScalesWithButtonHeightAndNeverClips() {
        assertEquals(50, CarUi.panelIconSize(72, true))
        assertEquals(48, CarUi.panelIconSize(72, false))
        assertEquals(44, CarUi.panelIconSize(56, true))
        assertEquals(30, CarUi.panelIconSize(40, true))
        assertTrue(CarUi.panelIconSize(40, true) < 40)
        assertFalse(CarUi.panelIconSize(48, false) > 48)
    }

    @Test
    fun renderIconSheetWhenPreviewEnabled() {
        if (System.getProperty("hql.preview") != "1") return
        val dir = File(System.getProperty("hql.preview.dir")!!)
        dir.mkdirs()
        val ids = PanelIcons.ALL_IDS + arrayOf("car_alert")
        val cols = 6
        val rows = (ids.size + cols - 1) / cols
        val cell = 170
        val w = cols * cell + 40
        val h = rows * cell * 2 + 80
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 20f
            textAlign = Paint.Align.CENTER
        }
        // Mitad superior: panel oscuro; mitad inferior: panel claro (día).
        for (mode in 0..1) {
            val dark = mode == 0
            val yOff = mode * (rows * cell + 40)
            bg.color = if (dark) 0xFF16191F.toInt() else 0xFFE9EDF1.toInt()
            cv.drawRect(0f, yOff.toFloat(), w.toFloat(), (yOff + rows * cell + 40).toFloat(), bg)
            txt.color = if (dark) 0xFFDADCE0.toInt() else 0xFF22282E.toInt()
            for (i in ids.indices) {
                val raw = ids[i]
                val isAlert = raw == "car_alert"
                val id = if (isAlert) "car" else raw
                val col = i % cols
                val row = i / cols
                val cx = 20 + col * cell + cell / 2
                val cy = yOff + 20 + row * cell + cell / 2 - 12
                val tile = PanelIcons.forNav(id)
                tile.setDarkSurface(dark)
                tile.setAlert(isAlert)
                tile.setSelected(i == 0)
                val sz = 104
                tile.setBounds(cx - sz / 2, cy - sz / 2, cx + sz / 2, cy + sz / 2)
                tile.draw(cv)
                cv.drawText(raw, cx.toFloat(), (cy + sz / 2 + 26).toFloat(), txt)
            }
        }
        FileOutputStream(File(dir, "panel_icons_sheet.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun hasVisiblePixels(bmp: Bitmap): Boolean {
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return pixels.any { (it ushr 24) > 32 }
    }
}
