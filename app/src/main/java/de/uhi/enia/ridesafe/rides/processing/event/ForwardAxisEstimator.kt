package de.uhi.enia.ridesafe.rides.processing.event

import de.uhi.enia.ridesafe.rides.recording.LocationSample
import de.uhi.enia.ridesafe.rides.recording.MotionSample
import de.uhi.enia.ridesafe.rides.recording.MotionSensor
import de.uhi.enia.ridesafe.rides.recording.RideSample
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Estimates the vehicle's forward axis in *device* coordinates — the constant that frees heading
 * from GPS — from a stream of filtered fixes and orientation samples. O(1) state: three running
 * sums and a count.
 *
 * The phone doesn't move relative to the car, so the car's forward direction is a fixed vector in
 * the device frame. Recover that fixed vector once from GPS and the detector can split device-frame
 * acceleration into longitudinal and lateral without ever entering the world frame — which is the
 * point, since the world frame drags in the rotation vector's magnetometer-fused yaw.
 *
 * Only fast, straight, well-fixed stretches contribute, because that is the only place GPS heading
 * is worth believing. Each contributes `Rᵀ · heading`, and the average is the estimate. Individual
 * contributions wobble with the rotation vector's yaw error against true course; only the mean
 * matters downstream, and over hundreds of samples it settles to within a degree or two.
 *
 * [result] returns null rather than a bad answer in two cases: too few samples to be sure, or samples
 * that disagree with each other. Disagreement is measured by the summed vector's length — unit
 * vectors that agree sum to nearly the sample count, ones that scatter fall well short — which
 * catches the phone having been moved or re-mounted mid-ride.
 *
 * ponytail: one estimate for the whole ride. A phone re-seated halfway through fails the coherence
 * check and falls back to GPS heading, rather than being tracked through the change; make this a
 * sliding window if that turns out to be common.
 *
 * Single-use and single-threaded: one instance per analysis, driven by one coroutine. Concurrent
 * rides each get their own, which is what keeps parallel analysis safe.
 */
internal class ForwardAxisEstimator(
    private val config: RideEventConfig,
) {
    private val rows = DoubleArray(9)
    private var latestRotation: MotionSample? = null
    private var previousFix: LocationSample? = null
    private var sumX = 0.0
    private var sumY = 0.0
    private var sumZ = 0.0
    private var used = 0

    fun onMotion(sample: MotionSample) {
        if (sample.sensor == MotionSensor.ROTATION) latestRotation = sample
    }

    fun onFix(filtered: LocationSample) {
        val previous = previousFix
        previousFix = filtered
        if (previous == null) return
        if (filtered.speed < config.alignmentMinSpeedMps) return
        if (filtered.accuracy > config.maxFixAccuracyMeters) return
        if (bearingDeltaDeg(previous.bearing, filtered.bearing) > config.alignmentMaxTurnDeg) return
        val rotation = latestRotation ?: return
        if (filtered.t - rotation.t > config.maxSampleAgeNanos) return

        fillRotationMatrix(rotation, rows)
        // forward_device = Rᵀ · heading_world. With heading horizontal its up component is zero, so
        // only the two horizontal rows contribute, and Rᵀ reads them down the columns.
        val headingRad = Math.toRadians(filtered.bearing.toDouble())
        val east = sin(headingRad)
        val north = cos(headingRad)
        sumX += rows[0] * east + rows[3] * north
        sumY += rows[1] * east + rows[4] * north
        sumZ += rows[2] * east + rows[5] * north
        used++
    }

    fun result(): DoubleArray? {
        if (used < config.alignmentMinSamples) return null
        val length = sqrt(sumX * sumX + sumY * sumY + sumZ * sumZ)
        if (length / used < config.alignmentMinCoherence) return null
        return doubleArrayOf(sumX / length, sumY / length, sumZ / length)
    }
}

/** [ForwardAxisEstimator] driven over lists — the in-memory counterpart of the streaming pass. */
internal fun estimateForwardAxis(
    fixes: List<LocationSample>,
    rotations: List<MotionSample>,
    config: RideEventConfig,
): DoubleArray? {
    if (fixes.size < 2 || rotations.isEmpty()) return null
    val estimator = ForwardAxisEstimator(config)
    val merged = ArrayList<RideSample>(fixes.size + rotations.size)
    merged.addAll(fixes)
    merged.addAll(rotations)
    merged.sortWith { a, b -> a.t.compareTo(b.t) }
    for (sample in merged) {
        when (sample) {
            is LocationSample -> estimator.onFix(sample)
            is MotionSample -> estimator.onMotion(sample)
        }
    }
    return estimator.result()
}
