package de.uhi.enia.ridesafe.rides.processing.score

import de.uhi.enia.ridesafe.data.DYNAMICS_G_PER_BIN
import de.uhi.enia.ridesafe.data.DYNAMICS_JERK_PER_BIN
import de.uhi.enia.ridesafe.data.DirectionHistogram
import de.uhi.enia.ridesafe.data.RideDynamics
import de.uhi.enia.ridesafe.data.SafetyScore
import de.uhi.enia.ridesafe.rides.processing.event.DirectionThresholds
import de.uhi.enia.ridesafe.rides.processing.event.RideEventConfig
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Scoring knobs (ANL-01). Every constant that decides what a number means lives here, and none of
 * them is baked into a stored histogram — which is the point of storing histograms at all. Changing
 * anything in this class invalidates stored scores, so bump
 * [de.uhi.enia.ridesafe.rides.processing.ScoreStage.version] alongside it; that re-derives every
 * ride from its stored profile with no sample file touched, so re-tuning is seconds rather than a
 * full re-analysis.
 *
 * @property comfortFloor Fraction of a direction's own threshold below which driving is free, from 0
 * to 1. This single number is what replaces a separate "reward smooth driving" mechanism: everything
 * under it contributes exactly zero, so a smooth ride's penalty really is nought and there is
 * nothing to add up. Raise it and only near-events count, losing the gentle
 * gradient that distinguishes a careful driver from an average one; lower it and ordinary driving
 * accrues penalty, which flattens everyone toward the same mediocre score.
 *
 * @property exponent How sharply penalty grows above [comfortFloor]. This is what makes one real
 * event outweigh minutes of near-misses without either being ignored: at 3.0, a maneuver at
 * three-quarters of the threshold costs about an eighth of one at the threshold, and one at twice
 * the threshold costs twenty-seven times it. Raise it and the score becomes almost purely about
 * genuine events; lower it and sustained mediocre driving starts to rival them.
 *
 * @property jerkWeight How much of a direction's penalty comes from how fast force built rather than
 * how much of it there was, from 0 to 1; the remainder is the magnitude channel. Weighted toward
 * jerk for the same reason the detector triggers on it — harshness is abruptness. Raise it toward 1
 * and a smoothly-applied but brutal stop stops counting; lower it and every firm-but-gentle maneuver
 * is penalised like a stab at the pedal.
 *
 * @property brakingWeight, @property accelerationWeight, @property corneringWeight How the three
 * directions combine into one risk figure. They must sum to 1, since the credibility shrink below
 * relies on it to stay consistent between the total and its parts. Braking leads because it is the
 * event type telematics research most consistently ties to claims; cornering trails because lateral
 * force is largely geometry. Re-weight to change what the app tells drivers to work on.
 *
 * @property referenceRate The roughness rate at which the score falls to 1/e of 100, i.e. ~37. The
 * single dial that positions the whole population of scores: raise it and everyone scores higher,
 * lower it and everyone scores lower. Provisional until calibrated against real rides — see the log
 * line in [de.uhi.enia.ridesafe.rides.processing.ScoreStage].
 *
 * @property priorRate The rate a ride is assumed to have before its own driving says otherwise —
 * roughly what a typical ride scores. Only matters for short rides, where it is most of the answer.
 *
 * @property priorSeconds How much driving it takes before a ride is judged mostly on itself. Without
 * this, one hard brake three minutes into a drive reads as a catastrophe, because the rate divides
 * by almost nothing; with it, a 3-minute ride sits near [priorRate] and a 30-minute one stands on
 * its own. Raise it and even long rides are pulled toward the middle, compressing the range; lower
 * it and short rides swing wildly between 0 and 100.
 *
 * @property minQualifiedSeconds Least measurable driving a score may be based on. Below it there is
 * no score at all rather than a shaky one — the same rule the detector already follows for a missing
 * sensor.
 *
 * @property minCoverage Least share of a ride that must have been measurable, from 0 to 1. This is
 * what stops a ride recorded without a rotation vector, or spent entirely below the speed gate, from
 * reading as flawless driving: it produces no events and an empty histogram, which is indistinguishable
 * from perfection unless coverage is checked. Raise it and rides through tunnels and car parks lose
 * their scores; lower it and a score can rest on a couple of measured minutes out of an hour.
 */
