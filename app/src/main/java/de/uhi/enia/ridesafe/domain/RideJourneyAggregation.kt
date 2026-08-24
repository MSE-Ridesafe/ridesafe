package de.uhi.enia.ridesafe.domain

import de.uhi.enia.ridesafe.data.Ride
import de.uhi.enia.ridesafe.data.summarizeMerge
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

data class LogicalRideJourney(
    val key: String,
    val startEpochMs: Long,
    val distanceMeters: Double?,
    val travelDurationMillis: Long,
    val fuelLiters: Double? = null,
)

data class JourneyActivity(
    val day: LocalDate,
    val journeyCount: Int,
    val distanceMeters: Double,
    val durationMillis: Long,
)

data class JourneyHighlights(
    val longestRideMeters: Double?,
    val averageRideMeters: Double?,
    val mostActiveDay: DayOfWeek?,
)

data class JourneyPeriodTotals(
    val distanceMeters: Double,
    val durationMillis: Long,
    val journeyCount: Int,
    val fuelLiters: Double,
)

/**
 * Fold the logbook into the trips the dashboard counts: a merged ride is one journey, everything
 * else is its own.
 *
 * [fuelLitersByRide] carries each ride's calibrated fuel estimate (ANL-03), keyed by ride id. Passed
 * in rather than read off the ride, because the stored estimate is the raw model output and turning
 * it into litres for a specific car needs the garage — which this pure fold has no business knowing
 * about. Rides absent from the map (no estimate, or a vehicle the model doesn't describe) contribute
 * nothing, exactly as a ride with no distance yet does.
 */
fun logicalRideJourneys(
    rides: List<Ride>,
    fuelLitersByRide: Map<Long, Double> = emptyMap(),
): List<LogicalRideJourney> {
    val finishedRides = rides.filter { it.endedAtEpochMs != null }
    val consumedSegmentIds = mutableSetOf<Long>()
    val mergedJourneys =
        finishedRides
            .filter { it.mergeGroupId != null }
            .groupBy { it.mergeGroupId!! }
            .mapNotNull { (groupId, segments) ->
                if (segments.size < 2) return@mapNotNull null
                val summary = summarizeMerge(segments)
                consumedSegmentIds += segments.map { it.id }
                LogicalRideJourney(
                    key = "g$groupId",
                    startEpochMs = summary.startEpochMs,
                    distanceMeters = summary.distanceMeters,
                    travelDurationMillis = summary.movingDurationMs,
                    fuelLiters = segments.mapNotNull { fuelLitersByRide[it.id] }.takeIf { it.isNotEmpty() }?.sum(),
                )
            }

    val singleJourneys =
        finishedRides
            .filterNot { it.id in consumedSegmentIds }
            .map { ride ->
                LogicalRideJourney(
                    key = "r${ride.id}",
                    startEpochMs = ride.startedAtEpochMs,
                    distanceMeters = ride.distanceMeters,
                    travelDurationMillis = ride.durationMillis(),
                    fuelLiters = fuelLitersByRide[ride.id],
                )
            }

    return (mergedJourneys + singleJourneys).sortedByDescending { it.startEpochMs }
}

fun totalJourneyDistanceMeters(journeys: List<LogicalRideJourney>): Double = journeys.sumOf { it.distanceMeters ?: 0.0 }

fun totalJourneyTravelDurationMillis(journeys: List<LogicalRideJourney>): Long = journeys.sumOf { it.travelDurationMillis }

fun totalJourneyCount(journeys: List<LogicalRideJourney>): Int = journeys.size

fun totalJourneyFuelLiters(journeys: List<LogicalRideJourney>): Double = journeys.sumOf { it.fuelLiters ?: 0.0 }

fun journeyTotalsForMonth(
    journeys: List<LogicalRideJourney>,
    month: YearMonth,
    zone: ZoneId,
): JourneyPeriodTotals {
    val monthJourneys =
        journeys.filter { journey ->
            YearMonth.from(journey.startEpochMs.toLocalDate(zone)) == month
        }
    return JourneyPeriodTotals(
        distanceMeters = totalJourneyDistanceMeters(monthJourneys),
        durationMillis = totalJourneyTravelDurationMillis(monthJourneys),
        journeyCount = totalJourneyCount(monthJourneys),
        fuelLiters = totalJourneyFuelLiters(monthJourneys),
    )
}

fun calculateJourneyHighlights(
    journeys: List<LogicalRideJourney>,
    zone: ZoneId,
): JourneyHighlights {
    val distances = journeys.mapNotNull { it.distanceMeters }
    val mostActiveDay =
        journeys
            .groupingBy { it.startEpochMs.toLocalDate(zone).dayOfWeek }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
    return JourneyHighlights(
        longestRideMeters = distances.maxOrNull(),
        averageRideMeters = distances.takeIf { it.isNotEmpty() }?.average(),
        mostActiveDay = mostActiveDay,
    )
}

fun journeyActivityByDay(
    journeys: List<LogicalRideJourney>,
    zone: ZoneId,
): Map<LocalDate, JourneyActivity> =
    journeys
        .groupBy { it.startEpochMs.toLocalDate(zone) }
        .mapValues { (day, dayJourneys) ->
            JourneyActivity(
                day = day,
                journeyCount = dayJourneys.size,
                distanceMeters = dayJourneys.sumOf { it.distanceMeters ?: 0.0 },
                durationMillis = dayJourneys.sumOf { it.travelDurationMillis },
            )
        }

private fun Long.toLocalDate(zone: ZoneId): LocalDate = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

private fun Ride.durationMillis(): Long = endedAtEpochMs?.let { (it - startedAtEpochMs).coerceAtLeast(0L) } ?: 0L
