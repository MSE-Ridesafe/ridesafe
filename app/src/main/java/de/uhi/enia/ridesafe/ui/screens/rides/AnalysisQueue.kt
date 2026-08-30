@file:OptIn(ExperimentalMaterial3Api::class)

package de.uhi.enia.ridesafe.ui.screens.rides

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.Ride
import de.uhi.enia.ridesafe.rides.processing.RideAnalysisJob
import de.uhi.enia.ridesafe.rides.processing.RideAnalysisProgress
import de.uhi.enia.ridesafe.ui.components.BackNavIcon
import de.uhi.enia.ridesafe.ui.components.EmptyState
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.util.formatRideDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

// ponytail: presentation timings, tune by feel.
private const val RING_MS = 400 // catches the ring up to a new progress value
private const val DONE_HOLD_MS = 800L // how long a finished row shows its checkmark before leaving
private const val ROW_EXIT_MS = 400 // fade + collapse of a departing row
private const val BAR_MS = 300 // status bar slide in/out

/**
 * A ride's row in the queue. [done] rows are ones the pipeline has already dropped; the screen keeps
 * them around briefly so a finished ride is *seen* finishing rather than blinking out of existence.
 */
private data class QueueRow(
    val rideId: Long,
    val startedAtEpochMs: Long,
    val progress: Float,
    val done: Boolean,
)

/**
 * The queue as rows, with finished rides held back for [DONE_HOLD_MS] before they leave.
 *
 * The lingering lives here rather than in the pipeline: how long a completed row stays on screen is
 * a presentation decision, and the pipeline has no business holding work it has finished. Rows are
 * keyed by ride id, so a row that reappears (a later run picking the ride up again) replaces its
 * departing self instead of duplicating it.
 */
@Composable
private fun rememberQueueRows(progress: RideAnalysisProgress): List<QueueRow> {
    val live = progress.jobs
    val liveIds = live.map { it.rideId }
    // Rides the pipeline has dropped, kept on screen a moment longer so their completion is seen.
    val finished = remember { mutableStateMapOf<Long, QueueRow>() }
    val lastSeen = remember { mutableMapOf<Long, RideAnalysisJob>() }
    // Each hold is its own coroutine on the composition's scope, not on the effect below: the effect
    // restarts whenever the queue's membership changes, and a shared timer would be canceled by the
    // next ride finishing, stranding the previous row on screen forever.
    val scope = rememberCoroutineScope()

    LaunchedEffect(liveIds) {
        val ids = liveIds.toSet()
        lastSeen.values.filterNot { it.rideId in ids }.forEach { job ->
            finished[job.rideId] = QueueRow(job.rideId, job.startedAtEpochMs, 1f, done = true)
            scope.launch {
                delay(DONE_HOLD_MS.milliseconds)
                finished.remove(job.rideId)
            }
        }
        lastSeen.keys.retainAll(ids)
        live.forEach { lastSeen[it.rideId] = it }
    }

    // Rides actually being worked on float to the top, the rest stay in Logbook order underneath.
    // A row moves up when it *starts*, which is worth seeing, and a finished row counts as started
    // until it leaves — so nothing jumps at the moment it completes, which would be the jarring one.
    return (
        live.map { QueueRow(it.rideId, it.startedAtEpochMs, it.progress, done = false) } +
            finished.values.filterNot { row -> row.rideId in liveIds }
    ).sortedWith(
        compareBy<QueueRow> { if (it.done || it.progress > 0f) 0 else 1 }
            .thenByDescending { it.startedAtEpochMs },
    )
}

/**
 * Status bar for the Rides screen (ANL-03): tells the user analysis is running without them having
 * to go looking, and opens the queue. Slides itself away when there is nothing left to report.
 *
 * Lives in the Scaffold's bottom bar, so it stays put while the logbook scrolls and the list's own
 * insets keep the last ride clear of it. It collapses to nothing when there is no work, which is
 * what makes it safe to leave in the layout permanently.
 */
@Composable
fun AnalysisStatusBar(
    progress: RideAnalysisProgress,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = progress.running,
        enter = slideInVertically(tween(BAR_MS)) { it } + fadeIn(tween(BAR_MS)),
        exit = slideOutVertically(tween(BAR_MS)) { it } + fadeOut(tween(BAR_MS)),
        modifier = modifier,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpen),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Counts rides, matching the label beside it — the fine-grained per-ride progress is
                // one screen deeper, where there is room to show it per ride.
                ProgressRing(
                    fraction = if (progress.total == 0) 0f else progress.completed.toFloat() / progress.total,
                    size = 24.dp,
                    spinAtZero = false,
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = stringResource(R.string.analysis_status_title),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.analysis_status_count, progress.completed, progress.total),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.width(8.dp))
                MaterialSymbol(
                    symbolName = "chevron_right",
                    contentDescription = stringResource(R.string.analysis_status_open),
                    size = 20.dp,
                )
            }
        }
    }
}

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
    val start = ride.startAddress?.lineOne()
    val end = ride.endAddress?.lineOne()
    return when {
        start != null && end != null -> "$start → $end"
        else -> start ?: end
    }
}

/** Addresses are stored newline-separated (street, then ZIP+city); the first line is the useful bit. */
private fun String.lineOne(): String? = lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }

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

/**
 * A determinate ring that eases towards each new value, so byte-level progress reads as motion
 * rather than a series of jumps.
 *
 * At zero, it spins instead, because a ride that hasn't been picked up yet has no progress to report
 * and a frozen empty ring reads as broken. Pass [spinAtZero] false where zero is a real measurement
 * rather than an absence — the overall "0 of 69" is genuinely 0%, and known to be.
 */
@Composable
fun ProgressRing(
    fraction: Float,
    size: Dp,
    modifier: Modifier = Modifier,
    spinAtZero: Boolean = true,
) {
    if (fraction <= 0f && spinAtZero) {
        CircularProgressIndicator(
            modifier = modifier.size(size),
            strokeWidth = size / 10,
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        return
    }
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(RING_MS),
        label = "analysisProgress",
    )
    CircularProgressIndicator(
        progress = { animated },
        modifier = modifier.size(size),
        strokeWidth = size / 10,
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    )
}

/**
 * Shown on a ride's detail while that ride is still queued (ANL-03). Without it a half-analyzed
 * ride reads as a broken one — no distance, no events, no explanation — so this says what is
 * missing and how far along the work is.
 */
@Composable
fun AnalysisNoticeCard(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProgressRing(fraction = progress, size = 28.dp)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.analysis_detail_notice_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.analysis_detail_notice_message),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