data class ScoreWeights(
    val comfortFloor: Double = 0.5,
    val exponent: Double = 3.0,
    val jerkWeight: Double = 0.7,
    val brakingWeight: Double = 0.5,
    val accelerationWeight: Double = 0.25,
    val corneringWeight: Double = 0.25,
    val referenceRate: Double = 0.02,
    val priorRate: Double = 0.003,
    val priorSeconds: Double = 600.0,
    val minQualifiedSeconds: Double = 120.0,
    val minCoverage: Double = 0.25,
) {
    init {
        // Not defensive programming so much as a tuning guard: these are edited by hand while
        // calibrating, and a set that doesn't sum to 1 quietly breaks the total's relationship to
        // its parts rather than failing.
        val sum = brakingWeight + accelerationWeight + corneringWeight
        require(sum in 0.999..1.001) { "direction weights must sum to 1, got $sum" }
    }
}

/**
 * Score one ride from its dynamics profile (ANL-01), or null when there was too little measurable
 * driving to say anything.
 *
 * The whole method is one idea: instead of counting events, measure how much of the ride was spent
 * how far past comfortable, and divide by how long the ride was. Because the penalty grows as a
 * cube, a genuine event dominates the sum on its own while a near-miss still registers faintly and
 * smooth driving registers not at all — so penalising events, penalising near-misses and rewarding
 * smoothness are not three mechanisms but one, and the result stays monotone: nothing a driver does
 * can raise the score except driving more gently.
 *
 * [config] supplies the same thresholds the detector triggered on, so the score and the events shown
 * on the map are two readings of one yardstick rather than two opinions.
 */
fun scoreRide(
    dynamics: RideDynamics,
    config: RideEventConfig = RideEventConfig(),
    weights: ScoreWeights = ScoreWeights(),
): SafetyScore? {
    if (dynamics.qualifiedSeconds < weights.minQualifiedSeconds) return null
    if (dynamics.coverage < weights.minCoverage) return null
    return safetyScore(
        brakingPenalty = directionPenalty(dynamics.braking, config.braking, weights),
        accelerationPenalty = directionPenalty(dynamics.acceleration, config.acceleration, weights),
        corneringPenalty = directionPenalty(dynamics.cornering, config.cornering, weights),
        qualifiedSeconds = dynamics.qualifiedSeconds,
        weights = weights,
    )
}

/**
 * One window's score — a week, a month, all time — from the rides in it.
 *
 * Penalties and exposure are summed and mapped **once**, never averaged as scores. Risk adds across
 * rides; scores do not, because the 0–100 curve is nonlinear, so the mean of thirty ride scores is
 * a different and meaningless number. Summing also weights each ride by its own driving for free,
 * which is what stops a two-minute errand from counting as much as an hour on the motorway.
 *
 * Rides with no score contribute nothing, exactly as a ride with no distance contributes nothing to
 * mileage. Null when the window holds none at all.
 */
fun aggregateScore(
    scores: List<SafetyScore>,
    weights: ScoreWeights = ScoreWeights(),
): SafetyScore? {
    if (scores.isEmpty()) return null
    return safetyScore(
        brakingPenalty = scores.sumOf { it.brakingPenalty },
        accelerationPenalty = scores.sumOf { it.accelerationPenalty },
        corneringPenalty = scores.sumOf { it.corneringPenalty },
        qualifiedSeconds = scores.sumOf { it.qualifiedSeconds },
        weights = weights,
    )
}

/**
 * Penalty for one direction, in event-equivalent seconds: every bin's dwell time multiplied by how
 * bad that bin is, summed.
 *
 * Magnitude is measured against [DirectionThresholds.highPeakG] — the force that is harsh however
 * gently it arrived — rather than against [DirectionThresholds.minPeakG], which is only the floor an
 * event's peak must clear and which an entirely routine stop reaches. Directions with no such level
 * are scored on rate alone, cornering being the case that matters: lateral force is v²/r, so a tight
 * turn at walking pace produces real g with nothing harsh happening, and the detector already
 * refuses to judge cornering on force for exactly this reason.
 */
