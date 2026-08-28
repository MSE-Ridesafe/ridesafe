package de.uhi.enia.ridesafe.ui.screens.home

import android.text.format.DateFormat
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

fun formatActivityDateRange(
    days: List<ActivityBar>,
    locale: Locale,
): String {
    val start = days.firstOrNull()?.day ?: LocalDate.now()
    val end = days.lastOrNull()?.day ?: start
    // Day+month in the region's order — "dd.MM." for a German region, "MM/dd" for a US one.
    val formatter = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMdd"), locale)
    return "${start.format(formatter)} - ${end.format(formatter)}"
}

private fun emptyActivityBar(day: LocalDate): ActivityBar = ActivityBar(day, rideCount = 0, distanceMeters = 0.0, durationMillis = 0L)
