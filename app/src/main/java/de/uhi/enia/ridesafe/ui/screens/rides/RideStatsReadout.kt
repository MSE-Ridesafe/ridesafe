package de.uhi.enia.ridesafe.ui.screens.rides

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol

/**
 * The ride's numbers as a chromeless typographic readout — deliberately the only un-carded content
 * on the detail screens, so the trip's magnitude reads as the headline rather than one more box in
 * the card stack: distance as the hero line, duration under it, the two speeds as a quiet third
 * line. Each stat wears the dashboard cards' anatomy — icon with its label beside it, the value
 * beneath — so detail and dashboard speak one visual language; the per-line sizes and colors keep
 * the three-tier hierarchy.
 */
@Composable
internal fun RideStatsReadout(
    distance: String,
    duration: String?,
    avgSpeed: String?,
    maxSpeed: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatLine(
            icon = "route",
            iconSize = 28.dp,
            iconColor = MaterialTheme.colorScheme.primary,
            value = distance,
            style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.SemiBold),
            label = stringResource(R.string.ride_detail_section_distance),
        )
        duration?.let {
            StatLine(
                icon = "schedule",
                iconSize = 22.dp,
                iconColor = MaterialTheme.colorScheme.primary,
                value = it,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Medium),
                label = stringResource(R.string.ride_detail_duration),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            val speedStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
            avgSpeed?.let {
                StatLine(
                    icon = "avg_pace",
                    iconSize = 18.dp,
                    iconColor = muted,
                    value = it,
                    style = speedStyle,
                    label = stringResource(R.string.ride_stat_avg_speed),
                    valueColor = muted,
                )
            }
            StatLine(
                icon = "speed",
                iconSize = 18.dp,
                iconColor = muted,
                value = maxSpeed,
                style = speedStyle,
                label = stringResource(R.string.ride_stat_max_speed),
                valueColor = muted,
            )
        }
    }
}

@Composable
private fun StatLine(
    icon: String,
    iconSize: Dp,
    iconColor: Color,
    value: String,
    style: TextStyle,
    label: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val description = "$label: $value"
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.clearAndSetSemantics { contentDescription = description },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MaterialSymbol(
                symbolName = icon,
                contentDescription = null,
                size = iconSize,
                color = iconColor,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(lineHeight = 18.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Text(
            text = value,
            style = style,
            color = valueColor,
            maxLines = 1,
        )
    }
}