private fun directionPenalty(
    histogram: DirectionHistogram,
    thresholds: DirectionThresholds,
    weights: ScoreWeights,
): Double {
    val jerk = channelPenalty(histogram.jerkSeconds, DYNAMICS_JERK_PER_BIN, thresholds.enterJerkGPerS, weights)
    val magnitudeReference = thresholds.highPeakG ?: return jerk
    val magnitude = channelPenalty(histogram.magnitudeSeconds, DYNAMICS_G_PER_BIN, magnitudeReference, weights)
    return weights.jerkWeight * jerk + (1 - weights.jerkWeight) * magnitude
}

/** One histogram's contribution: dwell time weighted by how far each bin sits past comfortable. */
private fun channelPenalty(
    seconds: List<Float>,
    binWidth: Double,
    reference: Double,
    weights: ScoreWeights,
): Double {
    var total = 0.0
    for (i in seconds.indices) {
        val dwell = seconds[i]
        if (dwell <= 0f) continue
        // The bin's centre, not its edge: a bin is a range, and its lower edge would understate
        // every one of them by half a bin.
        total += dwell * density((i + 0.5) * binWidth / reference, weights)
    }
    return total
}

/**
 * How bad one instant is, where 1.0 is exactly at the threshold that makes driving an event.
 * Zero below [ScoreWeights.comfortFloor] — not merely small, but nothing at all, which is what makes
 * a genuinely smooth ride score 100 rather than 97.
 */
private fun density(
    fractionOfThreshold: Double,
    weights: ScoreWeights,
): Double {
    val excess = fractionOfThreshold - weights.comfortFloor
    if (excess <= 0) return 0.0
    return (excess / (1 - weights.comfortFloor)).pow(weights.exponent)
}

/**
 * Turn penalties and exposure into the four 0–100 figures.
 *
 * The total is scored from the weighted penalty rather than from the three scores, because the curve
 * is nonlinear and combining after mapping gives a different answer. Since the direction weights sum
 * to 1, shrinking the combined penalty once is identical to shrinking each direction's and then
 * combining, so the total stays consistent with its parts.
 *
 * ponytail: all four shrink toward one [ScoreWeights.priorRate], though braking and cornering
 * plainly have different typical rates. It costs a short ride's per-direction scores a little
 * accuracy and never affects the total; give each direction its own prior if the sub-scores start
 * looking wrong on brief rides.
 */
private fun safetyScore(
    brakingPenalty: Double,
    accelerationPenalty: Double,
    corneringPenalty: Double,
    qualifiedSeconds: Double,
    weights: ScoreWeights,
): SafetyScore {
    val combined =
        weights.brakingWeight * brakingPenalty +
            weights.accelerationWeight * accelerationPenalty +
            weights.corneringWeight * corneringPenalty
    return SafetyScore(
        total = curve(combined, qualifiedSeconds, weights),
        braking = curve(brakingPenalty, qualifiedSeconds, weights),
        acceleration = curve(accelerationPenalty, qualifiedSeconds, weights),
        cornering = curve(corneringPenalty, qualifiedSeconds, weights),
        brakingPenalty = brakingPenalty,
        accelerationPenalty = accelerationPenalty,
        corneringPenalty = corneringPenalty,
        qualifiedSeconds = qualifiedSeconds,
    )
}

/**
 * Penalty and exposure to a 0–100 score, via a credibility-weighted rate.
 *
 * The rate is `(penalty + priorRate·priorSeconds) / (seconds + priorSeconds)` — the actuarial
 * credibility form, which is just the observed rate with a fixed amount of assumed-average driving
 * mixed in. A long ride swamps the assumption and is judged on itself; a short one barely moves it.
 *
 * The curve is exponential decay so the score is bounded without ever being clipped, and the worse
 * driving gets the less the difference matters — the gap between 95 and 85 is worth noticing, the
 * gap between 15 and 5 is not.
 *
 * Note that 100 is approached rather than reached: a flawless ride still carries the assumed
 * average, so twenty clean minutes score in the mid-90s and it takes hours of them to sit at 99.
 * That is credibility working as intended — a perfect score should have to be earned over more than
 * one drive — and it is why the top of the range is not crowded.
 */
private fun curve(
    penalty: Double,
    seconds: Double,
    weights: ScoreWeights,
): Int {
    val rate = (penalty + weights.priorRate * weights.priorSeconds) / (seconds + weights.priorSeconds)
    return (100 * exp(-rate / weights.referenceRate)).roundToInt().coerceIn(0, 100)
}
