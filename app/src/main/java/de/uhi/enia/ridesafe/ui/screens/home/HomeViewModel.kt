package de.uhi.enia.ridesafe.ui.screens.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import de.uhi.enia.ridesafe.data.Refuel
import de.uhi.enia.ridesafe.data.RidesafeDatabase
import de.uhi.enia.ridesafe.domain.JourneyActivity
import de.uhi.enia.ridesafe.domain.JourneyHighlights
import de.uhi.enia.ridesafe.domain.calculateJourneyHighlights
import de.uhi.enia.ridesafe.domain.journeyActivityByDay
import de.uhi.enia.ridesafe.domain.journeyTotalsForMonth
import de.uhi.enia.ridesafe.domain.logicalRideJourneys
import de.uhi.enia.ridesafe.domain.totalJourneyCount
import de.uhi.enia.ridesafe.domain.totalJourneyDistanceMeters
import de.uhi.enia.ridesafe.domain.totalJourneyTravelDurationMillis
import de.uhi.enia.ridesafe.ui.screens.garage.displayTitle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class HomeViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val db = RidesafeDatabase.getInstance(app)

    val dashboard: Flow<HomeDashboardState> =
        combine(db.vehicleDao().observeAll(), db.rideDao().observeAll(), db.refuelDao().observeAll()) { vehicles, rides, refuels ->
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val currentMonth = YearMonth.from(today)
            val vehicleNames = vehicles.associate { it.id to it.displayTitle() }
            val primaryVehicle = vehicles.firstOrNull { it.isPrimary } ?: vehicles.firstOrNull()
            val activeRide =
                rides
                    .firstOrNull { it.endedAtEpochMs == null }
                    ?.let { ride ->
                        ActiveRideSummary(
                            ride = ride,
                            vehicleName =
                                ride.vehicleId?.let(vehicleNames::get)
                                    ?: primaryVehicle?.displayTitle(),
                        )
                    }
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
                activeRide = activeRide,
                totalDistanceMeters = totalJourneyDistanceMeters(logicalJourneys),
                totalDurationMillis = totalJourneyTravelDurationMillis(logicalJourneys),
                totalRecordedRides = totalJourneyCount(logicalJourneys),
                currentMonthDistanceMeters = currentMonthTotals.distanceMeters,
                currentMonthDurationMillis = currentMonthTotals.durationMillis,
                currentMonthRecordedRides = currentMonthTotals.journeyCount,
                activityByDay = activityByDay,
                highlights = calculateJourneyHighlights(logicalJourneys, zone).toHomeHighlights(),
            )
        }
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
