package de.uhi.enia.ridesafe.ui.screens.rides

import de.uhi.enia.ridesafe.data.MergedSummary
import de.uhi.enia.ridesafe.data.Refuel
import de.uhi.enia.ridesafe.data.Ride
import de.uhi.enia.ridesafe.data.SavedAddress

/** A ride plus its vehicle's display name (null when recorded in an unmapped/unassigned vehicle). */
data class RideRow(
    val ride: Ride,
    val vehicleName: String?,
    val startPlace: SavedAddress? = null,
    val endPlace: SavedAddress? = null,
)

data class RefuelRow(
    val refuel: Refuel,
    val vehicleName: String?,
)

/**
 * One row of the Logbook list: either a standalone ride or a merged ride collapsed into a single
 * entry (§3.8). [rideIds] is what the selection/merge machinery operates on — for a merged entry it's
 * all of its stops, since selecting a merged ride in the list means selecting the whole trip.
 */
sealed interface LogbookEntry {
    val sortEpochMs: Long
    val key: String
    val rides: List<Ride>

    val rideIds: List<Long> get() = rides.map { it.id }

    data class Single(
        val row: RideRow,
    ) : LogbookEntry {
        override val sortEpochMs get() = row.ride.startedAtEpochMs
        override val key get() = "r${row.ride.id}"
        override val rides get() = listOf(row.ride)
    }

    data class Merged(
        val groupId: Long,
        val stops: List<RideRow>, // chronological (oldest first)
        val summary: MergedSummary,
        val vehicleName: String?,
    ) : LogbookEntry {
        // Sort/day-group a merged trip by its most recent stop, so it slots into the newest-first list.
        override val sortEpochMs get() = stops.maxOf { it.ride.startedAtEpochMs }
        override val key get() = "g$groupId"
        override val rides get() = stops.map { it.ride }
    }
}

/** A heterogeneous Rides-page event; ride-only behavior stays inside [LogbookEntry]. */
sealed interface TimelineEntry {
    val sortEpochMs: Long
    val stableKey: String

    data class RideEntry(
        val entry: LogbookEntry,
        val refuels: List<RefuelRow> = emptyList(),
    ) : TimelineEntry {
        override val sortEpochMs get() = entry.sortEpochMs
        override val stableKey get() = entry.key
    }

    data class RefuelEntry(
        val row: RefuelRow,
    ) : TimelineEntry {
        override val sortEpochMs get() = row.refuel.timestampEpochMs
        override val stableKey get() = "f${row.refuel.id}"
    }
}

/** Newest first; exact ties put rides before refuels, then use the stable persistent key. */
val timelineEntryComparator =
    compareByDescending<TimelineEntry> { it.sortEpochMs }
        .thenBy { if (it is TimelineEntry.RideEntry) 0 else 1 }
        .thenByDescending { it.stableKey }

fun rideLogbookEntries(timeline: List<TimelineEntry>): List<LogbookEntry> = timeline.mapNotNull { (it as? TimelineEntry.RideEntry)?.entry }

fun timelineSelectionKeys(timeline: List<TimelineEntry>): Set<String> = timeline.mapTo(linkedSetOf()) { it.stableKey }

fun visibleTimelineSelectionKeys(timeline: List<TimelineEntry>): Set<String> =
    timeline.flatMapTo(linkedSetOf()) { entry ->
        listOf(entry.stableKey) +
            if (entry is TimelineEntry.RideEntry && entry.entry is LogbookEntry.Single) {
                entry.refuels.map { "f${it.refuel.id}" }
            } else {
                emptyList()
            }
    }

fun selectedRefuels(
    timeline: List<TimelineEntry>,
    selectedKeys: Set<String>,
): List<Refuel> =
    timeline
        .flatMap { entry ->
            when (entry) {
                is TimelineEntry.RefuelEntry -> listOf(entry.row)
                is TimelineEntry.RideEntry -> entry.refuels
            }
        }.filter { "f${it.refuel.id}" in selectedKeys }
        .map { it.refuel }

fun selectedRideLogbookEntries(
    timeline: List<TimelineEntry>,
    selectedKeys: Set<String>,
): List<LogbookEntry> = rideLogbookEntries(timeline).filter { it.key in selectedKeys }

fun buildTimeline(
    rideEntries: List<LogbookEntry>,
    refuelRows: List<RefuelRow>,
): List<TimelineEntry> {
    val entryKeyByRideId =
        buildMap {
            rideEntries.forEach { entry -> entry.rideIds.forEach { put(it, entry.key) } }
        }
    val attachedByKey =
        refuelRows
            .mapNotNull { row ->
                row.refuel.journeyAnchorRideId
                    ?.let(entryKeyByRideId::get)
                    ?.let { it to row }
            }.groupBy({ it.first }, { it.second })
    val attachedIds = attachedByKey.values.flatten().mapTo(hashSetOf()) { it.refuel.id }
    return buildList {
        rideEntries.forEach { entry ->
            add(
                TimelineEntry.RideEntry(
                    entry,
                    attachedByKey[entry.key].orEmpty().sortedWith(
                        compareBy<RefuelRow> { it.refuel.timestampEpochMs }.thenBy { it.refuel.id },
                    ),
                ),
            )
        }
        refuelRows.filterNot { it.refuel.id in attachedIds }.forEach { add(TimelineEntry.RefuelEntry(it)) }
    }.sortedWith(timelineEntryComparator)
}
