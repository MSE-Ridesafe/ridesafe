package de.uhi.enia.ridesafe.feature.home

import android.text.format.DateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

fun startOfCalendarWeek(day: LocalDate): LocalDate = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

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
    // Day+month in the region's order — "dd.MM." for a German region, "MM/dd" for a US one.
    val formatter = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMdd"), locale)
    return "${start.format(formatter)} - ${end.format(formatter)}"
}

private fun emptyActivityBar(day: LocalDate): ActivityBar = ActivityBar(day, rideCount = 0, distanceMeters = 0.0, durationMillis = 0L)
