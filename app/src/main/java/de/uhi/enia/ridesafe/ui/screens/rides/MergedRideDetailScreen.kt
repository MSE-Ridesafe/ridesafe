@file:OptIn(ExperimentalMaterial3Api::class)

package de.uhi.enia.ridesafe.ui.screens.rides

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.LatLng
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.Ride
import de.uhi.enia.ridesafe.data.RideEvent
import de.uhi.enia.ridesafe.data.Vehicle
import de.uhi.enia.ridesafe.data.canToggleStop
import de.uhi.enia.ridesafe.data.canUnmergeSelection
import de.uhi.enia.ridesafe.data.summarizeMerge
import de.uhi.enia.ridesafe.rides.processing.forVehicle
import de.uhi.enia.ridesafe.rides.processing.shortAddress
import de.uhi.enia.ridesafe.ui.components.DetailCard
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.util.currentUnitSystem
import de.uhi.enia.ridesafe.util.formatDistance
import de.uhi.enia.ridesafe.util.formatDurationMs
import de.uhi.enia.ridesafe.util.formatRideDateTime
import de.uhi.enia.ridesafe.util.formatSpeed
import de.uhi.enia.ridesafe.util.formatTimeOfDay

/**
 * Detail of a merged ride (§3.8): the stops' routes drawn as disconnected polylines (MRG-07), the
 * aggregated metrics (MRG-05), and the list of stops with un-merge controls (MRG-04, MRG-11). [stops]
 * is chronological; [segments] holds one route per stop (null while loading); [vehicle] is the car
 * every stop shares (MRG-09), which the fuel estimate is scaled onto on read (ANL-03). Un-merging happens via
 * [onUnmerge] (peel selected stops) and [onUnmergeAll]; the screen pops itself once fewer than two
 * stops remain, since the merged ride no longer exists.
 */
@Composable
fun MergedRideDetailScreen(
    stops: List<Ride>?,
    segments: List<List<LatLng>>?,
    rideEvents: List<RideEvent>,
    vehicle: Vehicle?,
    onBack: () -> Unit,
    onUnmergeAll: () -> Unit,
    onUnmerge: (stopIds: List<Long>) -> Unit,
    showBack: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val unitSystem = currentUnitSystem()
    val context = LocalContext.current

    // Group gone (fully un-merged, here or elsewhere, down to one stop): leave the now-defunct merged
    // view. Null = still loading, so don't treat it as "gone".
    LaunchedEffect(stops?.size) {
        if (stops != null && stops.size < 2) onBack()
    }
    if (stops == null || stops.size < 2) {
        // Loading (or transitioning out): just the top bar with a back affordance, empty body.
        Scaffold(
            modifier = modifier,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            topBar = {
                TopAppBar(
                    title = {},
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    navigationIcon = {
                        if (showBack) IconButton(onClick = onBack) {
                            MaterialSymbol(symbolName = "arrow_back", contentDescription = stringResource(R.string.action_back))
                        }
                    },
                )
            },
        ) { innerPadding ->
            Spacer(Modifier.padding(innerPadding))
        }
        return
    }

    val summary = remember(stops) { summarizeMerge(stops) }
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(formatRideDateTime(context, summary.startEpochMs))
                        Text(
                            text =
                                stringResource(R.string.ride_merged_label) + " · " +
                                    pluralStringResource(R.plurals.ride_stops_count, summary.stopCount, summary.stopCount),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                navigationIcon = {
                    if (showBack) IconButton(onClick = onBack) {
                        MaterialSymbol(
                            symbolName = "arrow_back",
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RouteMapCard(segments = segments, rideEvents = rideEvents)

            MergedJourneyCard(
                stops = stops,
                onUnmergeAll = onUnmergeAll,
                onUnmerge = onUnmerge,
            )

            DetailCard(
                title = stringResource(R.string.ride_detail_section_summary),
                rows =
                    listOf(
                        stringResource(R.string.ride_detail_total_distance) to
                            (
                                summary.distanceMeters?.let { formatDistance(it, unitSystem) }
                                    ?: stringResource(R.string.value_not_set)
                            ),
                        stringResource(R.string.ride_detail_duration) to formatDurationMs(summary.movingDurationMs),
                    ),
            )

            DetailCard(
                title = stringResource(R.string.ride_detail_section_speed),
                rows =
                    listOfNotNull(
                        stringResource(R.string.ride_detail_max_speed) to formatSpeed(context, summary.maxSpeedMps, unitSystem),
                        summary.avgSpeedMps?.let {
                            stringResource(R.string.ride_detail_avg_speed) to formatSpeed(context, it, unitSystem)
                        },
                    ),
            )

            // The trip's fuel, same card as a single ride's — the buckets add up across stops, so the
            // breakdown covers the whole trip rather than whichever leg the user happens to open.
            summary.fuel.forVehicle(vehicle)?.let { fuel ->
                FuelCard(
                    fuel = fuel,
                    distanceMeters = summary.distanceMeters,
                    calibrated = vehicle?.fuelEconomy != null,
                )
            }
        }
    }
}

/**
 * The journey card: one card doing both jobs (§3.8). In its default view it shows the trip as a places
 * timeline — origin, each parked waypoint (with its arrival time and a "left … · parked …" note), then
 * the destination (MRG-07: the boundary is one place, not a drawn connection). The header's "Manage
 * stops" toggle flips it to un-merge mode (MRG-04, MRG-11): the legs get end-only selection checkboxes
 * plus "Unmerge all" / "Unmerge (n)". For a two-stop merge the per-stop checkboxes are omitted since
 * peeling one stop dissolves the pair anyway — only "Unmerge all" is offered.
 */
@Composable
private fun MergedJourneyCard(
    stops: List<Ride>,
    onUnmergeAll: () -> Unit,
    onUnmerge: (stopIds: List<Long>) -> Unit,
) {
    val unitSystem = currentUnitSystem()
    val context = LocalContext.current
    val n = stops.size
    val selectable = n >= 3 // for two stops, peeling one == unmerging both, so offer only "Unmerge all"

    var managing by remember(stops) { mutableStateOf(false) }
    var selected by remember(stops) { mutableStateOf(emptySet<Int>()) }

    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            // Header: title + the view/manage toggle. Top-aligned so the small title keeps the same
            // vertical position as the sibling summary/speed card titles instead of being centered in
            // the taller button's row.
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = stringResource(R.string.ride_journey_section),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f).padding(start = 20.dp, top = 8.dp, bottom = 8.dp),
                )
                if (managing) {
                    TextButton(onClick = {
                        managing = false
                        selected = emptySet()
                    }) {
                        Text(stringResource(R.string.action_done))
                    }
                } else {
                    TextButton(onClick = { managing = true }) {
                        MaterialSymbol(symbolName = "edit", contentDescription = null, size = 18.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.ride_action_manage_stops))
                    }
                }
            }

            if (!managing) {
                JourneyTimeline(
                    stops = buildPlaces(context, stops),
                    duration = null,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                return@Column
            }

            // Manage mode: the legs, peelable from the ends only (MRG-11).
            if (selectable) {
                Text(
                    text = stringResource(R.string.ride_merged_unmerge_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            stops.forEachIndexed { index, ride ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                val canToggle = canToggleStop(index, selected, n)
                StopRow(
                    label = stringResource(R.string.ride_stop_label, index + 1),
                    destination = ride.endAddress?.let { shortAddress(it) } ?: stringResource(R.string.ride_address_unknown),
                    supporting =
                        buildString {
                            append(formatTimeOfDay(context, ride.startedAtEpochMs))
                            ride.endedAtEpochMs?.let { append(" – ").append(formatTimeOfDay(context, it)) }
                            ride.distanceMeters?.let { append("  •  ").append(formatDistance(it, unitSystem)) }
                        },
                    showCheckbox = selectable,
                    checked = index in selected,
                    checkboxEnabled = canToggle,
                    onToggle = { if (canToggle) selected = if (index in selected) selected - index else selected + index },
                )
            }
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = onUnmergeAll) {
                    Text(stringResource(R.string.ride_action_unmerge_all))
                }
                if (selectable) {
                    Button(
                        onClick = { onUnmerge(selected.map { stops[it].id }) },
                        enabled = canUnmergeSelection(selected, n),
                    ) {
                        Text(stringResource(R.string.ride_action_unmerge_selected, selected.size))
                    }
                }
            }
        }
    }
}

