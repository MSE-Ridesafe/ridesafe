package de.uhi.enia.ridesafe.rides.processing.event

import de.uhi.enia.ridesafe.data.RideDynamics
import de.uhi.enia.ridesafe.data.RideEvent
import de.uhi.enia.ridesafe.data.RideEventType
import de.uhi.enia.ridesafe.rides.processing.TrackFilter
import de.uhi.enia.ridesafe.rides.recording.LocationSample
import de.uhi.enia.ridesafe.rides.recording.MotionSample
import de.uhi.enia.ridesafe.rides.recording.MotionSensor
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The detector as a streaming stage: fed samples in time order, it holds O(1) state and never sees
 * the ride as a whole. The per-sample arithmetic is identical to the batch version; what changed is
 * where the supporting values come from.
 *
 * Orientation and gyro become "most recent" rather than "nearest", which at the recorded 50 Hz means
 * at most 20 ms of staleness instead of 10 — immaterial next to the ~0.02 g that a stale orientation
 * can leak, and it removes the need to look ahead.
 *
 * GPS is the one thing that genuinely needs a look ahead, because speed and position are interpolated
 * between the fixes bracketing a sample. So acceleration is held — as five primitives, not objects —
 * until the fix following it arrives, then processed. That backlog is one GPS interval, about 50
 * entries; [CAPACITY] bounds it for the case where GPS drops out entirely, and overflowing simply
 * processes the backlog with no fix state, which is what a coverage gap should do anyway.
 *
 * Single-use and single-threaded: one instance per analysis, driven by one coroutine. Concurrent
 * rides each get their own, which is what keeps parallel analysis safe.
 */
