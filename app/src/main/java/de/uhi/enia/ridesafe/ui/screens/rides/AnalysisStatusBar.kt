package de.uhi.enia.ridesafe.ui.screens.rides

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.rides.processing.RideAnalysisProgress
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.ui.components.ProgressRing

private const val BAR_MS = 300 // status bar slide in/out — a presentation timing, tune by feel

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
