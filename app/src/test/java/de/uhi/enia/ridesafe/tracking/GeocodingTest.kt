package de.uhi.enia.ridesafe.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeocodingTest {
    @Test
    fun addressLinesSplitsOnNewline() {
        val (primary, secondary) = addressLines("Hauptstraße 5\n20095 Hamburg")
        assertEquals("Hauptstraße 5", primary)
        assertEquals("20095 Hamburg", secondary)
    }

    @Test
    fun addressLinesHasNoSecondaryForSingleLine() {
        val (primary, secondary) = addressLines("University Building")
        assertEquals("University Building", primary)
        assertNull(secondary)
    }

    @Test
    fun shortAddressJoinsLinesWithComma() {
        assertEquals("Hauptstraße 5, 20095 Hamburg", shortAddress("Hauptstraße 5\n20095 Hamburg"))
    }

    @Test
    fun shortAddressPassesThroughSingleLine() {
        assertEquals("University Building", shortAddress("University Building"))
    }
}
