package de.uhi.enia.ridesafe.domain.ride

import de.uhi.enia.ridesafe.data.entity.Refuel
import de.uhi.enia.ridesafe.data.entity.Ride
import de.uhi.enia.ridesafe.feature.garage.ui.previewVehicles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The dashboard's vehicle-selection guard and pre-fold filters (HomeViewModel helpers). */
class HomeVehicleFilterTest {
    private fun ride(
        id: Long,
        vehicleId: Long?,
    ): Ride =
        Ride(
            id = id,
            vehicleId = vehicleId,
            startedAtEpochMs = id * 60_000L,
            startedElapsedNanos = 0,
            sampleFile = "ride_$id.ndjson.gz",
        )

    private fun refuel(
        id: Long,
        vehicleId: Long,
    ): Refuel =
        Refuel(
            id = id,
            vehicleId = vehicleId,
            timestampEpochMs = id * 60_000L,
            fuelAmountMilliliters = 40_000,
            totalPriceMinor = 6_500,
            currencyCode = "EUR",
            odometerMeters = 82_000_000,
        )

    @Test
    fun selectionKeptWhenVehicleExists() {
        assertEquals(2L, effectiveVehicleSelection(previewVehicles, 2L))
    }

    @Test
    fun staleOrAbsentSelectionFallsBackToAllVehicles() {
        assertNull(effectiveVehicleSelection(previewVehicles, 99L))
        assertNull(effectiveVehicleSelection(previewVehicles, null))
    }

    @Test
    fun selectionNeutralizedWhenGarageShrinksBelowTwo() {
        // With the selector hidden below two cars, an id would be an invisible filter — even a valid one.
        assertNull(effectiveVehicleSelection(previewVehicles.take(1), 1L))
        assertNull(effectiveVehicleSelection(emptyList(), 1L))
    }

    @Test
    fun allVehiclesKeepsEveryRideIncludingUnassigned() {
        val rides = listOf(ride(1, vehicleId = 1L), ride(2, vehicleId = 2L), ride(3, vehicleId = null))
        assertEquals(rides, ridesForVehicle(rides, null))
    }

    @Test
    fun specificVehicleDropsOtherAndUnassignedRides() {
        val rides = listOf(ride(1, vehicleId = 1L), ride(2, vehicleId = 2L), ride(3, vehicleId = null))
        assertEquals(listOf(1L), ridesForVehicle(rides, 1L).map { it.id })
    }

    @Test
    fun refuelsFilterBySelection() {
        val refuels = listOf(refuel(1, vehicleId = 1L), refuel(2, vehicleId = 2L))
        assertEquals(refuels, refuelsForVehicle(refuels, null))
        assertEquals(listOf(2L), refuelsForVehicle(refuels, 2L).map { it.id })
    }
}
