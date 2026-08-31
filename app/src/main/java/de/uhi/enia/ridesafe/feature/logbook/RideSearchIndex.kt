package de.uhi.enia.ridesafe.feature.logbook

import android.content.Context
import android.text.format.DateUtils
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.core.format.formatDuration
import de.uhi.enia.ridesafe.core.format.formatDurationMs
import de.uhi.enia.ridesafe.core.format.formatTimeOfDay
import de.uhi.enia.ridesafe.core.format.toLocalDate
import de.uhi.enia.ridesafe.domain.ride.LogbookEntry
import de.uhi.enia.ridesafe.domain.ride.RideRow
import de.uhi.enia.ridesafe.domain.ride.searchFold

/**
 * Each entry's searchable text, keyed by [LogbookEntry.key] and already folded, so a keystroke only
 * runs `contains` over it (LOG-11). Covers the vehicle, matched saved places, geocoded addresses,
 * the date (localized, weekday and numeric forms, plus ISO), start/end times and the duration.
 *
 * Built against a [Context] rather than cached in the ViewModel on purpose: the strings are
 * localized, and reading them off the composition's context means a locale change rebuilds the
 * index instead of leaving it answering in the old language.
 *
 * ponytail: rebuilt whole whenever the entries change, and only while a query is active — the date
 * formatting per entry is too slow for the list's first composition frame, which is when the
 * detail-close animation runs. Move it to the ViewModel on Dispatchers.Default if typing the first
 * character ever visibly hitches.
 */
fun searchIndex(
    context: Context,
    entries: List<LogbookEntry>,
): Map<String, String> = entries.associate { it.key to searchFold(it.searchableText(context)) }

private fun LogbookEntry.searchableText(context: Context): String =
    buildList {
        when (this@searchableText) {
            is LogbookEntry.Single -> {
                val ride = row.ride
                put(row.vehicleName)
                putAll(row.placeTerms())
                put(dateTerms(context, ride.startedAtEpochMs))
                put(formatTimeOfDay(context, ride.startedAtEpochMs))
                put(ride.endedAtEpochMs?.let { formatTimeOfDay(context, it) })
                put(formatDuration(ride.startedAtEpochMs, ride.endedAtEpochMs))
            }

            is LogbookEntry.Merged -> {
                put(vehicleName)
                put(context.getString(R.string.ride_merged_label))
                stops.forEach { putAll(it.placeTerms()) }
                // Both ends of the trip, so a merged trip spanning days is findable by either date.
                put(dateTerms(context, summary.startEpochMs))
                put(dateTerms(context, sortEpochMs))
                put(formatTimeOfDay(context, summary.startEpochMs))
                put(summary.endEpochMs?.let { formatTimeOfDay(context, it) })
                put(formatDurationMs(summary.movingDurationMs))
            }
        }
    }.joinToString(" ")

/** A stop's own text: the labels of the places its endpoints matched, plus the raw addresses. */
private fun RideRow.placeTerms(): List<String?> = listOf(startPlace?.label, endPlace?.label, ride.startAddress, ride.endAddress)

/**
 * The date in the shapes someone might actually type: the localized long form ("Monday, 24 August
 * 2026"), the localized numeric one ("24.08.2026") and the ISO one ("2026-08-24"). Between them,
 * "monday", "mon", "aug", "24.08" and "2026-08" all hit.
 */
private fun dateTerms(
    context: Context,
    epochMs: Long,
): String {
    val long =
        DateUtils.formatDateTime(
            context,
            epochMs,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_YEAR,
        )
    val numeric =
        DateUtils.formatDateTime(
            context,
            epochMs,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NUMERIC_DATE or DateUtils.FORMAT_SHOW_YEAR,
        )
    return "$long $numeric ${epochMs.toLocalDate()}"
}

private fun MutableList<String>.put(value: String?) {
    value?.let { add(it) }
}

private fun MutableList<String>.putAll(values: List<String?>) {
    values.filterNotNullTo(this)
}
