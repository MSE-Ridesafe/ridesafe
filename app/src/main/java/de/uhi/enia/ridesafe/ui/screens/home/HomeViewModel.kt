package de.uhi.enia.ridesafe.ui.screens.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import de.uhi.enia.ridesafe.data.Ride
import de.uhi.enia.ridesafe.data.RidesafeDatabase
import de.uhi.enia.ridesafe.data.Vehicle
import de.uhi.enia.ridesafe.ui.screens.garage.displayTitle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

data class HomeDashboardState(
    val primaryVehicle: Vehicle? = null,
    val activeRide: ActiveRideSummary? = null,
    val totalDistanceMeters: Double = 0.0,
    val totalDurationMillis: Long = 0L,
    val activityBars: List<ActivityBar> = emptyList(),
    val monthlyActivity: List<ActivityBar> = emptyList(),
    val activityByDay: Map<LocalDate, ActivityBar> = emptyMap(),
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
)

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
            )
        }
}

private fun Long.toLocalDate(zone: ZoneId): LocalDate = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

private fun Ride.durationMillis(): Long = endedAtEpochMs?.let { (it - startedAtEpochMs).coerceAtLeast(0L) } ?: 0L
