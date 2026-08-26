package de.uhi.enia.ridesafe.domain

import de.uhi.enia.ridesafe.data.Ride
import de.uhi.enia.ridesafe.data.SafetyScore
import de.uhi.enia.ridesafe.rides.processing.score.ScoreWeights
import de.uhi.enia.ridesafe.rides.processing.score.aggregateScore
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

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

/**
 * Each ISO week's combined score, keyed by the week's Monday — the dashboard's weekly bar chart
 * (DSH-04). Weeks with nothing scoreable are simply absent — the chart shows a stub, not a zero,
 * since zero is the worst possible driver and an empty week is no driver at all. Values are the same
 * aggregate the gauges use, so a bar and a matching window can never disagree.
 */
fun weeklySafetyScores(
    rides: List<Ride>,
    zone: ZoneId,
    weights: ScoreWeights = ScoreWeights(),
): Map<LocalDate, Int> =
    rides
        .filter { it.score != null }
        .groupBy { it.startedAtEpochMs.toLocalDate(zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
        .mapNotNull { (weekStart, weekRides) ->
            aggregateScore(weekRides.mapNotNull { it.score }, weights)?.let { weekStart to it.total }
        }.toMap()

/**
 * The combined score per calendar month — the dashboard's monthly bar chart (DSH-04). Months with
 * nothing scoreable are absent, for the same reason as [weeklySafetyScores].
 */
fun monthlySafetyScores(
    rides: List<Ride>,
    zone: ZoneId,
    weights: ScoreWeights = ScoreWeights(),
): Map<YearMonth, Int> =
    rides
        .filter { it.score != null }
        .groupBy { YearMonth.from(it.startedAtEpochMs.toLocalDate(zone)) }
        .mapNotNull { (month, monthRides) ->
            aggregateScore(monthRides.mapNotNull { it.score }, weights)?.let { month to it.total }
        }.toMap()

/**
 * The all-time score as it stood at the end of each day that had scored driving, oldest first — the
 * dashboard's trend line (DSH-04). Each point aggregates *everything up to and including* that day,
 * so the line is the headline gauge's own history: its last point always equals the gauge, and early
 * points swing while later ones settle as evidence accumulates, exactly as the real figure did. Days
 * with no scored driving get no point of their own; the line bridges them.
 */
fun allTimeSafetyScoreHistory(
    rides: List<Ride>,
    zone: ZoneId,
    weights: ScoreWeights = ScoreWeights(),
): List<Pair<LocalDate, Int>> {
    val byDay =
        rides
            .filter { it.score != null }
            .groupBy { it.startedAtEpochMs.toLocalDate(zone) }
            .toSortedMap()
    val soFar = mutableListOf<SafetyScore>()
    return byDay.mapNotNull { (day, dayRides) ->
        soFar += dayRides.mapNotNull { it.score }
        aggregateScore(soFar, weights)?.let { day to it.total }
    }
}

private fun Long.toLocalDate(zone: ZoneId): LocalDate = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()
