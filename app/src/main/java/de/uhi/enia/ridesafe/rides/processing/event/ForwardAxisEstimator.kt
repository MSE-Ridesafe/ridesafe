package de.uhi.enia.ridesafe.rides.processing.event

import de.uhi.enia.ridesafe.rides.recording.LocationSample
import de.uhi.enia.ridesafe.rides.recording.MotionSample
import de.uhi.enia.ridesafe.rides.recording.MotionSensor
import de.uhi.enia.ridesafe.rides.recording.RideSample
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The vehicle's forward axis in device coordinates, per stretch of the ride it held for.
 *
 * One axis per *mounting epoch*: the phone doesn't move relative to the car — until somebody picks
 * it up and puts it back differently, which real logbooks show mid-ride. Each epoch's axis is only
 * valid over its own span, and the span between two epochs — where the phone was demonstrably in
 * motion but nothing says exactly when — belongs to neither, so [axisAt] answers null there and
 * detection treats it like any other unmeasurable stretch.
 *
 * Queried in sample order, like everything else in the detector; the cursor only moves forward.
 */
internal class AxisTimeline(
    private val segments: List<Segment>,
) {
    internal class Segment(
        val fromNanos: Long,
        val untilNanos: Long,
        val axis: DoubleArray,
    )

    private var cursor = 0

    fun axisAt(nanos: Long): DoubleArray? {
        while (cursor < segments.size && nanos > segments[cursor].untilNanos) cursor++
        val segment = segments.getOrNull(cursor) ?: return null
        return if (nanos >= segment.fromNanos) segment.axis else null
    }
}

/**
 * Estimates the vehicle's forward axis in *device* coordinates from a stream of filtered fixes and
 * orientation samples — one axis per mounting epoch, segmented where the phone was re-seated.
 *
 * The phone doesn't move relative to the car, so the car's forward direction is a fixed vector in
 * the device frame. Recover that fixed vector from GPS and the detector can split device-frame
 * acceleration into longitudinal and lateral without ever entering the world frame — which is the
 * point, since the world frame drags in the rotation vector's magnetometer-fused yaw.
 *
 * Only fast, straight, well-fixed stretches contribute, because that is the only place GPS heading
 * is worth believing. Each contributes `Rᵀ · heading`, and a running average is the estimate.
 * Individual contributions wobble with the rotation vector's yaw error against true course; only
 * the mean matters downstream, and over hundreds of samples it settles to within a degree or two.
 *
 * A re-seated phone shows up as contributions that abruptly and *consistently* disagree with the
 * running mean: [OUTLIER_RUN] in a row beyond [OUTLIER_COS] close the current epoch — its validity
 * ending at its last agreeing contribution — and seed the next from the disagreeing run. A stray
 * disagreement (a bearing glitch, a magnetometer snap) never comes five-in-a-row, and costs at
 * most those few discarded samples. Each epoch is then accepted on its own count and coherence,
 * exactly as the whole ride used to be; an epoch that fails stays out of the timeline and its span
 * reads as axis-less. [result] returns null only when no epoch at all was acceptable.
 *
 * Single-use and single-threaded: one instance per analysis, driven by one coroutine. Concurrent
 * rides each get their own, which is what keeps parallel analysis safe.
 */
