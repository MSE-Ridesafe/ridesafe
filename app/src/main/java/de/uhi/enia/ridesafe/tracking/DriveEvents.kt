package de.uhi.enia.ridesafe.tracking

import android.content.Context
import android.util.Log
import de.uhi.enia.ridesafe.data.DriveEvent
import de.uhi.enia.ridesafe.data.DriveEventType
import de.uhi.enia.ridesafe.data.Ride
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Standard gravity, for reporting event magnitudes in g rather than m/s². */
private const val G = 9.80665

/**
 * Bumped whenever detection changes in a way that invalidates stored events. Rides stamped with an
 * older value are re-analyzed on next launch, which is the whole re-tuning workflow: change the
 * detector, bump this, run the app.
 *
 * v2: detection threshold dropped from 0.20 g to 0.10 g, where real-world harsh driving actually
 * sits — 0.20 g was high enough that ordinary rides logged nothing at all.
 * v3: trigger moved from force to rate of force (jerk), with a magnitude path retained for
 * maneuvers that are hard however smoothly they were started.
 * v4: both halves of that AND raised to real-driving levels — jerk 0.6 → 1.0 g/s and the peak floor
 * 0.10 → 0.25 g. At the old values ordinary braking cleared both and nearly every stop was an event.
 * v5: heading now comes from the phone's orientation and a calibrated vehicle forward axis rather
 * than per-sample GPS, the speed gate reads Doppler instead of position-derived speed, and poorly
 * fixed stretches are skipped — all three so a wandering GPS can't manufacture events.
 * v6: the high-force bypass no longer applies to cornering, where v²/r geometry made every tight
 * low-speed turn an event regardless of how gently it was driven.
 * v7: thresholds are per-direction (an engine can't pull what brakes can), and the speed gate now
 * takes the lower of GPS and an IMU-derived speed so a wandering fix can't fake its way past it.
 */
const val ANALYZER_VERSION = 7

/**
 * Analyze one recorded ride (ANL-01): read its sample file and detect its driving events, tagged
 * with the ride's id and ready to persist. Null means the ride could not be read at all and should
 * be retried later; an empty list is a real result — a ride with nothing harsh in it.
 *
 * ponytail: a ride recorded without a gyroscope or rotation vector also yields an empty list, so
 * "no events" currently conflates "clean" with "unscoreable". Harmless while this only feeds a
 * marker layer; when the safety score (ANL-01) lands it needs its own sensor-availability signal
 * rather than reading zero events as a perfect drive.
 */
suspend fun analyzeRide(
    appContext: Context,
    ride: Ride,
    config: DriveEventConfig = DriveEventConfig(),
): List<DriveEvent>? {
    val file = File(ridesDir(appContext), ride.sampleFile)
    if (!file.exists()) return null
    val samples = readRideSamples(file)
    if (samples.accel.isEmpty()) return emptyList()

    // Motion and GPS timestamps are both meant to be on the elapsed-realtime base, but a few vendors
    // stamp sensors differently. Streams that don't overlap at all mean the clocks disagree, which
    // would otherwise show up as a silently event-free ride rather than something worth chasing.
    val fixes = samples.locations
    if (fixes.isNotEmpty() &&
        (samples.accel.last().t < fixes.first().t || fixes.last().t < samples.accel.first().t)
    ) {
        Log.w(TAG_EVENTS, "ride ${ride.id}: motion and GPS timestamps don't overlap; events unreliable")
    }

    return detectDriveEvents(samples, ride.startedElapsedNanos, config).map { it.copy(rideId = ride.id) }
}

private const val TAG_EVENTS = "DriveEvents"

/**
 * Thresholds for one direction of travel. They differ per direction because the physics do: brakes
 * bite harder and faster than an engine pushes, and cornering force is geometry rather than pedal
 * input, so a single set of numbers is wrong for at least two of the three.
 *
 * An event opens on [enterJerkGPerS] *and* a peak clearing [minPeakG] — abruptness and force
 * together, since either alone misjudges real driving. [highPeakG] is the exception that opens on
 * force alone; null means the direction has no such bypass.
 */
data class DirectionThresholds(
    val enterJerkGPerS: Double,
    val exitJerkGPerS: Double,
    val minPeakG: Double,
    val highPeakG: Double?,
)

