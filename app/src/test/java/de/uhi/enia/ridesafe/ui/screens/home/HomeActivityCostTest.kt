package de.uhi.enia.ridesafe.ui.screens.home

import de.uhi.enia.ridesafe.data.Refuel
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
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

        assertEquals(rideActivity.copy(costMinorByCurrency = mapOf("EUR" to 6_500L)), result[rideDay])
        assertEquals(mapOf("EUR" to 7_200L), result.getValue(refuelOnlyDay).costMinorByCurrency)
        assertEquals(0, result.getValue(refuelOnlyDay).rideCount)
    }

    @Test
    fun refuelCostsAreBucketedPerCurrencyNeverSummedAcrossCurrencies() {
        val day = LocalDate.of(2026, 8, 24)

        val result =
            addRefuelCosts(
                activityByDay = emptyMap(),
                refuels =
                    listOf(
                        refuel(day, 4_000, "EUR"),
                        refuel(day, 2_500, "USD"),
                        // Imported backups may carry lowercase codes; folds into the EUR bucket.
                        refuel(day, 1_000, "eur"),
                    ),
                zone = zone,
            )

        val bar = result.getValue(day)
        assertEquals(mapOf("EUR" to 5_000L, "USD" to 2_500L), bar.costMinorByCurrency)
        // The chart reads only the selected currency's bucket, absent currencies as zero.
        assertEquals(5_000.0, bar.valueFor(ActivityChartMetric.COST, "EUR"), 0.0)
        assertEquals(2_500.0, bar.valueFor(ActivityChartMetric.COST, "USD"), 0.0)
        assertEquals(0.0, bar.valueFor(ActivityChartMetric.COST, "CHF"), 0.0)
        assertEquals(5_000.0, activityScaleMaximum(result.values, ActivityChartMetric.COST, "EUR"), 0.0)
    }

    @Test
    fun initialCalendarWeekStartsOnMonday() {
        val referenceDay = LocalDate.of(2026, 8, 26)

        val monday = startOfCalendarWeek(referenceDay)
        val bars = buildSevenDayActivity(emptyMap(), monday)

        assertEquals(7, bars.size)
        assertEquals(DayOfWeek.MONDAY, bars.first().day.dayOfWeek)
        assertEquals(LocalDate.of(2026, 8, 24), bars.first().day)
        assertEquals(DayOfWeek.SUNDAY, bars.last().day.dayOfWeek)
        assertEquals(LocalDate.of(2026, 8, 30), bars.last().day)
    }

    @Test
    fun sevenDayWindowCanStartOnTuesday() {
        val tuesday = LocalDate.of(2026, 8, 25)

        val bars = buildSevenDayActivity(emptyMap(), tuesday)

        assertEquals(DayOfWeek.TUESDAY, bars.first().day.dayOfWeek)
        assertEquals(tuesday.plusDays(6), bars.last().day)
        assertEquals(DayOfWeek.MONDAY, bars.last().day.dayOfWeek)
    }

    @Test
    fun barScaleCanRemainStableWhenTheLargestDayLeavesTheWindow() {
        val monday = LocalDate.of(2026, 8, 24)
        val activityByDay =
            mapOf(
                monday to ActivityBar(monday, 1, 157_700.0, 1_000),
                monday.plusDays(1) to ActivityBar(monday.plusDays(1), 1, 93_400.0, 1_000),
            )

        val scaleMaximum = activityScaleMaximum(activityByDay.values, ActivityChartMetric.DISTANCE, "EUR")
        val tuesdayFraction = activityByDay.getValue(monday.plusDays(1)).distanceMeters / scaleMaximum

        assertEquals(157_700.0, scaleMaximum, 0.0)
        assertEquals(93_400.0 / 157_700.0, tuesdayFraction, 0.0)
    }

    private fun refuel(
        day: LocalDate,
        costMinor: Long,
        currencyCode: String = "EUR",
    ) = Refuel(
        vehicleId = 1,
        timestampEpochMs =
            day
                .atTime(12, 0)
                .atZone(zone)
                .toInstant()
                .toEpochMilli(),
        fuelAmountMilliliters = 30_000,
        totalPriceMinor = costMinor,
        currencyCode = currencyCode,
        odometerMeters = 100_000_000,
    )
}
