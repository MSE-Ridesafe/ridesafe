package de.uhi.enia.ridesafe.ui.screens.home

import de.uhi.enia.ridesafe.data.SafetyScore
import de.uhi.enia.ridesafe.data.Vehicle
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

data class HomeDashboardState(
    // The dashboard's vehicle filter, already past the stale-id guard: null means "All vehicles",
    // which also pools rides recorded without one. Only ever non-null with two or more cars in the
    // garage — below that the top-bar selector is hidden and a filter would be invisible.
    val selectedVehicleId: Long? = null,
    // The garage, primary first — feeds the top-bar selector and header card, and the manual-start
    // button asks which car when there is a choice (TRK-08).
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
    // The pooled efficiency level (ANL-03) over the current selection, for the dashboard's eco
    // card. Null hides the card — nothing ratable means nothing to rate, same rule as the safety
    // card. The level (not the profile) gates it: a profile can lack enough qualified data to rate.
    val ecoLevel: Int? = null,
)

data class ActivityBar(
    val day: LocalDate,
    val rideCount: Int,
    val distanceMeters: Double,
    val durationMillis: Long,
    // Keyed by ISO 4217 code: minor units of different currencies must never be added, and the
    // chart shows only the bucket of the currently selected currency (no on-device FX rates).
    val costMinorByCurrency: Map<String, Long> = emptyMap(),
)

data class HomeHighlights(
    val longestRideMeters: Double? = null,
    val averageRideMeters: Double? = null,
    val mostActiveDay: DayOfWeek? = null,
)
