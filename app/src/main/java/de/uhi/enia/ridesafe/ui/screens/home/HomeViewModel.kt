package de.uhi.enia.ridesafe.ui.screens.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import de.uhi.enia.ridesafe.data.RidesafeDatabase
import de.uhi.enia.ridesafe.domain.JourneyActivity
import de.uhi.enia.ridesafe.domain.JourneyHighlights
import de.uhi.enia.ridesafe.domain.calculateJourneyHighlights
import de.uhi.enia.ridesafe.domain.journeyActivityByDay
import de.uhi.enia.ridesafe.domain.logicalRideJourneys
import de.uhi.enia.ridesafe.domain.totalJourneyDistanceMeters
import de.uhi.enia.ridesafe.domain.totalJourneyTravelDurationMillis
import de.uhi.enia.ridesafe.ui.screens.garage.displayTitle
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
                activeRide = activeRide,
                totalDistanceMeters = totalJourneyDistanceMeters(logicalJourneys),
                totalDurationMillis = totalJourneyTravelDurationMillis(logicalJourneys),
                activityBars = bars,
                monthlyActivity = monthlyActivity,
                activityByDay = activityByDay,
                highlights = calculateJourneyHighlights(logicalJourneys, zone).toHomeHighlights(),
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
