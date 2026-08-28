package de.uhi.enia.ridesafe.ui.screens.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.util.formattingLocale
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.max

@Composable
fun ActivitySection(activityByDay: Map<LocalDate, ActivityBar>) {
    var selectedTimeRange by rememberSaveable { mutableStateOf(ActivityTimeRange.WEEK) }
    var selectedMetric by rememberSaveable { mutableStateOf(ActivityChartMetric.DISTANCE) }
    var weekOffset by rememberSaveable { mutableStateOf(0) }
    var monthOffset by rememberSaveable { mutableStateOf(0) }
    val today = LocalDate.now()
    val selectedWeekEnd = today.plusDays(weekOffset * 7L)
    val selectedMonth = YearMonth.from(today).plusMonths(monthOffset.toLong())
    val weeklyBars = buildRollingWeekActivity(activityByDay, selectedWeekEnd)
    val monthlyActivity = buildMonthActivity(activityByDay, selectedMonth)
    val visibleData =
        when (selectedTimeRange) {
            ActivityTimeRange.WEEK -> weeklyBars
            ActivityTimeRange.MONTH -> monthlyActivity
        }
    val dateRange = formatActivityDateRange(visibleData, formattingLocale())
    val maxValue =
        max(
            1.0,
            visibleData.maxOfOrNull { it.valueFor(selectedMetric) } ?: 0.0,
        )
    val subtitle =
        stringResource(
            when (selectedTimeRange) {
                ActivityTimeRange.WEEK -> {
                    when (selectedMetric) {
                        ActivityChartMetric.DISTANCE -> R.string.home_activity_distance_week
                        ActivityChartMetric.TRAVEL_TIME -> R.string.home_activity_time_week
                    }
                }

                ActivityTimeRange.MONTH -> {
                    when (selectedMetric) {
                        ActivityChartMetric.DISTANCE -> R.string.home_activity_distance_month
                        ActivityChartMetric.TRAVEL_TIME -> R.string.home_activity_time_month
                    }
                }
            },
        )
    val canNavigateForward =
        when (selectedTimeRange) {
            ActivityTimeRange.WEEK -> weekOffset < 0
            ActivityTimeRange.MONTH -> monthOffset < 0
        }
    val onNavigatePeriod: (Int) -> Unit = { direction ->
        when (selectedTimeRange) {
            ActivityTimeRange.WEEK -> {
                val nextOffset = weekOffset + direction
                if (nextOffset <= 0) {
                    weekOffset = nextOffset
                }
            }

            ActivityTimeRange.MONTH -> {
                val nextOffset = monthOffset + direction
                if (nextOffset <= 0) {
                    monthOffset = nextOffset
                }
            }
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
            ActivityTimeRangeChips(
                selected = selectedTimeRange,
                onSelected = { selectedTimeRange = it },
                dateRange = dateRange,
            )
            Box(
                modifier =
                    Modifier.activitySwipeNavigation(
                        enabledForward = canNavigateForward,
                        onNavigate = onNavigatePeriod,
                    ),
            ) {
                Crossfade(
                    targetState = selectedTimeRange,
                    animationSpec = tween(durationMillis = 250),
                    label = "activity_visualization",
                ) { timeRange ->
                    when (timeRange) {
                        ActivityTimeRange.WEEK -> {
                            WeeklyBarChart(
                                bars = weeklyBars,
                                selectedMetric = selectedMetric,
                                maxValue = maxValue,
                            )
                        }

                        ActivityTimeRange.MONTH -> {
                            MonthlyHeatMap(
                                days = monthlyActivity,
                                selectedMetric = selectedMetric,
                                maxValue = maxValue,
                            )
                        }
                    }
                }
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
