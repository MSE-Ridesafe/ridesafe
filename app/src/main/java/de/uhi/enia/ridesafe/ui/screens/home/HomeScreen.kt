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
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.format.DateTimeFormatter
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
                    distanceMeters = state.totalDistanceMeters,
                    durationMillis = state.totalDurationMillis,
                    unitSystem = unitSystem,
                )
            }
            item {
                ActivitySection(
                    activityByDay = state.activityByDay,
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
                value = formatDuration(durationMillis),
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
                value = formatDuration(durationMillis),
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
                ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Visible,
            softWrap = false,
        )
    }
}

@Composable
private fun ActivitySection(
    activityByDay: Map<LocalDate, ActivityBar>,
    unitSystem: UnitSystemSetting,
) {
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
    val dateRange = formatActivityDateRange(visibleData)
    val maxValue =
        max(
            1.0,
            visibleData.maxOfOrNull { it.valueFor(selectedMetric) } ?: 0.0,
        )
    val hasActivity = visibleData.any { it.distanceMeters > 0.0 || it.durationMillis > 0L }
    val subtitle =
        when (selectedTimeRange) {
            ActivityTimeRange.WEEK -> "${stringResource(selectedMetric.labelRes)} - $dateRange"
            ActivityTimeRange.MONTH ->
                "${
                    stringResource(selectedMetric.labelRes)
                } - ${formatMonthLabel(selectedMonth)}"
        }
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
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
                                unitSystem = unitSystem,
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
private fun ActivityMetricTabs(
    selected: ActivityChartMetric,
    onSelected: (ActivityChartMetric) -> Unit,
) {
    PrimaryTabRow(
        selectedTabIndex = ActivityChartMetric.entries.indexOf(selected),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.primary,
    ) {
        ActivityChartMetric.entries.forEach { metric ->
            Tab(
                selected = selected == metric,
                onClick = { onSelected(metric) },
                text = { Text(stringResource(metric.labelRes)) },
            )
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

@Composable
private fun ActivityTimeRangeChips(
    selected: ActivityTimeRange,
    onSelected: (ActivityTimeRange) -> Unit,
    dateRange: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActivityTimeRange.entries.forEach { range ->
                FilterChip(
                    selected = selected == range,
                    onClick = { onSelected(range) },
                    label = { Text(stringResource(range.labelRes)) },
                    leadingIcon =
                        if (selected == range) {
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
        Text(
            text = dateRange,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
                        java.time.DayOfWeek.of(dayOfWeek)
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
        ActivityHeatMapLegend()
    }
}

@Composable
private fun HeatMapCell(
    activity: ActivityBar,
    fraction: Float,
    selectedMetric: ActivityChartMetric,
    modifier: Modifier = Modifier,
) {
    val intensity =
        when (selectedMetric) {
            ActivityChartMetric.DISTANCE -> {
                when {
                    fraction <= 0f -> ActivityIntensity.EMPTY
                    fraction < 0.25f -> ActivityIntensity.LOW
                    fraction < 0.5f -> ActivityIntensity.MEDIUM
                    fraction < 0.75f -> ActivityIntensity.HIGH
                    else -> ActivityIntensity.VERY_HIGH
                }
            }

            ActivityChartMetric.TRAVEL_TIME -> {
                when {
                    activity.durationMillis <= 0L -> ActivityIntensity.EMPTY
                    fraction < 0.25f -> ActivityIntensity.LOW
                    fraction < 0.5f -> ActivityIntensity.MEDIUM
                    fraction < 0.75f -> ActivityIntensity.HIGH
                    else -> ActivityIntensity.VERY_HIGH
                }
            }
        }
    val targetColor =
        intensity.color()
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
                    if (intensity == ActivityIntensity.HIGH || intensity == ActivityIntensity.VERY_HIGH) {
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

@Composable
private fun ActivityHeatMapLegend() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.home_activity_legend_less),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        ActivityIntensity.entries.forEach { intensity ->
            Box(
                modifier =
                    Modifier
                        .size(10.dp)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(intensity.color()),
            )
        }
        Text(
            text = stringResource(R.string.home_activity_legend_more),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun ActivityIntensity.color(): Color =
    when (this) {
        ActivityIntensity.EMPTY -> MaterialTheme.colorScheme.surfaceContainerHighest
        ActivityIntensity.LOW -> MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
        ActivityIntensity.MEDIUM -> MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)
        ActivityIntensity.HIGH -> MaterialTheme.colorScheme.primary.copy(alpha = 0.68f)
        ActivityIntensity.VERY_HIGH -> MaterialTheme.colorScheme.primary
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
    TRAVEL_TIME,
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
    VERY_HIGH,
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
            ActivityChartMetric.TRAVEL_TIME -> R.string.home_activity_metric_time
        }

private fun formatActivityDateRange(days: List<ActivityBar>): String {
    val start = days.firstOrNull()?.day ?: LocalDate.now()
    val end = days.lastOrNull()?.day ?: start
    val formatter = DateTimeFormatter.ofPattern("dd.MM.", Locale.getDefault())
    return "${start.format(formatter)} - ${end.format(formatter)}"
}

private fun formatMonthLabel(month: YearMonth): String {
    val formatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
    return month.atDay(1).format(formatter)
}

private fun buildRollingWeekActivity(
    activityByDay: Map<LocalDate, ActivityBar>,
    endDay: LocalDate,
): List<ActivityBar> =
    (6 downTo 0).map { offset ->
        val day = endDay.minusDays(offset.toLong())
        activityByDay[day] ?: ActivityBar(day, rideCount = 0, distanceMeters = 0.0, durationMillis = 0L)
    }

private fun buildMonthActivity(
    activityByDay: Map<LocalDate, ActivityBar>,
    month: YearMonth,
): List<ActivityBar> =
    (1..month.lengthOfMonth()).map { dayOfMonth ->
        val day = month.atDay(dayOfMonth)
        activityByDay[day] ?: ActivityBar(day, rideCount = 0, distanceMeters = 0.0, durationMillis = 0L)
    }

private fun ActivityBar.valueFor(metric: ActivityChartMetric): Double =
    when (metric) {
        ActivityChartMetric.DISTANCE -> distanceMeters
        ActivityChartMetric.TRAVEL_TIME -> durationMillis.toDouble()
    }

private fun ActivityBar.labelFor(
    context: android.content.Context,
    metric: ActivityChartMetric,
    unitSystem: UnitSystemSetting,
): String =
    when (metric) {
        ActivityChartMetric.DISTANCE -> formatChartDistance(context, distanceMeters, unitSystem)
        ActivityChartMetric.TRAVEL_TIME -> formatDuration(durationMillis)
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
