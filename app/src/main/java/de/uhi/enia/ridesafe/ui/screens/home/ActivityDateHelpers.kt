package de.uhi.enia.ridesafe.ui.screens.home

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

fun startOfCalendarWeek(day: LocalDate): LocalDate =
    day.minusDays((day.dayOfWeek.value - 1).toLong())

fun buildSevenDayActivity(
    activityByDay: Map<LocalDate, ActivityBar>,
    startDay: LocalDate,
): List<ActivityBar> = buildActivityWindow(activityByDay, startDay, dayCount = 7)

fun buildActivityWindow(
    activityByDay: Map<LocalDate, ActivityBar>,
    startDay: LocalDate,
    dayCount: Int,
): List<ActivityBar> =
    (0 until dayCount).map { offset ->
        val day = startDay.plusDays(offset.toLong())
        activityByDay[day] ?: emptyActivityBar(day)
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
