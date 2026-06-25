package de.uhi.enia.ridesafe.util

import android.content.Context
import android.text.format.DateUtils
import de.uhi.enia.ridesafe.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The calendar day a ride falls on, in the device's zone — the rides list's grouping key. */
fun rideDay(epochMs: Long): LocalDate = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()

/** Locale-aware time of day, e.g. "14:32". */
fun formatTimeOfDay(
    context: Context,
    epochMs: Long,
): String = DateUtils.formatDateTime(context, epochMs, DateUtils.FORMAT_SHOW_TIME)

/** Locale-aware date + time, e.g. "23 Jun 2026, 14:32" — used for the detail title. */
fun formatRideDateTime(
    context: Context,
    epochMs: Long,
): String =
    DateUtils.formatDateTime(
        context,
        epochMs,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR,
    )

/**
 * Elapsed ride duration as "5 min" / "1 h 05 min" / "2 d 03 h 04 min" — whole minutes, seconds
 * dropped. Leading zero units are omitted; null while a ride is still in progress (no end).
 */
fun formatDuration(
    startMs: Long,
    endMs: Long?,
): String? {
    endMs ?: return null
    val totalMinutes = ((endMs - startMs) / 60_000).coerceAtLeast(0)
    val days = totalMinutes / (24 * 60)
    val hours = (totalMinutes % (24 * 60)) / 60
    val minutes = totalMinutes % 60
    return when {
        days > 0 -> "%d d %02d h %02d min".format(days, hours, minutes)
        hours > 0 -> "%d h %02d min".format(hours, minutes)
        else -> "%d min".format(minutes)
    }
}

/** List group header: "Today" / "Yesterday", otherwise a localized weekday-date. */
fun formatDayHeader(
    context: Context,
    day: LocalDate,
    today: LocalDate = LocalDate.now(),
): String =
    when (day) {
        today -> {
            context.getString(R.string.ride_day_today)
        }

        today.minusDays(1) -> {
            context.getString(R.string.ride_day_yesterday)
        }

        else -> {
            DateUtils.formatDateTime(
                context,
                day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_YEAR,
            )
        }
    }
