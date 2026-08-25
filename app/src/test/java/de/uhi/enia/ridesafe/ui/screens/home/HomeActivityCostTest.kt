package de.uhi.enia.ridesafe.ui.screens.home

import de.uhi.enia.ridesafe.data.Refuel
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class HomeActivityCostTest {
    private val zone = ZoneId.of("Europe/Berlin")

    @Test
    fun refuelCostsAreSummedByLocalDayWithoutChangingRideActivity() {
        val rideDay = LocalDate.of(2026, 8, 24)
        val refuelOnlyDay = rideDay.plusDays(1)
        val rideActivity =
            ActivityBar(
                day = rideDay,
                rideCount = 2,
                distanceMeters = 42_000.0,
                durationMillis = 3_600_000,
            )

        val result =
            addRefuelCosts(
                activityByDay = mapOf(rideDay to rideActivity),
                refuels =
                    listOf(
                        refuel(rideDay, 4_000),
                        refuel(rideDay, 2_500),
                        refuel(refuelOnlyDay, 7_200),
                    ),
                zone = zone,
            )

        assertEquals(rideActivity.copy(costMinor = 6_500), result[rideDay])
        assertEquals(7_200L, result.getValue(refuelOnlyDay).costMinor)
        assertEquals(0, result.getValue(refuelOnlyDay).rideCount)
    }

    private fun refuel(
        day: LocalDate,
        costMinor: Long,
    ) =
        Refuel(
            vehicleId = 1,
            timestampEpochMs = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
            fuelAmountMilliliters = 30_000,
            totalPriceMinor = costMinor,
            currencyCode = "EUR",
            odometerMeters = 100_000_000,
        )
}