/**
 * Detection knobs (NFR-08).
 *
 * The three directions carry their own thresholds. Braking keeps a 0.5 g force bypass because a stop
 * that hard is harsh however gently it was applied. Acceleration gets a lower one at 0.35 g and a
 * lower jerk gate, because torque builds more slowly than brakes bite and an ordinary car simply
 * cannot pull 0.5 g. Cornering gets no bypass at all: lateral force is v²/r, so a tight radius at
 * low speed clears any fixed threshold with nothing harsh happening — and jerk handles that case by
 * itself, since lateral jerk carries a v² factor that keeps low-speed steering under the gate.
 *
 * Both halves of the AND have to be set in real-driving terms or it means nothing. Reaching 0.25 g
 * at 1.0 g/s takes a quarter second, a deliberate stab at the pedal; at 0.6 g/s the same maneuver
 * takes 0.42 s, which is simply how people brake.
 *
 * [DirectionThresholds.minPeakG] does double duty. It keeps an event alive through the steady middle
 * of a maneuver, where jerk is near zero by definition — without it [DriveEvent.durationMs] would
 * measure how long the maneuver was *abrupt* rather than how long it lasted. And it is the floor an
 * event's peak must clear to be kept at all. Note that floor is a verdict on the finished event,
 * never a per-sample gate: jerk peaks at a maneuver's onset while force is still near zero, so
 * gating per sample would reject the very spike being triggered on.
 *
 * [minSpeedMps] rejects parking and the walk to the car (AutoTrackEngine starts recording before you
 * drive off). Speed for that gate is the lower of the GPS reading and an IMU-derived one — see
 * [minYawForImuSpeedRadPerS] — so a fix that wanders can't fake its way past it.
 * [maxGyroRadPerSec] rejects handling the phone, which swamps any real cornering: a hard U-turn is
 * well under 1 rad/s, picking a phone up is several. [maxFixAccuracyMeters] drops stretches where
 * the GPS admits it is lost — no events beats events invented from a position that isn't real.
 *
 * The alignment* knobs govern estimating the vehicle's forward axis in device coordinates, which is
 * what frees heading from GPS. Calibration only samples fast, straight, accurately-fixed driving
 * ([alignmentMinSpeedMps], [alignmentMaxTurnDeg]) because that is where GPS heading is worth
 * trusting; [alignmentMinSamples] and [alignmentMinCoherence] are the two ways it refuses to answer
 * rather than answer badly.
 */
data class DriveEventConfig(
    val braking: DirectionThresholds = DirectionThresholds(1.0, 0.7, 0.25, 0.50),
    val acceleration: DirectionThresholds = DirectionThresholds(0.8, 0.55, 0.25, 0.35),
    val cornering: DirectionThresholds = DirectionThresholds(1.0, 0.7, 0.30, null),
    val jerkBaselineMs: Long = 100,
    val minDurationMs: Long = 250,
    val mergeGapMs: Long = 500,
    val minSpeedMps: Double = 4.0, // ~15 km/h
    val minYawForImuSpeedRadPerS: Double = 0.15, // below this the IMU speed divides by ~nothing
    val maxGyroRadPerSec: Double = 2.5,
    val lowPassHz: Double = 2.0, // vehicle dynamics live below ~2 Hz; above it is road and mount noise
    val maxSampleAgeNanos: Long = 1_000_000_000, // ignore an orientation/gyro reading staler than this
    val maxFixAccuracyMeters: Double = 30.0,
    val alignmentMinSpeedMps: Double = 8.0, // ~29 km/h, fast enough for GPS heading to mean something
    val alignmentMaxTurnDeg: Double = 5.0, // straight-line only; heading lags through a corner
    val alignmentMinSamples: Int = 20,
    val alignmentMinCoherence: Double = 0.95, // ~18° mean scatter; below this the phone likely moved
)

