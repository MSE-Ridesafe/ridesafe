package de.uhi.enia.ridesafe.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.util.UnitSystemSetting
import de.uhi.enia.ridesafe.util.formatDistance
import java.time.format.TextStyle

@Composable
fun HighlightsCard(
    highlights: HomeHighlights,
    unitSystem: UnitSystemSetting,
) {
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HighlightRow(
                icon = "emoji_events",
                label = stringResource(R.string.home_highlight_longest_ride),
                value =
                    highlights.longestRideMeters?.let {
                        formatDistance(it, unitSystem)
                    } ?: stringResource(R.string.value_not_set),
            )
            HighlightRow(
                icon = "directions_car",
                label = stringResource(R.string.home_highlight_average_ride),
                value =
                    highlights.averageRideMeters?.let {
                        formatDistance(it, unitSystem)
                    } ?: stringResource(R.string.value_not_set),
            )
            HighlightRow(
                icon = "calendar_month",
                label = stringResource(R.string.home_highlight_most_active_day),
                value =
                    highlights.mostActiveDay
                        ?.getDisplayName(TextStyle.FULL, locale)
                        ?: stringResource(R.string.value_not_set),
            )
        }
    }
}

@Composable
private fun HighlightRow(
    icon: String,
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MaterialSymbol(
            symbolName = icon,
            contentDescription = null,
            color = MaterialTheme.colorScheme.primary,
            size = 24.dp,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
        )
    }
}
