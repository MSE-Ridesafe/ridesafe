package de.uhi.enia.ridesafe.ui.screens.rides

import android.content.Context
import android.text.format.DateUtils
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.util.formatDuration
import de.uhi.enia.ridesafe.util.formatDurationMs
import de.uhi.enia.ridesafe.util.formatTimeOfDay
import de.uhi.enia.ridesafe.util.rideDay
import java.text.Normalizer
import java.util.Locale

/** Which kind of logbook entry to keep — a standalone ride or a merged trip (§3.8). */
enum class TripType {
    SINGLE,
    MERGED,
}

/**
 * The Logbook's search + filter criteria (LOG-06, LOG-07, LOG-11 … LOG-15). Every field is
 * independently optional and they combine with AND; the default instance keeps everything.
 *
 * Held by [RidesViewModel] rather than the screen so it survives opening a ride and switching
 * tabs, and so a later filtered export (LOG-09) can read the same criteria.
 *
 * [fromEpochMs] is inclusive and [toEpochMs] exclusive (the start of the day *after* the picked
 * one), so a range picked as "24 Aug – 24 Aug" covers that whole day. Distances are canonical
 * meters, converted from the user's units by the filter sheet.
 */
data class RideFilter(
    val query: String = "",
    val vehicleId: Long? = null,
    val startPlaceId: Long? = null,
    val endPlaceId: Long? = null,
    val fromEpochMs: Long? = null,
    val toEpochMs: Long? = null,
    val minDistanceMeters: Double? = null,
    val maxDistanceMeters: Double? = null,
    val tripType: TripType? = null,
    val onlyWithEvents: Boolean = false,
)

/** How many filters (everything but the search text) are set — the filter button's badge count. */
val RideFilter.activeFilterCount: Int
    get() =
        listOfNotNull(vehicleId, startPlaceId, endPlaceId, tripType).size +
            (if (fromEpochMs != null || toEpochMs != null) 1 else 0) +
            (if (minDistanceMeters != null || maxDistanceMeters != null) 1 else 0) +
            (if (onlyWithEvents) 1 else 0)

/** Whether anything at all is narrowing the list — drives the "no matches" state and its reset. */
val RideFilter.isActive: Boolean get() = query.isNotBlank() || activeFilterCount > 0

/**
 * The entries left after applying [filter]. [searchIndex] maps [LogbookEntry.key] to that entry's
 * folded searchable text (see [searchIndex]); [ridesWithEvents] are the ride ids that have at least
 * one detected driving event (ANL-01).
 */
fun List<LogbookEntry>.applyFilter(
    filter: RideFilter,
    searchIndex: Map<String, String>,
    ridesWithEvents: Set<Long>,
): List<LogbookEntry> {
    if (!filter.isActive) return this
    val tokens = queryTokens(filter.query)
    return filter { entry ->
        entry.matches(filter, tokens, searchIndex[entry.key].orEmpty(), ridesWithEvents)
    }
}

private fun LogbookEntry.matches(
    filter: RideFilter,
    tokens: List<String>,
    haystack: String,
    ridesWithEvents: Set<Long>,
): Boolean {
    if (filter.vehicleId != null && vehicleId() != filter.vehicleId) return false
    if (filter.startPlaceId != null && startPlaceId() != filter.startPlaceId) return false
    if (filter.endPlaceId != null && endPlaceId() != filter.endPlaceId) return false
    if (filter.tripType != null && tripType() != filter.tripType) return false
    if (filter.onlyWithEvents && rideIds.none { it in ridesWithEvents }) return false

    // Overlap, not "started inside": a trip crossing midnight — and a merged trip whose stops span
    // days — belongs to every day it touches, and the list already sorts merged trips by their
    // newest stop rather than their first.
    if (filter.fromEpochMs != null && endEpochMs() < filter.fromEpochMs) return false
    if (filter.toEpochMs != null && startEpochMs() >= filter.toEpochMs) return false

    if (filter.minDistanceMeters != null || filter.maxDistanceMeters != null) {
        // A ride the analysis pass hasn't reached yet has no distance; an unknown distance can't
        // honestly satisfy a bound, so it drops out rather than being assumed to fit.
        val distance = distanceMeters() ?: return false
        if (filter.minDistanceMeters != null && distance < filter.minDistanceMeters) return false
        if (filter.maxDistanceMeters != null && distance > filter.maxDistanceMeters) return false
    }

    return tokens.all { it in haystack }
}

