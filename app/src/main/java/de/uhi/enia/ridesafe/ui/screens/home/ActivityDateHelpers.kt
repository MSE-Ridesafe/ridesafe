package de.uhi.enia.ridesafe.ui.screens.home

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

fun buildCalendarWeekActivity(
    activityByDay: Map<LocalDate, ActivityBar>,
    referenceDay: LocalDate,
): List<ActivityBar> {
    val monday = referenceDay.minusDays((referenceDay.dayOfWeek.value - 1).toLong())
    return (0..6).map { offset ->
        val day = monday.plusDays(offset.toLong())
        activityByDay[day] ?: emptyActivityBar(day)
    }
}

fun formatActivityDateRange(
    days: List<ActivityBar>,
    locale: Locale,
): String {
    val start = days.firstOrNull()?.day ?: LocalDate.now()
    val end = days.lastOrNull()?.day ?: start
    val formatter = DateTimeFormatter.ofPattern("dd.MM.", locale)
    return "${start.format(formatter)} - ${end.format(formatter)}"
}

private fun emptyActivityBar(day: LocalDate): ActivityBar = ActivityBar(day, rideCount = 0, distanceMeters = 0.0, durationMillis = 0L)
