package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Juegos a pantalla completa: el encaje de la imagen (lo más grande que cabe sin deformarla, o el aumento entero si casi
 * llega), el toque de la esquina que saca de ella y el atajo Select + Start del mando.
 */
class GameFullscreenTest {
    @Test
    fun theImageFillsAsMuchAsItCanKeepingItsShape() {
        // En una pantalla ancha manda el alto: la NES (256x224) y la Mega Drive (320x224) a todo el alto del C10.
        assertEquals(listOf(1008, 882), NesScreen.NesView.fit(256, 224, 1920, 882).toList())
        assertEquals(listOf(1260, 882), NesScreen.NesView.fit(320, 224, 1920, 882).toList())
        // El lienzo del T03 (1024x498 a escala 0,6).
        assertEquals(listOf(949, 830), NesScreen.NesView.fit(256, 224, 1707, 830).toList())
        // En una alta manda el ancho.
        assertEquals(listOf(800, 600), NesScreen.NesView.fit(4, 3, 800, 900).toList())
        assertEquals(listOf(1000, 875), NesScreen.NesView.fit(256, 224, 1000, 2000).toList())
        // Justo a la medida.
        assertEquals(listOf(1176, 882), NesScreen.NesView.fit(4, 3, 1176, 882).toList())
        // Sin tamaño, nada.
        assertEquals(listOf(0, 0), NesScreen.NesView.fit(0, 224, 1920, 882).toList())
        assertEquals(listOf(0, 0), NesScreen.NesView.fit(256, 224, 1920, 0).toList())
    }

    @Test
    fun theFitNeverOverflowsNorDeformsTheImage() {
        val frames = listOf(256 to 224, 320 to 224, 320 to 240, 256 to 239, 368 to 240, 4 to 3)
        for ((fw, fh) in frames) for (w in 200..2600 step 37) for (h in 150..1500 step 41) {
            val d = NesScreen.NesView.fit(fw, fh, w, h)
            val where = "$fw x $fh en $w x $h"
            assertTrue(where, d[0] <= w && d[1] <= h)
            // Llega a un borde (el que manda) y conserva la proporción (±1 px del redondeo).
            assertTrue(where, d[0] == w || d[1] == h)
            assertEquals(where, d[0].toDouble() * fh / fw, d[1].toDouble(), 1.0)
        }
    }

    @Test
    fun thePlayStationFitIsTheSameAsBefore() {
        // fit43 es ahora fit(4, 3, …): las mismas medidas que la cuenta de antes, en todos los tamaños.
        fun before(w: Int, h: Int): List<Int> {
            var dh = h
            var dw = Math.round(h * 4f / 3f)
            if (dw > w) {
                dw = w
                dh = Math.round(w * 3f / 4f)
            }
            return listOf(dw, dh)
        }
        for (w in 100..2600 step 7) for (h in 100..1500 step 11) {
            assertEquals("$w x $h", before(w, h), NesScreen.NesView.fit43(w, h).toList())
        }
    }

    @Test
    fun fullScreenKeepsTheSharpIntegerZoomWhenItAlmostFills() {
        // C10: la NES, la SNES y la Mega Drive a ×4 ya ocupan todo el alto: píxeles exactos, como siempre.
        assertTrue(NesScreen.NesView.fullSharp(256, 224, 1920, 882))
        assertTrue(NesScreen.NesView.fullSharp(320, 224, 1920, 882))
        // La Mega Drive europea (240 líneas) se quedaría en ×3, 960 de 1176: se estira.
        assertFalse(NesScreen.NesView.fullSharp(320, 240, 1920, 882))
        // T03: ×3, 768 de 949.
        assertFalse(NesScreen.NesView.fullSharp(256, 224, 1707, 830))
        // Una pantalla de 1080 líneas: ×4 son 1024 de 1234.
        assertFalse(NesScreen.NesView.fullSharp(256, 224, 1920, 1080))
    }

    @Test
    fun tappingTheTopLeftCornerLeavesFullScreen() {
        val side = NesScreen.EXIT_CORNER
        assertTrue(NesScreen.exitTap(10f, 10f, 12f, 14f, side))
        assertTrue(NesScreen.exitTap(0f, 0f, side - 1f, side - 1f, side))
        // Deslizando hacia fuera, o desde fuera hasta la esquina, no.
        assertFalse(NesScreen.exitTap(10f, 10f, side + 50f, 10f, side))
        assertFalse(NesScreen.exitTap(400f, 300f, 10f, 10f, side))
        // El borde ya es fuera, y fuera de la pantalla tampoco.
        assertFalse(NesScreen.exitTap(side.toFloat(), 10f, side.toFloat(), 10f, side))
        assertFalse(NesScreen.exitTap(-1f, 5f, 5f, 5f, side))
        // Ni la cruceta (abajo a la izquierda), ni Select (a media altura), ni la otra esquina de arriba.
        assertFalse(NesScreen.exitTap(150f, 800f, 150f, 800f, side))
        assertFalse(NesScreen.exitTap(60f, 441f, 60f, 441f, side))
        assertFalse(NesScreen.exitTap(1900f, 10f, 1900f, 10f, side))
    }

    @Test
    fun theExitCornerDoesNotReachTheTouchButtons() {
        // Select (56 de alto) va a media altura a la izquierda, y L1 y L2 de la PlayStation (60) a 344 del borde de abajo:
        // la esquina no llega a ellos ni en el C10 (882 de alto) ni en el lienzo del T03 (830).
        for (h in listOf(882, 830)) {
            assertTrue(NesScreen.EXIT_CORNER <= (h - 56) / 2)
            assertTrue(NesScreen.EXIT_CORNER <= h - 344 - 60)
        }
    }

    @Test
    fun selectPlusStartIsTheGamepadShortcut() {
        val sel = RetroPad.mask(RetroPad.Btn.SELECT)
        val start = RetroPad.mask(RetroPad.Btn.START)
        val a = RetroPad.mask(RetroPad.Btn.A)
        assertTrue(RetroPad.selectStart(sel, sel or start))
        assertTrue(RetroPad.selectStart(start or a, start or a or sel))
        assertFalse(RetroPad.selectStart(0, start))
        assertFalse(RetroPad.selectStart(sel or start, sel or start or a))
        // En un mando: un aviso por combinación, y los dos botones siguen llegando al juego.
        val pad = RetroPad()
        var shortcuts = 0
        pad.setOnSelectStart { shortcuts++ }
        pad.press(RetroPad.Btn.SELECT)
        assertEquals(0, shortcuts)
        pad.press(RetroPad.Btn.START)
        assertEquals(1, shortcuts)
        pad.press(RetroPad.Btn.A)
        pad.press(RetroPad.Btn.START)
        assertEquals(1, shortcuts)
        assertEquals(sel or start or a, pad.bits())
        pad.release(RetroPad.Btn.START)
        pad.press(RetroPad.Btn.START)
        assertEquals(2, shortcuts)
        // Sin nadie escuchando, como siempre.
        val quiet = RetroPad()
        quiet.press(RetroPad.Btn.SELECT)
        quiet.press(RetroPad.Btn.START)
        assertEquals(sel or start, quiet.read())
    }
}
