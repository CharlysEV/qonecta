package com.headqlink.link

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Ruta en Qonecta+»: el sitio que viene en lo compartido (Google Maps, WhatsApp, «geo:», una dirección). */
class SharedDestTest {
    private fun at(s: String) = SharedDest.coords(s)

    @Test
    fun thePlaceOfAPlacePageBeatsTheMapCentre() {
        // «@» es el centro del mapa; «!3d…!4d…», el sitio.
        val u = "https://www.google.com/maps/place/Museo+del+Prado/@40.4137,-3.6943,17z/data=!3m1!4b1!4m6!3m5!1s0x0:0x0!8m2!3d40.4138!4d-3.6921"
        assertArrayEquals(doubleArrayOf(40.4138, -3.6921), at(u)!!, 1e-9)
    }

    @Test
    fun coordinatesInParametersAndInGeo() {
        assertArrayEquals(doubleArrayOf(41.3874, 2.1686), at("https://maps.google.com/?q=41.3874,2.1686")!!, 1e-9)
        assertArrayEquals(doubleArrayOf(39.47, -0.376), at("https://www.google.com/maps/search/?api=1&query=39.47%2C-0.376")!!, 1e-9)
        assertArrayEquals(doubleArrayOf(37.38, -5.98), at("geo:37.38,-5.98?q=37.38,-5.98(Casa)")!!, 1e-9)
        assertArrayEquals(doubleArrayOf(40.0, -3.0), at("https://www.google.com/maps/@40.0,-3.0,15z")!!, 1e-9)
        // «geo:0,0?q=dirección»: sin coordenadas, se busca el texto.
        assertNull(at("geo:0,0?q=Calle+Mayor+1,+Madrid"))
        assertEquals("Calle Mayor 1, Madrid", SharedDest.query("geo:0,0?q=Calle+Mayor+1,+Madrid"))
    }

    @Test
    fun theTextToSearchWhenThereAreNoCoordinates() {
        assertEquals("Museo del Prado", SharedDest.query("https://www.google.com/maps/place/Museo+del+Prado/data=abc"))
        assertEquals("Plaza Mayor", SharedDest.query("https://maps.google.com/?q=Plaza%20Mayor"))
        assertNull(SharedDest.query("https://maps.google.com/?q=41.38,2.17"))
        assertEquals("Casa de Ana", SharedDest.geoLabel("geo:40.1,-3.2?q=40.1,-3.2(Casa%20de%20Ana)"))
    }

    @Test
    fun nameAndLinkOfAGoogleMapsShare() {
        val shared = "Museo del Prado\nC. de Ruiz de Alarcón, 23, 28014 Madrid\nhttps://maps.app.goo.gl/AbCdEf123"
        assertEquals("Museo del Prado", SharedDest.name(shared))
        assertEquals("https://maps.app.goo.gl/AbCdEf123", SharedDest.firstUrl(shared))
        assertTrue(SharedDest.shortUrl("https://maps.app.goo.gl/AbCdEf123"))
        assertNull(SharedDest.name("https://maps.app.goo.gl/x"))
    }

    @Test
    fun onlyGoogleMapsLinksAreFollowed() {
        assertTrue(SharedDest.mapsUrl("https://maps.app.goo.gl/AbC"))
        assertTrue(SharedDest.mapsUrl("https://www.google.com/maps/place/X"))
        assertTrue(SharedDest.mapsUrl("https://maps.google.es/?q=1,2"))
        assertTrue(SharedDest.mapsUrl("https://goo.gl/maps/xyz"))
        assertFalse(SharedDest.mapsUrl("https://www.google.com/search?q=x"))
        assertFalse(SharedDest.mapsUrl("https://evil.example/maps/place"))
        assertFalse(SharedDest.mapsUrl("https://google.com.evil.example/maps"))
        assertFalse(SharedDest.mapsUrl("no es un enlace"))
    }
}
