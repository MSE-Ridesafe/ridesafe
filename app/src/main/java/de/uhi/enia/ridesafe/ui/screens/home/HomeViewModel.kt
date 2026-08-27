package de.uhi.enia.ridesafe.ui.screens.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class HomeViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val db = RidesafeDatabase.getInstance(app)

    val dashboard: Flow<HomeDashboardState> =
        combine(db.vehicleDao().observeAll(), db.rideDao().observeAll()) { vehicles, rides ->
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val currentMonth = YearMonth.from(today)
            val primaryVehicle = vehicles.firstOrNull { it.isPrimary } ?: vehicles.firstOrNull()
            val logicalJourneys = logicalRideJourneys(rides)
            val currentMonthTotals = journeyTotalsForMonth(logicalJourneys, currentMonth, zone)
            val activityByDay =
                journeyActivityByDay(logicalJourneys, zone)
                    .mapValues { it.value.toActivityBar() }
            val weekDays = (6 downTo 0).map { today.minusDays(it.toLong()) }
            val bars =
                weekDays.map { day ->
                    activityByDay[day] ?: ActivityBar(day, rideCount = 0, distanceMeters = 0.0, durationMillis = 0L)
                }
            val monthDays =
                (1..currentMonth.lengthOfMonth()).map { dayOfMonth ->
                    currentMonth.atDay(dayOfMonth)
                }
            val monthlyActivity =
                monthDays.map { day ->
                    activityByDay[day] ?: ActivityBar(day, rideCount = 0, distanceMeters = 0.0, durationMillis = 0L)
                }

            HomeDashboardState(
                primaryVehicle = primaryVehicle,
                vehicles = vehicles,
                totalDistanceMeters = totalJourneyDistanceMeters(logicalJourneys),
                totalDurationMillis = totalJourneyTravelDurationMillis(logicalJourneys),
                totalRecordedRides = totalJourneyCount(logicalJourneys),
                currentMonthDistanceMeters = currentMonthTotals.distanceMeters,
                currentMonthDurationMillis = currentMonthTotals.durationMillis,
                currentMonthRecordedRides = currentMonthTotals.journeyCount,
                activityBars = bars,
                monthlyActivity = monthlyActivity,
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
        }
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
