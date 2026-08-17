package de.uhi.enia.ridesafe.rides.processing

import de.uhi.enia.ridesafe.rides.recording.LocationSample
import de.uhi.enia.ridesafe.rides.recording.trackDistanceMeters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the non-trivial GPS smoothing: outlier rejection, recovery, and that it doesn't inflate distance. */
class RideProcessingTest {
    // ~1 Hz fix along latitude 50°, stepping east; accuracy mimics a decent urban fix.
    private fun loc(
        index: Int,
        lon: Double,
        lat: Double = 50.0,
        accuracy: Float = 5f,
    ) = LocationSample(
        t = index * 1_000_000_000L,
        lat = lat,
        lon = lon,
        alt = 0.0,
        speed = 0f,
        bearing = 0f,
        accuracy = accuracy,
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

    /** A fix the receiver itself calls hopeless is dropped outright, not weighted and kept. */
    @Test
    fun dropsFixesTheReceiverAdmitsAreLost() {
        val clean = (0..9).map { loc(it, 8.0 + it * 0.0001) }
        // What the fused provider emits once GNSS is gone: kilometers off, and saying so.
        val withNetworkFix = clean.toMutableList().apply { this[5] = loc(5, 8.03, lat = 50.045, accuracy = 1200f) }

        val filtered = kalmanFilterLocations(withNetworkFix)

        assertEquals("only the bad fix should be gone", 9, filtered.size)
        assertTrue("the bad fix must not survive at all", filtered.none { it.lat > 50.001 })
    }

    /**
     * The reported failure. A two-minute GPS gap, then one bogus fix 5 km away that reports a
     * plausible accuracy. It used to be accepted — 5 km over 120 s is a mere 42 m/s — dragging the
     * estimate *and its velocity* to nowhere, after which every true fix looked like a 5 km jump and
     * was rejected forever: the drawn track ended at the bad point. The tail has to survive.
     */
    @Test
    fun survivesABogusFixAfterAGpsGap() {
        val before = (0..59).map { loc(it, 8.0 + it * 0.0001) }
        val bogus = loc(180, 8.0059, lat = 50.045, accuracy = 12f) // 5 km north, not admitting it
        val after = (181..210).map { loc(it, 8.0059 + (it - 180) * 0.0001) }

        val filtered = kalmanFilterLocations(before + bogus + after)

        assertTrue("bogus fix must not survive, got ${filtered.maxOf { it.lat }}", filtered.none { it.lat > 50.01 })
        assertTrue("the tail after the jump was dropped: only ${filtered.size} fixes", filtered.size > 80)
        assertEquals("track must end where the ride did", 8.0089, filtered.last().lon, 0.001)
    }
}
