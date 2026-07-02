@file:OptIn(ExperimentalMaterial3Api::class)

package de.uhi.enia.ridesafe.ui.screens.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.ConfigurationCompat
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.Vehicle
import de.uhi.enia.ridesafe.tracking.shortAddress
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.ui.screens.garage.VehicleImage
import de.uhi.enia.ridesafe.ui.screens.garage.labelRes
import de.uhi.enia.ridesafe.util.UnitSystemSetting
import de.uhi.enia.ridesafe.util.formatDistance
import de.uhi.enia.ridesafe.util.formatDuration
import de.uhi.enia.ridesafe.util.formatOdometer
import de.uhi.enia.ridesafe.util.usesMetric
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.max

@Composable
fun HomeScreen(
    state: HomeDashboardState,
    modifier: Modifier = Modifier,
    unitSystem: UnitSystemSetting = UnitSystemSetting.AUTOMATIC,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.screen_home_title),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                },
                actions = {
                    IconButton(onClick = { }) {
                        MaterialSymbol(
                            symbolName = "notifications",
                            contentDescription = stringResource(R.string.home_notifications),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                VehicleCard(
                    vehicle = state.primaryVehicle,
                    unitSystem = unitSystem,
                )
            }
            state.activeRide?.let { activeRide ->
                item {
                    ActiveRideCard(
                        activeRide = activeRide,
                        unitSystem = unitSystem,
                    )
                }
            }
            item {
                MonthlyStats(
                    distanceMeters = state.monthlyDistanceMeters,
                    durationMillis = state.monthlyDurationMillis,
                    trendData = state.monthlyActivity,
                    unitSystem = unitSystem,
                )
            }
            item {
                ActivitySection(
                    weeklyBars = state.activityBars,
                    monthlyActivity = state.monthlyActivity,
                    unitSystem = unitSystem,
                )
            }
        }
    }
}

