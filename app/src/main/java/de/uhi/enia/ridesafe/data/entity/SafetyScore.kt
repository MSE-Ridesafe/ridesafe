package de.uhi.enia.ridesafe.data.entity

import kotlinx.serialization.Serializable

/**
 * A driver safety score (ANL-01), 0–100 per dimension plus a combined [total].
 *
 * One type for both a single ride and a window of them — a month's score is the same four numbers
 * over summed exposure, not a different quantity — so [de.uhi.enia.ridesafe.data.entity.Ride.score] and the
 * dashboard's all-time/monthly/weekly figures share it. Absent (null) means *unscoreable*, which is
 * deliberately not the same as zero: a ride the sensors could not measure has no score, never a bad
 * one, and never a perfect one.
 *
 * The raw penalties are stored alongside the scores because they, not the scores, are what
 * aggregates: risk adds across rides, scores do not. Summing [brakingPenalty] and
 * [qualifiedSeconds] over a month and mapping once gives the month's score; averaging thirty ride
 * scores gives a different and wrong number, because the 0–100 curve is nonlinear. Keeping them here
 * means a window aggregate never has to re-read a histogram.
 *
 * Each penalty is in *event-equivalent seconds*: one unit is one second spent at exactly the
 * threshold where that direction's driving becomes an event. See scoreRide for how the
 * histogram becomes this number.
 *
 * The 0–100 figures are stored rather than derived on read — unlike the eco level, which [RideEco]
 * leaves to read time. Storing them costs no more: the scoring constants are fixed by the build, and
 * changing them bumps the score stage's version, which re-derives every ride from stored histograms
 * with no file pass.
 */
@Serializable
data class SafetyScore(
    val total: Int,
    val braking: Int,
    val acceleration: Int,
    val cornering: Int,
    val brakingPenalty: Double,
    val accelerationPenalty: Double,
    val corneringPenalty: Double,
    val qualifiedSeconds: Double,
)
