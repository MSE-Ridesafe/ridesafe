package de.uhi.enia.ridesafe.domain.ride

import de.uhi.enia.ridesafe.core.format.toLocalDate
import de.uhi.enia.ridesafe.data.entity.Ride
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

data class LogicalRideJourney(
    val key: String,
    val startEpochMs: Long,
    val distanceMeters: Double?,
    val travelDurationMillis: Long,
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
)

fun logicalRideJourneys(rides: List<Ride>): List<LogicalRideJourney> {
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
                )
            }

    return (mergedJourneys + singleJourneys).sortedByDescending { it.startEpochMs }
}

fun totalJourneyDistanceMeters(journeys: List<LogicalRideJourney>): Double = journeys.sumOf { it.distanceMeters ?: 0.0 }

fun totalJourneyTravelDurationMillis(journeys: List<LogicalRideJourney>): Long = journeys.sumOf { it.travelDurationMillis }

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
        journeyCount = monthJourneys.size,
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

private fun Ride.durationMillis(): Long = endedAtEpochMs?.let { (it - startedAtEpochMs).coerceAtLeast(0L) } ?: 0L
