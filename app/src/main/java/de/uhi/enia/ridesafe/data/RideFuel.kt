package de.uhi.enia.ridesafe.data

import kotlinx.serialization.Serializable

/**
 * A ride's estimated fuel consumption (ANL-03), in litres, split by the driving regime it was burned
 * in — the split being the point, since "how much" alone says nothing the odometer doesn't already
 * say, while "a third of it went while standing still" is advice.
 *
 * Stored **uncalibrated**: this is the VT-Micro model's own output for its composite light-duty
 * vehicle, a pure function of the ride's filtered track and nothing else. Scaling it onto a specific
 * car, and deciding whether that car's drivetrain makes the number meaningful at all, happen on read
 * — see forVehicle. Both depend on the vehicle, which the user can reassign or edit long after the
 * analysis stage has stamped this ride as done; baking them in would leave every stored estimate
 * quietly describing a car the ride no longer belongs to.
 *
 * Kept in one JSON column on the ride rather than four REAL ones, the same way [Vehicle.bluetoothDevices]
 * holds its list: a small owned value read and written whole, never queried across, and adding a
 * fifth bucket then costs no migration. Ride totals are folded in Kotlin (see logicalRideJourneys),
 * so nothing needs SQL to sum it.
 */
@Serializable
data class RideFuel(
    val idleLiters: Double,
    val cruiseLiters: Double,
    val accelLiters: Double,
    val decelLiters: Double,
) {
    val totalLiters: Double get() = idleLiters + cruiseLiters + accelLiters + decelLiters

    /** Share of the ride's fuel burned while stationary — the stop-and-go tell (ANL-03). */
    val idleShare: Double get() = totalLiters.takeIf { it > 0 }?.let { idleLiters / it } ?: 0.0

    operator fun plus(other: RideFuel) =
        RideFuel(
            idleLiters + other.idleLiters,
            cruiseLiters + other.cruiseLiters,
            accelLiters + other.accelLiters,
            decelLiters + other.decelLiters,
        )

    operator fun times(factor: Double) = RideFuel(idleLiters * factor, cruiseLiters * factor, accelLiters * factor, decelLiters * factor)
}
