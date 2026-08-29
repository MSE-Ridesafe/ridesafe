package de.uhi.enia.ridesafe.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.uhi.enia.ridesafe.util.UnitSystemSetting
import de.uhi.enia.ridesafe.util.currentCurrencySetting
import de.uhi.enia.ridesafe.util.currentUnitSystem
import de.uhi.enia.ridesafe.util.formatDistance
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.format.TextStyle
import java.util.Currency
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun WeeklyBarChart(
    bars: List<ActivityBar>,
    selectedMetric: ActivityChartMetric,
    maxValue: Double,
    dragOffsetPx: Float,
) {
    val unitSystem = currentUnitSystem()
    val locale = LocalLocale.current.platformLocale
    val currency = currentCurrencySetting().currency
    val spacing = 8.dp
    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clipToBounds(),
    ) {
        val barWidth = (maxWidth - spacing * 6) / 7
        val density = LocalDensity.current
        val barWidthPx = with(density) { barWidth.toPx() }
        val dayStepPx = with(density) { (barWidth + spacing).toPx() }
        val viewportWidthPx = with(density) { maxWidth.toPx() }
        val contentWidthPx = (barWidthPx * bars.size + with(density) { spacing.toPx() } * (bars.size - 1)).roundToInt()
        Row(
            modifier =
                Modifier
                    .height(160.dp)
                    .horizontalChartViewport(
                        contentWidthPx = contentWidthPx,
                        offsetPx = (-dayStepPx + dragOffsetPx).roundToInt(),
                    ),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.Bottom,
        ) {
            bars.forEachIndexed { index, bar ->
                val value = bar.valueFor(selectedMetric, currency.currencyCode)
                val left = (index - 1) * dayStepPx + dragOffsetPx
                val visibleWidth = (minOf(left + barWidthPx, viewportWidthPx) - maxOf(left, 0f)).coerceAtLeast(0f)
                val visibleFraction = if (barWidthPx > 0f) visibleWidth / barWidthPx else 0f
                val labelAlpha = ((visibleFraction - 0.45f) / 0.35f).coerceIn(0f, 1f)
                ActivityBarColumn(
                    bar = bar,
                    valueLabel = bar.labelFor(selectedMetric, unitSystem, currency, locale),
                    hasValue = value > 0.0,
                    fraction = (value / maxValue).toFloat().coerceIn(0f, 1f),
                    labelAlpha = labelAlpha,
                    modifier = Modifier.width(barWidth),
                )
            }
        }
    }
}

private fun Modifier.horizontalChartViewport(
    contentWidthPx: Int,
    offsetPx: Int,
): Modifier =
    layout { measurable, constraints ->
        val placeable =
            measurable.measure(
                constraints.copy(
                    minWidth = contentWidthPx,
                    maxWidth = contentWidthPx,
                ),
            )
        layout(width = constraints.maxWidth, height = placeable.height) {
            placeable.placeRelative(x = offsetPx, y = 0)
        }
    }

@Composable
private fun ActivityBarColumn(
    bar: ActivityBar,
    valueLabel: String,
    hasValue: Boolean,
    fraction: Float,
    labelAlpha: Float,
    modifier: Modifier = Modifier,
) {
    val locale = LocalLocale.current.platformLocale
    val hideZeroLabel = !hasValue && LocalConfiguration.current.screenWidthDp < 360
    val barHeight = max(if (hasValue) 18f else 8f, 100f * fraction).dp

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
    ) {
        Text(
            text = if (hideZeroLabel) "" else valueLabel,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().alpha(labelAlpha),
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
            modifier = Modifier.fillMaxWidth().alpha(labelAlpha),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

private fun ActivityBar.labelFor(
    metric: ActivityChartMetric,
    unitSystem: UnitSystemSetting,
    currency: Currency,
    locale: Locale,
): String =
    when (metric) {
        ActivityChartMetric.DISTANCE -> {
            formatDistance(distanceMeters, unitSystem)
        }

        ActivityChartMetric.TRAVEL_TIME -> {
            formatCompactDuration(durationMillis)
        }

        ActivityChartMetric.COST -> {
            val fractionDigits = currency.defaultFractionDigits.takeIf { it >= 0 } ?: 2
            NumberFormat
                .getCurrencyInstance(locale)
                .apply { this.currency = currency }
                .format(BigDecimal.valueOf(costMinorByCurrency[currency.currencyCode] ?: 0L, fractionDigits))
        }
    }
