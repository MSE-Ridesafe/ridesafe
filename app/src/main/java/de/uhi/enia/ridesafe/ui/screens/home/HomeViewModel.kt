package de.uhi.enia.ridesafe.ui.screens.home

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.uhi.enia.ridesafe.data.Refuel
import de.uhi.enia.ridesafe.data.Ride
import de.uhi.enia.ridesafe.data.RidesafeDatabase
import de.uhi.enia.ridesafe.data.Vehicle
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
import de.uhi.enia.ridesafe.rides.processing.score.ecoLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class HomeViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val db = RidesafeDatabase.getInstance(app)

    // The top-bar dropdown's raw choice (null = "All vehicles"), seeded from prefs so the dashboard
    // reopens on the last-viewed car. Validated against the garage inside the combine — the one
    // place the id and the vehicle list meet, and it re-runs when a vehicle is deleted mid-session.
    private val selection = MutableStateFlow(HomeVehiclePrefs.read(app))

    fun selectVehicle(id: Long?) {
        selection.value = id
        HomeVehiclePrefs.write(getApplication(), id)
    }

    // Hot + prefetched, same pattern (and reason) as RidesViewModel.entries: the combine below folds
    // every score/eco/activity window over all rides, so it runs on Default (off the frame thread)
    // and stays warm app-wide — a tab switch reads the last value on its first frame instead of
    // re-querying Room and recomputing the whole dashboard mid-fade on the main thread.
    val dashboard: StateFlow<HomeDashboardState> =
        combine(
            db.vehicleDao().observeAll(),
            db.rideDao().observeAll(),
            db.refuelDao().observeAll(),
            selection,
        ) { vehicles, allRides, allRefuels, persistedId ->
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val currentMonth = YearMonth.from(today)
            // Everything below sees only the selection's slice; the folds themselves stay
            // selection-blind. Null keeps the full lists, including vehicle-less rides.
            val selectedVehicleId = effectiveVehicleSelection(vehicles, persistedId)
            val rides = ridesForVehicle(allRides, selectedVehicleId)
            val refuels = refuelsForVehicle(allRefuels, selectedVehicleId)
            val logicalJourneys = logicalRideJourneys(rides)
            val currentMonthTotals = journeyTotalsForMonth(logicalJourneys, currentMonth, zone)
            val activityByDay =
                addRefuelCosts(
                    activityByDay = journeyActivityByDay(logicalJourneys, zone).mapValues { it.value.toActivityBar() },
                    refuels = refuels,
                    zone = zone,
                )

            HomeDashboardState(
                selectedVehicleId = selectedVehicleId,
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
                // Also off the raw rows. The level (not the profile) is what gates the card: a
                // selection can have profiled rides without enough qualified data to rate.
                ecoLevel = ecoLevel(ecoProfileForRides(rides)),
            )
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, HomeDashboardState())
}

/**
 * The persisted dropdown choice, or null ("All vehicles") when it no longer applies: the vehicle
 * was deleted, or the garage shrank below two cars — the selector is hidden then, and a lingering
 * filter would be invisible with no UI left to clear it.
 */
internal fun effectiveVehicleSelection(
    vehicles: List<Vehicle>,
    persistedId: Long?,
): Long? = persistedId.takeIf { id -> vehicles.size >= 2 && vehicles.any { it.id == id } }

/** Null keeps every ride, including ones recorded without a vehicle; an id keeps exact matches only. */
internal fun ridesForVehicle(
    rides: List<Ride>,
    vehicleId: Long?,
): List<Ride> = if (vehicleId == null) rides else rides.filter { it.vehicleId == vehicleId }

internal fun refuelsForVehicle(
    refuels: List<Refuel>,
    vehicleId: Long?,
): List<Refuel> = if (vehicleId == null) refuels else refuels.filter { it.vehicleId == vehicleId }

internal fun addRefuelCosts(
    activityByDay: Map<LocalDate, ActivityBar>,
    refuels: List<Refuel>,
    zone: ZoneId,
): Map<LocalDate, ActivityBar> {
    val result = activityByDay.toMutableMap()
    refuels.forEach { refuel ->
        val day = Instant.ofEpochMilli(refuel.timestampEpochMs).atZone(zone).toLocalDate()
        val existing = result[day] ?: ActivityBar(day, rideCount = 0, distanceMeters = 0.0, durationMillis = 0L)
        result[day] = existing.copy(costMinor = existing.costMinor + refuel.totalPriceMinor)
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

/**
 * The dashboard's persisted vehicle selection, same prefs-file idiom as [de.uhi.enia.ridesafe.util.UnitPrefs]
 * but without the snapshot-state cache — nothing composes off it; the ViewModel's flow carries the
 * live value and the pref only has to survive process death.
 */
private object HomeVehiclePrefs {
    private const val PREFS_NAME = "ridesafe_prefs"
    private const val KEY_SELECTED_VEHICLE = "home_selected_vehicle_id"
    private const val NO_SELECTION = 0L // Room ids autoGenerate from 1, so 0 is free as "unset".

    fun read(context: Context): Long? =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_SELECTED_VEHICLE, NO_SELECTION)
            .takeIf { it != NO_SELECTION }

    fun write(
        context: Context,
        id: Long?,
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            if (id == null) remove(KEY_SELECTED_VEHICLE) else putLong(KEY_SELECTED_VEHICLE, id)
        }
    }
}
