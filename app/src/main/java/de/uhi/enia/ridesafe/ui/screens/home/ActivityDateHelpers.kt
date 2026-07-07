package de.uhi.enia.ridesafe.ui.screens.home

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

fun buildRollingWeekActivity(
    activityByDay: Map<LocalDate, ActivityBar>,
    endDay: LocalDate,
): List<ActivityBar> =
    (6 downTo 0).map { offset ->
        val day = endDay.minusDays(offset.toLong())
        activityByDay[day] ?: emptyActivityBar(day)
    }

fun buildMonthActivity(
    activityByDay: Map<LocalDate, ActivityBar>,
    month: YearMonth,
): List<ActivityBar> =
    (1..month.lengthOfMonth()).map { dayOfMonth ->
        val day = month.atDay(dayOfMonth)
        activityByDay[day] ?: emptyActivityBar(day)
    }

fun formatActivityDateRange(days: List<ActivityBar>): String {
    val start = days.firstOrNull()?.day ?: LocalDate.now()
    val end = days.lastOrNull()?.day ?: start
    val formatter = DateTimeFormatter.ofPattern("dd.MM.", Locale.getDefault())
    return "${start.format(formatter)} - ${end.format(formatter)}"
}

private fun emptyActivityBar(day: LocalDate): ActivityBar =
    ActivityBar(day, rideCount = 0, distanceMeters = 0.0, durationMillis = 0L)