/**
 * The places timeline for a merged ride (N+1 waypoints for N legs): origin, one waypoint per parked
 * boundary, then the destination. A boundary waypoint is labeled with the more reliable next-leg
 * start address (falling back to the previous leg's end), its arrival time, and a "left … · parked …"
 * note — collapsing the two "unrelated" fixes into one place for a readable trip (MRG-07).
 */
private fun buildPlaces(
    context: android.content.Context,
    stops: List<Ride>,
): List<JourneyStop> =
    buildList {
        val first = stops.first()
        add(
            JourneyStop(
                address = first.startAddress,
                time =
                    formatTimeOfDay(
                        context,
                        first.startedAtEpochMs,
                    ),
            ),
        )
        for (i in 0 until stops.size - 1) {
            val arrive = stops[i]
            val depart = stops[i + 1]
            val note =
                arrive.endedAtEpochMs?.let { arrivedMs ->
                    context.getString(
                        R.string.ride_merged_parked_note,
                        formatTimeOfDay(context, depart.startedAtEpochMs),
                        formatDurationMs(depart.startedAtEpochMs - arrivedMs),
                    )
                }
            add(
                JourneyStop(
                    address = depart.startAddress ?: arrive.endAddress,
                    time = arrive.endedAtEpochMs?.let { formatTimeOfDay(context, it) },
                    note = note,
                ),
            )
        }
        val last = stops.last()
        add(JourneyStop(address = last.endAddress, time = last.endedAtEpochMs?.let { formatTimeOfDay(context, it) }))
    }

@Composable
private fun StopRow(
    label: String,
    destination: String,
    supporting: String,
    showCheckbox: Boolean,
    checked: Boolean,
    checkboxEnabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(if (showCheckbox && checkboxEnabled) Modifier.clickable(onClick = onToggle) else Modifier)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MaterialSymbol(
            symbolName = "trip_origin",
            contentDescription = null,
            size = 18.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = destination,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showCheckbox) {
            Spacer(Modifier.width(8.dp))
            Checkbox(
                checked = checked,
                onCheckedChange = { if (checkboxEnabled) onToggle() },
                enabled = checkboxEnabled,
            )
        }
    }
}
