package com.headqlink.link

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/** El diario del viaje: qué fotos van en la postal. */
class TripPostcardTest {
    @Test
    fun photosAreSpreadOverTheTrip() {
        assertArrayEquals(intArrayOf(), TripPostcard.spread(0, 3))
        assertArrayEquals(intArrayOf(0), TripPostcard.spread(1, 3))
        assertArrayEquals(intArrayOf(0, 1), TripPostcard.spread(2, 3))
        // La primera, una de en medio y la última.
        assertArrayEquals(intArrayOf(0, 5, 10), TripPostcard.spread(11, 3))
    }
}
