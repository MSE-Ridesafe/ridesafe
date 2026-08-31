package de.uhi.enia.ridesafe.domain.refuel

import de.uhi.enia.ridesafe.data.entity.Refuel
import de.uhi.enia.ridesafe.data.entity.Ride
import de.uhi.enia.ridesafe.domain.ride.LogbookEntry
import de.uhi.enia.ridesafe.domain.ride.MergeCheck
import de.uhi.enia.ridesafe.domain.ride.RefuelRow
import de.uhi.enia.ridesafe.domain.ride.RideRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefuelAssociationTest {
    private fun ride(
        id: Long,
        start: Long,
        vehicleId: Long = 1,
        groupId: Long? = null,
    ) = Ride(
        id = id,
        vehicleId = vehicleId,
        mergeGroupId = groupId,
        startedAtEpochMs = start,
        startedElapsedNanos = 0,
        endedAtEpochMs = start + 10,
        sampleFile = "ride-$id.ndjson",
    )

    private fun entry(vararg rides: Ride): LogbookEntry =
        if (rides.size == 1) {
            LogbookEntry.Single(RideRow(rides.single(), null))
        } else {
            val rows = rides.map { RideRow(it, null) }
            LogbookEntry.Merged(
                rides.first().mergeGroupId!!,
                rows,
                de.uhi.enia.ridesafe.domain.ride
                    .summarizeMerge(rides.toList()),
                null,
            )
        }

    private fun refuel(
        id: Long,
        time: Long,
        vehicleId: Long = 1,
        anchor: Long? = null,
    ) = Refuel(
        id = id,
        vehicleId = vehicleId,
        timestampEpochMs = time,
        fuelAmountMilliliters = 1_000,
        totalPriceMinor = 100,
        currencyCode = "EUR",
        odometerMeters = 1_000,
        journeyAnchorRideId = anchor,
    )

    @Test
    fun addToRideRequiresOneRideCompatibleRefuelsAndARealChange() {
        val a = ride(1, 100)
        val b = ride(2, 200)

        assertEquals(RefuelAssociationCheck.OK, checkAddRefuelsToRide(listOf(entry(a)), listOf(refuel(10, 150)), listOf(a, b)))
        assertEquals(
            RefuelAssociationCheck.VEHICLE_MISMATCH,
            checkAddRefuelsToRide(listOf(entry(a)), listOf(refuel(10, 150, vehicleId = 2)), listOf(a, b)),
        )
        assertEquals(
            RefuelAssociationCheck.OTHER_JOURNEY,
            checkAddRefuelsToRide(listOf(entry(a)), listOf(refuel(10, 150, anchor = b.id)), listOf(a, b)),
        )
        assertEquals(
            RefuelAssociationCheck.NO_CHANGES,
            checkAddRefuelsToRide(listOf(entry(a)), listOf(refuel(10, 150, anchor = a.id)), listOf(a, b)),
        )
        assertEquals(
            RefuelAssociationCheck.WRONG_SELECTION,
            checkAddRefuelsToRide(listOf(entry(a), entry(b)), listOf(refuel(10, 150)), listOf(a, b)),
        )
    }

    @Test
    fun detachRequiresOnlyAttachedRefuels() {
        val a = ride(1, 100)
        assertEquals(
            RefuelAssociationCheck.OK,
            checkRemoveRefuelsFromRide(emptyList(), listOf(refuel(10, 150, anchor = a.id)), listOf(a)),
        )
        assertEquals(
            RefuelAssociationCheck.NOT_ALL_ATTACHED,
            checkRemoveRefuelsFromRide(emptyList(), listOf(refuel(10, 150, anchor = a.id), refuel(11, 160)), listOf(a)),
        )
    }

    @Test
    fun mixedMergeUsesRidesForEligibilityAndValidatesRefuelsSeparately() {
        val a = ride(1, 100)
        val b = ride(2, 200)
        val c = ride(3, 300)
        assertEquals(MergeCheck.OK, checkMixedMerge(listOf(entry(a), entry(b)), listOf(refuel(10, 150)), listOf(a, b, c)).rideCheck)
        assertEquals(
            RefuelAssociationCheck.VEHICLE_MISMATCH,
            checkMixedMerge(listOf(entry(a), entry(b)), listOf(refuel(10, 150, vehicleId = 2)), listOf(a, b, c)).refuelCheck,
        )
        assertEquals(
            RefuelAssociationCheck.OTHER_JOURNEY,
            checkMixedMerge(listOf(entry(a), entry(b)), listOf(refuel(10, 150, anchor = c.id)), listOf(a, b, c)).refuelCheck,
        )
        assertEquals(MergeCheck.NOT_ENOUGH, checkMixedMerge(listOf(entry(a)), listOf(refuel(10, 150)), listOf(a, b)).rideCheck)
    }

    @Test
    fun theSelectionPicksMergeUnmergeAttachOrDetach() {
        val a = ride(1, 100, groupId = 1)
        val b = ride(2, 200, groupId = 1)
        val c = ride(3, 300)
        val all = listOf(a, b, c)

        // One ride plus a refuel attaches — no second ride needed, and no merge group is created.
        val attach = logbookAction(listOf(entry(c)), listOf(refuel(10, 350)), all)
        assertEquals(LogbookActionKind.ATTACH, attach.kind)
        assertTrue(attach.enabled)

        // Refuels on their own can only be detached; two of them never merge into anything.
        val detach = logbookAction(emptyList(), listOf(refuel(10, 350, anchor = c.id)), all)
        assertEquals(LogbookActionKind.DETACH, detach.kind)
        assertTrue(detach.enabled)
        val unattached = logbookAction(emptyList(), listOf(refuel(10, 350), refuel(11, 360)), all)
        assertEquals(LogbookActionKind.DETACH, unattached.kind)
        assertFalse(unattached.enabled)

        assertEquals(LogbookActionKind.MERGE, logbookAction(listOf(entry(a, b), entry(c)), emptyList(), all).kind)
        assertEquals(LogbookActionKind.UNMERGE, logbookAction(listOf(entry(a, b)), emptyList(), all).kind)
        assertEquals(1L, logbookAction(listOf(entry(a, b)), emptyList(), all).unmergeGroupId)
        // A lone single ride is still a (disabled) merge, with the reason the user knows.
        assertEquals(MergeCheck.NOT_ENOUGH, logbookAction(listOf(entry(c)), emptyList(), all).rideCheck)
    }

    @Test
    fun closestAnchorAndCombinedChildrenHaveDeterministicChronology() {
        val earlier = ride(2, 100)
        val later = ride(1, 200)
        val tied = refuel(10, 150)
        assertEquals(earlier, closestRideAnchor(tied, listOf(later, earlier)))

        val sameTimeRefuel = refuel(9, 100)
        val children = combinedJourneyChildren(listOf(earlier, later), listOf(RefuelRow(tied, null), RefuelRow(sameTimeRefuel, null)))
        assertEquals(
            listOf(
                CombinedJourneyChild.RideChild(earlier),
                CombinedJourneyChild.RefuelChild(RefuelRow(sameTimeRefuel, null)),
                CombinedJourneyChild.RefuelChild(RefuelRow(tied, null)),
                CombinedJourneyChild.RideChild(later),
            ),
            children,
        )
    }
}
