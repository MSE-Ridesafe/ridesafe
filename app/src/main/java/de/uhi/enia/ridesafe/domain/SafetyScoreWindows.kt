package de.uhi.enia.ridesafe.domain

import de.uhi.enia.ridesafe.data.Ride
import de.uhi.enia.ridesafe.data.SafetyScore
import de.uhi.enia.ridesafe.rides.processing.score.aggregateScore
import de.uhi.enia.ridesafe.util.toLocalDate
import java.time.DayOfWeek
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
 * that aren't rated (see [isRated] — both scores or neither) contribute nothing, exactly as a ride
 * with no distance contributes nothing to mileage.
 *
 * Null means the window holds no scoreable driving at all — not a bad score, and not zero.
 */
fun safetyScoreForRides(rides: List<Ride>): SafetyScore? = aggregateScore(rides.mapNotNull { it.ratedScore })

/** One calendar month, matching how [journeyTotalsForMonth] slices mileage. */
fun safetyScoreForMonth(
    rides: List<Ride>,
    month: YearMonth,
    zone: ZoneId,
): SafetyScore? = safetyScoreForRides(rides.filter { YearMonth.from(it.startedAtEpochMs.toLocalDate(zone)) == month })

/**
 * The last seven days including [endDay] — a rolling window, not an ISO week, to match
 * buildRollingWeekActivity. A weekly score that reset every Monday would say something
 * different from the weekly chart sitting next to it.
 */
fun safetyScoreForRollingWeek(
    rides: List<Ride>,
    endDay: LocalDate,
    zone: ZoneId,
): SafetyScore? {
    val from = endDay.minusDays(6)
    return safetyScoreForRides(rides.filter { it.startedAtEpochMs.toLocalDate(zone) in from..endDay })
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
): Map<LocalDate, Int> =
    rides
        .filter { it.isRated() }
        .groupBy { it.startedAtEpochMs.toLocalDate(zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
        .mapNotNull { (weekStart, weekRides) ->
            aggregateScore(weekRides.mapNotNull { it.ratedScore })?.let { weekStart to it.total }
        }.toMap()

/**
 * The combined score per calendar month — the dashboard's monthly bar chart (DSH-04). Months with
 * nothing scoreable are absent, for the same reason as [weeklySafetyScores].
 */
fun monthlySafetyScores(
    rides: List<Ride>,
    zone: ZoneId,
): Map<YearMonth, Int> =
    rides
        .filter { it.isRated() }
        .groupBy { YearMonth.from(it.startedAtEpochMs.toLocalDate(zone)) }
        .mapNotNull { (month, monthRides) ->
            aggregateScore(monthRides.mapNotNull { it.ratedScore })?.let { month to it.total }
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
): List<Pair<LocalDate, Int>> {
    val byDay =
        rides
            .filter { it.isRated() }
            .groupBy { it.startedAtEpochMs.toLocalDate(zone) }
            .toSortedMap()
    val soFar = mutableListOf<SafetyScore>()
    return byDay.mapNotNull { (day, dayRides) ->
        soFar += dayRides.mapNotNull { it.ratedScore }
        aggregateScore(soFar)?.let { day to it.total }
    }
}