internal class ForwardAxisEstimator(
    private val config: RideEventConfig,
) {
    private class Epoch(
        val firstNanos: Long,
    ) {
        var sumX = 0.0
        var sumY = 0.0
        var sumZ = 0.0
        var used = 0
        var lastNanos = firstNanos

        fun add(
            nanos: Long,
            x: Double,
            y: Double,
            z: Double,
        ) {
            sumX += x
            sumY += y
            sumZ += z
            used++
            lastNanos = nanos
        }

        /** Whether a unit contribution agrees with the mean so far, within [OUTLIER_COS]. */
        fun agrees(
            x: Double,
            y: Double,
            z: Double,
        ): Boolean {
            val length = sqrt(sumX * sumX + sumY * sumY + sumZ * sumZ)
            if (length < 1e-9) return true
            return (sumX * x + sumY * y + sumZ * z) / length >= OUTLIER_COS
        }
    }

    private val rows = DoubleArray(9)
    private var latestRotation: MotionSample? = null
    private var previousFix: LocationSample? = null

    private val closed = mutableListOf<Epoch>()
    private var current: Epoch? = null

    // A run of contributions disagreeing with the current epoch, held until it either proves a
    // re-seat (OUTLIER_RUN of them) or is dismissed by the next agreeing contribution.
    private val outlierNanos = LongArray(OUTLIER_RUN)
    private val outlierX = DoubleArray(OUTLIER_RUN)
    private val outlierY = DoubleArray(OUTLIER_RUN)
    private val outlierZ = DoubleArray(OUTLIER_RUN)
    private var outliers = 0

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
        var x = rows[0] * east + rows[3] * north
        var y = rows[1] * east + rows[4] * north
        var z = rows[2] * east + rows[5] * north
        val length = sqrt(x * x + y * y + z * z)
        if (length < 1e-9) return
        x /= length
        y /= length
        z /= length

        val epoch = current ?: Epoch(filtered.t).also { current = it }
        if (epoch.used < ESTABLISHED_SAMPLES || epoch.agrees(x, y, z)) {
            outliers = 0
            epoch.add(filtered.t, x, y, z)
            return
        }
        outlierNanos[outliers] = filtered.t
        outlierX[outliers] = x
        outlierY[outliers] = y
        outlierZ[outliers] = z
        if (++outliers < OUTLIER_RUN) return

        // Five in a row disagreeing: the phone was re-seated. The old epoch ends at its own last
        // agreeing contribution; everything from the first disagreement on belongs to the new one.
        closed += epoch
        val next = Epoch(outlierNanos[0])
        for (i in 0 until OUTLIER_RUN) next.add(outlierNanos[i], outlierX[i], outlierY[i], outlierZ[i])
        current = next
        outliers = 0
    }

    fun result(): AxisTimeline? {
        val epochs = closed + listOfNotNull(current)
        val segments = ArrayList<AxisTimeline.Segment>(epochs.size)
        for ((index, epoch) in epochs.withIndex()) {
            if (epoch.used < config.alignmentMinSamples) continue
            val length = sqrt(epoch.sumX * epoch.sumX + epoch.sumY * epoch.sumY + epoch.sumZ * epoch.sumZ)
            if (length / epoch.used < config.alignmentMinCoherence) continue
            segments.add(
                AxisTimeline.Segment(
                    // The first epoch reaches back to the ride's start: the phone was where it was
                    // before the car first drove fast enough to prove it. Later epochs begin only
                    // where their own evidence does — the re-seat happened somewhere in the gap.
                    fromNanos = if (index == 0) Long.MIN_VALUE else epoch.firstNanos,
                    untilNanos = if (index == epochs.lastIndex) Long.MAX_VALUE else epoch.lastNanos,
                    axis = doubleArrayOf(epoch.sumX / length, epoch.sumY / length, epoch.sumZ / length),
                ),
            )
        }
        return if (segments.isEmpty()) null else AxisTimeline(segments)
    }

    private companion object {
        // Contributions before a mean is worth disagreeing with; below this everything accumulates.
        const val ESTABLISHED_SAMPLES = 10

        // cos 45°: beyond this a contribution is not magnetometer wobble around the mean (measured
        // at ±25–35° on real rides) but a different axis altogether.
        const val OUTLIER_COS = 0.7071

        // How many consecutive outliers prove a re-seat rather than a glitch. At the ~1 Hz the
        // qualifying fixes arrive, five means a re-seat is confirmed within seconds.
        const val OUTLIER_RUN = 5
    }
}

/** [ForwardAxisEstimator] driven over lists — the in-memory counterpart of the streaming pass. */
internal fun estimateForwardAxis(
    fixes: List<LocationSample>,
    rotations: List<MotionSample>,
    config: RideEventConfig,
): AxisTimeline? {
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
