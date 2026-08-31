package de.uhi.enia.ridesafe.domain.ride

import de.uhi.enia.ridesafe.data.entity.Refuel
import de.uhi.enia.ridesafe.data.entity.Ride
import de.uhi.enia.ridesafe.data.entity.Vehicle

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
