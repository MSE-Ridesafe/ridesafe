package de.uhi.enia.ridesafe.data.file

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One recorded sample. Stored one-per-line as JSON in the per-ride file. [t] is a monotonic
 * timestamp ([android.os.SystemClock.elapsedRealtimeNanos] stamped at callback receipt) shared
 * by every sample type, so GPS and motion streams line up regardless of source clock; the
 * [de.uhi.enia.ridesafe.data.entity.Ride] carries the epoch/elapsed base to map [t] to wall time.
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
