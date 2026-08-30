@file:OptIn(ExperimentalMaterial3Api::class)

package de.uhi.enia.ridesafe.ui.screens.rides

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.Ride
import de.uhi.enia.ridesafe.rides.processing.RideAnalysisProgress
import de.uhi.enia.ridesafe.rides.processing.addressLines
import de.uhi.enia.ridesafe.ui.components.BackNavIcon
import de.uhi.enia.ridesafe.ui.components.EmptyState
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.ui.components.ProgressRing
import de.uhi.enia.ridesafe.util.formatRideDateTime

private const val ROW_EXIT_MS = 400 // fade + collapse of a departing row — presentation timing, tune by feel

/**
 * The analysis queue (ANL-03): every ride the pipeline is working through, each with its own
 * progress, rows leaving as they finish.
 *
 * Deliberately does *not* navigate away when the queue empties — a screen that closes itself under
 * the user is disorienting. It shows an empty state instead and waits to be dismissed.
 */
@Composable
fun AnalysisQueueScreen(
    modifier: Modifier = Modifier,
    progress: RideAnalysisProgress,
    rides: Map<Long, Ride>,
    onBack: () -> Unit,
    showBack: Boolean = true,
) {
    val context = LocalContext.current
    val rows = rememberQueueRows(progress)
    val listState = rememberLazyListState()

    // Rides finish at the top of the queue, and removing a row there leaves the list resting
    // part-way through the new first one — a card sliced off under the app bar that nobody scrolled
    // to. Only corrected when the user is already at the top; if they scrolled down to look at
    // something, their position is theirs.
    LaunchedEffect(rows.size) {
        if (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset > 0) {
            listState.animateScrollToItem(0)
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.analysis_queue_title))
                        if (progress.total > 0) {
                            Text(
                                text =
                                    stringResource(
                                        R.string.analysis_queue_subtitle,
                                        progress.completed,
                                        progress.total,
                                    ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                navigationIcon = { BackNavIcon(onBack = onBack, showBack = showBack) },
            )
        },
    ) { innerPadding ->
        if (rows.isEmpty()) {
            EmptyState(
                symbolName = "check_circle",
                title = stringResource(R.string.analysis_queue_empty_title),
                message = stringResource(R.string.analysis_queue_empty_message),
                iconColor = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier
                        .padding(innerPadding)
                        .fillMaxSize()
                        .padding(32.dp),
            )
            return@Scaffold
        }
        LazyColumn(
            state = listState,
            modifier =
                Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    // A row that is leaving keeps being drawn while it fades, and a lazy list does
                    // not confine that to its own box — at the top of the queue, where rows finish,
                    // it would fade out across the app bar. Clipping keeps it inside the list.
                    .clipToBounds(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(rows, key = { it.rideId }) { row ->
                QueueCard(
                    row = row,
                    label =
                        rides[row.rideId]?.let { formatRideDateTime(context, it.startedAtEpochMs) }
                            ?: formatRideDateTime(context, row.startedAtEpochMs),
                    place = rides[row.rideId]?.let { rideEndpointLabel(it) },
                    modifier = Modifier.animateItem(fadeOutSpec = tween(ROW_EXIT_MS)),
                )
            }
        }
    }
}

/** "Start → End" from whichever endpoint addresses the ride has; null when it has neither. */
private fun rideEndpointLabel(ride: Ride): String? {
    val start = ride.startAddress?.let { addressLines(it).first }
    val end = ride.endAddress?.let { addressLines(it).first }
    return when {
        start != null && end != null -> "$start → $end"
        else -> start ?: end
    }
}

@Composable
private fun QueueCard(
    row: QueueRow,
    label: String,
    place: String?,
    modifier: Modifier = Modifier,
) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(36.dp)) {
                if (row.done) {
                    MaterialSymbol(
                        symbolName = "check_circle",
                        contentDescription = null,
                        fill = true,
                        size = 28.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    ProgressRing(fraction = row.progress, size = 32.dp)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (place != null) {
                    Text(
                        text = place,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text =
                    when {
                        row.done -> stringResource(R.string.analysis_queue_done)
                        row.progress <= 0f -> stringResource(R.string.analysis_queue_waiting)
                        else -> "${(row.progress * 100).toInt()}%"
                    },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
