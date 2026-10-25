package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pantalla partida: AA junto a la barra (o en la otra mitad) y Web, Vídeos o TV al lado. */
class SplitScreenTest {
    @Test
    fun onlyWebVideosAndTvCanSitNextToAndroidAuto() {
        assertTrue(CarUi.splitCapable("web"))
        assertTrue(CarUi.splitCapable("videos"))
        assertTrue(CarUi.splitCapable("tv"))
        // La sección Coche también (instrumentos junto a AA), y sin aviso: es información de conducción.
        assertTrue(CarUi.splitCapable("car"))
        assertFalse(CarUi.splitNeedsWarning("car"))
        // La guía del camino, igual: junto a AA y sin aviso.
        assertTrue(CarUi.splitCapable("guide"))
        assertFalse(CarUi.splitNeedsWarning("guide"))
        // El karaoke también, pero con el aviso del ocio.
        assertTrue(CarUi.splitCapable("karaoke"))
        assertTrue(CarUi.splitNeedsWarning("karaoke"))
        assertTrue(CarUi.splitNeedsWarning("web"))
        assertFalse(CarUi.splitCapable("aa"))
        assertFalse(CarUi.splitCapable("photos"))
    }

    @Test
    fun androidAutoKeepsTheWidthWhereMapsStillWorks() {
        // C10: 1920x882 a 200 dpi y la barra de 96. La mitad (912) deja Maps sin toques; medido: 950 ya va bien.
        val aa = CarUi.splitAaWidth(1920, 882, 96, 200)
        assertEquals(1014, aa)
        assertTrue(aa >= 950)
        assertEquals(810, 1920 - 96 - aa)
    }

    @Test
    fun theCarSectionIsScaledDownWhenTheHalfIsNarrow() {
        // En el C10 la mitad deja 810 px: la sección se dibuja a 1100 y se escala a 0,74. Las de ocio, nunca.
        // La sección Coche ya no se encoge en la pantalla partida: va en una columna (CarKit.narrow).
        assertEquals(1f, CarUi.narrowScale("car", 810), 0f)
        assertEquals(listOf(300, 482), NarrowGrid.split(floatArrayOf(0f, 1f), intArrayOf(300, 0), 800, 18).toList())
        assertEquals(listOf(391, 391), NarrowGrid.split(floatArrayOf(1f, 1f), intArrayOf(0, 0), 800, 18).toList())
        assertEquals(1f, CarUi.narrowScale("car", 1600), 0f)
        assertEquals(1f, CarUi.narrowScale("web", 810), 0f)
    }

    @Test
    fun androidAutoCanGoToTheOtherHalf() {
        // C10: barra de 96 a la izquierda y AA de 1014. De serie, AA junto a la barra y nuestra pantalla detrás.
        assertEquals(listOf(96, 1110), CarUi.splitPlaces(1920, 96, 1014, false, false).toList())
        // AA a la derecha: nuestra pantalla junto a la barra (810 px) y AA pegado al borde, con el mismo ancho.
        assertEquals(listOf(906, 96), CarUi.splitPlaces(1920, 96, 1014, false, true).toList())
        // Con el panel a la derecha: de serie AA en el borde izquierdo; con la opción, AA junto al panel.
        assertEquals(listOf(0, 1014), CarUi.splitPlaces(1920, 96, 1014, true, false).toList())
        assertEquals(listOf(810, 0), CarUi.splitPlaces(1920, 96, 1014, true, true).toList())
        // Sin partir, la opción no cuenta: AA y nuestra pantalla empiezan tras la barra.
        assertEquals(listOf(96, 96), CarUi.splitPlaces(1920, 96, 0, false, true).toList())
    }

    @Test
    fun aWideScreenStaysHalfAndHalf() {
        // Con sitio de sobra, mitad y mitad.
        assertEquals(1452, CarUi.splitAaWidth(3000, 882, 96, 200))
    }

    @Test
    fun ourScreenKeepsAMinimumWidth() {
        // Pantalla estrecha: AA no se come la nuestra (al menos 560 px), salvo que ni la mitad llegue.
        assertEquals(1280 - 96 - CarUi.SPLIT_MIN_SCREEN_W, CarUi.splitAaWidth(1280, 882, 96, 200))
        assertEquals((1000 - 96) / 2, CarUi.splitAaWidth(1000, 882, 96, 200))
    }
}