/** The trip's vehicle; a merged trip's stops all share one (MRG-09), so the first stop answers for it. */
private fun LogbookEntry.vehicleId(): Long? =
    when (this) {
        is LogbookEntry.Single -> row.ride.vehicleId
        is LogbookEntry.Merged -> stops.first().ride.vehicleId
    }

/** Where the trip began: for a merged trip that is its first stop's start, not any stop's (LOG-12). */
private fun LogbookEntry.startPlaceId(): Long? =
    when (this) {
        is LogbookEntry.Single -> row.startPlace?.id
        is LogbookEntry.Merged -> stops.first().startPlace?.id
    }

/** Where the trip ended: for a merged trip that is its last stop's end — its final destination. */
private fun LogbookEntry.endPlaceId(): Long? =
    when (this) {
        is LogbookEntry.Single -> row.endPlace?.id
        is LogbookEntry.Merged -> stops.last().endPlace?.id
    }

private fun LogbookEntry.tripType(): TripType =
    when (this) {
        is LogbookEntry.Single -> TripType.SINGLE
        is LogbookEntry.Merged -> TripType.MERGED
    }

/** Total driven distance, summed over the stops for a merged trip (MRG-05); null until analysed. */
private fun LogbookEntry.distanceMeters(): Double? =
    when (this) {
        is LogbookEntry.Single -> row.ride.distanceMeters
        is LogbookEntry.Merged -> summary.distanceMeters
    }

private fun LogbookEntry.startEpochMs(): Long =
    when (this) {
        is LogbookEntry.Single -> row.ride.startedAtEpochMs
        is LogbookEntry.Merged -> summary.startEpochMs
    }

/** The trip's end, falling back to its start while a ride is still in progress (no end yet). */
private fun LogbookEntry.endEpochMs(): Long =
    when (this) {
        is LogbookEntry.Single -> row.ride.endedAtEpochMs ?: row.ride.startedAtEpochMs
        is LogbookEntry.Merged -> summary.endEpochMs ?: sortEpochMs
    }

private val COMBINING_MARKS = Regex("\\p{Mn}+")

/**
 * The comparison form both sides of a search are reduced to: lowercase, ß expanded, accents
 * stripped — so "munchen" finds "München" and "hauptstrasse" finds "Hauptstraße".
 */
fun searchFold(text: String): String =
    Normalizer
        .normalize(text.lowercase(Locale.ROOT).replace("ß", "ss"), Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")

/** A query splits into whitespace-separated tokens, all of which must match (AND). */
fun queryTokens(query: String): List<String> = searchFold(query).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

/**
 * Each entry's searchable text, keyed by [LogbookEntry.key] and already folded, so a keystroke only
 * runs `contains` over it (LOG-11). Covers the vehicle, matched saved places, geocoded addresses,
 * the date (localized, weekday and numeric forms, plus ISO), start/end times and the duration.
 *
 * Built against a [Context] rather than cached in the ViewModel on purpose: the strings are
 * localized, and reading them off the composition's context means a locale change rebuilds the
 * index instead of leaving it answering in the old language.
 *
 * ponytail: rebuilt whole whenever the entries change — a couple of milliseconds at a personal
 * logbook's scale. Move it to the ViewModel on Dispatchers.Default if it ever runs into thousands.
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
    return "$long $numeric ${rideDay(epochMs)}"
}

private fun MutableList<String>.put(value: String?) {
    value?.let { add(it) }
}

private fun MutableList<String>.putAll(values: List<String?>) {
    values.filterNotNullTo(this)
}