/**
 * Detect harsh braking, acceleration and cornering in one ride's samples (ANL-01).
 *
 * The whole method rests on getting out of the device's frame, which is arbitrary and can shift
 * mid-ride. Each acceleration sample is rotated into the world ENU frame using the recorded
 * rotation vector, and the vertical component is then discarded. That single step does three jobs:
 * it removes gravity *exactly* (gravity is world-vertical by definition, so it never touches the
 * horizontal components), it makes the result slope-proof with no road-plane estimation, and it
 * leaves the true horizontal acceleration. Projecting that onto the direction of travel splits it
 * into longitudinal (braking/acceleration) and lateral (cornering).
 *
 * Direction of travel comes from the Kalman-filtered track, not raw GPS bearing, which is noise
 * below walking pace.
 *
 * What counts as harsh is then judged on how fast the force builds rather than how large it gets —
 * see [DriveEventConfig] — with a magnitude path for maneuvers that are hard however smoothly they
 * were started. Differentiating is only viable because the low-pass runs first: the derivative of a
 * raw 50 Hz signal is noise.
 *
 * Returns empty when the ride lacks the accelerometer, rotation vector or GPS the method needs — a
 * missing sensor means no score, never a guessed one.
 */
fun detectDriveEvents(
    samples: RideSamples,
    rideStartElapsedNanos: Long,
    config: DriveEventConfig = DriveEventConfig(),
): List<DriveEvent> {
    if (samples.accel.isEmpty() || samples.rotation.isEmpty() || samples.locations.size < 2) return emptyList()

    val fixes = kalmanFilterLocations(samples.locations)
    val forward = estimateForwardAxis(fixes, samples.rotation, config)
    val track = TrackInterpolator(fixes, config.maxFixAccuracyMeters)
    val rotations = NearestWalker(samples.rotation, config.maxSampleAgeNanos)
    val gyros = NearestWalker(samples.gyro, config.maxSampleAgeNanos)
    val braking = EventAccumulator(DriveEventType.BRAKING, config, config.braking, rideStartElapsedNanos)
    val accelerating = EventAccumulator(DriveEventType.ACCELERATION, config, config.acceleration, rideStartElapsedNanos)
    val cornering = EventAccumulator(DriveEventType.CORNERING, config, config.cornering, rideStartElapsedNanos)

    val baselineNanos = config.jerkBaselineMs * 1_000_000
    val brakingRate = RateTracker(baselineNanos)
    val acceleratingRate = RateTracker(baselineNanos)
    val corneringRate = RateTracker(baselineNanos)

    // Reused every sample instead of reallocated; the loop body runs once per acceleration reading.
    val matrix = DoubleArray(9)
    val heading = DoubleArray(2)

    val rc = 1.0 / (2 * Math.PI * config.lowPassHz)
    var smoothedLongitudinal = 0.0
    var smoothedLateral = 0.0
    var smoothedYawRate = 0.0
    var previousNanos = 0L
    var seeded = false

    for (accel in samples.accel) {
        val rotation = rotations.at(accel.t) ?: continue
        fillRotationMatrix(rotation, matrix)
        val ax = accel.x.toDouble()
        val ay = accel.y.toDouble()
        val az = accel.z.toDouble()
        val east = matrix[0] * ax + matrix[1] * ay + matrix[2] * az
        val north = matrix[3] * ax + matrix[4] * ay + matrix[5] * az
        val state = track.at(accel.t)

        // Heading comes from the phone's own orientation whenever the forward axis could be
        // calibrated: it updates at motion rate rather than 1 Hz, and it doesn't care what the GPS
        // is doing. The interpolated GPS heading is only the fallback for a ride that never gave
        // enough clean straight-line driving to calibrate from.
        val hasHeading =
            when {
                forward != null -> headingInto(matrix, forward, heading)
                state != null -> {
                    heading[0] = state.headEast
                    heading[1] = state.headNorth
                    true
                }
                else -> false
            }

        // Longitudinal is along the heading (negative = braking), lateral is perpendicular to it.
        val longitudinal = if (!hasHeading) 0.0 else east * heading[0] + north * heading[1]
        val lateral = if (!hasHeading) 0.0 else east * heading[1] - north * heading[0]

        // One-pole low-pass, dt from the real timestamps — sensor delivery is never truly uniform.
        // Filtering runs even on gated samples so the state stays warm and doesn't jump when the
        // gate lifts. The first sample seeds the filter outright instead of ramping up from zero.
        val dt = if (seeded) ((accel.t - previousNanos) / 1e9).coerceIn(1e-4, 1.0) else 0.0
        val alpha = if (seeded) dt / (dt + rc) else 1.0
        previousNanos = accel.t
        seeded = true
        smoothedLongitudinal += alpha * (longitudinal - smoothedLongitudinal)
        smoothedLateral += alpha * (lateral - smoothedLateral)

        val gyro = gyros.at(accel.t)
        val yawRate =
            if (gyro == null) 0.0 else verticalComponent(matrix, gyro.x.toDouble(), gyro.y.toDouble(), gyro.z.toDouble())
        smoothedYawRate += alpha * (yawRate - smoothedYawRate)

        val brakingG = (-smoothedLongitudinal / G).coerceAtLeast(0.0)
        val acceleratingG = (smoothedLongitudinal / G).coerceAtLeast(0.0)
        val corneringG = abs(smoothedLateral) / G

        // Rates track the real signal even while gated, so lifting a gate doesn't read as a step
        // change and fire a phantom event. Only the onset counts: easing off a brake is a negative
        // rate and isn't harsh, so the accumulators see rises only.
        val brakingJerk = brakingRate.update(accel.t, brakingG)
        val acceleratingJerk = acceleratingRate.update(accel.t, acceleratingG)
        val corneringJerk = corneringRate.update(accel.t, corneringG)

        val handling = gyro != null && hypot(hypot(gyro.x, gyro.y), gyro.z) > config.maxGyroRadPerSec

        // Speed for the gate is the lower of GPS and an estimate the IMU derives on its own. In any
        // turn, lateral acceleration is v·ω (since a = v²/r and ω = v/r), so dividing the two gives
        // speed with no GPS involved at all — which is the point, because GPS speed is least
        // trustworthy exactly where parking happens. Only valid while genuinely turning; below
        // minYawForImuSpeed the division is by ~nothing, so GPS stands alone there.
        //
        // ponytail: taken per sample, so a turn-in transient where yaw leads lateral force can read
        // low for a moment and gate out the start of a genuinely hard corner. Low-passing both
        // inputs keeps that small; widen to a held estimate if real events start going missing.
        val imuSpeed =
            if (abs(smoothedYawRate) >= config.minYawForImuSpeedRadPerS) abs(smoothedLateral) / abs(smoothedYawRate) else null
        val speed = if (state == null) 0.0 else minOf(state.speedMps, imuSpeed ?: Double.MAX_VALUE)

        if (state == null || !hasHeading || speed < config.minSpeedMps || handling) {
            // Below the speed gate or the phone is being handled: feed zero so any open event
            // closes on its own timing rather than spanning the excluded stretch.
            braking.feed(accel.t, 0.0, 0.0, null)
            accelerating.feed(accel.t, 0.0, 0.0, null)
            cornering.feed(accel.t, 0.0, 0.0, null)
            continue
        }

        braking.feed(accel.t, brakingG, brakingJerk, state)
        accelerating.feed(accel.t, acceleratingG, acceleratingJerk, state)
        cornering.feed(accel.t, corneringG, corneringJerk, state)
    }

    return (braking.finish() + accelerating.finish() + cornering.finish())
        .sortedBy { it.startOffsetMs }
}

