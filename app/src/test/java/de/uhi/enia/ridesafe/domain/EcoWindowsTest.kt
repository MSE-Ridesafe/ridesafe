package de.uhi.enia.ridesafe.domain

import de.uhi.enia.ridesafe.data.Ride
import de.uhi.enia.ridesafe.data.RideEco
import de.uhi.enia.ridesafe.data.SafetyScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the dashboard's eco pooling (ANL-03): the vehicle filter, null meaning "no data", and the
 * ANL-01/ANL-03 coupling — only rated rides (both scores) contribute.
 */
class EcoWindowsTest {
    // Passes the level floors (>= 500 m, >= 120 s) so a scored ride counts as rated.
    private fun eco(meters: Double) = RideEco(150.0, 10.0, 20.0, 120.0, 10.0, meters, 50.0, 40.0, 10.0)

    private val score = SafetyScore(80, 80, 80, 80, 0.1, 0.1, 0.1, 600.0)

    private fun ride(
        id: Long,
        vehicle: Long?,
        eco: RideEco?,
        score: SafetyScore? = this.score,
    ) = Ride(
        id = id,
        vehicleId = vehicle,
        startedAtEpochMs = id * 60_000,
        startedElapsedNanos = 0,
        endedAtEpochMs = id * 60_000 + 60_000,
        sampleFile = "r$id.ndjson.gz",
        eco = eco,
        score = score,
    )

    private val rides =
        listOf(
            ride(1, vehicle = 1, eco = eco(1000.0)),
            ride(2, vehicle = 1, eco = eco(2000.0)),
            ride(3, vehicle = 2, eco = eco(4000.0)),
            ride(4, vehicle = null, eco = eco(8000.0)), // recorded in no vehicle
            ride(5, vehicle = 1, eco = null), // not yet profiled
        )

    @Test
    fun poolsEverythingIncludingUnassignedRides() {
        assertEquals(15_000.0, ecoProfileForRides(rides)!!.meters, 1e-9)
    }

    @Test
    fun vehicleFilterPoolsOnlyThatCarsRides() {
        assertEquals(3000.0, ecoProfileForRides(rides, vehicleId = 1)!!.meters, 1e-9)
        assertEquals(4000.0, ecoProfileForRides(rides, vehicleId = 2)!!.meters, 1e-9)
    }

    @Test
    fun nothingProfiledIsNullNotAPerfectProfile() {
        assertNull(ecoProfileForRides(rides, vehicleId = 99))
        assertNull(ecoProfileForRides(listOf(ride(6, vehicle = 1, eco = null)), vehicleId = 1))
        assertNull(ecoProfileForRides(emptyList()))
    }

    @Test
    fun unratedRidesContributeNothing() {
        // Eco profile but no safety score: half-rated, so it pools nothing (both or neither).
        val ecoOnly = ride(7, vehicle = 1, eco = eco(9000.0), score = null)
        assertNull(ecoProfileForRides(listOf(ecoOnly)))
        // And it doesn't leak into a pool that has rated rides either.
        assertEquals(1000.0, ecoProfileForRides(listOf(ride(1, vehicle = 1, eco = eco(1000.0)), ecoOnly))!!.meters, 1e-9)
    }
}
