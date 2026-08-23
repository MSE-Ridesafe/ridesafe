package de.uhi.enia.ridesafe.ui.screens.rides

import de.uhi.enia.ridesafe.data.Refuel
import de.uhi.enia.ridesafe.data.Ride
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineEntryTest {
    private fun rideEntry(id: Long, timestamp: Long) =
        TimelineEntry.RideEntry(
            LogbookEntry.Single(
                RideRow(
                    Ride(
                        id = id,
                        startedAtEpochMs = timestamp,
                        startedElapsedNanos = 0,
                        sampleFile = "ride-$id.ndjson",
                    ),
                    vehicleName = null,
                ),
            ),
        )

    private fun refuelEntry(id: Long, timestamp: Long) =
        TimelineEntry.RefuelEntry(
            RefuelRow(
                Refuel(
                    id = id,
                    vehicleId = 1,
                    timestampEpochMs = timestamp,
                    fuelAmountMilliliters = 1_000,
                    totalPriceMinor = 100,
                    currencyCode = "EUR",
                    odometerMeters = 1_000,
                ),
                vehicleName = null,
            ),
        )

    @Test
    fun ordersNewestFirstAndRideBeforeRefuelOnExactTie() {
        val ride = rideEntry(1, 100)
        val tiedRefuel = refuelEntry(2, 100)
        val newestRefuel = refuelEntry(3, 200)

        assertEquals(listOf(newestRefuel, ride, tiedRefuel), listOf(tiedRefuel, ride, newestRefuel).sortedWith(timelineEntryComparator))
    }

    @Test
    fun rideExtractionExcludesRefuelsForSelectionMergeAndExportInputs() {
        val ride = rideEntry(7, 100)
        val refuel = refuelEntry(8, 200)

        assertEquals(listOf(7L), rideLogbookEntries(listOf(refuel, ride)).flatMap { it.rideIds })
        assertEquals(setOf("f8", "r7"), timelineSelectionKeys(listOf(refuel, ride)))
        assertEquals(listOf(7L), selectedRideLogbookEntries(listOf(refuel, ride), setOf("f8", "r7")).flatMap { it.rideIds })
    }

    @Test
    fun attachedRefuelIsNestedOnceAndMissingAnchorFallsBackTopLevel() {
        val ride = rideEntry(7, 100).entry
        val attached = refuelEntry(8, 120).row.copy(
            refuel = refuelEntry(8, 120).row.refuel.copy(journeyAnchorRideId = 7),
        )
        val missingAnchor = refuelEntry(9, 130).row.copy(
            refuel = refuelEntry(9, 130).row.refuel.copy(journeyAnchorRideId = 99),
        )

        val timeline = buildTimeline(listOf(ride), listOf(attached, missingAnchor))
        val rideTimeline = timeline.filterIsInstance<TimelineEntry.RideEntry>().single()

        assertEquals(listOf(8L), rideTimeline.refuels.map { it.refuel.id })
        assertEquals(listOf(9L), timeline.filterIsInstance<TimelineEntry.RefuelEntry>().map { it.row.refuel.id })
        assertEquals(setOf("f9", "r7"), timelineSelectionKeys(timeline))
        assertEquals(setOf("f9", "r7", "f8"), visibleTimelineSelectionKeys(timeline))
        assertEquals(emptyList<Refuel>(), selectedRefuels(timeline, setOf("r7")))
        assertEquals(listOf(8L), selectedRefuels(timeline, setOf("f8")).map { it.id })
    }

    @Test
    fun combinedRideRefuelsAreNotSelectableOnMainTimeline() {
        val first = (rideEntry(1, 100).entry as LogbookEntry.Single).row
        val second = (rideEntry(2, 200).entry as LogbookEntry.Single).row
        val merged =
            LogbookEntry.Merged(
                groupId = 1,
                stops = listOf(first, second),
                summary = de.uhi.enia.ridesafe.data.summarizeMerge(listOf(first.ride, second.ride)),
                vehicleName = null,
            )
        val refuel = refuelEntry(8, 150).row.copy(refuel = refuelEntry(8, 150).row.refuel.copy(journeyAnchorRideId = 1))
        val timeline = buildTimeline(listOf(merged), listOf(refuel))

        assertEquals(setOf("g1"), visibleTimelineSelectionKeys(timeline))
        assertEquals(listOf(8L), (timeline.single() as TimelineEntry.RideEntry).refuels.map { it.refuel.id })
    }
}
