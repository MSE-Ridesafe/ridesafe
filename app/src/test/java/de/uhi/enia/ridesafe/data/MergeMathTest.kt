package de.uhi.enia.ridesafe.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the non-trivial merge logic: metric aggregation (MRG-05) and the merge/peel rules (MRG-02/09/11). */
class MergeMathTest {
    private fun ride(
        id: Long,
        vehicle: Long? = 1,
        startMin: Long,
        durMin: Long,
        distanceM: Double? = null,
        maxMps: Double = 0.0,
        fuel: RideFuel? = null,
    ) = Ride(
        id = id,
        vehicleId = vehicle,
        startedAtEpochMs = startMin * 60_000,
        startedElapsedNanos = 0,
        endedAtEpochMs = (startMin + durMin) * 60_000,
        distanceMeters = distanceM,
        maxSpeedMps = maxMps,
        sampleFile = "r$id.ndjson.gz",
        fuel = fuel,
    )

    @Test
    fun summaryUsesTotalsNotAverages() {
        // Two legs: 10 km in 10 min, then 30 km in 10 min. Avg must be over the totals (40 km / 20 min
        // = 33.3 m/s), NOT the mean of the two leg speeds (16.7 & 50 -> 33.3 here by luck), and max is
        // taken across legs. Duration sums the legs, excluding any parked gap between them.
        val a = ride(1, startMin = 0, durMin = 10, distanceM = 10_000.0, maxMps = 20.0)
        val b = ride(2, startMin = 30, durMin = 10, distanceM = 30_000.0, maxMps = 55.0) // 20-min gap before it

        val s = summarizeMerge(listOf(b, a)) // unordered input

        assertEquals(40_000.0, s.distanceMeters!!, 0.001)
        assertEquals(20 * 60_000L, s.movingDurationMs) // 20 min, gap excluded
        assertEquals(40_000.0 / (20 * 60), s.avgSpeedMps!!, 0.001)
        assertEquals(55.0, s.maxSpeedMps, 0.001)
        assertEquals(a.startedAtEpochMs, s.startEpochMs)
        assertEquals(b.endedAtEpochMs, s.endEpochMs)
    }

    @Test
    fun distanceNullUntilAllStopsProcessed() {
        val s = summarizeMerge(listOf(ride(1, startMin = 0, durMin = 5), ride(2, startMin = 10, durMin = 5)))
        assertNull(s.distanceMeters)
        assertNull(s.avgSpeedMps)
    }

    /**
     * A trip's fuel is its stops' fuel added up bucket by bucket (ANL-03), and a stop still waiting
     * on analysis contributes nothing rather than voiding the trip's total — the same best-effort
     * rule the distances follow.
     */
    @Test
    fun fuelSumsAcrossStopsBucketByBucket() {
        val a = ride(1, startMin = 0, durMin = 10, fuel = RideFuel(0.1, 1.0, 0.4, 0.2))
        val b = ride(2, startMin = 30, durMin = 10, fuel = RideFuel(0.3, 2.0, 0.6, 0.1))

        val summed = summarizeMerge(listOf(b, a)).fuel!!
        assertEquals(0.4, summed.idleLiters, 1e-9)
        assertEquals(3.0, summed.cruiseLiters, 1e-9)
        assertEquals(1.0, summed.accelLiters, 1e-9)
        assertEquals(0.3, summed.decelLiters, 1e-9)
        assertEquals(4.7, summed.totalLiters, 1e-9)

        // One stop analysed, one not: the trip reports what it has rather than nothing.
        val partial = summarizeMerge(listOf(a, ride(3, startMin = 60, durMin = 10))).fuel!!
        assertEquals(1.7, partial.totalLiters, 1e-9)
        // Nothing analysed at all is no estimate, not zero litres.
        assertNull(summarizeMerge(listOf(ride(4, startMin = 0, durMin = 5), ride(5, startMin = 10, durMin = 5))).fuel)
    }

    @Test
    fun canMergeRejectsMixedAndUnassignedVehicles() {
        val all =
            listOf(
                ride(1, vehicle = 1, startMin = 0, durMin = 5),
                ride(2, vehicle = 2, startMin = 10, durMin = 5),
                ride(3, vehicle = null, startMin = 20, durMin = 5),
            )
        assertEquals(MergeCheck.MIXED_VEHICLE, canMerge(setOf(1, 2), all)) // two different vehicles
        assertEquals(MergeCheck.MIXED_VEHICLE, canMerge(setOf(1, 3), all)) // one unassigned ride
        assertEquals(MergeCheck.NOT_ENOUGH, canMerge(setOf(1), all)) // only one ride
    }

    @Test
    fun canMergeAllowsInterleavedOtherVehicleButNotSkippedSameVehicle() {
        // Car 1's rides at t=0,20,40; car 2's ride at t=10 sits between two of car 1's — allowed (MRG-09).
        val all =
            listOf(
                ride(1, vehicle = 1, startMin = 0, durMin = 5),
                ride(2, vehicle = 2, startMin = 10, durMin = 5),
                ride(3, vehicle = 1, startMin = 20, durMin = 5),
                ride(4, vehicle = 1, startMin = 40, durMin = 5),
            )
        assertEquals(MergeCheck.OK, canMerge(setOf(1, 3), all)) // interleaved car-2 ride ignored
        assertEquals(MergeCheck.NOT_CONTIGUOUS, canMerge(setOf(1, 4), all)) // skips car-1 ride #3
        assertEquals(MergeCheck.OK, canMerge(setOf(1, 3, 4), all))
    }

    @Test
    fun mergeGroupIdIsSmallestId() {
        assertEquals(3L, mergeGroupIdFor(listOf(7, 3, 9)))
    }

    @Test
    fun peelRulesAllowEndsOnly() {
        val n = 4
        // Only the two ends are togglable from an empty selection.
        assertTrue(canToggleStop(0, emptySet(), n))
        assertTrue(canToggleStop(3, emptySet(), n))
        assertFalse(canToggleStop(1, emptySet(), n)) // interior would split the remaining block
        assertFalse(canToggleStop(2, emptySet(), n))

        // After peeling index 0, index 1 becomes the new front and is togglable.
        assertTrue(canToggleStop(1, setOf(0), n))
        assertFalse(canToggleStop(2, setOf(0), n))

        // Peeling from both ends at once is valid (MRG-11); leaving a hole in the middle is not.
        assertTrue(isValidPeel(setOf(0, 3), n))
        assertTrue(canUnmergeSelection(setOf(0, 3), n))
        assertFalse(isValidPeel(setOf(0, 2), n))
        assertFalse(canUnmergeSelection(emptySet(), n))
    }
}
