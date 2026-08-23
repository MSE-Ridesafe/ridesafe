package de.uhi.enia.ridesafe.ui.screens.rides

import de.uhi.enia.ridesafe.data.MergeCheck
import de.uhi.enia.ridesafe.data.Refuel
import de.uhi.enia.ridesafe.data.Ride
import org.junit.Assert.assertEquals
import org.junit.Test

class RefuelAssociationTest {
    private fun ride(id: Long, start: Long, vehicleId: Long = 1, groupId: Long? = null) =
        Ride(
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
            LogbookEntry.Merged(rides.first().mergeGroupId!!, rows, de.uhi.enia.ridesafe.data.summarizeMerge(rides.toList()), null)
        }

    private fun refuel(id: Long, time: Long, vehicleId: Long = 1, anchor: Long? = null) =
        Refuel(
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
