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
 */
const val ANALYZER_VERSION = 2

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
 * Detection knobs (NFR-08). [enterG] is deliberately low: detection is meant to over-collect, with
 * [DriveEvent.peakG] carrying the magnitude so *how harsh counts as harsh* stays a read-time
 * decision. Raising the bar on what the UI calls harsh must never require re-analysis.
 *
 * 0.10 g is where insurance telematics tends to start flagging, and matches what a real in-car
 * g-meter reads as noticeably harsh — 0.3 g lateral is a genuinely hard corner, not an everyday one.
 * Lowering this much further starts logging ordinary traffic-light stops.
 *
 * [exitG] below [enterG] gives hysteresis, so a signal hovering at the threshold produces one event
 * instead of a burst. [mergeGapMs] then folds a brief dip back into the event it interrupted — the
 * wobble in the middle of one long brake is not two brakes. [minDurationMs] drops the leftovers, and
 * carries more weight at this threshold: it is the main thing separating a real event from a bump.
 *
 * [minSpeedMps] rejects parking maneuvers and the walk to the car (AutoTrackEngine starts recording
 * before you drive off); [maxGyroRadPerSec] rejects handling the phone, which swamps any real
 * cornering — a hard U-turn is well under 1 rad/s, picking a phone up is several.
 */
