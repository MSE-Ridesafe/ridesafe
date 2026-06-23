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

private const val EARTH_RADIUS_M = 6_371_000.0

/**
 * Great-circle distance between two WGS84 points, in meters.
 * ponytail: kept as the primitive for the deferred dataset distance calculation (ANL-02).
 */
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
 * Record-time ride statistics, fed one GPS fix at a time. Captures the endpoints (start/end
 * position, DR-RID) and the fastest reported fix. Distance and average speed are deferred to the
 * analysis pass over the sample file (ANL-02), so they're left for that pass to fill, not here.
 */
class RideStats {
    var startFix: LocationSample? = null
        private set
    var endFix: LocationSample? = null
        private set
    var maxSpeedMps = 0.0
        private set

    fun add(loc: LocationSample) {
        if (startFix == null) startFix = loc
        endFix = loc
        if (loc.speed > maxSpeedMps) maxSpeedMps = loc.speed.toDouble()
    }
}

/** Build [RideStats] from a recorded location stream — used when recovering a ride from its file. */
fun rideStatsOf(locations: List<LocationSample>): RideStats = RideStats().apply { locations.forEach(::add) }
