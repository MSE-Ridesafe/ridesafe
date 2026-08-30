@file:OptIn(ExperimentalMaterial3Api::class)

package de.uhi.enia.ridesafe.ui.screens.rides

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.LatLng
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.Ride
import de.uhi.enia.ridesafe.data.RideEvent
import de.uhi.enia.ridesafe.data.canToggleStop
import de.uhi.enia.ridesafe.data.canUnmergeSelection
import de.uhi.enia.ridesafe.data.summarizeMerge
import de.uhi.enia.ridesafe.domain.safetyScoreForRides
import de.uhi.enia.ridesafe.rides.processing.shortAddress
import de.uhi.enia.ridesafe.ui.components.CardDivider
import de.uhi.enia.ridesafe.ui.components.DetailScaffold
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.ui.components.SafetyScoreCard
import de.uhi.enia.ridesafe.ui.components.SectionTitle
import de.uhi.enia.ridesafe.util.currentUnitSystem
import de.uhi.enia.ridesafe.util.formatDistance
import de.uhi.enia.ridesafe.util.formatDurationMs
import de.uhi.enia.ridesafe.util.formatRideDateTime
import de.uhi.enia.ridesafe.util.formatSpeed
import de.uhi.enia.ridesafe.util.formatTimeOfDay

/**
 * Detail of a merged ride (§3.8): the stops' routes drawn as disconnected polylines (MRG-07), the
 * aggregated metrics (MRG-05), and the list of stops with un-merge controls (MRG-04, MRG-11). [stops]
 * is chronological; [segments] holds one route per stop (null while loading). Un-merging happens via
 * [onUnmerge] (peel selected stops) and [onUnmergeAll]; the screen pops itself once fewer than two
 * stops remain, since the merged ride no longer exists.
 */
@Composable
fun MergedRideDetailScreen(
    modifier: Modifier = Modifier,
    stops: List<Ride>?,
    segments: List<List<LatLng>>?,
    rideEvents: List<RideEvent>,
    refuels: List<RefuelRow>,
    onOpenRefuel: (Long) -> Unit,
    onDetachRefuel: (Long) -> Unit,
    onBack: () -> Unit,
    onUnmergeAll: () -> Unit,
    onUnmerge: (stopIds: List<Long>) -> Unit,
    showBack: Boolean = true,
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
        DetailScaffold(title = {}, onBack = onBack, showBack = showBack, modifier = modifier) {}
        return
    }

    val summary = remember(stops) { summarizeMerge(stops) }
    DetailScaffold(
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
        onBack = onBack,
        showBack = showBack,
        modifier = modifier,
    ) {
        // The trip in numbers, same headline readout as a single ride's (duration = moving time).
        RideStatsReadout(
            distance =
                summary.distanceMeters?.let { formatDistance(it, unitSystem) }
                    ?: stringResource(R.string.value_not_set),
            duration = formatDurationMs(summary.movingDurationMs),
            avgSpeed = summary.avgSpeedMps?.let { formatSpeed(context, it, unitSystem) },
            maxSpeed = formatSpeed(context, summary.maxSpeedMps, unitSystem),
        )

        RouteMapCard(segments = segments, rideEvents = rideEvents)

        MergedJourneyCard(
            stops = stops,
            refuels = refuels,
            onOpenRefuel = onOpenRefuel,
            onDetachRefuel = onDetachRefuel,
            onUnmergeAll = onUnmergeAll,
            onUnmerge = onUnmerge,
        )

        // The whole trip's score: the stops' penalties and exposure summed, mapped once — never
        // an average of their scores (see SafetyScoreWindows). Hidden when no stop was scoreable.
        safetyScoreForRides(stops)?.let { SafetyScoreCard(score = it) }

        // The trip's efficiency, same card as a single ride's — the aggregates add up across
        // stops and the level is derived once from the whole trip's driving (MRG-05 rule).
        summary.eco?.let { EcoCard(eco = it) }
    }
}

/**
 * The journey card: one card doing both jobs (§3.8). In its default view it shows the trip as a places
 * timeline — origin, each parked waypoint (with its arrival time and a "left … · parked …" note), then
 * the destination (MRG-07: the boundary is one place, not a drawn connection). The header's "Manage
 * stops" toggle flips it to un-merge mode (MRG-04, MRG-11): the legs get end-only selection checkboxes
 * plus "Unmerge all" / "Unmerge (n)", and each refuel gets a detach button that only breaks that one
 * anchor. For a two-stop merge the per-stop checkboxes are omitted since peeling one stop dissolves
 * the pair anyway — only "Unmerge all" is offered.
 */
@Composable
private fun MergedJourneyCard(
    stops: List<Ride>,
    refuels: List<RefuelRow>,
    onOpenRefuel: (Long) -> Unit,
    onDetachRefuel: (Long) -> Unit,
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
                SectionTitle(
                    text = stringResource(R.string.ride_journey_section),
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

            // The journey reads the same in both modes, so it is one list either way; manage mode
            // just adds the end-only peel checkboxes (MRG-11) and a per-refuel detach.
            if (managing && selectable) {
                Text(
                    text = stringResource(R.string.ride_merged_unmerge_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            combinedJourneyChildren(stops, refuels).forEachIndexed { childIndex, child ->
                if (childIndex > 0) {
                    CardDivider()
                }
                when (child) {
                    is CombinedJourneyChild.RideChild -> {
                        val ride = child.ride
                        val index = stops.indexOfFirst { it.id == ride.id }
                        val canToggle = canToggleStop(index, selected, n)
                        StopRow(
                            label = stringResource(R.string.ride_stop_label, index + 1),
                            destination =
                                ride.endAddress?.let { shortAddress(it) }
                                    ?: stringResource(R.string.ride_address_unknown),
                            supporting =
                                buildString {
                                    append(formatTimeOfDay(context, ride.startedAtEpochMs))
                                    ride.endedAtEpochMs?.let { append(" – ").append(formatTimeOfDay(context, it)) }
                                    ride.distanceMeters?.let { append("  •  ").append(formatDistance(it, unitSystem)) }
                                },
                            showCheckbox = managing && selectable,
                            checked = index in selected,
                            checkboxEnabled = canToggle,
                            onToggle = {
                                if (canToggle) selected = if (index in selected) selected - index else selected + index
                            },
                        )
                    }

                    is CombinedJourneyChild.RefuelChild -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.weight(1f)) {
                                RefuelTimelineRow(
                                    row = child.row,
                                    selectionMode = false,
                                    selected = false,
                                    onClick = { onOpenRefuel(child.row.refuel.id) },
                                    onLongClick = {},
                                    showVehicle = false,
                                )
                            }
                            // A refuel is anchored to a stop, not to the merge: detaching one leaves
                            // the trip merged instead of forcing an un-merge to get rid of it.
                            if (managing) {
                                IconButton(onClick = { onDetachRefuel(child.row.refuel.id) }) {
                                    MaterialSymbol(
                                        symbolName = "link_off",
                                        contentDescription = stringResource(R.string.ride_action_detach_refuel),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (!managing) return@Column

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
