package de.uhi.enia.ridesafe.analysis.event

import de.uhi.enia.ridesafe.data.file.MotionSample
import kotlin.math.abs
import kotlin.math.sqrt

/** Standard gravity, for reporting event magnitudes in g rather than m/s². */
internal const val G = 9.80665

/**
 * The device→world rotation matrix Android's `getRotationMatrixFromVector` builds, row-major.
 * Reimplemented rather than calling SensorManager so the detector stays pure Kotlin
 * and testable off-device.
 *
 * Detection reads only row 2, the world-vertical in device coordinates: projecting it out of the
 * accelerometer removes gravity exactly and slope-proofs the method, and the same row turns the
 * gyro into the vehicle's rate of turn. Row 2 is also the one row a magnetometer yaw error cannot
 * touch — yaw rotates about that very axis — which is why detection never uses the other six.
 * Calibration ([ForwardAxisEstimator]) is the sole consumer of rows 0–5, where a yaw error only
 * shifts the *mean* axis a little rather than misfiling force per sample.
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

/** Smallest angle between two compass bearings, in degrees — handles the 359°→1° wrap. */
internal fun bearingDeltaDeg(
    a: Float,
    b: Float,
): Double {
    val delta = abs(b - a).toDouble() % 360.0
    return if (delta > 180.0) 360.0 - delta else delta
}