/**
 * The device→world rotation matrix Android's `getRotationMatrixFromVector` builds, row-major as
 * `[r00..r22]`. Reimplemented rather than calling SensorManager so the detector stays pure Kotlin
 * and testable off-device.
 *
 * Acceleration uses only the first two rows: row 2 is the vertical, which is exactly where gravity
 * lives, and dropping it is both how gravity is removed and what makes the method slope-proof with
 * no road-plane estimation. Yaw rate is the opposite case — row 2 is the whole point there, since
 * the vertical component of the gyro *is* the vehicle's rate of turn.
 */
private fun fillRotationMatrix(
    rotation: MotionSample,
    into: DoubleArray,
) {
    val x = rotation.x.toDouble()
    val y = rotation.y.toDouble()
    val z = rotation.z.toDouble()
    // Some devices omit the scalar component; recover it from the unit-quaternion constraint.
    val w = rotation.w?.toDouble() ?: sqrt((1.0 - x * x - y * y - z * z).coerceAtLeast(0.0))
    // Written into a caller-owned array rather than returned: this runs once per acceleration
    // sample, millions of times on a long ride, and a fresh array each time is 78 MB of garbage.
    into[0] = 1 - 2 * (y * y + z * z)
    into[1] = 2 * (x * y - z * w)
    into[2] = 2 * (x * z + y * w)
    into[3] = 2 * (x * y + z * w)
    into[4] = 1 - 2 * (x * x + z * z)
    into[5] = 2 * (y * z - x * w)
    into[6] = 2 * (x * z - y * w)
    into[7] = 2 * (y * z + x * w)
    into[8] = 1 - 2 * (x * x + y * y)
}

