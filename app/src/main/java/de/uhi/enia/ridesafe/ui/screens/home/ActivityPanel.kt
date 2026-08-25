package de.uhi.enia.ridesafe.ui.screens.home

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import java.time.LocalDate
import kotlin.math.max

@Composable
fun ActivitySection(activityByDay: Map<LocalDate, ActivityBar>) {
    var selectedMetric by rememberSaveable { mutableStateOf(ActivityChartMetric.DISTANCE) }
    var weekOffset by rememberSaveable { mutableStateOf(0) }
    val locale = LocalLocale.current.platformLocale
    val today = LocalDate.now()
    val selectedWeekEnd = today.plusDays(weekOffset * 7L)
    val weeklyBars = buildRollingWeekActivity(activityByDay, selectedWeekEnd)
    val dateRange = formatActivityDateRange(weeklyBars, locale)
    val maxValue =
        max(
            1.0,
            weeklyBars.maxOfOrNull { it.valueFor(selectedMetric) } ?: 0.0,
        )
    val subtitle =
        stringResource(
            when (selectedMetric) {
                ActivityChartMetric.DISTANCE -> R.string.home_activity_distance_week
                ActivityChartMetric.TRAVEL_TIME -> R.string.home_activity_time_week
                ActivityChartMetric.COST -> R.string.home_activity_cost_week
            },
        )
    val canNavigateForward = weekOffset < 0
    val onNavigatePeriod: (Int) -> Unit = { direction ->
        val nextOffset = weekOffset + direction
        if (nextOffset <= 0) {
            weekOffset = nextOffset
        }
    }

    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.home_activity_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MaterialSymbol(
                    symbolName = "bar_chart",
                    contentDescription = null,
                    color = MaterialTheme.colorScheme.primary,
                    size = 24.dp,
                )
            }
            ActivityMetricTabs(
                selected = selectedMetric,
                onSelected = { selectedMetric = it },
            )
            Text(
                text = dateRange,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth(),
            )
            Box(
                modifier =
                    Modifier.activitySwipeNavigation(
                        enabledForward = canNavigateForward,
                        onNavigate = onNavigatePeriod,
                    ),
            ) {
                WeeklyBarChart(
                    bars = weeklyBars,
                    selectedMetric = selectedMetric,
                    maxValue = maxValue,
                )
            }
        }
    }
}

@Composable
private fun Modifier.activitySwipeNavigation(
    enabledForward: Boolean,
    onNavigate: (Int) -> Unit,
): Modifier {
    var dragAmount by remember { mutableStateOf(0f) }
    return pointerInput(enabledForward, onNavigate) {
        detectHorizontalDragGestures(
            onDragStart = { dragAmount = 0f },
            onHorizontalDrag = { _, delta ->
                dragAmount += delta
            },
            onDragEnd = {
                val threshold = 48f
                when {
                    dragAmount <= -threshold && enabledForward -> onNavigate(1)
                    dragAmount >= threshold -> onNavigate(-1)
                }
                dragAmount = 0f
            },
            onDragCancel = { dragAmount = 0f },
        )
    }
}
