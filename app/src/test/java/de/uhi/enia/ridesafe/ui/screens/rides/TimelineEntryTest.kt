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
}
