package com.headqlink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Teclado del coche: capas, mayúsculas y la contraseña en puntos encima de las teclas. */
class CarKeyboardTest {
    @Test
    fun everyPrintableSymbolCanBeTyped() {
        // Cualquier contraseña: los 32 símbolos ASCII están en la capa «#+=» o en la fila de abajo.
        val typable = CarKeyboard.LAYERS[CarKeyboard.SYMBOLS].joinToString("") + CarKeyboard.BOTTOM
        for (c in '!'..'~') {
            if (c.isLetterOrDigit()) continue
            assertTrue("falta «$c»", typable.contains(c))
        }
        // El caso del foro: la almohadilla.
        assertTrue(typable.contains('#'))
    }

    @Test
    fun accentsCoverGermanSpanishFrenchPortuguese() {
        val accents = CarKeyboard.LAYERS[CarKeyboard.ACCENTS].joinToString("") + CarKeyboard.LAYERS[CarKeyboard.LETTERS].joinToString("")
        for (c in "äöüßáéíóúñçãõâêôàèùœ¿¡") assertTrue("falta «$c»", accents.contains(c))
    }

    @Test
    fun rowsFitTheCarScreen() {
        // Como mucho 10 teclas por fila (la de ⇧ y ⌫ deja sitio para 7–9).
        for (layer in CarKeyboard.LAYERS) {
            assertEquals(3, layer.size)
            for (row in layer) assertTrue(row, row.length <= 10)
        }
    }

    @Test
    fun shiftKeepsKeysWithoutASingleCapital() {
        assertEquals("Ä", CarKeyboard.caseOf("ä", true))
        assertEquals("Ñ", CarKeyboard.caseOf("ñ", true))
        assertEquals("ß", CarKeyboard.caseOf("ß", true)) // no «SS»
        assertEquals("#", CarKeyboard.caseOf("#", true))
        assertEquals("q", CarKeyboard.caseOf("Q", false))
    }

    @Test
    fun passwordShowsDotsAndTheLastLetterAMoment() {
        assertEquals("", CarKeyboard.mask("", true))
        assertEquals("•••#", CarKeyboard.mask("abc#", true))
        assertEquals("••••", CarKeyboard.mask("abc#", false))
        // Un emoji cuenta como una letra, no como dos.
        assertEquals("•😀", CarKeyboard.mask("a😀", true))
    }
}