/** The world-vertical (up) component of a device-frame vector — for the gyro, the yaw rate. */
private fun verticalComponent(
    rows: DoubleArray,
    x: Double,
    y: Double,
    z: Double,
): Double = rows[6] * x + rows[7] * y + rows[8] * z

/**
 * The vehicle's forward axis expressed in *device* coordinates — the constant that frees heading
 * from GPS.
 *
 * The phone doesn't move relative to the car, so the car's forward direction is a fixed vector in
 * the device frame, and the rotation vector already tracks the phone's absolute orientation at
 * 50 Hz. Recover that fixed vector once from GPS, and afterwards `R(t) · forward` gives the car's
 * heading at motion-sample rate whatever the GPS is doing — which is the point, since GPS heading is
 * meaningless at low speed and outright wrong when a fix jumps.
 *
 * Only fast, straight, well-fixed stretches are sampled, because that is the only place GPS heading
 * is worth believing. Each contributes `Rᵀ · heading`, and the average is the estimate.
 *
 * Returns null rather than a bad answer in two cases: too few samples to be sure, or samples that
 * disagree with each other. Disagreement is measured by the summed vector's length — unit vectors
 * that agree sum to nearly the sample count, ones that scatter fall well short — which catches the
 * phone having been moved or re-mounted mid-ride.
 *
 * ponytail: one estimate for the whole ride. A phone re-seated halfway through fails the coherence
 * check and falls back to GPS heading, rather than being tracked through the change; make this a
 * sliding window if that turns out to be common.
 */
private fun estimateForwardAxis(
    fixes: List<LocationSample>,
    rotations: List<MotionSample>,
    config: DriveEventConfig,
): DoubleArray? {
    if (fixes.size < 2 || rotations.isEmpty()) return null
    val walker = NearestWalker(rotations, config.maxSampleAgeNanos)
    val rows = DoubleArray(9)
    var sumX = 0.0
    var sumY = 0.0
    var sumZ = 0.0
    var used = 0

    for (i in 1 until fixes.size) {
        val fix = fixes[i]
        if (fix.speed < config.alignmentMinSpeedMps) continue
        if (fix.accuracy > config.maxFixAccuracyMeters) continue
        if (bearingDeltaDeg(fixes[i - 1].bearing, fix.bearing) > config.alignmentMaxTurnDeg) continue
        fillRotationMatrix(walker.at(fix.t) ?: continue, rows)

        // forward_device = Rᵀ · heading_world. With heading horizontal its up component is zero, so
        // only the two rows we compute contribute, and Rᵀ reads them down the columns.
        val headingRad = Math.toRadians(fix.bearing.toDouble())
        val east = sin(headingRad)
        val north = cos(headingRad)
        sumX += rows[0] * east + rows[3] * north
        sumY += rows[1] * east + rows[4] * north
        sumZ += rows[2] * east + rows[5] * north
        used++
    }

    if (used < config.alignmentMinSamples) return null
    val length = sqrt(sumX * sumX + sumY * sumY + sumZ * sumZ)
    if (length / used < config.alignmentMinCoherence) return null
    return doubleArrayOf(sumX / length, sumY / length, sumZ / length)
}

/**
 * Writes the vehicle's heading as a world-frame unit vector into [out], from the phone's current
 * orientation and the calibrated forward axis. False only if the axis somehow ends up pointing
 * straight up, which a real vehicle's forward direction never does. Fills a caller-owned array for
 * the same reason [fillRotationMatrix] does — it runs per acceleration sample.
 */
private fun headingInto(
    rows: DoubleArray,
    forward: DoubleArray,
    out: DoubleArray,
): Boolean {
    val east = rows[0] * forward[0] + rows[1] * forward[1] + rows[2] * forward[2]
    val north = rows[3] * forward[0] + rows[4] * forward[1] + rows[5] * forward[2]
    val length = hypot(east, north)
    if (length < 1e-6) return false
    out[0] = east / length
    out[1] = north / length
    return true
}

/** Smallest angle between two compass bearings, in degrees — handles the 359°→1° wrap. */
private fun bearingDeltaDeg(
    a: Float,
    b: Float,
): Double {
    val delta = abs(b - a).toDouble() % 360.0
    return if (delta > 180.0) 360.0 - delta else delta
}

