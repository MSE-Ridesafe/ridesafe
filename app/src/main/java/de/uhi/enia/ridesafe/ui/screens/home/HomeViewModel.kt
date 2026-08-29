package de.uhi.enia.ridesafe.ui.screens.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.uhi.enia.ridesafe.data.Refuel
import de.uhi.enia.ridesafe.data.RidesafeDatabase
import de.uhi.enia.ridesafe.domain.JourneyActivity
import de.uhi.enia.ridesafe.domain.JourneyHighlights
import de.uhi.enia.ridesafe.domain.allTimeSafetyScoreHistory
import de.uhi.enia.ridesafe.domain.calculateJourneyHighlights
import de.uhi.enia.ridesafe.domain.ecoProfileForRides
import de.uhi.enia.ridesafe.domain.journeyActivityByDay
import de.uhi.enia.ridesafe.domain.journeyTotalsForMonth
import de.uhi.enia.ridesafe.domain.logicalRideJourneys
import de.uhi.enia.ridesafe.domain.monthlySafetyScores
import de.uhi.enia.ridesafe.domain.safetyScoreForMonth
import de.uhi.enia.ridesafe.domain.safetyScoreForRides
import de.uhi.enia.ridesafe.domain.safetyScoreForRollingWeek
import de.uhi.enia.ridesafe.domain.totalJourneyCount
import de.uhi.enia.ridesafe.domain.totalJourneyDistanceMeters
import de.uhi.enia.ridesafe.domain.totalJourneyTravelDurationMillis
import de.uhi.enia.ridesafe.domain.weeklySafetyScores
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

class HomeViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val db = RidesafeDatabase.getInstance(app)

    // Hot + prefetched, same pattern (and reason) as RidesViewModel.entries: the combine below folds
    // every score/eco/activity window over all rides, so it runs on Default (off the frame thread)
    // and stays warm app-wide — a tab switch reads the last value on its first frame instead of
    // re-querying Room and recomputing the whole dashboard mid-fade on the main thread.
    val dashboard: StateFlow<HomeDashboardState> =
        combine(db.vehicleDao().observeAll(), db.rideDao().observeAll(), db.refuelDao().observeAll()) { vehicles, rides, refuels ->
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val currentMonth = YearMonth.from(today)
            val primaryVehicle = vehicles.firstOrNull { it.isPrimary } ?: vehicles.firstOrNull()
            val logicalJourneys = logicalRideJourneys(rides)
            val currentMonthTotals = journeyTotalsForMonth(logicalJourneys, currentMonth, zone)
            val activityByDay =
                addRefuelCosts(
                    activityByDay = journeyActivityByDay(logicalJourneys, zone).mapValues { it.value.toActivityBar() },
                    refuels = refuels,
                    zone = zone,
                )

            HomeDashboardState(
                primaryVehicle = primaryVehicle,
                vehicles = vehicles,
                totalDistanceMeters = totalJourneyDistanceMeters(logicalJourneys),
                totalDurationMillis = totalJourneyTravelDurationMillis(logicalJourneys),
                totalRecordedRides = totalJourneyCount(logicalJourneys),
                currentMonthDistanceMeters = currentMonthTotals.distanceMeters,
                currentMonthDurationMillis = currentMonthTotals.durationMillis,
                currentMonthRecordedRides = currentMonthTotals.journeyCount,
                activityByDay = activityByDay,
                highlights = calculateJourneyHighlights(logicalJourneys, zone).toHomeHighlights(),
                // Off the raw ride rows, not the journeys: a merged ride's stops carry their own
                // scores and summing them is already the right aggregate (see SafetyScoreWindows).
                safetyWeek = safetyScoreForRollingWeek(rides, today, zone),
                safetyMonth = safetyScoreForMonth(rides, currentMonth, zone),
                safetyAllTime = safetyScoreForRides(rides),
                safetyScoreByWeek = weeklySafetyScores(rides, zone),
                safetyScoreByMonth = monthlySafetyScores(rides, zone),
                safetyScoreHistory = allTimeSafetyScoreHistory(rides, zone),
                // Also off the raw rows, and per vehicle for the eco card's garage filter. Vehicles
                // with nothing profiled are simply absent; their chip shows the empty state.
                ecoAllTime = ecoProfileForRides(rides),
                ecoByVehicle =
                    vehicles
                        .mapNotNull { vehicle ->
                            ecoProfileForRides(rides, vehicle.id)?.let { vehicle.id to it }
                        }.toMap(),
            )
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, HomeDashboardState())
}

internal fun addRefuelCosts(
    activityByDay: Map<LocalDate, ActivityBar>,
    refuels: List<Refuel>,
    zone: ZoneId,
): Map<LocalDate, ActivityBar> {
    val result = activityByDay.toMutableMap()
    refuels.forEach { refuel ->
        val day = Instant.ofEpochMilli(refuel.timestampEpochMs).atZone(zone).toLocalDate()
        val existing = result[day] ?: ActivityBar(day, rideCount = 0, distanceMeters = 0.0, durationMillis = 0L)
        // Bucketed per currency, never summed across codes — the chart picks the selected
        // currency's bucket. Uppercased because imported backups may carry lowercase codes
        // (RideBackupImport matches them case-insensitively).
        val code = refuel.currencyCode.uppercase(Locale.ROOT)
        val costs = existing.costMinorByCurrency.toMutableMap()
        costs[code] = (costs[code] ?: 0L) + refuel.totalPriceMinor
        result[day] = existing.copy(costMinorByCurrency = costs)
    }
    return result
}

private fun JourneyActivity.toActivityBar(): ActivityBar =
    ActivityBar(
        day = day,
        rideCount = journeyCount,
        distanceMeters = distanceMeters,
        durationMillis = durationMillis,
    )

private fun JourneyHighlights.toHomeHighlights(): HomeHighlights =
    HomeHighlights(
        longestRideMeters = longestRideMeters,
        averageRideMeters = averageRideMeters,
        mostActiveDay = mostActiveDay,
    )
