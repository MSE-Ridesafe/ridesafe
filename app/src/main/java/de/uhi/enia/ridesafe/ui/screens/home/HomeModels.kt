package de.uhi.enia.ridesafe.ui.screens.home

import de.uhi.enia.ridesafe.data.Ride
import de.uhi.enia.ridesafe.data.Vehicle
import java.time.DayOfWeek
import java.time.LocalDate

data class HomeDashboardState(
    val primaryVehicle: Vehicle? = null,
    val activeRide: ActiveRideSummary? = null,
    val totalDistanceMeters: Double = 0.0,
    val totalDurationMillis: Long = 0L,
    val totalRecordedRides: Int = 0,
    val currentMonthDistanceMeters: Double = 0.0,
    val currentMonthDurationMillis: Long = 0L,
    val currentMonthRecordedRides: Int = 0,
    val activityByDay: Map<LocalDate, ActivityBar> = emptyMap(),
    val highlights: HomeHighlights = HomeHighlights(),
)

data class ActiveRideSummary(
    val ride: Ride,
    val vehicleName: String?,
)

data class ActivityBar(
    val day: LocalDate,
    val rideCount: Int,
    val distanceMeters: Double,
    val durationMillis: Long,
    val costMinor: Long = 0L,
)

data class HomeHighlights(
    val longestRideMeters: Double? = null,
    val averageRideMeters: Double? = null,
    val mostActiveDay: DayOfWeek? = null,
)