/** Speed, unit heading vector and position at one instant, interpolated between two GPS fixes. */
private class TrackState(
    val speedMps: Double,
    val headEast: Double,
    val headNorth: Double,
    val lat: Double,
    val lon: Double,
)

/**
 * Interpolates the ~1 Hz track up to the ~50 Hz motion stream. Queried in increasing time order, so
 * it just walks an index forward rather than searching. Returns null outside the ride's GPS
 * coverage — motion recorded before the first or after the last fix has no direction of travel.
 */
private class TrackInterpolator(
    private val fixes: List<LocationSample>,
    private val maxAccuracyMeters: Double,
) {
    private var index = 0

    fun at(nanos: Long): TrackState? {
        if (fixes.size < 2 || nanos < fixes.first().t || nanos > fixes.last().t) return null
        while (index < fixes.size - 2 && fixes[index + 1].t <= nanos) index++
        val a = fixes[index]
        val b = fixes[index + 1]
        // A fix the receiver itself reports as poor is not worth interpolating between. Suppressing
        // this stretch costs real events; inventing them from a position that isn't real costs more.
        if (a.accuracy > maxAccuracyMeters || b.accuracy > maxAccuracyMeters) return null
        val span = (b.t - a.t).toDouble()
        val f = if (span > 0) ((nanos - a.t) / span).coerceIn(0.0, 1.0) else 0.0

        // Heading is interpolated as a unit vector, never as degrees: lerping angles is wrong
        // across the 359°→1° wrap and would invent a backwards heading every time a ride crosses it.
        val aRad = Math.toRadians(a.bearing.toDouble())
        val bRad = Math.toRadians(b.bearing.toDouble())
        var east = sin(aRad) + (sin(bRad) - sin(aRad)) * f
        var north = cos(aRad) + (cos(bRad) - cos(aRad)) * f
        val length = hypot(east, north)
        if (length < 1e-6) return null // opposing headings cancelled out; direction is undefined here
        east /= length
        north /= length

        return TrackState(
            speedMps = a.speed + (b.speed - a.speed) * f,
            headEast = east,
            headNorth = north,
            lat = a.lat + (b.lat - a.lat) * f,
            lon = a.lon + (b.lon - a.lon) * f,
        )
    }
}

/**
 * Rate of rise of a signal, in units per second, measured across a fixed time baseline.
 *
 * The baseline is the whole point. Differencing adjacent 50 Hz samples divides by 0.02 s, which
 * turns even 0.003 g of residual ripple into 0.15 g/s — the same range as the genuine jerk of smooth
 * driving, so the measurement would be mostly noise. Differencing across ~100 ms cuts that by five
 * while still resolving events that last several hundred ms.
 *
 * Only rises are reported; a falling signal returns zero, since easing off a brake or unwinding a
 * corner isn't harsh. Returns zero until the buffer spans at least half the baseline, so the first
 * samples of a ride can't divide a small change by a tiny dt and invent a spike.
 */
private class RateTracker(
    private val baselineNanos: Long,
) {
    private val times = LongArray(CAPACITY)
    private val values = DoubleArray(CAPACITY)
    private var head = 0 // index of the oldest retained entry
    private var size = 0

    fun update(
        nanos: Long,
        value: Double,
    ): Double {
        val tail = (head + size) % CAPACITY
        times[tail] = nanos
        values[tail] = value
        if (size < CAPACITY) size++ else head = (head + 1) % CAPACITY

        // Drop entries older than the baseline, keeping the one that straddles it as the reference.
        while (size > 2 && nanos - times[(head + 1) % CAPACITY] >= baselineNanos) {
            head = (head + 1) % CAPACITY
            size--
        }
        if (size < 2) return 0.0

        val dt = (nanos - times[head]) / 1e9
        if (dt < baselineNanos / 2e9) return 0.0
        return ((value - values[head]) / dt).coerceAtLeast(0.0)
    }

    private companion object {
        // ~1.3 s at 50 Hz — ample for a 100 ms baseline, which evicts long before this fills.
        const val CAPACITY = 64
    }
}

/**
 * Yields the sample nearest a query time from a time-ordered stream, or null when the nearest is
 * staler than [maxAgeNanos] — a sensor that dropped out mid-ride must not silently keep supplying
 * its last reading. Queried in increasing time order, so it walks forward instead of searching.
 */
