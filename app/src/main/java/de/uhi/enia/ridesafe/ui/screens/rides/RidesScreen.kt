@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package de.uhi.enia.ridesafe.ui.screens.rides

import android.net.Uri
import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.MergeCheck
import de.uhi.enia.ridesafe.data.canMerge
import de.uhi.enia.ridesafe.rides.processing.RideAnalysisProgress
import de.uhi.enia.ridesafe.rides.processing.shortAddress
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.util.currentUnitSystem
import de.uhi.enia.ridesafe.util.formatDayHeader
import de.uhi.enia.ridesafe.util.formatDistance
import de.uhi.enia.ridesafe.util.formatDuration
import de.uhi.enia.ridesafe.util.formatDurationMs
import de.uhi.enia.ridesafe.util.formatTimeOfDay
import de.uhi.enia.ridesafe.util.rideDay
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Currency
import java.util.Locale

@Composable
fun RidesScreen(
    timeline: List<TimelineEntry>,
    analysis: RideAnalysisProgress,
    exportState: RideExportState,
    onOpenRide: (Long) -> Unit,
    onOpenMerged: (Long) -> Unit,
    onOpenRefuel: (Long) -> Unit,
    onOpenAnalysisQueue: () -> Unit,
    onMerge: (List<Long>) -> Unit,
    onExport: (List<RideExportRequest>, RideExportFormat) -> Unit,
    onExportResultConsumed: () -> Unit,
    onAddRefuel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val exportSuccessMessage = stringResource(R.string.ride_export_success)
    val exportErrorMessage = stringResource(R.string.ride_export_error)
    val openFileLabel = stringResource(R.string.ride_export_notification_open)

    var selectionMode by rememberSaveable { mutableStateOf(false) }
    val entries = remember(timeline) { rideLogbookEntries(timeline) }
    var pendingExportRequests by remember { mutableStateOf<List<RideExportRequest>?>(null) }
    // Selection is by entry key; keys that no longer exist (data changed) are ignored below.
    var selectedKeys by
        rememberSaveable(
            stateSaver = listSaver(save = { it.toList() }, restore = { it.toSet() }),
        ) { mutableStateOf(emptySet<String>()) }

    val liveKeys = remember(timeline) { timelineSelectionKeys(timeline) }
    val selected = selectedKeys.intersect(liveKeys)
    val selectedRideEntries = remember(timeline, selected) { selectedRideLogbookEntries(timeline, selected) }

    fun exitSelection() {
        selectionMode = false
        selectedKeys = emptySet()
    }

    fun toggle(key: String) {
        selectedKeys = if (key in selected) selected - key else selected + key
    }

    LaunchedEffect(exportState) {
        when (exportState) {
            is RideExportState.Success -> {
                val saved =
                    SavedRideExport(
                        fileName = exportState.export.fileName,
                        uri = Uri.parse(exportState.export.contentUri),
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

            RideExportState.Idle, RideExportState.Exporting -> Unit
        }
    }

    // The status bar overlays the Scaffold rather than sitting in its bottomBar slot: that slot
    // reserves an opaque strip of the Scaffold's own container color, so the pill ends up on the
    // same background as everything else and reads as docked. Floating over the list — which keeps
    // scrolling behind it — is what makes it look like it is hovering.
    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            snackbarHost = { RideSnackbarHost(snackbarHostState) },
            topBar = {
                if (selectionMode) {
                    SelectionTopBar(
                        count = selected.size,
                        allSelected = timeline.isNotEmpty() && selected.size == timeline.size,
                        mergeCheck =
                            if (selected.isEmpty()) {
                                MergeCheck.NOT_ENOUGH
                            } else {
                                canMerge(
                                    selectedIds = selectedRideEntries.flatMap { it.rideIds }.toSet(),
                                    allRides = entries.flatMap { it.rides },
                                )
                            },
                        onExit = ::exitSelection,
                        onSelectAll = { selectedKeys = liveKeys },
                        onDeselectAll = { selectedKeys = emptySet() },
                        onMerge = {
                            onMerge(selectedRideEntries.flatMap { it.rideIds })
                            exitSelection()
                        },
                        exportEnabled = selectedRideEntries.isNotEmpty() && exportState != RideExportState.Exporting,
                        onExport = { pendingExportRequests = exportRequests(entries, selected) },
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
            if (timeline.isEmpty()) {
                EmptyRides(
                    modifier =
                        Modifier
                            .padding(innerPadding)
                            .fillMaxSize()
                            .padding(32.dp),
                )
                return@Scaffold
            }

            // One card per calendar day; entries arrive newest-first, so insertion order gives newest day
            // first, newest entry first within each day.
            val groups =
                remember(timeline) { timeline.groupByTo(LinkedHashMap()) { rideDay(it.sortEpochMs) } }
            val today = LocalDate.now()

            LazyColumn(
                modifier =
                    Modifier
                        .padding(innerPadding)
                        .fillMaxSize(),
                // Nothing reserves space for the floating status bar, so the list leaves room for it
                // itself — otherwise the last ride of the logbook can never be scrolled out from under it.
                contentPadding =
                    PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 16.dp,
                        bottom = if (analysis.running) 88.dp else 16.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                groups.forEach { (day, dayEntries) ->
                    item(key = "h$day") {
                        DayHeader(text = formatDayHeader(context, day, today))
                    }
                    item(key = "c$day") {
                        Card(
                            shape = MaterialTheme.shapes.extraLarge,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column {
                                dayEntries.forEachIndexed { index, timelineEntry ->
                                    if (index > 0) {
                                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                                    }
                                    when (timelineEntry) {
                                        is TimelineEntry.RideEntry -> {
                                            val entry = timelineEntry.entry
                                            LogbookRow(
                                                entry = entry,
                                                selectionMode = selectionMode,
                                                selected = entry.key in selected,
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
                                        }

                                        is TimelineEntry.RefuelEntry -> {
                                            RefuelTimelineRow(
                                                row = timelineEntry.row,
                                                selectionMode = selectionMode,
                                                selected = timelineEntry.stableKey in selected,
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
        AnalysisStatusBar(
            progress = analysis,
            onOpen = onOpenAnalysisQueue,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
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
}

@Composable
private fun ExportFormatSheet(
    onFormatSelected: (RideExportFormat) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var selectionInProgress by remember { mutableStateOf(false) }

    fun select(format: RideExportFormat) {
        if (selectionInProgress) return
        selectionInProgress = true
        scope.launch {
            sheetState.hide()
            onFormatSelected(format)
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!selectionInProgress) onDismiss() },
        sheetState = sheetState,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.ride_action_export),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                text = stringResource(R.string.ride_export_format_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, top = 4.dp, end = 24.dp, bottom = 8.dp),
            )
            ExportFormatOption(
                title = stringResource(R.string.ride_export_format_pdf),
                description = stringResource(R.string.ride_export_format_pdf_description),
                symbolName = "picture_as_pdf",
                enabled = !selectionInProgress,
                onClick = { select(RideExportFormat.PDF) },
            )
            HorizontalDivider(modifier = Modifier.padding(start = 72.dp, end = 16.dp))
            ExportFormatOption(
                title = stringResource(R.string.ride_export_format_csv),
                description = stringResource(R.string.ride_export_format_csv_description),
                symbolName = "table_view",
                enabled = !selectionInProgress,
                onClick = { select(RideExportFormat.CSV) },
            )
        }
    }
}

@Composable
private fun ExportFormatOption(
    title: String,
    description: String,
    symbolName: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        supportingContent = {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = {
            MaterialSymbol(
                symbolName = symbolName,
                contentDescription = null,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            MaterialSymbol(
                symbolName = "chevron_right",
                contentDescription = null,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

@Composable
private fun RideSnackbarHost(hostState: SnackbarHostState) {
    SnackbarHost(
        hostState = hostState,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    ) { data ->
        Snackbar(
            snackbarData = data,
            shape = MaterialTheme.shapes.medium,
            containerColor = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            actionColor = MaterialTheme.colorScheme.inversePrimary,
            dismissActionContentColor = MaterialTheme.colorScheme.inverseOnSurface,
        )
    }
}

@Composable
private fun SelectionTopBar(
    count: Int,
    allSelected: Boolean,
    mergeCheck: MergeCheck,
    onExit: () -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onMerge: () -> Unit,
    exportEnabled: Boolean,
    onExport: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    TopAppBar(
        title = { Text(stringResource(R.string.ride_selection_count, count)) },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        navigationIcon = {
            IconButton(onClick = onExit) {
                MaterialSymbol(symbolName = "close", contentDescription = stringResource(R.string.action_exit_selection))
            }
        },
        actions = {
            IconButton(onClick = if (allSelected) onDeselectAll else onSelectAll) {
                MaterialSymbol(
                    symbolName = "select_all",
                    contentDescription =
                        stringResource(if (allSelected) R.string.action_deselect_all else R.string.action_select_all),
                )
            }
            IconButton(onClick = { menuOpen = true }) {
                MaterialSymbol(symbolName = "more_vert", contentDescription = stringResource(R.string.action_more))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    enabled = mergeCheck == MergeCheck.OK,
                    onClick = {
                        menuOpen = false
                        onMerge()
                    },
                    text = {
                        Column {
                            Text(stringResource(R.string.ride_action_merge))
                            // When disabled, tell the user why merging isn't available right now (MRG-08).
                            mergeDisabledReason(mergeCheck)?.let { reason ->
                                Text(
                                    text = stringResource(reason),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    leadingIcon = { MaterialSymbol(symbolName = "merge", contentDescription = null) },
                )
                DropdownMenuItem(
                    enabled = exportEnabled,
                    onClick = {
                        menuOpen = false
                        onExport()
                    },
                    text = { Text(stringResource(R.string.ride_action_export)) },
                    leadingIcon = { MaterialSymbol(symbolName = "download", contentDescription = null) },
                )
            }
        },
    )
}

/** The reason string for a disabled "Merge rides" action, or null when merging is allowed (MRG-08). */
private fun mergeDisabledReason(check: MergeCheck): Int? =
    when (check) {
        MergeCheck.OK -> null
        MergeCheck.NOT_ENOUGH -> R.string.merge_reason_not_enough
        MergeCheck.MIXED_VEHICLE -> R.string.merge_reason_mixed_vehicle
        MergeCheck.NOT_CONTIGUOUS -> R.string.merge_reason_not_contiguous
    }

@Composable
private fun DayHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
    )
}

@Composable
private fun LogbookRow(
    entry: LogbookEntry,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val unitSystem = currentUnitSystem()
    val context = LocalContext.current

    val overline: String?
    val headline: String
    val supporting: String
    val icon: String
    when (entry) {
        is LogbookEntry.Single -> {
            val ride = entry.row.ride
            overline = entry.row.vehicleName
            // Prefer a matched saved place's label + icon (ADR-08), else the raw destination address.
            headline =
                entry.row.endPlace?.label
                    ?: ride.endAddress?.let { shortAddress(it) }
                    ?: stringResource(R.string.ride_address_unknown)
            supporting =
                listOfNotNull(
                    rideTimeRange(context, ride.startedAtEpochMs, ride.endedAtEpochMs),
                    formatDuration(ride.startedAtEpochMs, ride.endedAtEpochMs),
                ).joinToString("  •  ")
            icon = entry.row.endPlace?.icon ?: "route"
        }

        is LogbookEntry.Merged -> {
            val s = entry.summary
            // The merged trip's final destination = the newest stop's end (stops are oldest-first).
            val destPlace = entry.stops.last().endPlace
            val badge =
                stringResource(R.string.ride_merged_label) + " · " +
                    pluralStringResource(R.plurals.ride_stops_count, s.stopCount, s.stopCount)
            overline = listOfNotNull(entry.vehicleName, badge).joinToString(" · ")
            headline =
                destPlace?.label
                    ?: s.endAddress?.let { shortAddress(it) }
                    ?: stringResource(R.string.ride_address_unknown)
            supporting =
                listOfNotNull(
                    s.distanceMeters?.let { formatDistance(it, unitSystem) },
                    formatDurationMs(s.movingDurationMs),
                ).joinToString("  •  ")
            // Keep the "merge" icon so a merged trip stays visually distinct in the list.
            icon = "merge"
        }
    }

    ListItem(
        modifier =
            Modifier.combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            if (selectionMode) {
                SelectionCircle(selected = selected)
            } else {
                MaterialSymbol(symbolName = icon, contentDescription = null)
            }
        },
        overlineContent = overline?.let { { Text(it) } },
        headlineContent = {
            Text(
                headline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        supportingContent = { Text(supporting, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent =
            if (selectionMode) {
                null
            } else {
                {
                    MaterialSymbol(
                        symbolName = "chevron_right",
                        contentDescription = stringResource(R.string.ride_open),
                    )
                }
            },
    )
}

@Composable
private fun RefuelTimelineRow(
    row: RefuelRow,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
    val refuel = row.refuel
    val currency = runCatching { Currency.getInstance(refuel.currencyCode) }.getOrElse { defaultCurrency(locale) }
    val fractionDigits = currency.defaultFractionDigits.takeIf { it >= 0 } ?: 2
    val total = java.math.BigDecimal.valueOf(refuel.totalPriceMinor, fractionDigits)
    val currencyFormat = NumberFormat.getCurrencyInstance(locale).apply { this.currency = currency }
    val unitPrice =
        pricePerLiter(refuel.totalPriceMinor, refuel.fuelAmountMilliliters, fractionDigits)
            ?.let(currencyFormat::format)

    ListItem(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            if (selectionMode) {
                SelectionCircle(selected)
            } else {
                MaterialSymbol(symbolName = "local_gas_station", contentDescription = null)
            }
        },
        overlineContent = {
            Text(row.vehicleName ?: stringResource(R.string.refuel_unknown_vehicle))
        },
        headlineContent = {
            Text(
                refuel.stationAddress ?: stringResource(R.string.refuel_station_fallback),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        supportingContent = {
            Text(
                buildList {
                    add(formatTimeOfDay(context, refuel.timestampEpochMs))
                    add(currencyFormat.format(total))
                    unitPrice?.let { add(stringResource(R.string.refuel_price_per_liter_value, it)) }
                }.joinToString("  •  "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

/** The native Android selection indicator: a filled primary check when selected, an empty circle otherwise. */
@Composable
private fun SelectionCircle(selected: Boolean) {
    MaterialSymbol(
        symbolName = if (selected) "check_circle" else "radio_button_unchecked",
        contentDescription = stringResource(if (selected) R.string.ride_selected else R.string.ride_not_selected),
        fill = selected,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** "14:32 – 14:58" (or just the start while a ride is in progress). */
private fun rideTimeRange(
    context: android.content.Context,
    startMs: Long,
    endMs: Long?,
): String =
    buildString {
        append(formatTimeOfDay(context, startMs))
        endMs?.let { append(" – ").append(formatTimeOfDay(context, it)) }
    }

@Composable
private fun EmptyRides(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MaterialSymbol(
            symbolName = "route",
            contentDescription = null,
            size = 64.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(16.dp))
        Text(
            text = stringResource(R.string.rides_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = stringResource(R.string.rides_empty_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
