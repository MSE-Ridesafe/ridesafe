package de.uhi.enia.ridesafe.ui.screens.home

import de.uhi.enia.ridesafe.data.Ride
import java.time.ZoneId

fun calculateHomeHighlights(
    finishedRides: List<Ride>,
    zone: ZoneId,
): HomeHighlights {
    val finishedRideDistances = finishedRides.mapNotNull { it.distanceMeters }
    val mostActiveDay =
        finishedRides
            .groupingBy { it.startedAtEpochMs.toLocalDate(zone).dayOfWeek }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
    return HomeHighlights(
        longestRideMeters = finishedRideDistances.maxOrNull(),
        averageRideMeters = finishedRideDistances.takeIf { it.isNotEmpty() }?.average(),
        mostActiveDay = mostActiveDay,
    )
}
