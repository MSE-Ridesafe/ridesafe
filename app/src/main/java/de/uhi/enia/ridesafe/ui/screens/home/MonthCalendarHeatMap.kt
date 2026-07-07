package de.uhi.enia.ridesafe.ui.screens.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import java.time.DayOfWeek
import java.time.format.TextStyle

@Composable
fun MonthlyHeatMap(
    days: List<ActivityBar>,
    selectedMetric: ActivityChartMetric,
    maxValue: Double,
) {
    val locale =
        ConfigurationCompat.getLocales(LocalConfiguration.current).get(0)
    val firstDay = days.firstOrNull()?.day
    val weekRows =
        if (firstDay == null) {
            5
        } else {
            val offset = firstDay.dayOfWeek.value - 1
            ((offset + days.size + 6) / 7).coerceAtLeast(1)
        }
    val calendarRows =
        (0 until weekRows).map { week ->
            (0..6).map { dayIndex ->
                days.firstOrNull { activity ->
                    val offset = firstDay?.dayOfWeek?.value ?: 1
                    activity.day.dayOfWeek.value == dayIndex + 1 &&
                        ((activity.day.dayOfMonth + offset - 2) / 7) == week
                }
            }
        }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(160.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            (1..7).forEach { dayOfWeek ->
                Text(
                    text =
                        DayOfWeek.of(dayOfWeek)
                            .getDisplayName(TextStyle.SHORT, locale)
                            .trimEnd('.')
                            .take(2),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            calendarRows.forEach { week ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    week.forEach { day ->
                        if (day == null) {
                            Spacer(Modifier.weight(1f))
                        } else {
                            HeatMapCell(
                                activity = day,
                                fraction = (day.valueFor(selectedMetric) / maxValue).toFloat().coerceIn(0f, 1f),
                                selectedMetric = selectedMetric,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeatMapCell(
    activity: ActivityBar,
    fraction: Float,
    selectedMetric: ActivityChartMetric,
    modifier: Modifier = Modifier,
) {
    val hasValue =
        when (selectedMetric) {
            ActivityChartMetric.DISTANCE -> activity.distanceMeters > 0.0
            ActivityChartMetric.TRAVEL_TIME -> activity.durationMillis > 0L
        }
    val targetColor =
        if (hasValue) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.24f + 0.76f * fraction.coerceIn(0f, 1f))
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        }
    val color by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = 250),
        label = "activity_heat_color",
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(18.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(color),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = activity.day.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelSmall,
                color =
                    if (hasValue && fraction >= 0.72f) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}
