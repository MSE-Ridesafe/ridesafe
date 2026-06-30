package de.uhi.enia.ridesafe.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the non-trivial GPS smoothing: jump rejection and that filtering doesn't inflate distance. */
class RideProcessingTest {
    // ~1 Hz fix along latitude 50°, stepping east; accuracy mimics a decent urban fix.
    private fun loc(
        index: Int,
        lon: Double,
        lat: Double = 50.0,
    ) = LocationSample(
        t = index * 1_000_000_000L,
        lat = lat,
        lon = lon,
        alt = 0.0,
        speed = 0f,
        bearing = 0f,
        accuracy = 5f,
    )

    @Test
    fun shortTracksPassThroughUntouched() {
        val one = listOf(loc(0, 8.0))
        assertEquals(one, kalmanFilterLocations(one))
        assertTrue(kalmanFilterLocations(emptyList()).isEmpty())
    }

    @Test
    fun rejectsImpossibleJumpAndKeepsDistanceSane() {
        // A clean straight line of 10 fixes, ~7 m apart, with one ~2 km outlier spliced into the middle.
        val clean = (0..9).map { loc(it, 8.0 + it * 0.0001) }
        val withOutlier = clean.toMutableList().apply { this[5] = loc(5, 8.03) } // implied speed ~2 km/s

        val filtered = kalmanFilterLocations(withOutlier)

        // The outlier is pulled back onto the line, nowhere near its raw 8.03 longitude.
        assertTrue("outlier should be rejected, was ${filtered[5].lon}", filtered[5].lon < 8.002)

        // Raw path with the jump is kilometers long; the filtered path stays in the tens of meters.
        val rawDistance = trackDistanceMeters(withOutlier)
        val filteredDistance = trackDistanceMeters(filtered)
        assertTrue("raw should be inflated by the jump, was $rawDistance", rawDistance > 1_000.0)
        assertTrue("filtered should be sane, was $filteredDistance", filteredDistance in 30.0..200.0)
    }
}
