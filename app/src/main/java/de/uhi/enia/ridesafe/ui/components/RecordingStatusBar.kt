package de.uhi.enia.ridesafe.ui.components

import android.os.SystemClock
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.Vehicle
import de.uhi.enia.ridesafe.rides.recording.RecordingStatus
import de.uhi.enia.ridesafe.rides.recording.RideRecordingService
import de.uhi.enia.ridesafe.ui.screens.garage.displayTitle
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

private const val BAR_MS = 250

/**
 * How much room the bar wants above whatever it floats over — its own height plus the gap. A
 * constant rather than a measurement: nothing lays out around this bar, the other floating
 * overlays just step up over it (see the Rides screen's analysis bar).
 */
val RECORDING_BAR_INSET = 80.dp

/**
 * The ride recording right now, floating over whichever tab the user is on (TRK-05): the clock
 * keeps running while they browse the logbook or edit a vehicle, and stop is always one tap away.
 * Hosted by the app shell, above the navigation bar, so it costs no screen its own layout.
 *
 * State comes from [RecordingStatus] — the engine's live flag — rather than from the ride's
 * database row, which is deliberately incomplete until the ride is finalized. The clock ticks off
 * [SystemClock.elapsedRealtimeNanos] for the same reason the engine records it: it is the one
 * timebase a clock change can't move under a running ride.
 */
@Composable
fun RecordingStatusBar(
    vehicles: List<Vehicle>,
    modifier: Modifier = Modifier,
) {
    val running by RecordingStatus.running.collectAsState()
    // The ride is gone by the time the bar slides out, so its last state is kept to animate with.
    var last by remember { mutableStateOf(running) }
    running?.let { last = it }
    var elapsedMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(running?.startedElapsedNanos) {
        val startedAt = running?.startedElapsedNanos ?: return@LaunchedEffect
        while (true) {
            elapsedMs = (SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000
            delay(1_000.milliseconds) // ponytail: 1 Hz, the coarsest tick a seconds display can have
        }
    }

    AnimatedVisibility(
        visible = running != null,
        enter = slideInVertically(tween(BAR_MS)) { it } + fadeIn(tween(BAR_MS)),
        exit = slideOutVertically(tween(BAR_MS)) { it } + fadeOut(tween(BAR_MS)),
        modifier = modifier,
    ) {
        val vehicleName = last?.vehicleId?.let { id -> vehicles.firstOrNull { it.id == id }?.displayTitle() }
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(start = 20.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MaterialSymbol(symbolName = "radio_button_checked", contentDescription = null, fill = true)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.recording_active_ride),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = vehicleName ?: stringResource(R.string.home_unknown_vehicle),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = DateUtils.formatElapsedTime((elapsedMs / 1_000).coerceAtLeast(0)),
                    style = MaterialTheme.typography.titleMedium,
                )
                val context = LocalContext.current
                IconButton(onClick = { RideRecordingService.stop(context, manual = true) }) {
                    MaterialSymbol(
                        symbolName = "stop_circle",
                        contentDescription = stringResource(R.string.home_record_stop),
                        fill = true,
                    )
                }
            }
        }
    }
}
