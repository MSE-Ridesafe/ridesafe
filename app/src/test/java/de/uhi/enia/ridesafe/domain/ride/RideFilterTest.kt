package de.uhi.enia.ridesafe.domain.ride

import de.uhi.enia.ridesafe.data.entity.Ride
import de.uhi.enia.ridesafe.data.entity.SavedAddress
import de.uhi.enia.ridesafe.data.entity.SavedPlaceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Covers the Logbook's search + filter matching (LOG-06, LOG-07, LOG-11 … LOG-15): what each filter
 * keeps, how a merged trip answers for its stops, and the folding the search does before matching.
 */
class RideFilterTest {
    private val home = place(1, "Home")
    private val work = place(2, "Work")
    private val gym = place(3, "Gym")

    private fun place(
        id: Long,
        label: String,
    ) = SavedAddress(
        id = id,
        label = label,
        kind = SavedPlaceKind.CUSTOM,
        latitude = 0.0,
        longitude = 0.0,
        radiusMeters = 100,
        icon = "place",
    )

    /** [day] is a day number; the ride runs from 08:00 to 09:00 local time on it. */
    private fun ride(
        id: Long,
        day: Int,
        vehicle: Long? = 1,
        distanceM: Double? = 10_000.0,
    ): Ride {
        val start =
            LocalDate
                .of(2026, 8, day)
                .atStartOfDay(ZoneId.systemDefault())
                .plusHours(8)
                .toInstant()
                .toEpochMilli()
        return Ride(
            id = id,
            vehicleId = vehicle,
            startedAtEpochMs = start,
            startedElapsedNanos = 0,
            endedAtEpochMs = start + 3_600_000,
            distanceMeters = distanceM,
            sampleFile = "r$id.ndjson.gz",
        )
    }

    private fun single(
        id: Long,
        day: Int,
        vehicle: Long? = 1,
        distanceM: Double? = 10_000.0,
        from: SavedAddress? = null,
        to: SavedAddress? = null,
    ) = LogbookEntry.Single(
        RideRow(ride(id, day, vehicle, distanceM), vehicleName = "Golf", startPlace = from, endPlace = to),
    )

    /** A merged trip from the given stops, in the shape the Logbook builds them (chronological). */
    private fun merged(
        groupId: Long,
        stops: List<LogbookEntry.Single>,
    ) = LogbookEntry.Merged(
        groupId = groupId,
        stops = stops.map { it.row },
        summary = summarizeMerge(stops.map { it.row.ride }),
        vehicleName = "Golf",
    )

    private fun filtered(
        entries: List<LogbookEntry>,
        filter: RideFilter,
        index: Map<String, String> = emptyMap(),
        withEvents: Set<Long> = emptySet(),
    ) = entries.applyFilter(filter, index, withEvents).map { it.key }

    private fun dayStart(day: Int): Long =
        LocalDate
            .of(2026, 8, day)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    @Test
    fun noFilterKeepsEverything() {
        val entries = listOf(single(1, day = 1), single(2, day = 2))
        assertEquals(listOf("r1", "r2"), filtered(entries, RideFilter()))
    }

    @Test
    fun vehicleFilterKeepsOnlyThatCar() {
        val entries = listOf(single(1, day = 1, vehicle = 1), single(2, day = 1, vehicle = 2), single(3, day = 1, vehicle = null))
        assertEquals(listOf("r2"), filtered(entries, RideFilter(vehicleId = 2)))
    }

    @Test
    fun placeFilterMatchesEndpointsIndependently() {
        val homeToWork = single(1, day = 1, from = home, to = work)
        val workToHome = single(2, day = 1, from = work, to = home)
        val entries = listOf(homeToWork, workToHome)

        assertEquals(listOf("r1"), filtered(entries, RideFilter(startPlaceId = home.id)))
        assertEquals(listOf("r2"), filtered(entries, RideFilter(endPlaceId = home.id)))
        // Both ends set: only the ride that has *both* survives.
        assertEquals(listOf("r1"), filtered(entries, RideFilter(startPlaceId = home.id, endPlaceId = work.id)))
        assertTrue(filtered(entries, RideFilter(startPlaceId = home.id, endPlaceId = gym.id)).isEmpty())
    }

    @Test
    fun mergedTripFiltersOnTripEndpointsNotIntermediateStops() {
        // Home -> Gym -> Work as one trip: it starts at Home and ends at Work. The Gym in the middle
        // is where it stopped, not where it started or ended, so it must not match either end.
        val trip =
            merged(
                1,
                listOf(
                    single(1, day = 1, from = home, to = gym),
                    single(2, day = 1, from = gym, to = work),
                ),
            )
        val entries = listOf<LogbookEntry>(trip)

        assertEquals(listOf("g1"), filtered(entries, RideFilter(startPlaceId = home.id)))
        assertEquals(listOf("g1"), filtered(entries, RideFilter(endPlaceId = work.id)))
        assertTrue(filtered(entries, RideFilter(startPlaceId = gym.id)).isEmpty())
        assertTrue(filtered(entries, RideFilter(endPlaceId = gym.id)).isEmpty())
    }

