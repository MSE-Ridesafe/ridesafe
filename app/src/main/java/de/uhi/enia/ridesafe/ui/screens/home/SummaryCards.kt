package de.uhi.enia.ridesafe.ui.screens.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.util.UnitSystemSetting
import de.uhi.enia.ridesafe.util.formatDistance

@Composable
fun MonthlyStats(
    distanceMeters: Double,
    durationMillis: Long,
    unitSystem: UnitSystemSetting,
) {
    val context = LocalContext.current
    val useColumns = LocalConfiguration.current.screenWidthDp >= 360
    if (useColumns) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            StatCard(
                icon = "route",
                label = stringResource(R.string.home_total_distance),
                value = formatDistance(context, distanceMeters, unitSystem),
                modifier = Modifier.weight(1f),
            )
            StatCard(
                icon = "schedule",
                label = stringResource(R.string.home_total_travel_time),
                value = formatCompactDuration(durationMillis),
                modifier = Modifier.weight(1f),
            )
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
            StatCard(
                icon = "route",
                label = stringResource(R.string.home_total_distance),
                value = formatDistance(context, distanceMeters, unitSystem),
                modifier = Modifier.fillMaxWidth(),
            )
            StatCard(
                icon = "schedule",
                label = stringResource(R.string.home_total_travel_time),
                value = formatCompactDuration(durationMillis),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun StatCard(
    icon: String,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        modifier = modifier.heightIn(min = 156.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MaterialSymbol(
                    symbolName = icon,
                    contentDescription = null,
                    color = MaterialTheme.colorScheme.primary,
                    size = 24.dp,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge.copy(lineHeight = 18.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    softWrap = true,
                    modifier = Modifier.weight(1f),
                )
            }
            AnimatedPrimaryValue(value = value)
        }
    }
}

@Composable
private fun AnimatedPrimaryValue(value: String) {
    AnimatedContent(
        targetState = value,
        transitionSpec = {
            (slideInVertically(tween(250)) { it / 3 } + fadeIn(tween(250))) togetherWith
                (slideOutVertically(tween(250)) { -it / 3 } + fadeOut(tween(250)))
        },
        label = "dashboard_primary_value",
        modifier = Modifier.fillMaxWidth(),
    ) { targetValue ->
        val fontSize =
            when {
                targetValue.length >= 14 -> 22.sp
                targetValue.length >= 11 -> 25.sp
                targetValue.length >= 9 -> 28.sp
                else -> 30.sp
            }
        Text(
            text = targetValue,
            style =
                MaterialTheme.typography.displaySmall.copy(
                    fontSize = fontSize,
                    lineHeight = fontSize * 1.12f,
                    fontWeight = FontWeight.SemiBold,
                ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Visible,
            softWrap = false,
        )
    }
}
