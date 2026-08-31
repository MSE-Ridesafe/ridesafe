package de.uhi.enia.ridesafe.domain.place

import de.uhi.enia.ridesafe.data.entity.SavedAddress
import de.uhi.enia.ridesafe.data.entity.SavedPlaceKind
import de.uhi.enia.ridesafe.data.entity.findExistingSavedPlace
import de.uhi.enia.ridesafe.data.entity.matchAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SavedAddressMatchTest {
    private fun place(
        id: Long,
        lat: Double,
        lon: Double,
        radius: Int,
    ) = SavedAddress(
        id = id,
        label = "P$id",
        kind = SavedPlaceKind.CUSTOM,
        latitude = lat,
        longitude = lon,
        radiusMeters = radius,
        icon = "place",
    )

    // 0.001° of longitude at the equator ≈ 111 m; used to place points a known distance apart.
    @Test
    fun pointInsideRadiusMatches() {
        val home = place(1, 0.0, 0.001, radius = 150) // ~111 m from (0,0)
        assertEquals(1L, matchAddress(0.0, 0.0, listOf(home))?.id)
    }

    @Test
    fun pointOutsideRadiusMatchesNothing() {
        val home = place(1, 0.0, 0.001, radius = 100) // ~111 m away, radius 100
        assertNull(matchAddress(0.0, 0.0, listOf(home)))
    }

    @Test
    fun overlappingRadiiPickNearestCenter() {
        val far = place(1, 0.0, 0.001, radius = 500) // ~111 m
        val near = place(2, 0.0, 0.0005, radius = 500) // ~56 m
        assertEquals(2L, matchAddress(0.0, 0.0, listOf(far, near))?.id)
    }

    @Test
    fun nullCoordinatesMatchNothing() {
        val home = place(1, 0.0, 0.0, radius = 500)
        assertNull(matchAddress(null, 0.0, listOf(home)))
        assertNull(matchAddress(0.0, null, listOf(home)))
    }

    @Test
    fun emptyAddressesMatchNothing() {
        assertNull(matchAddress(0.0, 0.0, emptyList()))
    }
}

class FindExistingSavedPlaceTest {
    private fun place(
        id: Long,
        lat: Double,
        lon: Double,
        address: String? = null,
    ) = SavedAddress(
        id = id,
        label = "P$id",
        kind = SavedPlaceKind.CUSTOM,
        latitude = lat,
        longitude = lon,
        radiusMeters = 100,
        icon = "place",
        address = address,
    )

    @Test
    fun matchesByNormalizedAddressRegardlessOfDistance() {
        val saved = place(1, 50.0, 8.0, address = "Hauptstraße 5, Hamburg")
        // Case- and punctuation-insensitive (ROOT uppercase also folds ß to SS), any distance.
        val found = findExistingSavedPlace("hauptstrasse 5 hamburg", 51.0, 9.0, listOf(saved), editedId = null)
        assertEquals(saved, found)
        assertNull(findExistingSavedPlace("another street 7", 51.0, 9.0, listOf(saved), editedId = null))
    }

    @Test
    fun matchesByProximityWithinFifteenMeters() {
        val saved = place(2, 50.0, 8.0)
        val nearby = findExistingSavedPlace("somewhere else", 50.0001, 8.0, listOf(saved), editedId = null)
        assertEquals(saved, nearby)
        val far = findExistingSavedPlace("somewhere else", 50.001, 8.0, listOf(saved), editedId = null)
        assertNull(far)
    }

    @Test
    fun excludesThePlaceBeingEdited() {
        val saved = place(3, 50.0, 8.0)
        assertNull(findExistingSavedPlace("x", 50.0, 8.0, listOf(saved), editedId = 3))
    }
}
