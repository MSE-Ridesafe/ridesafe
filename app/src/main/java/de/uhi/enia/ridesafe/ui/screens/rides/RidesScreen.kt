@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package de.uhi.enia.ridesafe.ui.screens.rides

import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.SavedAddress
import de.uhi.enia.ridesafe.data.Vehicle
import de.uhi.enia.ridesafe.export.RideExportFormat
import de.uhi.enia.ridesafe.export.RideExportRequest
import de.uhi.enia.ridesafe.export.SavedRideExport
import de.uhi.enia.ridesafe.export.buildOpenExportIntent
import de.uhi.enia.ridesafe.rides.processing.RideAnalysisProgress
import de.uhi.enia.ridesafe.rides.recording.RecordingStatus
import de.uhi.enia.ridesafe.ui.components.AppSnackbarHost
import de.uhi.enia.ridesafe.ui.components.CardDivider
import de.uhi.enia.ridesafe.ui.components.ConfirmDestructiveDialog
import de.uhi.enia.ridesafe.ui.components.EmptyState
import de.uhi.enia.ridesafe.ui.components.ListGroupItem
import de.uhi.enia.ridesafe.ui.components.ListGroupItemGap
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.ui.components.RECORDING_BAR_INSET
import de.uhi.enia.ridesafe.ui.components.SectionTitle
import de.uhi.enia.ridesafe.util.formatDayHeader
import de.uhi.enia.ridesafe.util.toLocalDate
import java.time.LocalDate

