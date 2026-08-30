package de.uhi.enia.ridesafe.ui.screens.rides

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import de.uhi.enia.ridesafe.data.Refuel

/** Everything the Logbook's multi-select mode exposes to the screen, plus the moves that change it. */
internal class LogbookSelection(
    val selectionMode: Boolean,
    val selected: Set<String>,
    val selectAllKeys: Set<String>,
    val selectedRideEntries: List<LogbookEntry>,
    val selectedRefuelRecords: List<Refuel>,
    val action: LogbookAction,
    val enter: () -> Unit,
    val exit: () -> Unit,
    val toggle: (String) -> Unit,
    val selectAll: () -> Unit,
    val deselectAll: () -> Unit,
)

/**
 * The Logbook's selection state, hoisted out of the screen body.
 *
 * Selection tracks what is on screen: a ride hidden by the filter counts as deselected, so
 * "select all" means all the *shown* rides and no invisible ride can be swept into a merge.
 * Selection is by entry key; keys that no longer exist (data changed) are ignored via the
 * live-key intersection.
 */
@Composable
internal fun rememberLogbookSelection(
    timeline: List<TimelineEntry>,
    visibleTimeline: List<TimelineEntry>,
    entries: List<LogbookEntry>,
): LogbookSelection {
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedKeys by
        rememberSaveable(
            stateSaver = listSaver(save = { it.toList() }, restore = { it.toSet() }),
        ) { mutableStateOf(emptySet<String>()) }

    val selectAllKeys = remember(visibleTimeline) { timelineSelectionKeys(visibleTimeline) }
    val liveKeys = remember(visibleTimeline) { visibleTimelineSelectionKeys(visibleTimeline) }
    val selected = selectedKeys.intersect(liveKeys)
    val selectedRideEntries = remember(timeline, selected) { selectedRideLogbookEntries(timeline, selected) }
    val selectedRefuelRecords = remember(timeline, selected) { selectedRefuels(timeline, selected) }
    // Contiguity (MRG-02) is judged against every ride, not the shown ones: a filtered-out ride
    // between two selected ones still breaks the run, and merging across it would be wrong.
    val allRides = remember(entries) { entries.flatMap { it.rides } }
    // Merge, unmerge, attach or detach — one primary action, picked from what is selected.
    val action =
        remember(selectedRideEntries, selectedRefuelRecords, allRides) {
            logbookAction(selectedRideEntries, selectedRefuelRecords, allRides)
        }

    return LogbookSelection(
        selectionMode = selectionMode,
        selected = selected,
        selectAllKeys = selectAllKeys,
        selectedRideEntries = selectedRideEntries,
        selectedRefuelRecords = selectedRefuelRecords,
        action = action,
        enter = { selectionMode = true },
        exit = {
            selectionMode = false
            selectedKeys = emptySet()
        },
        toggle = { key -> selectedKeys = if (key in selected) selected - key else selected + key },
        selectAll = { selectedKeys = selectAllKeys },
        deselectAll = { selectedKeys = emptySet() },
    )
}
