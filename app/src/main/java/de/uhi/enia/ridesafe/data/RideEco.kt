package de.uhi.enia.ridesafe.data

import kotlinx.serialization.Serializable

/**
 * A ride's efficiency profile (ANL-03): the raw kinematic aggregates the eco level is derived from,
 * integrated once from the filtered GPS trace and stored on the ride row.
 *
 * Everything here is pure kinematics — seconds, meters, and kinetic energy per kilogram of vehicle
 * (J/kg, so mass cancels) — which is what makes the profile car-independent: no engine model, no
 * rated economy, no fuel type. The same numbers are equally meaningful for a diesel and an EV, and
 * nothing needs re-deriving when a ride's vehicle changes.
 *
 * Deliberately aggregates, not a verdict: what counts as an efficient ride is a read-time decision
 * (see ecoLevel and EcoKnobs), exactly as the safety score derives from the stored [RideDynamics]
 * rather than storing a finished number of its own. Re-tuning the level therefore costs nothing at
 * all: it is never stored, so no ride is re-derived and no stage version moves. Even the score, which
 * is stored, is cheap to recalibrate — its stage reads no samples.
 *
 * The time buckets split the ride by driving regime for the breakdown bar: [brakeSeconds] is only
 * *friction* braking (deceleration beyond what drag and engine braking produce); gentle
 * deceleration — gliding, the thing ANL-03 wants encouraged — deliberately lands in [cruiseSeconds],
 * so the bar's braking slice is exactly the avoidable part.
 */
@Serializable
data class RideEco(
    /** Time at driving speed, however steady — the denominator for the idle share. */
    val movingSeconds: Double,
    val idleSeconds: Double,
    val accelSeconds: Double,
    val cruiseSeconds: Double,
    val brakeSeconds: Double,
    /** Distance integrated from the same trace (∫v·dt), so the profile stands on its own. */
    val meters: Double,
    /**
     * Kinetic energy shed by friction braking, in J/kg: Σ Δ(v²)/2 over intervals decelerating
     * beyond the coasting rate. The core of the level — whatever is braked away was bought with
     * fuel, and whatever is glided off was not.
     */
    val brakeJPerKg: Double,
    /** Kinetic energy gained while accelerating, in J/kg. */
    val accelJPerKg: Double,
    /** The part of [accelJPerKg] gained at aggressive rates — the "gentle acceleration" signal. */
    val hardAccelJPerKg: Double,
) {
    /** Share of the ride's time spent standing still — the stop-and-go tell (ANL-03). */
    val idleShare: Double get() =
        (idleSeconds + movingSeconds).takeIf { it > 0 }?.let { idleSeconds / it } ?: 0.0

    /** Share of acceleration energy gained aggressively rather than gently. */
    val hardAccelShare: Double get() =
        accelJPerKg.takeIf { it > 0 }?.let { hardAccelJPerKg / it } ?: 0.0

    /** Friction-braking energy per kilometre — the route-length-normalised waste rate. */
    val brakeJPerKgPerKm: Double get() =
        meters.takeIf { it > 0 }?.let { brakeJPerKg / (it / 1000.0) } ?: 0.0

    /** Bucket-wise sum, for a merged ride's whole-trip profile (MRG-05 for efficiency). */
    operator fun plus(other: RideEco) =
        RideEco(
            movingSeconds + other.movingSeconds,
            idleSeconds + other.idleSeconds,
            accelSeconds + other.accelSeconds,
            cruiseSeconds + other.cruiseSeconds,
            brakeSeconds + other.brakeSeconds,
            meters + other.meters,
            brakeJPerKg + other.brakeJPerKg,
            accelJPerKg + other.accelJPerKg,
            hardAccelJPerKg + other.hardAccelJPerKg,
        )
}
