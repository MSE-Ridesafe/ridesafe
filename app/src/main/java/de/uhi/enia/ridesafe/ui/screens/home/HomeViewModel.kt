package de.uhi.enia.ridesafe.ui.screens.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import de.uhi.enia.ridesafe.data.RidesafeDatabase
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
            val finishedRides = rides.filter { it.endedAtEpochMs != null }
            val activityByDay =
                rides
                    .groupBy { it.startedAtEpochMs.toLocalDate(zone) }
                    .mapValues { (day, dayRides) ->
                        ActivityBar(
                            day = day,
                            rideCount = dayRides.count { it.endedAtEpochMs != null },
                            distanceMeters = dayRides.sumOf { it.distanceMeters ?: 0.0 },
                            durationMillis = dayRides.sumOf { it.durationMillis() },
                        )
                    }
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
                totalDistanceMeters = finishedRides.sumOf { it.distanceMeters ?: 0.0 },
                totalDurationMillis = finishedRides.sumOf { it.durationMillis() },
                activityBars = bars,
                monthlyActivity = monthlyActivity,
                activityByDay = activityByDay,
                highlights = calculateHomeHighlights(finishedRides, zone),
            )
        }
}