data class DriveEventConfig(
    val enterG: Double = 0.10,
    val exitG: Double = 0.075,
    val minDurationMs: Long = 250,
    val mergeGapMs: Long = 500,
    val minSpeedMps: Double = 4.0, // ~15 km/h
    val maxGyroRadPerSec: Double = 2.5,
    val lowPassHz: Double = 2.0, // vehicle dynamics live below ~2 Hz; above it is road and mount noise
    val maxSampleAgeNanos: Long = 1_000_000_000, // ignore an orientation/gyro reading staler than this
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
 * below walking pace. Returns empty when the ride lacks the accelerometer, rotation vector or GPS
 * the method needs — a missing sensor means no score, never a guessed one.
 */
fun detectDriveEvents(
    samples: RideSamples,
    rideStartElapsedNanos: Long,
    config: DriveEventConfig = DriveEventConfig(),
): List<DriveEvent> {
    if (samples.accel.isEmpty() || samples.rotation.isEmpty() || samples.locations.size < 2) return emptyList()

    val track = TrackInterpolator(kalmanFilterLocations(samples.locations))
    val rotations = NearestWalker(samples.rotation, config.maxSampleAgeNanos)
    val gyros = NearestWalker(samples.gyro, config.maxSampleAgeNanos)
    val braking = EventAccumulator(DriveEventType.BRAKING, config, rideStartElapsedNanos)
    val accelerating = EventAccumulator(DriveEventType.ACCELERATION, config, rideStartElapsedNanos)
    val cornering = EventAccumulator(DriveEventType.CORNERING, config, rideStartElapsedNanos)

    val rc = 1.0 / (2 * Math.PI * config.lowPassHz)
    var smoothedLongitudinal = 0.0
    var smoothedLateral = 0.0
    var previousNanos = 0L
    var seeded = false

    for (accel in samples.accel) {
        val rotation = rotations.at(accel.t) ?: continue
        val (east, north) = worldHorizontal(accel, rotation)
        val state = track.at(accel.t)

        // Longitudinal is along the heading (negative = braking), lateral is perpendicular to it.
        val longitudinal = if (state == null) 0.0 else east * state.headEast + north * state.headNorth
        val lateral = if (state == null) 0.0 else east * state.headNorth - north * state.headEast

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
        val handling = gyro != null && hypot(hypot(gyro.x, gyro.y), gyro.z) > config.maxGyroRadPerSec
        if (state == null || state.speedMps < config.minSpeedMps || handling) {
            // Below the speed gate or the phone is being handled: feed zero so any open event
            // closes on its own timing rather than spanning the excluded stretch.
            braking.feed(accel.t, 0.0, null)
            accelerating.feed(accel.t, 0.0, null)
            cornering.feed(accel.t, 0.0, null)
            continue
        }

        braking.feed(accel.t, (-smoothedLongitudinal / G).coerceAtLeast(0.0), state)
        accelerating.feed(accel.t, (smoothedLongitudinal / G).coerceAtLeast(0.0), state)
        cornering.feed(accel.t, abs(smoothedLateral) / G, state)
    }

    return (braking.finish() + accelerating.finish() + cornering.finish())
        .sortedBy { it.startOffsetMs }
}

/**
 * The horizontal (east, north) components of a device-frame acceleration reading, using the
 * rotation vector's quaternion. This is rows 0 and 1 of the device→world matrix Android's
 * `getRotationMatrixFromVector` builds; row 2 (up) is deliberately never computed, since that is
 * where gravity lives and dropping it is exactly how gravity is removed. Reimplemented rather than
 * calling SensorManager so the whole detector stays pure Kotlin and unit-testable off-device.
 */
private fun worldHorizontal(
    accel: MotionSample,
    rotation: MotionSample,
): Pair<Double, Double> {
    val x = rotation.x.toDouble()
    val y = rotation.y.toDouble()
    val z = rotation.z.toDouble()
    // Some devices omit the scalar component; recover it from the unit-quaternion constraint.
    val w = rotation.w?.toDouble() ?: sqrt((1.0 - x * x - y * y - z * z).coerceAtLeast(0.0))
    val ax = accel.x.toDouble()
    val ay = accel.y.toDouble()
    val az = accel.z.toDouble()
    val east = (1 - 2 * (y * y + z * z)) * ax + 2 * (x * y - z * w) * ay + 2 * (x * z + y * w) * az
    val north = 2 * (x * y + z * w) * ax + (1 - 2 * (x * x + z * z)) * ay + 2 * (y * z - x * w) * az
    return east to north
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
) {
    private var index = 0

    fun at(nanos: Long): TrackState? {
        if (fixes.size < 2 || nanos < fixes.first().t || nanos > fixes.last().t) return null
        while (index < fixes.size - 2 && fixes[index + 1].t <= nanos) index++
        val a = fixes[index]
        val b = fixes[index + 1]
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
 * Turns a stream of magnitudes into discrete events for one event type: enter above
 * [DriveEventConfig.enterG], stay in while above [DriveEventConfig.exitG], and only truly end once
 * the signal has stayed low for [DriveEventConfig.mergeGapMs] — that grace period is what keeps one
 * sustained brake from being reported as a handful of separate ones.
 */
private class EventAccumulator(
    private val type: DriveEventType,
    private val config: DriveEventConfig,
    private val rideStartElapsedNanos: Long,
) {
    private val collected = mutableListOf<DriveEvent>()
    private var open = false
    private var startNanos = 0L
    private var endNanos = 0L // last moment the signal was still above exitG
    private var closingSince: Long? = null
    private var peak = 0.0
    private var sum = 0.0
    private var count = 0
    private var peakSpeed = 0.0
    private var peakLat: Double? = null
    private var peakLon: Double? = null

    fun feed(
        nanos: Long,
        magnitudeG: Double,
        state: TrackState?,
    ) {
        if (open) {
            if (magnitudeG >= config.exitG) {
                closingSince = null
                extend(nanos, magnitudeG, state)
            } else {
                val since = closingSince ?: nanos.also { closingSince = it }
                if (nanos - since > config.mergeGapMs * 1_000_000) flush()
            }
        } else if (magnitudeG >= config.enterG) {
            open = true
            startNanos = nanos
            closingSince = null
            peak = 0.0
            sum = 0.0
            count = 0
            extend(nanos, magnitudeG, state)
        }
    }

    private fun extend(
        nanos: Long,
        magnitudeG: Double,
        state: TrackState?,
    ) {
        endNanos = nanos
        sum += magnitudeG
        count++
        if (magnitudeG > peak) {
            peak = magnitudeG
            peakSpeed = state?.speedMps ?: 0.0
            peakLat = state?.lat
            peakLon = state?.lon
        }
    }

    private fun flush() {
        val durationMs = (endNanos - startNanos) / 1_000_000
        if (durationMs >= config.minDurationMs && count > 0) {
            collected.add(
                DriveEvent(
                    type = type,
                    // Offset from the ride's start, so an event reads on its own; the sample stream's
                    // monotonic base comes from Ride.startedElapsedNanos.
                    startOffsetMs = (startNanos - rideStartElapsedNanos) / 1_000_000,
                    durationMs = durationMs,
                    peakG = peak,
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
