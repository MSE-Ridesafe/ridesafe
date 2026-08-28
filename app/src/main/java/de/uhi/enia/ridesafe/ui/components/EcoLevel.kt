package de.uhi.enia.ridesafe.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R

/**
 * The eco level's shared rendering (ANL-03), used by the ride detail card and the dashboard's
 * aggregate — one place, so a level can never look different on two screens. Deliberately its own
 * visual language: three segments that fill, not a gauge and not a number, because the safety score
 * next door owns those and this is a coarser claim.
 */

private const val ECO_SEGMENTS = 3
private val EcoSegmentHeight = 14.dp

/**
 * The level as a segmented bar plus its label and coaching subtitle — the standard reading of an
 * eco level wherever one is shown.
 */
@Composable
fun EcoLevelDisplay(
    level: Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        EcoLevelBar(level = level)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = ecoLevelLabel(level),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = ecoLevelSubtitle(level),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The level as [ECO_SEGMENTS] equal segments, filled left to right. Filled segments use the theme's
 * primary — the scheme's own positive accent, so a Material You dynamic palette recolours it with
 * the rest of the app rather than fighting a hardcoded green.
 */
@Composable
fun EcoLevelBar(level: Int) {
    val description = stringResource(R.string.ride_eco_level_a11y, level, ECO_SEGMENTS)
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { contentDescription = description },
    ) {
        repeat(ECO_SEGMENTS) { index ->
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .height(EcoSegmentHeight)
                        .clip(CircleShape)
                        .background(
                            if (index < level) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                        ),
            )
        }
    }
}

@Composable
private fun ecoLevelLabel(level: Int): String =
    stringResource(
        when (level) {
            3 -> R.string.ride_eco_level_3
            2 -> R.string.ride_eco_level_2
            1 -> R.string.ride_eco_level_1
            else -> R.string.ride_eco_level_0
        },
    )

/** The coaching line under the label — encouraging at every level, since scolding closes the app. */
@Composable
private fun ecoLevelSubtitle(level: Int): String =
    stringResource(
        when (level) {
            3 -> R.string.ride_eco_sub_3
            2 -> R.string.ride_eco_sub_2
            1 -> R.string.ride_eco_sub_1
            else -> R.string.ride_eco_sub_0
        },
    )
