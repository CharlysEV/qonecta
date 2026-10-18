package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El icono de la ruta del recuadro de Maps, por su forma y no por su sitio (18-10): con «…» (cinco botones), en otro
 * orden, de día y de noche, grande (coche) y pequeño (pantalla partida, otra resolución).
 */
class MapsRouteShapeTest {
    private val g = MapsCard.GRID

    private val route = "0055000000000000/05ff703500000000/0affa3fffa000000/05ff7055af700000/003300000fa00000/000000005f800000/" +
        "003afffffd200000/03ffa75552035500/0dd2000000afffd2/8f50000005fffff8/df0000000afc07ff/8f20000008fc27ff/" +
        "5f70000003fffff8/0af7000000affff2/00affcaaa22dff50/0005aaaaa5028300"
    private val x = "4b000000000004b0/ffb0000000004ff8/bffb00000008ffb0/0bffb000004ffb00/00bffb0008fff400/000bffb4bfff0000/" +
        "0000bffffff00000/00000bffff400000/000008ffff000000/00004fffffb00000/0004fff4bffb0000/008fff400bffb000/" +
        "04fff40000bffb00/4fff4000000bffb0/fff400000000bff8/4f40000000000bb0"
    private val fork = "000b80000003f300/00aff500002dfd20/0affff8003dfffd2/8fdffff50ffdfffa/0a0fa580085af582/00000000000df000/" +
        "0000d500003fb000/0000dd0000af5000/000058032df80000/000000bfdfd00000/0000008ffd200000/0000000df0000000/" +
        "0000000af0000000/0000000af0000000/0000000af0000000/00000008d0000000"
    private val plus = "000005ffff500000/0003cfdaadfd3000/005fc300003cf500/05f7000000007f50/0da0000750000ad0/8f20000a800002f5/" +
        "fa00000a800000aa/fa00aaaffaaa00aa/fa00ffffffff00aa/fa00000cd00000aa/ad00000a800000d7/3f50000a800005f2/" +
        "08d2000330002d80/00ad50000005da00/000afc7557cfa000/00003adffda30000"
    private val dots = "0000000000000000/0000000000000000/0000000000000000/0000000000000000/0000000000000000/0000000000000000/" +
        "0000000000000000/ffff000fff000fff/ffff000fff000fff/ffff000fff000fff/0000000000000000/0000000000000000/" +
        "0000000000000000/0000000000000000/0000000000000000/0000000000000000"

    private fun l(left: Int, top: Int, right: Int, bottom: Int) = MapsCard.Line("t", left, top, right, bottom)

    /**
     * Un recuadro de Maps dibujado: fondo, el tiempo y los km (como líneas leídas) y los iconos en fila, cada celda de su
     * forma como cell × cell píxeles, separados por gap. Devuelve la imagen, las dos líneas y el centro de cada icono.
     */
    private class Card(val px: IntArray, val w: Int, val h: Int, val eta: MapsCard.Line, val km: MapsCard.Line, val centers: List<Int>)

    private fun card(icons: List<String>, cell: Int, gap: Int, bg: Int, ink: Int, map: Int): Card {
        val side = g * cell
        val lh = side * 2 / 3
        val w = 40 + icons.size * (side + gap) + 200
        val h = 40 + lh * 3 + side + 80
        val px = IntArray(w * h) { map }
        val cardRight = 20 + icons.size * (side + gap) + 20
        for (y in 10 until h - 30) for (xx in 10 until cardRight) px[y * w + xx] = bg
        val eta = l(30, 20, 30 + lh * 4, 20 + lh)
        val km = l(30, 20 + lh + lh / 3, 30 + lh * 5, 20 + lh * 2)
        val top = km.bottom + lh / 2
        val centers = mutableListOf<Int>()
        icons.forEachIndexed { i, s ->
            val shape = MapsCard.shapeFrom(s)
            val left = 30 + i * (side + gap)
            centers.add(left + side / 2)
            for (gy in 0 until g) for (gx in 0 until g) {
                if (shape[gy * g + gx] < 0.5f) continue
                for (dy in 0 until cell) for (dx in 0 until cell) px[(top + gy * cell + dy) * w + left + gx * cell + dx] = ink
            }
        }
        return Card(px, w, h, eta, km, centers)
    }

    private fun assertRouteAt(c: Card, index: Int) {
        val at = MapsCard.routeIconByShape(c.px, c.w, c.h, c.eta, c.km)
        assertNotNull("no reconoce la ruta", at)
        assertEquals(c.centers[index].toDouble(), at!![0].toDouble(), (MapsCard.GRID * 2).toDouble())
    }

    @Test
    fun theKnownShapesAreTold() {
        assertTrue(MapsCard.isRoute(MapsCard.shapeFrom(route)))
        for (s in listOf(x, fork, plus, dots)) assertFalse(MapsCard.isRoute(MapsCard.shapeFrom(s)))
    }

    @Test
    fun fourButtonsAtNightAndByDay() {
        assertRouteAt(card(listOf(x, fork, plus, route), 4, 70, 0xFF131313.toInt(), 0xFFE3E3E3.toInt(), 0xFF35466A.toInt()), 3)
        assertRouteAt(card(listOf(x, fork, plus, route), 4, 70, 0xFFFFFFFF.toInt(), 0xFF202124.toInt(), 0xFFF3EEE6.toInt()), 3)
    }

    /** Con el «…» de detrás (cinco botones), y si Maps cambiara el orden: el de la ruta, esté donde esté. */
    @Test
    fun withFiveButtonsOrInAnotherOrder() {
        val night = intArrayOf(0xFF131313.toInt(), 0xFFE3E3E3.toInt(), 0xFF35466A.toInt())
        assertRouteAt(card(listOf(x, fork, plus, route, dots), 3, 40, night[0], night[1], night[2]), 3)
        assertRouteAt(card(listOf(x, route, fork, plus), 3, 40, night[0], night[1], night[2]), 1)
        assertRouteAt(card(listOf(route, x, plus), 3, 40, night[0], night[1], night[2]), 0)
        assertRouteAt(card(listOf(x, fork, plus, dots, route), 3, 40, night[0], night[1], night[2]), 4)
    }

    /** Pequeño (pantalla partida, la de 1280 × 720): se reconoce igual. */
    @Test
    fun smallIcons() {
        assertRouteAt(card(listOf(x, fork, plus, route, dots), 2, 24, 0xFF111113.toInt(), 0xFFDDDDDD.toInt(), 0xFF35466A.toInt()), 3)
    }

    /** Sin el icono de la ruta: nada (no se toca otro botón por ser el cuarto). */
    @Test
    fun withoutTheRouteIconNothingIsChosenByShape() {
        val c = card(listOf(x, fork, plus, dots), 4, 70, 0xFF131313.toInt(), 0xFFE3E3E3.toInt(), 0xFF35466A.toInt())
        assertNull(MapsCard.routeIconByShape(c.px, c.w, c.h, c.eta, c.km))
    }
}