private class NearestWalker(
    private val samples: List<MotionSample>,
    private val maxAgeNanos: Long,
) {
    private var index = 0

    fun at(nanos: Long): MotionSample? {
        if (samples.isEmpty()) return null
        while (index < samples.size - 1 && abs(samples[index + 1].t - nanos) <= abs(samples[index].t - nanos)) {
            index++
        }
        return samples[index].takeIf { abs(it.t - nanos) <= maxAgeNanos }
    }
}

/**
 * Turns a stream of (magnitude, rate) pairs into discrete events for one event type.
 *
 * An event opens when the force builds fast enough ([DriveEventConfig.enterJerkGPerS]) *or* gets
 * high enough on its own ([DriveEventConfig.highPeakG]). It stays open while either the rate is
 * still elevated or the force is still above [DriveEventConfig.minPeakG] — the second term is what
 * carries it through the steady middle of a maneuver, where jerk is near zero by definition. It only
 * truly ends once both have stayed low for [DriveEventConfig.mergeGapMs], the grace period that
 * keeps one sustained brake from being reported as a handful of separate ones.
 *
 * On close, an event is kept only if it lasted long enough and its peak force cleared
 * [DriveEventConfig.minPeakG] — the check that discards a sudden but trivial twitch.
 */
private class EventAccumulator(
    private val type: DriveEventType,
    private val config: DriveEventConfig,
    private val thresholds: DirectionThresholds,
    private val rideStartElapsedNanos: Long,
) {
    private val collected = mutableListOf<DriveEvent>()
    private var open = false
    private var startNanos = 0L
    private var endNanos = 0L // last moment the maneuver still sustained
    private var closingSince: Long? = null
    private var peak = 0.0
    private var peakJerk = 0.0
    private var sum = 0.0
    private var count = 0
    private var peakSpeed = 0.0
    private var peakLat: Double? = null
    private var peakLon: Double? = null

    fun feed(
        nanos: Long,
        magnitudeG: Double,
        jerkGPerS: Double,
        state: TrackState?,
    ) {
        val sustains = jerkGPerS >= thresholds.exitJerkGPerS || magnitudeG >= thresholds.minPeakG
        if (open) {
            if (sustains) {
                closingSince = null
                extend(nanos, magnitudeG, jerkGPerS, state)
            } else {
                val since = closingSince ?: nanos.also { closingSince = it }
                if (nanos - since > config.mergeGapMs * 1_000_000) flush()
            }
        } else if (jerkGPerS >= thresholds.enterJerkGPerS ||
            (thresholds.highPeakG != null && magnitudeG >= thresholds.highPeakG)
        ) {
            open = true
            startNanos = nanos
            closingSince = null
            peak = 0.0
            peakJerk = 0.0
            sum = 0.0
            count = 0
            extend(nanos, magnitudeG, jerkGPerS, state)
        }
    }

    private fun extend(
        nanos: Long,
        magnitudeG: Double,
        jerkGPerS: Double,
        state: TrackState?,
    ) {
        endNanos = nanos
        sum += magnitudeG
        count++
        if (jerkGPerS > peakJerk) peakJerk = jerkGPerS
        if (magnitudeG > peak) {
            peak = magnitudeG
            peakSpeed = state?.speedMps ?: 0.0
            peakLat = state?.lat
            peakLon = state?.lon
        }
    }

    private fun flush() {
        val durationMs = (endNanos - startNanos) / 1_000_000
        // The peak-force floor is applied here, on the finished event, not per sample: jerk peaks at
        // a maneuver's onset while the force is still near zero, so a per-sample gate would have
        // thrown away the very spike that opened it.
        if (durationMs >= config.minDurationMs && count > 0 && peak >= thresholds.minPeakG) {
            collected.add(
                DriveEvent(
                    type = type,
                    // Offset from the ride's start, so an event reads on its own; the sample stream's
                    // monotonic base comes from Ride.startedElapsedNanos.
                    startOffsetMs = (startNanos - rideStartElapsedNanos) / 1_000_000,
                    durationMs = durationMs,
                    peakG = peak,
                    peakJerkGPerS = peakJerk,
                    avgG = sum / count,
                    speedMps = peakSpeed,
                    lat = peakLat,
                    lon = peakLon,
                ),
            )
        }
        open = false
        closingSince = null
    }

    fun finish(): List<DriveEvent> {
        if (open) flush()
        return collected
    }
}
