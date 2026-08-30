package de.uhi.enia.ridesafe.ui.screens.home

import de.uhi.enia.ridesafe.data.Refuel
import de.uhi.enia.ridesafe.data.Ride
import de.uhi.enia.ridesafe.data.Vehicle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

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
