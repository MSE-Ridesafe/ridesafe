package de.uhi.enia.ridesafe.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

// ponytail: presentation timing, tune by feel.
private const val RING_MS = 400 // catches the ring up to a new progress value

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
