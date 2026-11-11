package com.headqlink.link

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Coordenadas escritas como texto (LPMFF #416: se buscaban como dirección y el destino caía unos 3 km al lado). */
class CoordTextTest {
    private fun at(s: String) = CoordText.parse(s)

    @Test
    fun decimalWithDots() {
        assertArrayEquals(doubleArrayOf(48.1401, 11.5763), at("48.1401, 11.5763")!!, 1e-9)
        assertArrayEquals(doubleArrayOf(48.1401, 11.5763), at("48.1401 11.5763")!!, 1e-9)
        assertArrayEquals(doubleArrayOf(48.1401, 11.5763), at("48.1401;11.5763")!!, 1e-9)
        assertArrayEquals(doubleArrayOf(40.4138, -3.6921), at("40.4138,-3.6921")!!, 1e-9)
        assertArrayEquals(doubleArrayOf(48.1401, 11.5763), at("Treffpunkt: 48.1401, 11.5763")!!, 1e-9)
    }

    @Test
    fun decimalWithHemispheres() {
        assertArrayEquals(doubleArrayOf(48.1401, 11.5763), at("N48.1401 E11.5763")!!, 1e-9)
        assertArrayEquals(doubleArrayOf(48.1401, 11.5763), at("48.1401N, 11.5763O")!!, 1e-9)
        assertArrayEquals(doubleArrayOf(-33.8568, 151.2153), at("33.8568° S, 151.2153° E")!!, 1e-9)
        assertArrayEquals(doubleArrayOf(40.4138, -3.6921), at("40.4138N 3.6921W")!!, 1e-9)
    }

    @Test
    fun germanDecimalComma() {
        assertArrayEquals(doubleArrayOf(48.1401, 11.5763), at("48,1401 11,5763")!!, 1e-9)
        assertArrayEquals(doubleArrayOf(48.1401, 11.5763), at("48,1401; 11,5763")!!, 1e-9)
        assertArrayEquals(doubleArrayOf(48.1401, 11.5763), at("48,1401, 11,5763")!!, 1e-9)
    }

    @Test
    fun degreesMinutesSecondsAsGoogleMapsCopiesThem() {
        val r = at("48°08'24.4\"N 11°34'47.0\"E")!!
        assertEquals(48.0 + 8 / 60.0 + 24.4 / 3600, r[0], 1e-9)
        assertEquals(11.0 + 34 / 60.0 + 47.0 / 3600, r[1], 1e-9)
        val s = at("40°24'49.7\"N 3°41'31.6\"W")!!
        assertEquals(-(3.0 + 41 / 60.0 + 31.6 / 3600), s[1], 1e-9)
        // Con los signos tipográficos y la coma decimal.
        val t = at("48° 8′ 24,4″ N, 11° 34′ 47″ O")!!
        assertEquals(48.0 + 8 / 60.0 + 24.4 / 3600, t[0], 1e-9)
        assertEquals(11.0 + 34 / 60.0 + 47.0 / 3600, t[1], 1e-9)
    }

    @Test
    fun degreesAndDecimalMinutesAsInGeocaching() {
        val r = at("N 48° 08.407' E 011° 34.783'")!!
        assertEquals(48.0 + 8.407 / 60, r[0], 1e-9)
        assertEquals(11.0 + 34.783 / 60, r[1], 1e-9)
        // Longitud primero: se ordena por el hemisferio.
        val s = at("E 011° 34.783' N 48° 08.407'")!!
        assertEquals(48.0 + 8.407 / 60, s[0], 1e-9)
    }

    @Test
    fun addressesAndOtherNumbersAreNotCoordinates() {
        assertNull(at("Leopoldstraße 12, 80802 München"))
        assertNull(at("Calle Mayor 5, 28013 Madrid"))
        assertNull(at("Termin 11.11.2026 um 18.30"))
        assertNull(at("Qonecta+ 0.3.18"))
        assertNull(at("Preis 0,35 €/kWh 22 kW"))
        assertNull(at("0.0000, 0.0000"))
        assertNull(at("95.1234, 11.5763"))
        assertNull(at(""))
        assertNull(CoordText.parse(null))
    }

    @Test
    fun theSearchGivesTheExactPlaceWithoutAskingAnyone() {
        val found = PlaceSearch.byCoords("48.1401, 11.5763")!!
        assertEquals(1, found.size)
        assertEquals(48.1401, found[0].lat, 1e-9)
        assertEquals(11.5763, found[0].lon, 1e-9)
        assertEquals("48.14010, 11.57630", found[0].name)
        assertNull(PlaceSearch.byCoords("Marienplatz München"))
    }

    @Test
    fun aGoogleMapsPlacePathWithDegreesEndsAsCoordinates() {
        // El enlace largo de un punto marcado sin «!3d…!4d…»: la búsqueda queda en grados y ya no va al buscador.
        val u = "https://www.google.com/maps/place/48%C2%B008'24.4%22N+11%C2%B034'47.0%22E/data=!4m2!3m1!1s0x0:0x0"
        val q = SharedDest.query(u)!!
        val r = CoordText.parse(q)!!
        assertEquals(48.0 + 8 / 60.0 + 24.4 / 3600, r[0], 1e-9)
    }
}
