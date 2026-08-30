package de.uhi.enia.ridesafe.ui.screens.rides

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import de.uhi.enia.ridesafe.rides.processing.RideAnalysisJob
import de.uhi.enia.ridesafe.rides.processing.RideAnalysisProgress
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

// ponytail: presentation timing, tune by feel.
private const val DONE_HOLD_MS = 800L // how long a finished row shows its checkmark before leaving

/**
 * A ride's row in the queue. [done] rows are ones the pipeline has already dropped; the screen keeps
 * them around briefly so a finished ride is *seen* finishing rather than blinking out of existence.
 */
internal data class QueueRow(
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
internal fun rememberQueueRows(progress: RideAnalysisProgress): List<QueueRow> {
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
