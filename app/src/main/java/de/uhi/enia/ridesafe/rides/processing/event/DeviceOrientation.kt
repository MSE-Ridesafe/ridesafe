package de.uhi.enia.ridesafe.rides.processing.event

import de.uhi.enia.ridesafe.rides.recording.MotionSample
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/** Standard gravity, for reporting event magnitudes in g rather than m/s². */
internal const val G = 9.80665

/**
 * The device→world rotation matrix Android's `getRotationMatrixFromVector` builds, row-major.
 * Reimplemented rather than calling SensorManager so the detector stays pure Kotlin
 * and testable off-device.
 *
 * Acceleration uses only the first two rows: row 2 is the vertical, which is exactly where gravity
 * lives, and dropping it is both how gravity is removed and what makes the method slope-proof with
 * no road-plane estimation. Yaw rate is the opposite case — row 2 is the whole point there, since
 * the vertical component of the gyro *is* the vehicle's rate of turn.
 */
internal fun fillRotationMatrix(
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
internal fun verticalComponent(
    rows: DoubleArray,
    x: Double,
    y: Double,
    z: Double,
): Double = rows[6] * x + rows[7] * y + rows[8] * z

/**
 * Writes the vehicle's heading as a world-frame unit vector into [out], from the phone's current
 * orientation and the calibrated forward axis. False only if the axis somehow ends up pointing
 * straight up, which a real vehicle's forward direction never does. Fills a caller-owned array for
 * the same reason [fillRotationMatrix] does — it runs per acceleration sample.
 */
internal fun headingInto(
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
internal fun bearingDeltaDeg(
    a: Float,
    b: Float,
): Double {
    val delta = abs(b - a).toDouble() % 360.0
    return if (delta > 180.0) 360.0 - delta else delta
}
