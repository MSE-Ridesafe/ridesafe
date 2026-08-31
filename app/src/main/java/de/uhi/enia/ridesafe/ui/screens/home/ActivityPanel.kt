package de.uhi.enia.ridesafe.ui.screens.home

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.util.currentCurrencySetting
import de.uhi.enia.ridesafe.util.formattingLocale
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun ActivitySection(activityByDay: Map<LocalDate, ActivityBar>) {
    var selectedMetric by rememberSaveable { mutableStateOf(ActivityChartMetric.DISTANCE) }
    var startDayOffset by rememberSaveable { mutableIntStateOf(0) }
    // The region's conventions, not the in-app language's — a device set to English in
    // Germany still reads "25.08." here (see formattingLocale for why the two differ).
    val locale = formattingLocale()
    val today = LocalDate.now()
    val initialMonday = startOfCalendarWeek(today)
    val selectedStartDay = initialMonday.plusDays(startDayOffset.toLong())
    val weeklyBars = buildSevenDayActivity(activityByDay, selectedStartDay)
    val chartBars = buildActivityWindow(activityByDay, selectedStartDay.minusDays(1), dayCount = 9)
    val dateRange = formatActivityDateRange(weeklyBars, locale)
    val maxValue = activityScaleMaximum(activityByDay.values, selectedMetric, currentCurrencySetting().currencyCode)
    val subtitle =
        stringResource(
            when (selectedMetric) {
                ActivityChartMetric.DISTANCE -> R.string.home_activity_distance_week
                ActivityChartMetric.TRAVEL_TIME -> R.string.home_activity_time_week
                ActivityChartMetric.COST -> R.string.home_activity_cost_week
            },
        )
    val onNavigateDays: (Int) -> Boolean = { dayDelta ->
        startDayOffset += dayDelta
        true
    }
    var chartWidthPx by remember { mutableIntStateOf(0) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    var snapJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val spacingPx = with(LocalDensity.current) { 8.dp.toPx() }
    val dayStepPx = if (chartWidthPx > 0) (chartWidthPx + spacingPx) / 7f else 0f

    fun settleDrag() {
        if (dayStepPx <= 0f) {
            dragOffsetPx = 0f
            return
        }
        val dayShift =
            when {
                dragOffsetPx <= -dayStepPx / 2f -> 1
                dragOffsetPx >= dayStepPx / 2f -> -1
                else -> 0
            }
        val canShift = true
        val targetOffset =
            when {
                !canShift -> 0f
                dayShift > 0 -> -dayStepPx
                dayShift < 0 -> dayStepPx
                else -> 0f
            }
        snapJob =
            scope.launch {
                animate(
                    initialValue = dragOffsetPx,
                    targetValue = targetOffset,
                    animationSpec =
                        spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessLow,
                        ),
                ) { value, _ -> dragOffsetPx = value }
                if (canShift && dayShift != 0) onNavigateDays(dayShift)
                dragOffsetPx = 0f
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
                    Modifier
                        .fillMaxWidth()
                        .onSizeChanged { chartWidthPx = it.width }
                        .activityDragNavigation(
                            onDragStart = {
                                snapJob?.cancel()
                                snapJob = null
                            },
                            onDrag = { delta ->
                                if (dayStepPx <= 0f) return@activityDragNavigation
                                var nextOffset = dragOffsetPx + delta
                                while (nextOffset <= -dayStepPx) {
                                    if (onNavigateDays(1)) {
                                        nextOffset += dayStepPx
                                    } else {
                                        nextOffset = nextOffset.coerceAtLeast(-dayStepPx)
                                        break
                                    }
                                }
                                while (nextOffset >= dayStepPx) {
                                    if (onNavigateDays(-1)) {
                                        nextOffset -= dayStepPx
                                    }
                                }
                                dragOffsetPx = nextOffset
                            },
                            onDragEnd = ::settleDrag,
                        ),
            ) {
                WeeklyBarChart(
                    bars = chartBars,
                    selectedMetric = selectedMetric,
                    maxValue = maxValue,
                    dragOffsetPx = dragOffsetPx,
                )
            }
        }
    }
}

@Composable
private fun Modifier.activityDragNavigation(
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
): Modifier {
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    return pointerInput(Unit) {
        detectHorizontalDragGestures(
            onDragStart = { currentOnDragStart() },
            onHorizontalDrag = { _, delta -> currentOnDrag(delta) },
            onDragEnd = { currentOnDragEnd() },
            onDragCancel = { currentOnDragEnd() },
        )
    }
}
