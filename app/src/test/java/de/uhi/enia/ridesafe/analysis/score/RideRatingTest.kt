package de.uhi.enia.ridesafe.analysis.score

import de.uhi.enia.ridesafe.data.entity.Ride
import de.uhi.enia.ridesafe.data.entity.RideEco
import de.uhi.enia.ridesafe.data.entity.SafetyScore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ANL-01/ANL-03 coupling: a ride is rated by both scores or by neither. */
class RideRatingTest {
    private val score = SafetyScore(80, 80, 80, 80, 0.1, 0.1, 0.1, 600.0)

    // Comfortably past the level floors (>= 500 m, >= 120 s).
    private val ratableEco = RideEco(150.0, 10.0, 20.0, 120.0, 10.0, 2000.0, 50.0, 40.0, 10.0)

    // A real profile that is too short to rate: below both floors.
    private val tinyEco = RideEco(30.0, 5.0, 5.0, 20.0, 5.0, 200.0, 5.0, 4.0, 1.0)

    private fun ride(
        score: SafetyScore?,
        eco: RideEco?,
    ) = Ride(
        id = 1,
        vehicleId = 1,
        startedAtEpochMs = 0,
        startedElapsedNanos = 0,
        endedAtEpochMs = 60_000,
        sampleFile = "r1.ndjson.gz",
        eco = eco,
        score = score,
    )

    @Test
    fun bothPresentIsRated() {
        val ride = ride(score, ratableEco)
        assertTrue(ride.isRated())
        assertNotNull(ride.ratedScore)
        assertNotNull(ride.ratedEco)
    }

    @Test
    fun ecoWithoutSafetyScoreRatesNeither() {
        val ride = ride(score = null, eco = ratableEco)
        assertFalse(ride.isRated())
        assertNull(ride.ratedScore)
        assertNull(ride.ratedEco)
    }

    @Test
    fun safetyScoreWithoutEcoLevelRatesNeither() {
        for (eco in listOf(null, tinyEco)) {
            val ride = ride(score = score, eco = eco)
            assertFalse(ride.isRated())
            assertNull(ride.ratedScore)
            assertNull(ride.ratedEco)
        }
    }

    @Test
    fun halfRatedRidesVanishFromSafetyWindowsToo() {
        assertNull(safetyScoreForRides(listOf(ride(score = score, eco = tinyEco))))
        assertNotNull(safetyScoreForRides(listOf(ride(score = score, eco = ratableEco))))
    }
}
