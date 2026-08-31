package de.uhi.enia.ridesafe.domain.ride

import de.uhi.enia.ridesafe.data.entity.Ride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class RideJourneyAggregationTest {
    private val zone: ZoneId = ZoneId.of("Europe/Berlin")

    private fun epochMinute(
        date: LocalDate,
        minuteOfDay: Long,
    ): Long =
        date
            .atStartOfDay(zone)
            .plusMinutes(minuteOfDay)
            .toInstant()
            .toEpochMilli()

    private fun ride(
        id: Long,
        date: LocalDate = LocalDate.of(2026, 7, 13),
        startMinute: Long,
        durationMinutes: Long,
        distanceMeters: Double?,
        mergeGroupId: Long? = null,
    ): Ride =
        Ride(
            id = id,
            mergeGroupId = mergeGroupId,
            startedAtEpochMs = epochMinute(date, startMinute),
            startedElapsedNanos = 0,
            endedAtEpochMs = epochMinute(date, startMinute + durationMinutes),
            distanceMeters = distanceMeters,
            sampleFile = "ride_$id.ndjson.gz",
        )

    @Test
    fun onlySingleRidesAggregateAsIndividualJourneys() {
        val rides =
            listOf(
                ride(1, startMinute = 8 * 60, durationMinutes = 10, distanceMeters = 10_000.0),
                ride(2, startMinute = 9 * 60, durationMinutes = 20, distanceMeters = 20_000.0),
            )

        val journeys = logicalRideJourneys(rides)
        val highlights = calculateJourneyHighlights(journeys, zone)

        assertEquals(2, journeys.size)
        assertEquals(2, journeys.size)
        assertEquals(30_000.0, totalJourneyDistanceMeters(journeys), 0.001)
        assertEquals(30 * 60_000L, totalJourneyTravelDurationMillis(journeys))
        assertEquals(20_000.0, highlights.longestRideMeters!!, 0.001)
        assertEquals(15_000.0, highlights.averageRideMeters!!, 0.001)
        assertEquals(DayOfWeek.MONDAY, highlights.mostActiveDay)
    }

    @Test
    fun onlyCombinedRidesAggregateSegmentsAsOneJourney() {
        val rides =
            listOf(
                ride(1, startMinute = 8 * 60, durationMinutes = 10, distanceMeters = 10_000.0, mergeGroupId = 1),
                ride(2, startMinute = 9 * 60, durationMinutes = 20, distanceMeters = 20_000.0, mergeGroupId = 1),
            )

        val journeys = logicalRideJourneys(rides)

        assertEquals(1, journeys.size)
        assertEquals(1, journeys.size)
        assertEquals("g1", journeys.single().key)
        assertEquals(30_000.0, journeys.single().distanceMeters!!, 0.001)
        assertEquals(30 * 60_000L, journeys.single().travelDurationMillis)
        assertEquals(30_000.0, calculateJourneyHighlights(journeys, zone).averageRideMeters!!, 0.001)
    }

    @Test
    fun mixedSingleAndCombinedRidesCompareAsLogicalJourneys() {
        val rides =
            listOf(
                ride(1, startMinute = 8 * 60, durationMinutes = 10, distanceMeters = 10_000.0, mergeGroupId = 1),
                ride(2, startMinute = 9 * 60, durationMinutes = 20, distanceMeters = 20_000.0, mergeGroupId = 1),
                ride(3, startMinute = 11 * 60, durationMinutes = 15, distanceMeters = 40_000.0),
            )

        val journeys = logicalRideJourneys(rides)
        val highlights = calculateJourneyHighlights(journeys, zone)

        assertEquals(2, journeys.size)
        assertEquals(2, journeys.size)
        assertEquals(70_000.0, totalJourneyDistanceMeters(journeys), 0.001)
        assertEquals(45 * 60_000L, totalJourneyTravelDurationMillis(journeys))
        assertEquals(40_000.0, highlights.longestRideMeters!!, 0.001)
        assertEquals(35_000.0, highlights.averageRideMeters!!, 0.001)
    }

    @Test
    fun mergedSegmentsAreNotDoubleCounted() {
        val rides =
            listOf(
                ride(1, startMinute = 8 * 60, durationMinutes = 10, distanceMeters = 10_000.0, mergeGroupId = 1),
                ride(2, startMinute = 9 * 60, durationMinutes = 20, distanceMeters = 20_000.0, mergeGroupId = 1),
            )

        val journeys = logicalRideJourneys(rides)

        assertEquals(1, journeys.size)
        assertEquals(1, journeys.size)
        assertEquals(30_000.0, totalJourneyDistanceMeters(journeys), 0.001)
        assertFalse(journeys.any { it.key == "r1" || it.key == "r2" })
    }

    @Test
    fun combinedRideSpanningMultipleDaysIsAssignedToStartDay() {
        val firstDay = LocalDate.of(2026, 7, 13)
        val secondDay = firstDay.plusDays(1)
        val rides =
            listOf(
                ride(1, date = firstDay, startMinute = 23 * 60 + 50, durationMinutes = 10, distanceMeters = 5_000.0, mergeGroupId = 1),
                ride(2, date = secondDay, startMinute = 30, durationMinutes = 20, distanceMeters = 15_000.0, mergeGroupId = 1),
            )

        val journeys = logicalRideJourneys(rides)
        val activity = journeyActivityByDay(journeys, zone)

        assertEquals(1, journeys.size)
        assertEquals(20_000.0, activity.getValue(firstDay).distanceMeters, 0.001)
        assertEquals(30 * 60_000L, activity.getValue(firstDay).durationMillis)
        assertEquals(1, activity.getValue(firstDay).journeyCount)
        assertFalse(activity.containsKey(secondDay))
    }

    @Test
    fun emptyDataProducesZeroCountAndEmptyMetrics() {
        val journeys = logicalRideJourneys(emptyList())
        val highlights = calculateJourneyHighlights(journeys, zone)
        val activity = journeyActivityByDay(journeys, zone)
        val monthTotals = journeyTotalsForMonth(journeys, YearMonth.of(2026, 7), zone)

        assertEquals(0, journeys.size)
        assertEquals(0.0, totalJourneyDistanceMeters(journeys), 0.001)
        assertEquals(0L, totalJourneyTravelDurationMillis(journeys))
        assertEquals(0.0, monthTotals.distanceMeters, 0.001)
        assertEquals(0L, monthTotals.durationMillis)
        assertEquals(0, monthTotals.journeyCount)
        assertEquals(null, highlights.longestRideMeters)
        assertEquals(null, highlights.averageRideMeters)
        assertEquals(null, highlights.mostActiveDay)
        assertEquals(emptyMap<LocalDate, JourneyActivity>(), activity)
    }

    @Test
    fun monthTotalsUseJourneyStartMonth() {
        val july = LocalDate.of(2026, 7, 31)
        val august = LocalDate.of(2026, 8, 1)
        val rides =
            listOf(
                ride(1, date = july, startMinute = 23 * 60 + 50, durationMinutes = 10, distanceMeters = 5_000.0, mergeGroupId = 1),
                ride(2, date = august, startMinute = 30, durationMinutes = 20, distanceMeters = 15_000.0, mergeGroupId = 1),
                ride(3, date = august, startMinute = 10 * 60, durationMinutes = 15, distanceMeters = 8_000.0),
            )
        val journeys = logicalRideJourneys(rides)

        val julyTotals = journeyTotalsForMonth(journeys, YearMonth.of(2026, 7), zone)
        val augustTotals = journeyTotalsForMonth(journeys, YearMonth.of(2026, 8), zone)

        assertEquals(20_000.0, julyTotals.distanceMeters, 0.001)
        assertEquals(30 * 60_000L, julyTotals.durationMillis)
        assertEquals(1, julyTotals.journeyCount)
        assertEquals(8_000.0, augustTotals.distanceMeters, 0.001)
        assertEquals(15 * 60_000L, augustTotals.durationMillis)
        assertEquals(1, augustTotals.journeyCount)
    }
}
