package com.headqlink.link

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** «Calcular ruta» con el destino escrito y sin tocar ningún resultado (foro, #384): cuándo se elige solo el primero. */
class PhoneRouteTypedTest {
    private fun place(name: String) = RoutePlanner.Place().apply {
        this.name = name
        detail = ""
    }

    @Test
    fun aSingleResultIsUsed() {
        val a = place("Hamburg")
        assertSame(a, PhoneRoute.pickClear("hamburg", listOf(a)))
    }

    @Test
    fun noResultsPicksNothing() {
        assertNull(PhoneRoute.pickClear("xyzxyz", emptyList()))
        assertNull(PhoneRoute.pickClear("xyzxyz", null))
    }

    @Test
    fun theFirstIsUsedIfItStartsWithWhatWasTypedAndNoOtherIsCalledTheSame() {
        val a = place("Hamburg")
        val b = place("Hamburg-Altona")
        assertSame(a, PhoneRoute.pickClear("hamburg", listOf(a, b)))
    }

    @Test
    fun twoPlacesWithTheSameNameAreAsked() {
        assertNull(PhoneRoute.pickClear("valencia", listOf(place("Valencia"), place("Valencia"))))
    }

    @Test
    fun aFirstResultThatDoesNotStartWithTheTextIsAsked() {
        assertNull(PhoneRoute.pickClear("bahnhof", listOf(place("Hauptbahnhof"), place("Bahnhof Mitte"))))
    }
}
