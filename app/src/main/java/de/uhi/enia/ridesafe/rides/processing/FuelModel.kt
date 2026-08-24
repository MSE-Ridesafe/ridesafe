package de.uhi.enia.ridesafe.rides.processing

import de.uhi.enia.ridesafe.data.FuelType
import de.uhi.enia.ridesafe.data.RideFuel
import de.uhi.enia.ridesafe.data.Vehicle
import de.uhi.enia.ridesafe.rides.recording.LocationSample
import kotlin.math.exp

/**
 * Fuel-consumption estimation (ANL-03) with the VT-Micro model — a regression of instantaneous fuel
 * rate on instantaneous speed and acceleration, fitted at Oak Ridge National Laboratory to five
 * light-duty vehicles and three light-duty trucks (Ahn, Rakha, Trani & Van Aerde 2002):
 *
 *     F = exp( sum(i=0..3) sum(j=0..3) K[i][j] * v^i * a^j )    L/s
 *
 * with `v` in m/s and `a` in m/s². Two coefficient matrices, one per acceleration sign, because an
 * engine under load and an engine being pushed by the vehicle's own momentum are different regimes.
 * Their `j = 0` columns are identical, so the model is continuous through a = 0.
 *
 * Pure Kotlin, no Android dependencies, so the whole thing is unit-testable off-device — see
 * FuelModelTest, which pins the coefficient table to four physical facts (idle rate, cruise economy,
 * acceleration costs more, deceleration costs less). Thirty-two numbers transcribed from a paper is
 * exactly the kind of table where a single wrong digit produces plausible-looking nonsense.
 *
 * These are the coefficients for a >= 0, where the engine is doing the work. Rows are powers of
 * speed, columns powers of acceleration.
 *
 * Calibration knob, and the only one that changes what the model *is*: these describe a composite
 * light-duty petrol vehicle of the study's fleet, not the user's car. Anchoring the magnitude to a
 * specific car is [calibrationFactor]'s job, deliberately kept separate so the stored estimate stays
 * a pure function of the ride.
 */
private val ACCEL_COEFFS =
    arrayOf(
        doubleArrayOf(-7.537, 0.4438, 0.1716, -0.0420),
        doubleArrayOf(0.0973, 0.0518, 0.0029, -0.0071),
        doubleArrayOf(-0.003026, -5.188e-3, -5.04e-5, 6.60e-4),
        doubleArrayOf(5.34e-5, 7.65e-5, 3.72e-6, -1.75e-5),
    )

/** Coefficients for a < 0: the vehicle is decelerating and the engine is unloaded or motoring. */
private val DECEL_COEFFS =
    arrayOf(
        doubleArrayOf(-7.537, 0.3095, 0.0765, -0.0068),
        doubleArrayOf(0.0973, -0.0206, -0.0038, 0.00020),
        doubleArrayOf(-0.003026, 0.00105, 5.37e-5, -8.35e-6),
        doubleArrayOf(5.34e-5, -1.88e-5, -6.45e-7, 1.63e-7),
    )

/**
 * Speed ceiling fed to the model, in m/s (144 km/h).
 *
 * ponytail: a hard clamp, because the fitted polynomial is cubic in speed and its positive `v^3`
 * term takes over past roughly 45 m/s — at 162 km/h the model claims 27 L/100 km and it only gets
 * worse. The ORNL data it was fitted to tops out around 121 km/h, so anything above this was
 * extrapolation anyway. The cost is real for autobahn driving: a sustained 180 km/h stretch is
 * billed at 144 km/h rates and under-estimated. The upgrade path is a high-speed term of the
 * physical form (aerodynamic drag ∝ v³ in *power*, so fuel per second ∝ v³) blended in above the
 * clamp, which needs a source for the vehicle's drag area — hence not done here.
 */
private const val MODEL_MAX_SPEED_MPS = 40.0

/**
 * Acceleration magnitude ceiling fed to the model, in m/s². Beyond this a 1 Hz speed difference is
 * far more likely to be a GPS artifact than a car: 4 m/s² sustained for a second is a hard launch or
 * an emergency stop. Raise it and outliers get amplified by the model's cubic acceleration term;
 * lower it and genuine hard driving is flattened toward ordinary.
 */
private const val MODEL_MAX_ACCEL_MPS2 = 4.0

/**
 * Below this speed (m/s) the vehicle counts as stationary and its fuel lands in the idle bucket —
 * the "stop-and-go" half of what ANL-03 asks the user to see. Just above a standstill rather than
 * exactly zero, since a filtered Doppler speed sitting at a red light still jitters.
 */
private const val IDLE_SPEED_MPS = 0.5

/**
 * Acceleration deadband (m/s²) separating cruising from accelerating/decelerating in the *display*
 * breakdown. Set above the noise floor of a 1 Hz Doppler-speed difference (~0.2 m/s²) so a steady
 * cruise reads as cruising rather than as an alternating accelerate/decelerate flicker. Affects
 * which bucket the fuel is counted in, never how much fuel there is — that always uses the
 * unrounded acceleration.
 */
private const val CRUISE_BAND_MPS2 = 0.25

/**
 * Longest gap between two fixes (seconds) still treated as continuous driving. Past it the ride
 * went through a tunnel or the filter dropped a run, and the speed on either side says nothing
 * about what happened in between — so the interval contributes no fuel rather than a fabricated
 * average. Under-estimates a ride with poor coverage, which is the honest direction to be wrong in.
 */
private const val MAX_FIX_GAP_SECONDS = 10.0

