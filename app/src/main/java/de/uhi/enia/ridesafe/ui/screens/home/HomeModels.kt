package de.uhi.enia.ridesafe.ui.screens.home

import de.uhi.enia.ridesafe.data.RideEco
import de.uhi.enia.ridesafe.data.SafetyScore
import de.uhi.enia.ridesafe.data.Vehicle
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

data class HomeDashboardState(
    val primaryVehicle: Vehicle? = null,
    // The garage, primary first — the manual-start button asks which car when there is a choice (TRK-08).
    val vehicles: List<Vehicle> = emptyList(),
    val totalDistanceMeters: Double = 0.0,
    val totalDurationMillis: Long = 0L,
    val totalRecordedRides: Int = 0,
    val currentMonthDistanceMeters: Double = 0.0,
    val currentMonthDurationMillis: Long = 0L,
    val currentMonthRecordedRides: Int = 0,
    val activityByDay: Map<LocalDate, ActivityBar> = emptyMap(),
    val highlights: HomeHighlights = HomeHighlights(),
    // The safety score over three windows (DSH-06); null = nothing scoreable in that window. All-time
    // null implies the others are too, which is what hides the card on a fresh install.
    val safetyWeek: SafetyScore? = null,
    val safetyMonth: SafetyScore? = null,
    val safetyAllTime: SafetyScore? = null,
    // Chart inputs (DSH-04): combined score per ISO week (keyed by its Monday), per calendar month,
    // and the all-time figure's own day-end history, oldest first.
    val safetyScoreByWeek: Map<LocalDate, Int> = emptyMap(),
    val safetyScoreByMonth: Map<YearMonth, Int> = emptyMap(),
    val safetyScoreHistory: List<Pair<LocalDate, Int>> = emptyList(),
    // The pooled efficiency profile (ANL-03) and each vehicle's own, for the dashboard's eco card
    // and its garage filter. All-time null hides the card — nothing profiled means nothing to rate.
    val ecoAllTime: RideEco? = null,
    val ecoByVehicle: Map<Long, RideEco> = emptyMap(),
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
