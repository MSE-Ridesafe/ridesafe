package de.uhi.enia.ridesafe.domain

import de.uhi.enia.ridesafe.data.Ride
import de.uhi.enia.ridesafe.data.SafetyScore
import de.uhi.enia.ridesafe.rides.processing.score.ScoreWeights
import de.uhi.enia.ridesafe.rides.processing.score.aggregateScore
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * A window's safety score (ANL-01, DSH-06) from the rides that started inside it.
 *
 * Unlike mileage, this does **not** go through [logicalRideJourneys]: a merged ride's stops are
 * ordinary ride rows, and summing their penalties and their driving time is already the right
 * answer, so folding them into one journey first would only add a step that changes nothing. Rides
 * with no score contribute nothing, exactly as a ride with no distance contributes nothing to
 * mileage.
 *
 * Null means the window holds no scoreable driving at all — not a bad score, and not zero.
 */
fun safetyScoreForRides(
    rides: List<Ride>,
    weights: ScoreWeights = ScoreWeights(),
): SafetyScore? = aggregateScore(rides.mapNotNull { it.score }, weights)

/**
 * The headline figure. It moves slowly once there is a year of driving behind it, which is the
 * point: it is meant to describe a driver rather than a drive. Recent progress is what the rolling
 * week and the current month are for.
 */
fun allTimeSafetyScore(
    rides: List<Ride>,
    weights: ScoreWeights = ScoreWeights(),
): SafetyScore? = safetyScoreForRides(rides, weights)

/** One calendar month, matching how [journeyTotalsForMonth] slices mileage. */
fun safetyScoreForMonth(
    rides: List<Ride>,
    month: YearMonth,
    zone: ZoneId,
    weights: ScoreWeights = ScoreWeights(),
): SafetyScore? =
    safetyScoreForRides(
        rides.filter { YearMonth.from(it.startedAtEpochMs.toLocalDate(zone)) == month },
        weights,
    )

/**
 * The last seven days including [endDay] — a rolling window, not an ISO week, to match
 * buildRollingWeekActivity. A weekly score that reset every Monday would say something
 * different from the weekly chart sitting next to it.
 */
fun safetyScoreForRollingWeek(
    rides: List<Ride>,
    endDay: LocalDate,
    zone: ZoneId,
    weights: ScoreWeights = ScoreWeights(),
): SafetyScore? {
    val from = endDay.minusDays(6)
    return safetyScoreForRides(
        rides.filter { it.startedAtEpochMs.toLocalDate(zone) in from..endDay },
        weights,
    )
}

private fun Long.toLocalDate(zone: ZoneId): LocalDate = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()
