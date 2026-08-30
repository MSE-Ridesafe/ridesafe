package de.uhi.enia.ridesafe.domain

import de.uhi.enia.ridesafe.data.Ride
import de.uhi.enia.ridesafe.data.RideEco

/**
 * The pooled efficiency profile (ANL-03) of a set of rides, optionally one vehicle's — the
 * dashboard's aggregate, and the future home of any other eco window.
 *
 * Aggregates are summed and the level derived **once** from the sum, never averaged as levels —
 * the same rule [aggregateScore] follows for safety, and for the same reason: energy and time add
 * across rides, levels do not, and summing weights each ride by its own driving for free.
 *
 * Like the safety windows, this reads the raw ride rows rather than [logicalRideJourneys]: a merged
 * ride's stops carry their own profiles and summing them is already the whole trip's profile.
 *
 * [vehicleId] narrows to one car's rides; null pools every ride, including those recorded in no
 * vehicle. Rides that aren't rated (see [isRated] — both scores or neither) contribute nothing,
 * and null comes back when nothing rated is in the set — no data, never "perfectly efficient".
 */
fun ecoProfileForRides(
    rides: List<Ride>,
    vehicleId: Long? = null,
): RideEco? =
    rides
        .filter { vehicleId == null || it.vehicleId == vehicleId }
        .mapNotNull { it.ratedEco }
        .reduceOrNull(RideEco::plus)
