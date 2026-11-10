package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Buscar y ordenar las emisoras de una lista (foro #379): sin mayúsculas ni acentos, por nombre y grupo, estable y rápido. */
class RadioFilterTest {
    private fun st(name: String, group: String? = "") = RadioPlayer.Station().apply {
        this.name = name
        this.url = "https://x.example/" + name.hashCode()
        this.group = group
    }

    private val list = listOf(
        st("Radio Zeta", "Pop"),
        st("Café del Mar", "Chill"),
        st("Antena 3", "Pop"),
        st("Kiss FM", ""),
        st("Ñandú Radio", "Folk"),
        st("Cadena SER", null),
    )

    private fun names(l: List<RadioPlayer.Station>) = l.map { it.name }

    @Test
    fun foldIgnoresCaseAccentsAndSpaces() {
        assertEquals("cafe del mar", RadioFilter.fold("  CAFÉ del Mar "))
        assertEquals("nandu", RadioFilter.fold("Ñandú"))
        assertEquals("koln", RadioFilter.fold("Köln"))
        assertEquals("", RadioFilter.fold(null))
    }

    @Test
    fun searchByNameAndGroupWithoutAccents() {
        assertEquals(listOf("Café del Mar"), names(RadioFilter.apply(list, RadioFilter.ORIGINAL, "cafe")))
        assertEquals(listOf("Café del Mar"), names(RadioFilter.apply(list, RadioFilter.ORIGINAL, "CAFÉ")))
        assertEquals(listOf("Ñandú Radio"), names(RadioFilter.apply(list, RadioFilter.ORIGINAL, "nandu")))
        // Por grupo.
        assertEquals(listOf("Radio Zeta", "Antena 3"), names(RadioFilter.apply(list, RadioFilter.ORIGINAL, "pop")))
        // Varias palabras: todas, en cualquier orden y repartidas entre nombre y grupo.
        assertEquals(listOf("Radio Zeta"), names(RadioFilter.apply(list, RadioFilter.ORIGINAL, "zeta pop")))
        assertEquals(listOf("Radio Zeta"), names(RadioFilter.apply(list, RadioFilter.ORIGINAL, "pop radio")))
        assertTrue(RadioFilter.apply(list, RadioFilter.ORIGINAL, "xyz").isEmpty())
        // Sin búsqueda (o solo espacios): todas.
        assertEquals(list.size, RadioFilter.apply(list, RadioFilter.ORIGINAL, "   ").size)
    }

    @Test
    fun sortAzIsAccentBlindAndStable() {
        val l = listOf(st("beta"), st("Álamo"), st("alamo"), st("Zeta"), st("Ñu"))
        // «Álamo» y «alamo» son iguales sin acentos: siguen como venían.
        assertEquals(listOf("Álamo", "alamo", "beta", "Ñu", "Zeta"), names(RadioFilter.apply(l, RadioFilter.AZ, "")))
        // El original no se toca.
        assertEquals(names(l), names(RadioFilter.apply(l, RadioFilter.ORIGINAL, "")))
    }

    @Test
    fun groupedPutsUngroupedLastAndSortsInside() {
        val out = RadioFilter.apply(list, RadioFilter.GROUPED, "")
        assertEquals(listOf("Café del Mar", "Ñandú Radio", "Antena 3", "Radio Zeta"), names(out).take(4))
        assertEquals(setOf("Cadena SER", "Kiss FM"), names(out).drop(4).toSet())
        // Sin grupo: en A–Z.
        assertEquals(listOf("Cadena SER", "Kiss FM"), names(out).drop(4))
    }

    @Test
    fun nextModeSkipsGroupsWhenThereAreNone() {
        assertEquals(RadioFilter.AZ, RadioFilter.next(RadioFilter.ORIGINAL, true))
        assertEquals(RadioFilter.GROUPED, RadioFilter.next(RadioFilter.AZ, true))
        assertEquals(RadioFilter.ORIGINAL, RadioFilter.next(RadioFilter.GROUPED, true))
        assertEquals(RadioFilter.ORIGINAL, RadioFilter.next(RadioFilter.AZ, false))
        assertEquals(RadioFilter.ORIGINAL, RadioFilter.clampMode(99))
        assertFalse(RadioFilter.Index(listOf(st("a"), st("b"))).hasGroups())
        assertTrue(RadioFilter.Index(list).hasGroups())
    }

    @Test
    fun bigListsStayFast() {
        val big = (0 until 20_000).map { st("Emisora número $it de prueba", if (it % 7 == 0) "Grupo ${it % 50}" else "") }
        val t0 = System.nanoTime()
        val ix = RadioFilter.Index(big)
        val sorted = ix.view(RadioFilter.AZ, "")
        val hit = ix.view(RadioFilter.AZ, "numero 1999")
        val again = ix.view(RadioFilter.GROUPED, "grupo 7")
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(20_000, sorted.size)
        assertEquals(11, hit.size)
        assertTrue(again.isNotEmpty())
        assertTrue("tardó $ms ms", ms < 3_000)
        // Cada letra escrita después es una pasada barata.
        val t1 = System.nanoTime()
        repeat(20) { ix.view(RadioFilter.AZ, "emisora $it") }
        assertTrue("letras: ${(System.nanoTime() - t1) / 1_000_000} ms", (System.nanoTime() - t1) / 1_000_000 < 2_000)
    }
}
