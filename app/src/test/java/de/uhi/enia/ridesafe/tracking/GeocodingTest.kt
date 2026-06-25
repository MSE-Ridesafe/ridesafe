package de.uhi.enia.ridesafe.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

class GeocodingTest {
    @Test
    fun shortAddressKeepsFirstTwoParts() {
        assertEquals("Hauptstraße 5, 20095 Hamburg", shortAddress("Hauptstraße 5, 20095 Hamburg, Germany"))
    }

    @Test
    fun shortAddressPassesThroughSinglePart() {
        assertEquals("Hamburg", shortAddress("Hamburg"))
    }
}
