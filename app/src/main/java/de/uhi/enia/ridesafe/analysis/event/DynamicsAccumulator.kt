package de.uhi.enia.ridesafe.analysis.event

import de.uhi.enia.ridesafe.data.entity.DYNAMICS_BINS
import de.uhi.enia.ridesafe.data.entity.DYNAMICS_G_PER_BIN
import de.uhi.enia.ridesafe.data.entity.DYNAMICS_JERK_PER_BIN
import de.uhi.enia.ridesafe.data.entity.DirectionHistogram
import de.uhi.enia.ridesafe.data.entity.RideDynamics

/**
 * Time spent at each level of force and onset rate, accumulated as the detector runs.
 *
 * The counterpart to [EventAccumulator]: that one keeps the moments that crossed a threshold, this
 * one keeps *all* of them. Between them the ride is fully described — the tail as discrete events
 * with their real peaks, the body as a distribution — which is what lets scoring reward smooth
 * driving and mildly penalise a maneuver that nearly became an event.
 *
 * Only gated-in samples are offered here, so [qualifiedSeconds] is time the detector could actually
 * judge. [totalSeconds] is fed separately, for every sample, because the ratio of the two is the
 * only thing that distinguishes a clean ride from an unmeasurable one.
 *
 * Single-use and single-threaded, like everything else in the detector: one instance per analysis,
 * driven by one coroutine.
 */
internal class DynamicsAccumulator {
    private val braking = Direction()
    private val accelerating = Direction()
    private val cornering = Direction()

    private var qualifiedSeconds = 0.0
    private var qualifiedMeters = 0.0
    private var totalSeconds = 0.0

    /** Every sample's elapsed time, gated or not — the denominator of the coverage check. */
    fun addElapsed(dtSeconds: Double) {
        totalSeconds += dtSeconds
    }

    /** One gated-in sample: how hard, how fast it built, how long it stood for, and how fast we went. */
    fun add(
        brakingG: Double,
        brakingJerk: Double,
        acceleratingG: Double,
        acceleratingJerk: Double,
        corneringG: Double,
        corneringJerk: Double,
        speedMps: Double,
        dtSeconds: Double,
    ) {
        braking.add(brakingG, brakingJerk, dtSeconds)
        accelerating.add(acceleratingG, acceleratingJerk, dtSeconds)
        cornering.add(corneringG, corneringJerk, dtSeconds)
        qualifiedSeconds += dtSeconds
        qualifiedMeters += speedMps * dtSeconds
    }

    fun result() =
        RideDynamics(
            braking = braking.result(),
            acceleration = accelerating.result(),
            cornering = cornering.result(),
            qualifiedSeconds = qualifiedSeconds,
            qualifiedMeters = qualifiedMeters,
            totalSeconds = totalSeconds,
        )

    /**
     * One direction's pair of histograms. Doubles while accumulating and floats only on the way out:
     * a ride is hundreds of thousands of additions into the same handful of bins, and float's 24-bit
     * mantissa starts losing 0.02 s increments once a bin holds a few hundred seconds.
     */
    private class Direction {
        private val magnitude = DoubleArray(DYNAMICS_BINS)
        private val jerk = DoubleArray(DYNAMICS_BINS)

        fun add(
            magnitudeG: Double,
            jerkGPerS: Double,
            dtSeconds: Double,
        ) {
            magnitude[bin(magnitudeG, DYNAMICS_G_PER_BIN)] += dtSeconds
            jerk[bin(jerkGPerS, DYNAMICS_JERK_PER_BIN)] += dtSeconds
        }

        fun result() =
            DirectionHistogram(
                magnitudeSeconds = magnitude.map { it.toFloat() },
                jerkSeconds = jerk.map { it.toFloat() },
            )

        /** Clamped at both ends: negatives can't occur but shouldn't crash, and the top bin is open. */
        private fun bin(
            value: Double,
            width: Double,
        ) = (value / width).toInt().coerceIn(0, DYNAMICS_BINS - 1)
    }
}
