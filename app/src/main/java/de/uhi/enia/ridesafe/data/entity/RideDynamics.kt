package de.uhi.enia.ridesafe.data.entity

import kotlinx.serialization.Serializable

/** Bins per histogram, and the top of each range. See [DirectionHistogram] for why these numbers. */
const val DYNAMICS_BINS = 40
const val DYNAMICS_MAX_G = 1.0
const val DYNAMICS_MAX_JERK_G_PER_S = 4.0

/** Width of one bin, in the histogram's own units. */
const val DYNAMICS_G_PER_BIN = DYNAMICS_MAX_G / DYNAMICS_BINS
const val DYNAMICS_JERK_PER_BIN = DYNAMICS_MAX_JERK_G_PER_S / DYNAMICS_BINS

/**
 * How long one direction of travel spent at each level of force and of onset rate, in seconds.
 *
 * This is the whole body of the distribution, not just its tail. Events record the moments that
 * crossed a threshold; this records *everything*, which is what makes it possible to reward smooth
 * driving and mildly penalise a maneuver that came close to being an event without ever getting
 * there. Neither is recoverable from `ride_events`, because the detector discards every sample that
 * does not open one.
 *
 * Bins are absolute — g and g/s — rather than fractions of [de.uhi.enia.ridesafe.analysis.event.DirectionThresholds].
 * Normalising at write time would tie a stored histogram to the detector build that produced it, so
 * re-tuning a threshold would invalidate every ride's profile; in absolute units the same histogram
 * is still true and only the scoring reads it differently. That normalisation happens on read, in
 * scoreRide.
 *
 * [DYNAMICS_MAX_G] is 1.0 because a road car on dry asphalt tops out around there, so the last bin
 * is effectively unreachable rather than a crowd. [DYNAMICS_MAX_JERK_G_PER_S] is 4.0, roughly four
 * times the harshest entry threshold, which leaves headroom for genuine stabs at the pedal.
 *
 * ponytail: both top bins are clamped, so a truly violent outlier reads as merely severe. It is
 * already the heaviest thing in the sum by a wide margin, and the events table keeps its real peak
 * either way — widen the range only if real rides start piling into the last bin.
 */
@Serializable
data class DirectionHistogram(
    val magnitudeSeconds: List<Float>,
    val jerkSeconds: List<Float>,
) {
    operator fun plus(other: DirectionHistogram) =
        DirectionHistogram(
            magnitudeSeconds.zip(other.magnitudeSeconds) { a, b -> a + b },
            jerkSeconds.zip(other.jerkSeconds) { a, b -> a + b },
        )

    companion object {
        val EMPTY = DirectionHistogram(List(DYNAMICS_BINS) { 0f }, List(DYNAMICS_BINS) { 0f })
    }
}

/**
 * A ride's driving-dynamics profile (ANL-01): how its time was distributed across force and onset
 * rate, per direction of travel, plus how much of the ride was measurable at all.
 *
 * Stored as one JSON column on the ride, the same way [RideEco] is: a small owned value, read and
 * written whole and never queried across, so a change to the shape costs no migration. It is derived
 * and regenerable — the raw NDJSON sample file stays the source of truth.
 *
 * [qualifiedSeconds] is time that actually passed the detector's gates (moving fast enough, GPS
 * trustworthy, phone not being handled). It is the denominator every score divides by, which is what
 * makes a score a *rate* rather than a count and stops a long ride from accumulating a worse number
 * than a short one for the same driving.
 *
 * [qualifiedMeters] is the distance covered over that same time. Nothing reads it yet — insurers
 * normalise per distance rather than per hour, and storing it now means switching costs a scoring
 * re-derive instead of re-reading every sample file.
 *
 * [totalSeconds] counts every sample, gated or not. Its ratio to [qualifiedSeconds] is the coverage
 * check that keeps "unmeasurable" distinguishable from "flawless": a ride recorded without a
 * rotation vector produces no events and an empty histogram, which a naive score would read as
 * perfect driving.
 */
@Serializable
data class RideDynamics(
    val braking: DirectionHistogram,
    val acceleration: DirectionHistogram,
    val cornering: DirectionHistogram,
    val qualifiedSeconds: Double,
    val qualifiedMeters: Double,
    val totalSeconds: Double,
) {
    /** Share of the ride the detector could actually measure; 0 when it saw nothing at all. */
    val coverage: Double get() = totalSeconds.takeIf { it > 0 }?.let { qualifiedSeconds / it } ?: 0.0
}