/** Speed used to anchor a vehicle's rated economy to the model, in m/s (90 km/h). */
private const val REFERENCE_SPEED_MPS = 25.0

/**
 * Instantaneous fuel rate in L/s at speed [mps] and acceleration [mps2], clamped to the range the
 * model was fitted over. The double loop is the model as published; at 16 terms it is not worth
 * unrolling.
 */
fun vtMicroLitersPerSecond(
    mps: Double,
    mps2: Double,
): Double {
    val v = mps.coerceIn(0.0, MODEL_MAX_SPEED_MPS)
    val a = mps2.coerceIn(-MODEL_MAX_ACCEL_MPS2, MODEL_MAX_ACCEL_MPS2)
    val coeffs = if (a >= 0) ACCEL_COEFFS else DECEL_COEFFS
    var ln = 0.0
    var vPow = 1.0
    for (i in 0..3) {
        var aPow = 1.0
        for (j in 0..3) {
            ln += coeffs[i][j] * vPow * aPow
            aPow *= a
        }
        vPow *= v
    }
    return exp(ln)
}

/**
 * What the model itself consumes cruising steadily at [REFERENCE_SPEED_MPS], in L/100 km — the
 * yardstick a vehicle's rated economy is measured against in [calibrationFactor]. Derived from the
 * coefficients rather than written down, so re-fitting the model moves this with it.
 */
val vtMicroReferenceLPer100Km: Double =
    vtMicroLitersPerSecond(REFERENCE_SPEED_MPS, 0.0) / REFERENCE_SPEED_MPS * 100_000.0

/**
 * Estimate a ride's fuel from its filtered GPS track, split by driving regime. Null when the track
 * holds nothing to integrate over.
 *
 * Speed comes from each fix's own Doppler reading — [TrackFilter] leaves that field alone precisely
 * because it is measured from carrier frequency shift rather than differenced positions — and
 * acceleration from the difference between consecutive fixes. That is a ~1 Hz speed/acceleration
 * trace, which is the input VT-Micro was fitted on.
 *
 * ponytail: the 50 Hz accelerometer would give a finer acceleration trace, but its longitudinal axis
 * carries the road's grade as well as the vehicle's acceleration, and separating the two needs an
 * altitude or pitch reference this app doesn't derive. Worth revisiting if hilly rides start looking
 * wrong; the model would have to be re-checked at that rate either way, since it was not fitted for
 * it.
 *
 * Each interval is integrated at its midpoint speed and constant acceleration — a trapezoid on
 * speed, a rectangle on rate. Sub-second curvature within a 1 Hz interval is below the model's own
 * resolution, so anything fancier would be false precision.
 */
fun estimateRideFuel(fixes: List<LocationSample>): RideFuel? {
    if (fixes.size < 2) return null
    var idle = 0.0
    var cruise = 0.0
    var accel = 0.0
    var decel = 0.0
    var integrated = false

    for (i in 1 until fixes.size) {
        val previous = fixes[i - 1]
        val current = fixes[i]
        val dt = (current.t - previous.t) / 1e9
        if (dt <= 0.0 || dt > MAX_FIX_GAP_SECONDS) continue

        val from = previous.speed.toDouble().coerceAtLeast(0.0)
        val to = current.speed.toDouble().coerceAtLeast(0.0)
        val v = (from + to) / 2.0
        val a = (to - from) / dt
        val liters = vtMicroLitersPerSecond(v, a) * dt
        integrated = true

        when {
            v < IDLE_SPEED_MPS -> idle += liters
            a > CRUISE_BAND_MPS2 -> accel += liters
            a < -CRUISE_BAND_MPS2 -> decel += liters
            else -> cruise += liters
        }
    }

    return if (integrated) RideFuel(idle, cruise, accel, decel) else null
}

/** Whether the model has any claim on a vehicle: it was fitted to petrol engines, diesel is close. */
fun FuelType.burnsEstimableFuel(): Boolean = this == FuelType.PETROL || this == FuelType.DIESEL

/**
 * How far the raw model output has to be scaled to describe [this] vehicle, from its rated economy.
 *
 * The coefficients describe the study's composite light-duty vehicle, so the *shape* of the estimate
 * — steady cruising cheap, stop-and-go expensive — transfers to any car, while the absolute litres
 * do not. Dividing the rated figure by what the model itself burns cruising at the same reference
 * speed keeps the shape and moves the magnitude onto the user's car.
 *
 * 1.0 when no economy is on file, which is the raw model rather than a guess at one.
 */
fun Vehicle.calibrationFactor(): Double {
    val rated = fuelEconomy ?: return 1.0
    return if (rated > 0) rated / vtMicroReferenceLPer100Km else 1.0
}

/**
 * A ride's stored estimate as it should be shown for [vehicle] (ANL-03), or null when there is
 * nothing honest to show — no estimate, no vehicle to attribute it to, or a drivetrain the model
 * does not describe (an electric car burns no litres at all, a hybrid shuts its engine off at rest
 * and recovers braking energy, both of which VT-Micro knows nothing about).
 *
 * Applied on read rather than baked into the stored value on purpose: a ride's vehicle can be
 * assigned afterwards and a vehicle's rated economy filled in later, and neither can re-trigger an
 * analysis stage that is already stamped as done.
 */
fun RideFuel?.forVehicle(vehicle: Vehicle?): RideFuel? {
    if (this == null || vehicle == null || !vehicle.fuelType.burnsEstimableFuel()) return null
    return this * vehicle.calibrationFactor()
}
