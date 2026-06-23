package de.uhi.enia.ridesafe.tracking

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One recorded sample. Stored one-per-line as JSON in the per-ride file. [t] is a monotonic
 * timestamp ([android.os.SystemClock.elapsedRealtimeNanos] stamped at callback receipt) shared
 * by every sample type, so GPS and motion streams line up regardless of source clock; the
 * [de.uhi.enia.ridesafe.data.Ride] carries the epoch/elapsed base to map [t] to wall time.
 */
@Serializable
sealed interface RideSample {
    val t: Long
}

@Serializable
@SerialName("loc")
data class LocationSample(
    override val t: Long,
    val lat: Double,
    val lon: Double,
    val alt: Double,
    val speed: Float,
    val bearing: Float,
    val accuracy: Float,
) : RideSample

/** Which motion sensor produced a [MotionSample]. */
enum class MotionSensor { ACCEL, GYRO, ROTATION }

/** [w] is only set for [MotionSensor.ROTATION] (rotation-vector scalar component). */
@Serializable
@SerialName("mot")
data class MotionSample(
    override val t: Long,
    val sensor: MotionSensor,
    val x: Float,
    val y: Float,
    val z: Float,
    val w: Float? = null,
) : RideSample

data class RideSummary(
    val distanceMeters: Double,
    val avgSpeedMps: Double,
    val maxSpeedMps: Double,
)

private const val EARTH_RADIUS_M = 6_371_000.0

/** Great-circle distance between two WGS84 points, in meters. */
fun haversineMeters(
    lat1: Double,
    lon1: Double,
    lat2: Double,
    lon2: Double,
): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a =
        sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
    return EARTH_RADIUS_M * 2 * atan2(sqrt(a), sqrt(1 - a))
}

/**
 * Ride summary fed one GPS fix at a time: distance is the sum of consecutive great-circle hops,
 * avg speed is distance over the wall-clock span, max speed is the fastest reported fix. Motion
 * samples don't affect the summary. One implementation, used live while recording and in batch
 * via [summarize] when recovering a dangling ride from its file.
 *
 * ponytail: raw consecutive-hop sum; GPS noise inflates distance slightly. Accuracy-gating /
 * smoothing is analysis-layer work (TRK-06), not recording.
 */
class RideStats {
    private var first: LocationSample? = null
    private var last: LocationSample? = null

    var distanceMeters = 0.0
        private set
    var maxSpeedMps = 0.0
        private set

    fun add(loc: LocationSample) {
        last?.let { distanceMeters += haversineMeters(it.lat, it.lon, loc.lat, loc.lon) }
        if (first == null) first = loc
        last = loc
        if (loc.speed > maxSpeedMps) maxSpeedMps = loc.speed.toDouble()
    }

    fun summary(): RideSummary {
        val span = first?.let { f -> last?.let { l -> (l.t - f.t) / 1_000_000_000.0 } } ?: 0.0
        val avg = if (span > 0) distanceMeters / span else 0.0
        return RideSummary(distanceMeters, avg, maxSpeedMps)
    }
}

fun summarize(locations: List<LocationSample>): RideSummary =
    RideStats().apply { locations.forEach(::add) }.summary()