@Composable
fun RidesScreen(
    modifier: Modifier = Modifier,
    timeline: List<TimelineEntry>,
    analysis: RideAnalysisProgress,
    exportState: RideExportState,
    vehicles: List<Vehicle>,
    places: List<SavedAddress>,
    ridesWithEvents: Set<Long>,
    filter: RideFilter,
    onFilterChange: (RideFilter) -> Unit,
    onOpenRide: (Long) -> Unit,
    onOpenMerged: (Long) -> Unit,
    onOpenRefuel: (Long) -> Unit,
    onOpenAnalysisQueue: () -> Unit,
    onMerge: (List<Long>, List<Long>) -> Unit,
    onUnmerge: (Long) -> Unit,
    onAttach: (List<Long>, List<Long>) -> Unit,
    onDetach: (List<Long>) -> Unit,
    onDelete: (List<Long>, List<Long>) -> Unit,
    logbookOperationState: LogbookOperationState,
    onLogbookOperationResultConsumed: () -> Unit,
    onExport: (List<RideExportRequest>, RideExportFormat) -> Unit,
    onExportResultConsumed: () -> Unit,
    onAddRefuel: () -> Unit,
    // The entry whose detail pane is showing (LogbookEntry.key). Null on a phone, where the detail
    // covers the list rather than sitting beside it.
    selectionDismissRequests: State<Int>,
    selectedKey: String? = null,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val exportSuccessMessage = stringResource(R.string.ride_export_success)
    val exportErrorMessage = stringResource(R.string.ride_export_error)
    val openFileLabel = stringResource(R.string.ride_export_notification_open)

    // Both floating overlays live in the same bottom corner, so the analysis bar (and the list
    // under it) step up over the app shell's recording bar while a ride runs.
    val recording by RecordingStatus.running.collectAsState()
    val recordingInset = if (recording != null) RECORDING_BAR_INSET else 0.dp

    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var handledSelectionDismissRequest by rememberSaveable {
        mutableIntStateOf(selectionDismissRequests.value)
    }
    val entries = remember(timeline) { rideLogbookEntries(timeline) }
    var pendingExportRequests by remember { mutableStateOf<List<RideExportRequest>?>(null) }
    var deleteConfirmationOpen by rememberSaveable { mutableStateOf(false) }
    var deleteOperationPending by rememberSaveable { mutableStateOf(false) }
    // Selection is by entry key; keys that no longer exist (data changed) are ignored below.
    var selectedKeys by
        rememberSaveable(
            stateSaver = listSaver(save = { it.toList() }, restore = { it.toSet() }),
        ) { mutableStateOf(emptySet<String>()) }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }

    // Each entry's searchable text, built once per data change; a keystroke then only scans it.
    // Only built while a query is active: applyFilter never reads it otherwise, and skipping it keeps
    // the list's first composition cheap — which is the frame the detail-close animation runs on.
    val searchActive = filter.query.isNotBlank()
    val index = remember(entries, context, searchActive) { if (searchActive) searchIndex(context, entries) else emptyMap() }
    val visibleTimeline =
        remember(timeline, filter, index, ridesWithEvents) {
            if (!filter.isActive) {
                timeline
            } else {
                val keys = entries.applyFilter(filter, index, ridesWithEvents).mapTo(hashSetOf()) { it.key }
                // The search and the filters speak the language of rides (vehicle, places, distance…),
                // so an active filter keeps only the matching rides. A refuel attached to a shown ride
                // stays with it; standalone refuel rows have no way to match and drop out.
                timeline.filter { it is TimelineEntry.RideEntry && it.stableKey in keys }
            }
        }

    // Selection tracks what is on screen: a ride hidden by the filter counts as deselected, so
    // "select all" means all the *shown* rides and no invisible ride can be swept into a merge.
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

    fun exitSelection() {
        selectionMode = false
        selectedKeys = emptySet()
    }

    fun toggle(key: String) {
        selectedKeys = if (key in selected) selected - key else selected + key
    }

    val selectionDismissRequest = selectionDismissRequests.value
    LaunchedEffect(selectionDismissRequest) {
        if (selectionDismissRequest != handledSelectionDismissRequest) {
            handledSelectionDismissRequest = selectionDismissRequest
            exitSelection()
        }
    }

    LaunchedEffect(exportState) {
        when (exportState) {
            is RideExportState.Success -> {
                val saved =
                    SavedRideExport(
                        fileName = exportState.export.fileName,
                        uri = exportState.export.contentUri.toUri(),
                        format = exportState.export.format,
                    )
                val openIntent = buildOpenExportIntent(saved)
                val canOpen = openIntent.resolveActivity(context.packageManager) != null
                val result =
                    snackbarHostState.showSnackbar(
                        message = exportSuccessMessage,
                        actionLabel = openFileLabel.takeIf { canOpen },
                        duration = SnackbarDuration.Long,
                    )
                if (result == SnackbarResult.ActionPerformed) {
                    runCatching { context.startActivity(openIntent) }
                        .onFailure { Log.w("RideExport", "Could not open exported file", it) }
                }
                onExportResultConsumed()
            }

            RideExportState.Error -> {
                snackbarHostState.showSnackbar(exportErrorMessage)
                onExportResultConsumed()
            }

            RideExportState.Idle, RideExportState.Exporting -> {}
        }
    }

    val attachSuccess = stringResource(R.string.refuel_attached_success)
    val detachSuccess = stringResource(R.string.refuel_detached_success)
    val mergeSuccess = stringResource(R.string.ride_merge_success)
    val deleteSuccess = stringResource(R.string.ride_delete_success)
    val operationError = stringResource(R.string.refuel_association_error)
    val deleteError = stringResource(R.string.ride_delete_error)
    LaunchedEffect(logbookOperationState) {
        when (logbookOperationState) {
            is LogbookOperationState.Success -> {
                val message =
                    when (logbookOperationState.operation) {
                        LogbookOperation.ATTACHED -> attachSuccess
                        LogbookOperation.DETACHED -> detachSuccess
                        LogbookOperation.MERGED -> mergeSuccess
                        LogbookOperation.DELETED -> deleteSuccess
                    }
                deleteOperationPending = false
                // A Snackbar suspends until it times out. Finish the operation first so the
                // completed selection disappears immediately and a later action is never ignored.
                exitSelection()
                onLogbookOperationResultConsumed()
                snackbarHostState.showSnackbar(message)
            }

            LogbookOperationState.Error -> {
                val message =
                    if (deleteOperationPending) deleteError else operationError
                deleteOperationPending = false
                onLogbookOperationResultConsumed()
                snackbarHostState.showSnackbar(message)
            }

            LogbookOperationState.Idle, LogbookOperationState.Running -> {}
        }
    }

    // The status bar overlays the Scaffold rather than sitting in its bottomBar slot: that slot
    // reserves an opaque strip of the Scaffold's own container color, so the pill ends up on the
    // same background as everything else and reads as docked. Floating over the list — which keeps
    // scrolling behind it — is what makes it look like it is hovering.
    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            snackbarHost = { AppSnackbarHost(snackbarHostState) },
            topBar = {
                if (selectionMode) {
                    SelectionTopBar(
                        count = selected.size,
                        allSelected = selectAllKeys.isNotEmpty() && selectAllKeys.all { it in selected },
                        action = action,
                        operationRunning = logbookOperationState == LogbookOperationState.Running,
                        onExit = ::exitSelection,
                        onSelectAll = { selectedKeys = selectAllKeys },
                        onDeselectAll = { selectedKeys = emptySet() },
                        onAction = {
                            val rideIds = selectedRideEntries.flatMap { it.rideIds }
                            val refuelIds = selectedRefuelRecords.map { it.id }
                            when (action.kind) {
                                LogbookActionKind.MERGE -> onMerge(rideIds, refuelIds)
                                LogbookActionKind.ATTACH -> onAttach(rideIds, refuelIds)
                                LogbookActionKind.DETACH -> onDetach(refuelIds)
                                // Un-merging is not a logbook operation, so it clears the selection itself.
                                LogbookActionKind.UNMERGE -> {
                                    action.unmergeGroupId?.let(onUnmerge)
                                    exitSelection()
                                }
                            }
                        },
                        exportEnabled = selectedRideEntries.isNotEmpty() && exportState != RideExportState.Exporting,
                        onExport = { pendingExportRequests = exportRequests(entries, selected) },
                        deleteEnabled =
                            selected.isNotEmpty() &&
                                selectedRideEntries.flatMap { it.rides }.all { it.endedAtEpochMs != null },
                        onDelete = { deleteConfirmationOpen = true },
                    )
                } else {
                    TopAppBar(
                        title = {
                            Text(
                                stringResource(R.string.screen_rides_title),
                                style = MaterialTheme.typography.headlineMedium,
                            )
                        },
                        actions = {
                            IconButton(onClick = onAddRefuel) {
                                MaterialSymbol(
                                    symbolName = "add",
                                    contentDescription = stringResource(R.string.refuel_add),
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    )
                }
            },
        ) { innerPadding ->
            Column(
                modifier =
                    Modifier
                        .padding(innerPadding)
                        .fillMaxSize(),
            ) {
                if (timeline.isEmpty()) {
                    // An empty logbook has nothing to search, so the search bar stays away entirely.
                    EmptyState(
                        symbolName = "route",
                        title = stringResource(R.string.rides_empty_title),
                        message = stringResource(R.string.rides_empty_message),
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                    )
                    return@Column
                }

                // Search and filters describe rides; a refuel-only timeline offers nothing to search.
                if (entries.isNotEmpty()) {
                    RideSearchBar(
                        query = filter.query,
                        activeFilterCount = filter.activeFilterCount,
                        onQueryChange = { onFilterChange(filter.copy(query = it)) },
                        onOpenFilters = { filtersOpen = true },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                if (visibleTimeline.isEmpty()) {
                    // Nothing matched, so there is no list to scroll the chips away with — and they
                    // are the way back out, so here they stay put.
                    ActiveFilterChips(
                        filter = filter,
                        vehicles = vehicles,
                        places = places,
                        onFilterChange = onFilterChange,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    EmptyState(
                        symbolName = "search_off",
                        title = stringResource(R.string.rides_no_matches_title),
                        message = stringResource(R.string.rides_no_matches_message),
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                    ) {
                        TextButton(onClick = { onFilterChange(RideFilter()) }) {
                            Text(stringResource(R.string.rides_filter_clear_all))
                        }
                    }
                    return@Column
                }

                // One card per calendar day; entries arrive newest-first, so insertion order gives newest day
                // first, newest entry first within each day.
                val groups =
                    remember(visibleTimeline) { visibleTimeline.groupByTo(LinkedHashMap()) { it.sortEpochMs.toLocalDate() } }
                val today = LocalDate.now()

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    // Nothing reserves space for the floating status bar, so the list leaves room for it
                    // itself — otherwise the last ride of the logbook can never be scrolled out from under it.
                    contentPadding =
                        PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 8.dp,
                            bottom = (if (analysis.running) 88.dp else 16.dp) + recordingInset,
                        ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // The chips are the list's first row rather than a bar above it, so a long set
                    // of them scrolls away under the search bar instead of permanently costing the
                    // user rides off the bottom of the screen. The filter button keeps its badge, so
                    // "something is filtered" is still visible once they are gone.
                    item(key = "chips") {
                        ActiveFilterChips(
                            filter = filter,
                            vehicles = vehicles,
                            places = places,
                            onFilterChange = onFilterChange,
                        )
                    }
                    groups.forEach { (day, dayEntries) ->
                        item(key = "h$day") {
                            DayHeader(text = formatDayHeader(context, day, today))
                        }
                        item(key = "c$day") {
                            Column(verticalArrangement = Arrangement.spacedBy(ListGroupItemGap)) {
                                dayEntries.forEachIndexed { index, timelineEntry ->
                                    ListGroupItem(index = index, count = dayEntries.size) {
                                        when (timelineEntry) {
                                            is TimelineEntry.RideEntry -> {
                                                val entry = timelineEntry.entry
                                                // A ride and its attached refuels share one list segment —
                                                // the indented divider keeps reading as "attached".
                                                Column {
                                                    LogbookRow(
                                                        entry = entry,
                                                        selectionMode = selectionMode,
                                                        selected = entry.key in selected,
                                                        isOpen = entry.key == selectedKey,
                                                        onClick = {
                                                            if (selectionMode) {
                                                                toggle(entry.key)
                                                            } else {
                                                                when (entry) {
                                                                    is LogbookEntry.Single -> onOpenRide(entry.row.ride.id)
                                                                    is LogbookEntry.Merged -> onOpenMerged(entry.groupId)
                                                                }
                                                            }
                                                        },
                                                        onLongClick = {
                                                            selectionMode = true
                                                            toggle(entry.key)
                                                        },
                                                    )
                                                    // Keep the compact main timeline focused on the combined
                                                    // journey summary. Its associated Refuels remain available
                                                    // in the combined-ride detail timeline.
                                                    if (entry is LogbookEntry.Single) {
                                                        timelineEntry.refuels.forEach { nested ->
                                                            CardDivider(modifier = Modifier.padding(start = 40.dp))
                                                            val key = "f${nested.refuel.id}"
                                                            RefuelTimelineRow(
                                                                row = nested,
                                                                selectionMode = selectionMode,
                                                                selected = key in selected,
                                                                isOpen = key == selectedKey,
                                                                nested = true,
                                                                onClick = {
                                                                    if (selectionMode) toggle(key) else onOpenRefuel(nested.refuel.id)
                                                                },
                                                                onLongClick = {
                                                                    selectionMode = true
                                                                    toggle(key)
                                                                },
                                                            )
                                                        }
                                                    }
                                                }
                                            }

                                            is TimelineEntry.RefuelEntry -> {
                                                RefuelTimelineRow(
                                                    row = timelineEntry.row,
                                                    selectionMode = selectionMode,
                                                    selected = timelineEntry.stableKey in selected,
                                                    isOpen = timelineEntry.stableKey == selectedKey,
                                                    onClick = {
                                                        if (selectionMode) {
                                                            toggle(timelineEntry.stableKey)
                                                        } else {
                                                            onOpenRefuel(timelineEntry.row.refuel.id)
                                                        }
                                                    },
                                                    onLongClick = {
                                                        selectionMode = true
                                                        toggle(timelineEntry.stableKey)
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        AnalysisStatusBar(
            progress = analysis,
            onOpen = onOpenAnalysisQueue,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .padding(bottom = recordingInset),
        )
        if (exportState == RideExportState.Exporting) {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)),
                contentAlignment = Alignment.Center,
            ) {
                Card(shape = MaterialTheme.shapes.extraLarge) {
                    Column(
                        modifier = Modifier.padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.ride_exporting))
                    }
                }
            }
        }
        if (filtersOpen) {
            RideFilterSheet(
                filter = filter,
                vehicles = vehicles,
                places = places,
                matchCount = visibleTimeline.count { it is TimelineEntry.RideEntry },
                onFilterChange = onFilterChange,
                onDismiss = { filtersOpen = false },
            )
        }
    }
    pendingExportRequests?.let { requests ->
        ExportFormatSheet(
            onFormatSelected = { format ->
                pendingExportRequests = null
                onExport(requests, format)
            },
            onDismiss = { pendingExportRequests = null },
        )
    }

    if (deleteConfirmationOpen) {
        ConfirmDestructiveDialog(
            title = stringResource(R.string.ride_delete_title),
            message = stringResource(R.string.ride_delete_message),
            onConfirm = {
                deleteOperationPending = true
                onDelete(
                    selectedRideEntries.flatMap { it.rideIds },
                    selectedRefuelRecords.map { it.id },
                )
            },
            onDismiss = { deleteConfirmationOpen = false },
        )
    }
}

@Composable
private fun DayHeader(text: String) {
    SectionTitle(text = text, modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp))
}
