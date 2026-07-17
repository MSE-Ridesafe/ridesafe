package de.uhi.enia.ridesafe.ui.screens.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.util.UnitSystemSetting
import de.uhi.enia.ridesafe.util.formatDistance
import kotlin.math.absoluteValue

data class HomeMetricCardModel(
    val icon: String,
    val title: String,
    val value: String,
    val supportingText: String,
    val contentDescription: String,
)

@Composable
fun SummaryMetricCarousel(
    distanceMeters: Double,
    durationMillis: Long,
    rideCount: Int,
    monthDistanceMeters: Double,
    monthDurationMillis: Long,
    monthRideCount: Int,
    unitSystem: UnitSystemSetting,
) {
    val context = LocalContext.current
    val totalDistance = formatDistance(context, distanceMeters, unitSystem)
    val totalDuration = formatCompactDuration(durationMillis)
    val totalRides = formatRecordedRideCount(context, rideCount)
    val monthDistance = formatDistance(context, monthDistanceMeters, unitSystem)
    val monthDuration = formatCompactDuration(monthDurationMillis)
    val monthRides = formatRecordedRideCount(context, monthRideCount)
    val metrics =
        listOf(
            HomeMetricCardModel(
                icon = "route",
                title = stringResource(R.string.home_total_distance),
                value = totalDistance,
                supportingText =
                    if (monthDistanceMeters > 0.0) {
                        stringResource(R.string.home_metric_distance_this_month, monthDistance)
                    } else {
                        stringResource(R.string.home_metric_no_distance_this_month)
                    },
                contentDescription =
                    stringResource(
                        R.string.home_metric_card_content_description,
                        stringResource(R.string.home_total_distance),
                        totalDistance,
                    ),
            ),
            HomeMetricCardModel(
                icon = "schedule",
                title = stringResource(R.string.home_total_travel_time),
                value = totalDuration,
                supportingText =
                    if (monthDurationMillis > 0L) {
                        stringResource(R.string.home_metric_duration_this_month, monthDuration)
                    } else {
                        stringResource(R.string.home_metric_no_duration_this_month)
                    },
                contentDescription =
                    stringResource(
                        R.string.home_metric_card_content_description,
                        stringResource(R.string.home_total_travel_time),
                        totalDuration,
                    ),
            ),
            HomeMetricCardModel(
                icon = "directions_car",
                title = stringResource(R.string.home_total_recorded_rides),
                value = totalRides,
                supportingText =
                    if (monthRideCount > 0) {
                        stringResource(R.string.home_metric_rides_this_month, monthRides)
                    } else {
                        stringResource(R.string.home_metric_no_rides_this_month)
                    },
                contentDescription =
                    stringResource(
                        R.string.home_metric_card_content_description,
                        stringResource(R.string.home_total_recorded_rides),
                        totalRides,
                    ),
            ),
        )
    val pagerState = rememberPagerState(pageCount = { metrics.size })

    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pagerState,
            pageSize = FractionalPageSize(0.76f),
            contentPadding = PaddingValues(end = 20.dp),
            pageSpacing = 14.dp,
            modifier = Modifier.fillMaxWidth(),
        ) { page ->
            val pageOffset =
                ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction)
                    .absoluteValue
                    .coerceIn(0f, 1f)
            val pageScale = lerp(start = 0.98f, stop = 1f, fraction = 1f - pageOffset)
            MetricCard(
                metric = metrics[page],
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            scaleX = pageScale
                            scaleY = pageScale
                        },
            )
        }
        PageIndicator(
            pageCount = metrics.size,
            selectedPage = pagerState.currentPage,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

private class FractionalPageSize(
    private val fraction: Float,
) : PageSize {
    override fun Density.calculateMainAxisPageSize(
        availableSpace: Int,
        pageSpacing: Int,
    ): Int = (availableSpace * fraction).toInt()
}

@Composable
private fun MetricCard(
    metric: HomeMetricCardModel,
    modifier: Modifier = Modifier,
) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        modifier =
            modifier
                .heightIn(min = 120.dp)
                .semantics { contentDescription = metric.contentDescription },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MaterialSymbol(
                    symbolName = metric.icon,
                    contentDescription = null,
                    color = MaterialTheme.colorScheme.primary,
                    size = 24.dp,
                )
                Text(
                    text = metric.title,
                    style = MaterialTheme.typography.labelLarge.copy(lineHeight = 18.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    softWrap = true,
                    modifier = Modifier.weight(1f),
                )
            }
            AnimatedPrimaryValue(value = metric.value)
            Text(
                text = metric.supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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

@Composable
private fun PageIndicator(
    pageCount: Int,
    selectedPage: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { index ->
            val selected = index == selectedPage
            Box(
                modifier =
                    Modifier
                        .size(if (selected) 8.dp else 6.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                        ),
            )
        }
    }
}
