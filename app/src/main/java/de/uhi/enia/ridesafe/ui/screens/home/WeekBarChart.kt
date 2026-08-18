package de.uhi.enia.ridesafe.ui.screens.home

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.uhi.enia.ridesafe.util.UnitSystemSetting
import de.uhi.enia.ridesafe.util.currentUnitSystem
import de.uhi.enia.ridesafe.util.formatDistance
import java.time.format.TextStyle
import kotlin.math.max

@Composable
fun WeeklyBarChart(
    bars: List<ActivityBar>,
    selectedMetric: ActivityChartMetric,
    maxValue: Double,
) {
    val unitSystem = currentUnitSystem()
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
                valueLabel = bar.labelFor(selectedMetric, unitSystem),
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
    val locale = LocalLocale.current.platformLocale
    val hideZeroLabel = !hasValue && LocalConfiguration.current.screenWidthDp < 360
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
            text = if (hideZeroLabel) "" else valueLabel,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Clip,
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

private fun ActivityBar.labelFor(
    metric: ActivityChartMetric,
    unitSystem: UnitSystemSetting,
): String =
    when (metric) {
        ActivityChartMetric.DISTANCE -> formatDistance(distanceMeters, unitSystem)
        ActivityChartMetric.TRAVEL_TIME -> formatCompactDuration(durationMillis)
    }