internal class StreamingDetector(
    private val forward: DoubleArray?,
    private val config: RideEventConfig,
    rideStartElapsedNanos: Long,
) {
    private val braking = EventAccumulator(RideEventType.BRAKING, config, config.braking, rideStartElapsedNanos)
    private val accelerating =
        EventAccumulator(RideEventType.ACCELERATION, config, config.acceleration, rideStartElapsedNanos)
    private val cornering = EventAccumulator(RideEventType.CORNERING, config, config.cornering, rideStartElapsedNanos)
    private val profile = DynamicsAccumulator()

    private val baselineNanos = config.jerkBaselineMs * 1_000_000
    private val brakingRate = RateTracker(baselineNanos)
    private val acceleratingRate = RateTracker(baselineNanos)
    private val corneringRate = RateTracker(baselineNanos)
    private val rc = 1.0 / (2 * Math.PI * config.lowPassHz)

    // Reused every sample rather than reallocated; this runs once per acceleration reading.
    private val matrix = DoubleArray(9)
    private val heading = DoubleArray(2)
    private val state = TrackState()

    private var latestRotation: MotionSample? = null
    private var latestGyro: MotionSample? = null
    private var previousFix: LocationSample? = null
    private var currentFix: LocationSample? = null

    private var smoothedLongitudinal = 0.0
    private var smoothedLateral = 0.0
    private var smoothedYawRate = 0.0
    private var previousNanos = 0L
    private var seeded = false

    private val pendingNanos = LongArray(CAPACITY)
    private val pendingEast = DoubleArray(CAPACITY)
    private val pendingNorth = DoubleArray(CAPACITY)
    private val pendingYaw = DoubleArray(CAPACITY)
    private val pendingGyroMagnitude = DoubleArray(CAPACITY)
    private var pendingCount = 0

    fun onFix(filtered: LocationSample) {
        previousFix = currentFix
        currentFix = filtered
        drain()
    }

    /**
     * Held acceleration is released only up to the newest fix, never past it. That matters because
     * [TrackFilter] withholds fixes until their run is corroborated and then releases them in a
     * burst — so a fix can arrive after motion that is newer than it. Draining everything on the
     * first of that burst would gate samples the later fixes are about to be able to place.
     */
    private fun drainLimit() = currentFix?.t ?: Long.MIN_VALUE

    fun onMotion(sample: MotionSample) {
        when (sample.sensor) {
            MotionSensor.ROTATION -> latestRotation = sample
            MotionSensor.GYRO -> latestGyro = sample
            MotionSensor.ACCEL -> hold(sample)
        }
    }

    fun finish(): List<RideEvent> {
        drain(force = true)
        return (braking.finish() + accelerating.finish() + cornering.finish()).sortedBy { it.startOffsetMs }
    }

    /**
     * The ride's dynamics profile (ANL-01). Read after [finish], which is what drains the last of the
     * held acceleration into it.
     */
    fun dynamics(): RideDynamics = profile.result()

    /** Resolve what only the orientation and gyro can give, and queue the rest for the next fix. */
    private fun hold(sample: MotionSample) {
        val rotation = latestRotation ?: return
        if (sample.t - rotation.t > config.maxSampleAgeNanos) return
        if (pendingCount == CAPACITY) drain(force = true) // GPS silent this long; treat it as a gap

        fillRotationMatrix(rotation, matrix)
        val ax = sample.x.toDouble()
        val ay = sample.y.toDouble()
        val az = sample.z.toDouble()
        val gyro = latestGyro
        val gyroFresh = gyro != null && sample.t - gyro.t <= config.maxSampleAgeNanos

        pendingNanos[pendingCount] = sample.t
        pendingEast[pendingCount] = matrix[0] * ax + matrix[1] * ay + matrix[2] * az
        pendingNorth[pendingCount] = matrix[3] * ax + matrix[4] * ay + matrix[5] * az
        pendingYaw[pendingCount] =
            if (!gyroFresh) 0.0 else verticalComponent(matrix, gyro!!.x.toDouble(), gyro.y.toDouble(), gyro.z.toDouble())
        pendingGyroMagnitude[pendingCount] =
            if (!gyroFresh) 0.0 else hypot(hypot(gyro!!.x, gyro.y), gyro.z).toDouble()
        pendingCount++
    }

    private fun drain(force: Boolean = false) {
        val limit = if (force) Long.MAX_VALUE else drainLimit()
        var kept = 0
        for (i in 0 until pendingCount) {
            if (pendingNanos[i] <= limit) {
                process(pendingNanos[i], pendingEast[i], pendingNorth[i], pendingYaw[i], pendingGyroMagnitude[i])
                continue
            }
            pendingNanos[kept] = pendingNanos[i]
            pendingEast[kept] = pendingEast[i]
            pendingNorth[kept] = pendingNorth[i]
            pendingYaw[kept] = pendingYaw[i]
            pendingGyroMagnitude[kept] = pendingGyroMagnitude[i]
            kept++
        }
        pendingCount = kept
    }

    /**
     * Fills [state] from the two fixes bracketing [nanos] and reports whether it is usable. A fix the
     * receiver itself calls poor is refused: suppressing the stretch costs real events, inventing
     * them from a position that isn't real costs more.
     */
    private fun trackStateAt(nanos: Long): Boolean {
        val a = previousFix ?: return false
        val b = currentFix ?: return false
        if (nanos < a.t || nanos > b.t) return false
        if (a.accuracy > config.maxFixAccuracyMeters || b.accuracy > config.maxFixAccuracyMeters) return false

        val span = (b.t - a.t).toDouble()
        val f = if (span > 0) ((nanos - a.t) / span).coerceIn(0.0, 1.0) else 0.0

        // Heading is interpolated as a unit vector, never as degrees: lerping angles is wrong across
        // the 359°→1° wrap and would invent a backwards heading every time a ride crosses it.
        val aRad = Math.toRadians(a.bearing.toDouble())
        val bRad = Math.toRadians(b.bearing.toDouble())
        var east = sin(aRad) + (sin(bRad) - sin(aRad)) * f
        var north = cos(aRad) + (cos(bRad) - cos(aRad)) * f
        val length = hypot(east, north)
        if (length < 1e-6) return false // opposing headings cancelled out; direction is undefined here
        east /= length
        north /= length

        state.speedMps = a.speed + (b.speed - a.speed) * f
        state.headEast = east
        state.headNorth = north
        state.lat = a.lat + (b.lat - a.lat) * f
        state.lon = a.lon + (b.lon - a.lon) * f
        return true
    }

    private fun process(
        nanos: Long,
        east: Double,
        north: Double,
        yawRate: Double,
        gyroMagnitude: Double,
    ) {
        val hasState = trackStateAt(nanos)

        // Heading comes from the phone's own orientation whenever the forward axis could be
        // calibrated: it updates at motion rate rather than 1 Hz, and it doesn't care what the GPS
        // is doing. The interpolated GPS heading is only the fallback for a ride that never gave
        // enough clean straight-line driving to calibrate from.
        val hasHeading =
            when {
                forward != null -> {
                    headingInto(matrix, forward, heading)
                }

                hasState -> {
                    heading[0] = state.headEast
                    heading[1] = state.headNorth
                    true
                }

                else -> {
                    false
                }
            }

        // Longitudinal is along the heading (negative = braking), lateral is perpendicular to it.
        val longitudinal = if (!hasHeading) 0.0 else east * heading[0] + north * heading[1]
        val lateral = if (!hasHeading) 0.0 else east * heading[1] - north * heading[0]

        // One-pole low-pass, dt from the real timestamps — sensor delivery is never truly uniform.
        // Filtering runs even on gated samples so the state stays warm and doesn't jump when the
        // gate lifts. The first sample seeds the filter outright instead of ramping up from zero.
        val dt = if (seeded) ((nanos - previousNanos) / 1e9).coerceIn(1e-4, 1.0) else 0.0
        val alpha = if (seeded) dt / (dt + rc) else 1.0
        previousNanos = nanos
        seeded = true
        profile.addElapsed(dt)
        smoothedLongitudinal += alpha * (longitudinal - smoothedLongitudinal)
        smoothedLateral += alpha * (lateral - smoothedLateral)
        smoothedYawRate += alpha * (yawRate - smoothedYawRate)

        val brakingG = (-smoothedLongitudinal / G).coerceAtLeast(0.0)
        val acceleratingG = (smoothedLongitudinal / G).coerceAtLeast(0.0)
        val corneringG = abs(smoothedLateral) / G

        // Rates track the real signal even while gated, so lifting a gate doesn't read as a step
        // change and fire a phantom event. Only the onset counts: easing off a brake is a negative
        // rate and isn't harsh, so the accumulators see rises only.
        val brakingJerk = brakingRate.update(nanos, brakingG)
        val acceleratingJerk = acceleratingRate.update(nanos, acceleratingG)
        val corneringJerk = corneringRate.update(nanos, corneringG)

        val handling = gyroMagnitude > config.maxGyroRadPerSec

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
        val speed = if (!hasState) 0.0 else minOf(state.speedMps, imuSpeed ?: Double.MAX_VALUE)

        if (!hasState || !hasHeading || speed < config.minSpeedMps || handling) {
            // Below the speed gate or the phone is being handled: feed zero so any open event
            // closes on its own timing rather than spanning the excluded stretch.
            braking.feed(nanos, 0.0, 0.0, null)
            accelerating.feed(nanos, 0.0, 0.0, null)
            cornering.feed(nanos, 0.0, 0.0, null)
            return
        }

        braking.feed(nanos, brakingG, brakingJerk, state)
        accelerating.feed(nanos, acceleratingG, acceleratingJerk, state)
        cornering.feed(nanos, corneringG, corneringJerk, state)
        // Only what survived the gates reaches the profile, so the score divides by time it could
        // actually judge rather than by however long the phone happened to be recording.
        profile.add(
            brakingG = brakingG,
            brakingJerk = brakingJerk,
            acceleratingG = acceleratingG,
            acceleratingJerk = acceleratingJerk,
            corneringG = corneringG,
            corneringJerk = corneringJerk,
            speedMps = state.speedMps,
            dtSeconds = dt,
        )
    }

    private companion object {
        // ~164 s of acceleration at 50 Hz — far past any normal gap between fixes, and 200 KB held.
        const val CAPACITY = 8192
    }
}
