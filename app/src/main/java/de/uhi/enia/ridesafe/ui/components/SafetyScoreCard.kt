package de.uhi.enia.ridesafe.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.RideEventType
import de.uhi.enia.ridesafe.data.SafetyScore
import de.uhi.enia.ridesafe.data.symbol

// The gauge is an open ring: 270° of arc with the gap at the bottom, where the label sits.
private const val GAUGE_START_DEG = 135f
private const val GAUGE_SWEEP_DEG = 270f

/**
 * The safety score (ANL-01) as one card: the combined score as a large gauge, the three dimensions
 * as smaller ones beside each other, each with its value in the middle.
 *
 * Shared between the dashboard (DSH-06) and the ride detail, which is what keeps "the score" looking
 * like one thing everywhere it appears. The dimension gauges carry the same symbols the map markers
 * use for the matching event types, so the ring under "podiatry" and the brake markers on the route
 * are recognisably one subject; only the combined gauge shows a number, because one number is the
 * summary and four is a table.
 *
 * [controls] is the slot the dashboard puts its period chips in, [chart] the one its history chart
 * renders in below the gauges; the ride detail leaves both empty. [largeTitle] gives the dashboard
 * the same headline style as its activity card, and [subtitle] the matching line under it — the
 * dashboard uses it to say what the chart below covers, so a mostly-empty chart reads as a period
 * choice rather than missing data. A null [score] renders [emptyText] instead of
 * gauges — the ride that was too short to judge, the week with nothing scored in it — because an
 * empty gauge showing a dash looks broken rather than deliberate.
 */
@Composable
fun SafetyScoreCard(
    score: SafetyScore?,
    modifier: Modifier = Modifier,
    emptyText: String? = null,
    largeTitle: Boolean = false,
    subtitle: String? = null,
    controls: (@Composable ColumnScope.() -> Unit)? = null,
    chart: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceBright),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (largeTitle) {
                Column {
                    Text(
                        text = stringResource(R.string.safety_score_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                SectionTitle(stringResource(R.string.safety_score_title))
            }

            controls?.invoke(this)

            if (score == null) {
                if (emptyText != null) {
                    Text(
                        text = emptyText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
            } else {
                ScoreGauge(
                    value = score.total,
                    label = stringResource(R.string.safety_score_total),
                    diameter = 132.dp,
                    stroke = 12.dp,
                    valueStyle = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    listOf(
                        Triple(score.braking, R.string.safety_score_braking, RideEventType.BRAKING),
                        Triple(score.acceleration, R.string.safety_score_acceleration, RideEventType.ACCELERATION),
                        Triple(score.cornering, R.string.safety_score_cornering, RideEventType.CORNERING),
                    ).forEach { (value, labelRes, eventType) ->
                        ScoreGauge(
                            value = value,
                            label = stringResource(labelRes),
                            diameter = 72.dp,
                            stroke = 8.dp,
                            valueStyle = MaterialTheme.typography.titleLarge,
                            icon = eventType.symbol(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            if (chart != null) {
                CardDivider()
                chart()
            }
        }
    }
}

/**
 * One 0–100 score as an arc gauge with [label] beneath; the arc's gap faces the label so the ring
 * never crowds it. The middle holds the value, or — for the dimension gauges — the [icon] its event
 * type wears on the map, tinted like the arc; the number is still spoken to accessibility either way.
 *
 * Colour comes from the value, not the dimension, so the card reads at a glance: the same green /
 * amber / red judgement everywhere a gauge appears. The sweep animates up from empty on first
 * appearance — [androidx.compose.animation.core.animateFloatAsState] alone would snap to the target
 * on the first frame, so the target starts at zero and moves once composed.
 */
@Composable
private fun ScoreGauge(
    value: Int,
    label: String,
    diameter: Dp,
    stroke: Dp,
    valueStyle: TextStyle,
    modifier: Modifier = Modifier,
    icon: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val color =
        when {
            value >= 80 -> scheme.primary
            value >= 50 -> scheme.tertiary
            else -> scheme.error
        }
    val track = scheme.surfaceContainerHighest

    var target by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(value) { target = value / 100f }
    val fraction by animateFloatAsState(targetValue = target, animationSpec = tween(700), label = "gauge")

    val description = stringResource(R.string.safety_score_gauge, label, value)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(diameter)) {
                val strokePx = stroke.toPx()
                val arcSize = Size(size.width - strokePx, size.height - strokePx)
                val topLeft = Offset(strokePx / 2, strokePx / 2)
                drawArc(
                    color = track,
                    startAngle = GAUGE_START_DEG,
                    sweepAngle = GAUGE_SWEEP_DEG,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
                if (fraction > 0f) {
                    drawArc(
                        color = color,
                        startAngle = GAUGE_START_DEG,
                        sweepAngle = GAUGE_SWEEP_DEG * fraction,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round),
                    )
                }
            }
            if (icon != null) {
                MaterialSymbol(
                    symbolName = icon,
                    contentDescription = null,
                    size = diameter * 0.34f,
                    color = color,
                )
            } else {
                Text(text = value.toString(), style = valueStyle, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