    @Test
    fun dateRangeEndsAreIndependentAndInclusive() {
        val entries = listOf(single(1, day = 1), single(2, day = 5), single(3, day = 9))

        // Open end: everything from the 5th on.
        assertEquals(listOf("r2", "r3"), filtered(entries, RideFilter(fromEpochMs = dayStart(5))))
        // Open start: everything up to and including the 5th (the bound is the next day, exclusive).
        assertEquals(listOf("r1", "r2"), filtered(entries, RideFilter(toEpochMs = dayStart(6))))
        // Both ends, and a single-day range still contains that day's rides.
        assertEquals(listOf("r2"), filtered(entries, RideFilter(fromEpochMs = dayStart(5), toEpochMs = dayStart(6))))
    }

    @Test
    fun mergedTripMatchesAnyDayItSpans() {
        // Stops on the 1st and the 3rd: the trip belongs to both days, and to the range between them.
        val trip = merged(1, listOf(single(1, day = 1), single(2, day = 3)))
        val entries = listOf<LogbookEntry>(trip)

        assertEquals(listOf("g1"), filtered(entries, RideFilter(fromEpochMs = dayStart(1), toEpochMs = dayStart(2))))
        assertEquals(listOf("g1"), filtered(entries, RideFilter(fromEpochMs = dayStart(3), toEpochMs = dayStart(4))))
        assertTrue(filtered(entries, RideFilter(fromEpochMs = dayStart(4))).isEmpty())
    }

    @Test
    fun distanceBoundsDropRidesWithNoDistanceYet() {
        val entries =
            listOf(
                single(1, day = 1, distanceM = 5_000.0),
                single(2, day = 1, distanceM = 25_000.0),
                // Not analysed yet: an unknown distance can't be claimed to satisfy a bound.
                single(3, day = 1, distanceM = null),
            )

        assertEquals(listOf("r2"), filtered(entries, RideFilter(minDistanceMeters = 10_000.0)))
        assertEquals(listOf("r1"), filtered(entries, RideFilter(maxDistanceMeters = 10_000.0)))
        assertEquals(listOf("r1", "r2"), filtered(entries, RideFilter(minDistanceMeters = 1_000.0, maxDistanceMeters = 30_000.0)))
    }

    @Test
    fun tripTypeSeparatesSingleRidesFromMergedTrips() {
        val entries = listOf(single(1, day = 1), merged(2, listOf(single(2, day = 2), single(3, day = 2))))

        assertEquals(listOf("r1"), filtered(entries, RideFilter(tripType = TripType.SINGLE)))
        assertEquals(listOf("g2"), filtered(entries, RideFilter(tripType = TripType.MERGED)))
    }

    @Test
    fun eventFilterKeepsATripWhenAnyStopHasEvents() {
        val entries = listOf(single(1, day = 1), merged(2, listOf(single(2, day = 2), single(3, day = 2))))

        // Only the merged trip's second stop has events; the trip still counts as having them.
        assertEquals(listOf("g2"), filtered(entries, RideFilter(onlyWithEvents = true), withEvents = setOf(3L)))
        assertEquals(listOf("r1"), filtered(entries, RideFilter(onlyWithEvents = true), withEvents = setOf(1L)))
    }

    @Test
    fun queryTokensAllHaveToMatch() {
        val entries = listOf(single(1, day = 1), single(2, day = 2))
        val index = mapOf("r1" to searchFold("Monday Hauptstraße 5 Golf"), "r2" to searchFold("Tuesday Bahnhofsweg 2 Golf"))

        assertEquals(listOf("r1", "r2"), filtered(entries, RideFilter(query = "golf"), index))
        assertEquals(listOf("r1"), filtered(entries, RideFilter(query = "golf monday"), index))
        assertTrue(filtered(entries, RideFilter(query = "golf sunday"), index).isEmpty())
    }

    @Test
    fun searchIgnoresCaseAccentsAndSharpS() {
        val entries = listOf(single(1, day = 1))
        val index = mapOf("r1" to searchFold("Hauptstraße 5, 81675 München"))

        assertEquals(listOf("r1"), filtered(entries, RideFilter(query = "hauptstrasse"), index))
        assertEquals(listOf("r1"), filtered(entries, RideFilter(query = "MUNCHEN"), index))
        assertEquals(listOf("r1"), filtered(entries, RideFilter(query = "münchen"), index))
    }

    @Test
    fun filtersCombineWithAnd() {
        val entries =
            listOf(
                single(1, day = 1, vehicle = 1, from = home, to = work),
                single(2, day = 1, vehicle = 2, from = home, to = work),
                single(3, day = 9, vehicle = 1, from = home, to = work),
            )
        val filter =
            RideFilter(
                vehicleId = 1,
                startPlaceId = home.id,
                fromEpochMs = dayStart(1),
                toEpochMs = dayStart(2),
            )
        assertEquals(listOf("r1"), filtered(entries, filter))
    }

    @Test
    fun activeFilterCountCountsEachRangeOnce() {
        assertEquals(0, RideFilter().activeFilterCount)
        assertEquals(1, RideFilter(fromEpochMs = 1, toEpochMs = 2).activeFilterCount)
        assertEquals(1, RideFilter(minDistanceMeters = 1.0, maxDistanceMeters = 2.0).activeFilterCount)
        assertEquals(3, RideFilter(vehicleId = 1, startPlaceId = 2, onlyWithEvents = true).activeFilterCount)
        // A search term narrows the list but is shown in the field, not as a filter badge.
        assertEquals(0, RideFilter(query = "home").activeFilterCount)
        assertTrue(RideFilter(query = "home").isActive)
    }
}