@Composable
private fun VehicleCard(
    vehicle: Vehicle?,
    unitSystem: UnitSystemSetting,
) {
    val context = LocalContext.current
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                VehicleImage(size = 96.dp)
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = vehicle?.let { "${it.make} ${it.model}" } ?: stringResource(R.string.home_no_primary_vehicle),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = vehicle?.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.home_add_vehicle_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    vehicle?.let {
                        Text(
                            text = "${it.year ?: stringResource(R.string.value_not_set)} - ${it.licensePlate}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (vehicle != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                    InfoChip(
                        label = stringResource(R.string.vehicle_mileage),
                        value = formatOdometer(context, vehicle.mileageKm, unitSystem),
                        modifier = Modifier.weight(1f),
                    )
                    InfoChip(
                        label = stringResource(R.string.vehicle_fuel_type),
                        value = stringResource(vehicle.fuelType.labelRes()),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveRideCard(
    activeRide: ActiveRideSummary,
    unitSystem: UnitSystemSetting,
) {
    val context = LocalContext.current
    val ride = activeRide.ride
    val duration = formatDuration(ride.startedAtEpochMs, System.currentTimeMillis()) ?: stringResource(R.string.value_not_set)
    val distance = formatDistance(context, ride.distanceMeters ?: 0.0, unitSystem)
    val startPoint = ride.startAddress?.let { shortAddress(it) } ?: stringResource(R.string.ride_address_unknown)

    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        MaterialSymbol(
                            symbolName = "radio_button_checked",
                            contentDescription = null,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.home_active_ride),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = activeRide.vehicleName ?: stringResource(R.string.home_unknown_vehicle),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                InfoChip(stringResource(R.string.ride_detail_duration), duration, Modifier.weight(1f))
                InfoChip(stringResource(R.string.ride_detail_total_distance), distance, Modifier.weight(1f))
            }
            Text(
                text = stringResource(R.string.home_started_at, startPoint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MonthlyStats(
    distanceMeters: Double,
    durationMillis: Long,
    trendData: List<ActivityBar>,
    unitSystem: UnitSystemSetting,
) {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        StatCard(
            icon = "route",
            label = stringResource(R.string.home_monthly_distance),
            value = formatDistance(context, distanceMeters, unitSystem),
            chart = StatMiniChart.Line(trendData.map { it.distanceMeters }),
            modifier = Modifier.weight(1f),
        )
        StatCard(
            icon = "schedule",
            label = stringResource(R.string.home_time_in_vehicle),
            value = formatDuration(durationMillis),
            chart = StatMiniChart.Bars(trendData.map { it.durationMillis.toDouble() }),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatCard(
    icon: String,
    label: String,
    value: String,
    chart: StatMiniChart,
    modifier: Modifier = Modifier,
) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        modifier = modifier.height(188.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MaterialSymbol(
                symbolName = icon,
                contentDescription = null,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.weight(1f))
            AnimatedPrimaryValue(value = value)
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            StatMiniChart(chart = chart, modifier = Modifier.fillMaxWidth().height(28.dp))
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
    ) { targetValue ->
        Text(
            text = targetValue,
            style =
                MaterialTheme.typography.displaySmall.copy(
                    fontSize = 32.sp,
                    lineHeight = 36.sp,
                ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StatMiniChart(
    chart: StatMiniChart,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)

    Crossfade(
        targetState = chart.values.takeLast(30),
        animationSpec = tween(durationMillis = 250),
        label = "dashboard_stat_chart",
        modifier = modifier,
    ) { values ->
        val maxValue = max(1.0, values.maxOrNull() ?: 0.0)
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (values.isEmpty()) return@Canvas
            when (chart) {
                is StatMiniChart.Line -> {
                    if (values.size == 1) {
                        val y = size.height * (1f - (values.first() / maxValue).toFloat())
                        drawLine(
                            color = track,
                            start =
                                androidx.compose.ui.geometry
                                    .Offset(0f, y),
                            end =
                                androidx.compose.ui.geometry
                                    .Offset(size.width, y),
                            strokeWidth = 4.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                    } else {
                        val step = size.width / (values.lastIndex.coerceAtLeast(1))
                        values.zipWithNext().forEachIndexed { index, pair ->
                            val startY = size.height * (1f - (pair.first / maxValue).toFloat())
                            val endY = size.height * (1f - (pair.second / maxValue).toFloat())
                            drawLine(
                                color = primary.copy(alpha = 0.42f),
                                start =
                                    androidx.compose.ui.geometry
                                        .Offset(step * index, startY),
                                end =
                                    androidx.compose.ui.geometry
                                        .Offset(step * (index + 1), endY),
                                strokeWidth = 3.dp.toPx(),
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                }

                is StatMiniChart.Bars -> {
                    val step = size.width / values.size
                    values.forEachIndexed { index, value ->
                        val barHeight = (size.height * (value / maxValue).toFloat()).coerceAtLeast(4.dp.toPx())
                        drawLine(
                            color = primary.copy(alpha = if (value > 0.0) 0.38f else 0.16f),
                            start =
                                androidx.compose.ui.geometry
                                    .Offset(step * index + step / 2f, size.height),
                            end =
                                androidx.compose.ui.geometry
                                    .Offset(step * index + step / 2f, size.height - barHeight),
                            strokeWidth = (step * 0.42f).coerceAtMost(6.dp.toPx()),
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }
        }
    }
}

private sealed class StatMiniChart(
    val values: List<Double>,
) {
    class Line(
        values: List<Double>,
    ) : StatMiniChart(values)

    class Bars(
        values: List<Double>,
    ) : StatMiniChart(values)
}

@Composable
private fun ActivitySection(
    weeklyBars: List<ActivityBar>,
    monthlyActivity: List<ActivityBar>,
    unitSystem: UnitSystemSetting,
) {
    var selectedTimeRange by rememberSaveable { mutableStateOf(ActivityTimeRange.WEEK) }
    var selectedMetric by rememberSaveable { mutableStateOf(ActivityChartMetric.DISTANCE) }
    val visibleData =
        when (selectedTimeRange) {
            ActivityTimeRange.WEEK -> weeklyBars
            ActivityTimeRange.MONTH -> monthlyActivity
        }
    val maxValue =
        max(
            1.0,
            visibleData.maxOfOrNull { it.valueFor(selectedMetric) } ?: 0.0,
        )
    val hasActivity = visibleData.any { it.distanceMeters > 0.0 || it.rideCount > 0 }

    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.home_activity_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text =
                            stringResource(
                                when (selectedTimeRange) {
                                    ActivityTimeRange.WEEK -> {
                                        when (selectedMetric) {
                                            ActivityChartMetric.DISTANCE -> R.string.home_activity_distance_week
                                            ActivityChartMetric.RIDES -> R.string.home_activity_rides_week
                                        }
                                    }

                                    ActivityTimeRange.MONTH -> {
                                        when (selectedMetric) {
                                            ActivityChartMetric.DISTANCE -> R.string.home_activity_distance_month
                                            ActivityChartMetric.RIDES -> R.string.home_activity_rides_month
                                        }
                                    }
                                },
                            ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MaterialSymbol(
                    symbolName = "bar_chart",
                    contentDescription = null,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            ActivityTimeRangeTabs(
                selected = selectedTimeRange,
                onSelected = { selectedTimeRange = it },
            )
            ActivityMetricChips(
                selected = selectedMetric,
                onSelected = { selectedMetric = it },
            )
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
                            unitSystem = unitSystem,
                        )
                    }

                    ActivityTimeRange.MONTH -> {
                        MonthlyHeatMap(
                            days = monthlyActivity,
                            selectedMetric = selectedMetric,
                            maxValue = maxValue,
                            unitSystem = unitSystem,
                        )
                    }
                }
            }
            if (!hasActivity) {
                Text(
                    text =
                        stringResource(
                            when (selectedTimeRange) {
                                ActivityTimeRange.WEEK -> R.string.home_activity_empty_week
                                ActivityTimeRange.MONTH -> R.string.home_activity_empty_month
                            },
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ActivityTimeRangeTabs(
    selected: ActivityTimeRange,
    onSelected: (ActivityTimeRange) -> Unit,
) {
    PrimaryTabRow(
        selectedTabIndex = ActivityTimeRange.entries.indexOf(selected),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.primary,
    ) {
        ActivityTimeRange.entries.forEach { range ->
            Tab(
                selected = selected == range,
                onClick = { onSelected(range) },
                text = { Text(stringResource(range.labelRes)) },
            )
        }
    }
}

@Composable
private fun ActivityMetricChips(
    selected: ActivityChartMetric,
    onSelected: (ActivityChartMetric) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ActivityChartMetric.entries.forEach { metric ->
            FilterChip(
                selected = selected == metric,
                onClick = { onSelected(metric) },
                label = { Text(stringResource(metric.labelRes)) },
                leadingIcon =
                    if (selected == metric) {
                        {
                            MaterialSymbol(
                                symbolName = "check",
                                contentDescription = null,
                                size = 18.dp,
                            )
                        }
                    } else {
                        null
                    },
            )
        }
    }
}

@Composable
private fun WeeklyBarChart(
    bars: List<ActivityBar>,
    selectedMetric: ActivityChartMetric,
    maxValue: Double,
    unitSystem: UnitSystemSetting,
) {
    val context = LocalContext.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(160.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        bars.forEach { bar ->
            val value = bar.valueFor(selectedMetric)
            ActivityBarColumn(
                bar = bar,
                valueLabel = bar.labelFor(context, selectedMetric, unitSystem),
                hasValue = value > 0.0,
                fraction = (value / maxValue).toFloat().coerceIn(0f, 1f),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ActivityBarColumn(
    bar: ActivityBar,
    valueLabel: String,
    hasValue: Boolean,
    fraction: Float,
    modifier: Modifier = Modifier,
) {
    val locale =
        ConfigurationCompat.getLocales(LocalConfiguration.current).get(0)
    val targetHeight = max(if (hasValue) 18f else 8f, 100f * fraction).dp
    val barHeight by animateDpAsState(
        targetValue = targetHeight,
        animationSpec = tween(durationMillis = 250),
        label = "activity_bar_height",
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
    ) {
        Text(
            text = valueLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(barHeight)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 4.dp, bottomEnd = 4.dp))
                    .background(
                        if (hasValue) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                    ),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text =
                bar.day.dayOfWeek
                    .getDisplayName(TextStyle.SHORT, locale)
                    .trimEnd('.')
                    .take(2),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun MonthlyHeatMap(
    days: List<ActivityBar>,
    selectedMetric: ActivityChartMetric,
    maxValue: Double,
    unitSystem: UnitSystemSetting,
) {
    val locale =
        ConfigurationCompat.getLocales(LocalConfiguration.current).get(0)
    var selectedDay by rememberSaveable { mutableStateOf<Long?>(null) }
    val firstDay = days.firstOrNull()?.day
    val weekColumns =
        if (firstDay == null) {
            5
        } else {
            val offset = firstDay.dayOfWeek.value - 1
            ((offset + days.size + 6) / 7).coerceAtLeast(1)
        }
    val selectedActivity = selectedDay?.let { epochDay -> days.firstOrNull { it.day.toEpochDay() == epochDay } }

    selectedActivity?.let { activity ->
        ActivityDayDialog(
            activity = activity,
            unitSystem = unitSystem,
            onDismiss = { selectedDay = null },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DayOfWeek.entries.forEach { dayOfWeek ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = dayOfWeek.getDisplayName(TextStyle.SHORT, locale).trimEnd('.').take(2),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(24.dp),
                )
                repeat(weekColumns) { week ->
                    val day =
                        days.firstOrNull { activity ->
                            activity.day.dayOfWeek == dayOfWeek &&
                                ((activity.day.dayOfMonth + (firstDay?.dayOfWeek?.value ?: 1) - 2) / 7) == week
                        }
                    HeatMapCell(
                        activity = day,
                        fraction = ((day?.valueFor(selectedMetric) ?: 0.0) / maxValue).toFloat().coerceIn(0f, 1f),
                        selectedMetric = selectedMetric,
                        onClick = { day?.let { selectedDay = it.day.toEpochDay() } },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun HeatMapCell(
    activity: ActivityBar?,
    fraction: Float,
    selectedMetric: ActivityChartMetric,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val intensity =
        when (selectedMetric) {
            ActivityChartMetric.DISTANCE -> {
                when {
                    fraction <= 0f -> ActivityIntensity.EMPTY
                    fraction < 0.34f -> ActivityIntensity.LOW
                    fraction < 0.67f -> ActivityIntensity.MEDIUM
                    else -> ActivityIntensity.HIGH
                }
            }

            ActivityChartMetric.RIDES -> {
                when {
                    (activity?.rideCount ?: 0) <= 0 -> ActivityIntensity.EMPTY
                    activity?.rideCount == 1 -> ActivityIntensity.LOW
                    activity?.rideCount == 2 -> ActivityIntensity.MEDIUM
                    else -> ActivityIntensity.HIGH
                }
            }
        }
    val targetColor =
        when (intensity) {
            ActivityIntensity.EMPTY -> MaterialTheme.colorScheme.surfaceContainerHighest
            ActivityIntensity.LOW -> MaterialTheme.colorScheme.tertiaryContainer
            ActivityIntensity.MEDIUM -> MaterialTheme.colorScheme.secondaryContainer
            ActivityIntensity.HIGH -> MaterialTheme.colorScheme.primary
        }
    val color by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = 250),
        label = "activity_heat_color",
    )

    Box(
        modifier =
            modifier
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.small)
                .background(color)
                .clickable(enabled = activity != null, onClick = onClick),
    )
}

@Composable
private fun ActivityDayDialog(
    activity: ActivityBar,
    unitSystem: UnitSystemSetting,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        },
        title = {
            Text(
                text =
                    android.text.format.DateUtils.formatDateTime(
                        context,
                        activity.day
                            .atStartOfDay(java.time.ZoneId.systemDefault())
                            .toInstant()
                            .toEpochMilli(),
                        android.text.format.DateUtils.FORMAT_SHOW_DATE or
                            android.text.format.DateUtils.FORMAT_SHOW_YEAR,
                    ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        R.string.home_activity_dialog_distance,
                        formatChartDistance(context, activity.distanceMeters, unitSystem),
                    ),
                )
                Text(stringResource(R.string.home_activity_dialog_rides, activity.rideCount))
            }
        },
    )
}

@Composable
private fun InfoChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun formatDuration(durationMillis: Long): String {
    val totalMinutes = (durationMillis / 60_000).coerceAtLeast(0)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 -> "%d h %02d min".format(hours, minutes)
        else -> "%d min".format(minutes)
    }
}

private enum class ActivityChartMetric {
    DISTANCE,
    RIDES,
}

private enum class ActivityTimeRange {
    WEEK,
    MONTH,
}

private enum class ActivityIntensity {
    EMPTY,
    LOW,
    MEDIUM,
    HIGH,
}

private val ActivityTimeRange.labelRes: Int
    get() =
        when (this) {
            ActivityTimeRange.WEEK -> R.string.home_activity_period_week
            ActivityTimeRange.MONTH -> R.string.home_activity_period_month
        }

private val ActivityChartMetric.labelRes: Int
    get() =
        when (this) {
            ActivityChartMetric.DISTANCE -> R.string.home_activity_metric_distance
            ActivityChartMetric.RIDES -> R.string.home_activity_metric_rides
        }

private fun ActivityBar.valueFor(metric: ActivityChartMetric): Double =
    when (metric) {
        ActivityChartMetric.DISTANCE -> distanceMeters
        ActivityChartMetric.RIDES -> rideCount.toDouble()
    }

private fun ActivityBar.labelFor(
    context: android.content.Context,
    metric: ActivityChartMetric,
    unitSystem: UnitSystemSetting,
): String =
    when (metric) {
        ActivityChartMetric.DISTANCE -> formatChartDistance(context, distanceMeters, unitSystem)
        ActivityChartMetric.RIDES -> rideCount.toString()
    }

private fun formatChartDistance(
    context: android.content.Context,
    meters: Double,
    setting: UnitSystemSetting,
): String {
    val locale = Locale.getDefault()
    val value = if (usesMetric(context, setting)) meters / 1000.0 else meters * 0.000621371
    val unit = context.getString(if (usesMetric(context, setting)) R.string.unit_km else R.string.unit_mi)
    val number =
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }
    return "${number.format(value)} $unit"
}
