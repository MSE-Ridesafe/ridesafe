package de.uhi.enia.ridesafe.feature.home.ui

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
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
import de.uhi.enia.ridesafe.core.components.MaterialSymbol
import de.uhi.enia.ridesafe.core.format.currentUnitSystem
import de.uhi.enia.ridesafe.core.format.formatDistance
import de.uhi.enia.ridesafe.feature.home.HomeHighlights
import de.uhi.enia.ridesafe.feature.home.formatCompactDuration
import de.uhi.enia.ridesafe.feature.home.formatRecordedRideCount
import java.time.format.TextStyle
import kotlin.math.absoluteValue

data class HomeMetricCardModel(
    val icon: String,
    val title: String,
    val value: String,
    // Empty is a real state (highlight cards): the line still renders so every card measures alike.
    val supportingText: String = "",
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
    highlights: HomeHighlights,
) {
    val unitSystem = currentUnitSystem()
    val totalDistance = formatDistance(distanceMeters, unitSystem)
    val totalDuration = formatCompactDuration(durationMillis)
    val totalRides = formatRecordedRideCount(rideCount)
    val monthDistance = formatDistance(monthDistanceMeters, unitSystem)
    val monthDuration = formatCompactDuration(monthDurationMillis)
    val monthRides = formatRecordedRideCount(monthRideCount)
    val notSet = stringResource(R.string.value_not_set)
    val longestRide = highlights.longestRideMeters?.let { formatDistance(it, unitSystem) } ?: notSet
    val averageRide = highlights.averageRideMeters?.let { formatDistance(it, unitSystem) } ?: notSet
    // Day names are words, not figures — they follow the in-app language like every other label.
    val mostActiveDay =
        highlights.mostActiveDay
            ?.getDisplayName(TextStyle.FULL, LocalLocale.current.platformLocale) ?: notSet
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
            HomeMetricCardModel(
                icon = "emoji_events",
                title = stringResource(R.string.home_highlight_longest_ride),
                value = longestRide,
                contentDescription =
                    stringResource(
                        R.string.home_metric_card_content_description,
                        stringResource(R.string.home_highlight_longest_ride),
                        longestRide,
                    ),
            ),
            HomeMetricCardModel(
                icon = "straighten",
                title = stringResource(R.string.home_highlight_average_ride),
                value = averageRide,
                contentDescription =
                    stringResource(
                        R.string.home_metric_card_content_description,
                        stringResource(R.string.home_highlight_average_ride),
                        averageRide,
                    ),
            ),
            HomeMetricCardModel(
                icon = "calendar_month",
                title = stringResource(R.string.home_highlight_most_active_day),
                value = mostActiveDay,
                contentDescription =
                    stringResource(
                        R.string.home_metric_card_content_description,
                        stringResource(R.string.home_highlight_most_active_day),
                        mostActiveDay,
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
            MetricCard(
                metric = metrics[page],
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            val pageOffset =
                                ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction)
                                    .absoluteValue
                                    .coerceIn(0f, 1f)
                            val pageScale = lerp(start = 0.98f, stop = 1f, fraction = 1f - pageOffset)
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
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        modifier = modifier.semantics { contentDescription = metric.contentDescription },
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
                // The row, not the text, reserves both title lines (and the supporting line below
                // always renders, even empty) so every page of the carousel measures the same
                // height — while a one-line title still centers against its icon.
                modifier = Modifier.height(with(LocalDensity.current) { 36.sp.toDp() }),
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
                    // Fixed at the largest size's height: a shrunken value must not shrink the card.
                    lineHeight = 30.sp * 1.12f,
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
