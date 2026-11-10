package com.headqlink.link

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** «Calcular ruta» con el destino escrito y sin tocar ningún resultado (foro, #384): cuándo se elige solo el primero. */
class PhoneRouteTypedTest {
    private fun place(name: String, lat: Double = 0.0, lon: Double = 0.0) = RoutePlanner.Place().apply {
        this.name = name
        detail = ""
        this.lat = lat
        this.lon = lon
    }

    private val madrid = doubleArrayOf(40.4168, -3.7038)

    @Test
    fun aSingleResultIsUsed() {
        val a = place("Hamburg")
        assertSame(a, PhoneRoute.pickClear("hamburg", listOf(a), null))
    }

    @Test
    fun noResultsPicksNothing() {
        assertNull(PhoneRoute.pickClear("xyzxyz", emptyList(), null))
        assertNull(PhoneRoute.pickClear("xyzxyz", null, null))
    }

    @Test
    fun theFirstIsUsedIfItStartsWithWhatWasTypedAndNoOtherIsCalledTheSame() {
        val a = place("Hamburg", 53.55, 9.99)
        val b = place("Hamburg-Altona", 53.55, 9.93)
        assertSame(a, PhoneRoute.pickClear("hamburg", listOf(a, b), null))
    }

    @Test
    fun twoPlacesWithTheSameNameFarApartAreAskedWithoutPosition() {
        val es = place("Valencia", 39.47, -0.38)
        val ve = place("Valencia", 10.16, -68.0)
        assertNull(PhoneRoute.pickClear("valencia", listOf(es, ve), null))
    }

    @Test
    fun aFirstResultThatDoesNotStartWithTheTextIsAsked() {
        assertNull(PhoneRoute.pickClear("bahnhof", listOf(place("Hauptbahnhof"), place("Bahnhof Mitte")), null))
    }

    /**
     * Probado en el móvil: «Zaragoza» daba la ciudad, su municipio, su provincia (centro a 32 km) y las de Colombia y
     * México (coordenadas reales del buscador).
     */
    @Test
    fun theSamePlaceTwiceAndFarAwayNamesakesDoNotAsk() {
        val city = place("Zaragoza", 41.6521, -0.8809)
        val province = place("Zaragoza", 41.62, -1.26)
        val colombia = place("Zaragoza", 7.49, -74.87)
        val mexico = place("Zaragoza", 19.43, -99.13)
        assertSame(city, PhoneRoute.pickClear("Zaragoza", listOf(city, province, colombia, mexico), madrid))
    }

    @Test
    fun twoNamesakesAtSimilarDistancesAreAsked() {
        // Dos «Villanueva» a ~150 km de Madrid, en direcciones distintas.
        val a = place("Villanueva", 41.2, -4.6)
        val b = place("Villanueva", 39.3, -2.4)
        assertNull(PhoneRoute.pickClear("villanueva", listOf(a, b), madrid))
    }
}
