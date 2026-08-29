package de.uhi.enia.ridesafe.ui.screens.home

import android.text.format.DateFormat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.uhi.enia.ridesafe.util.formattingLocale
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** The 0–100 span all score charts share; a bar or point at 100 exactly reaches the top gridline. */
private val PlotHeight = 110.dp

/** Room above the plot for a full bar's value label, and matching offset for the y-axis column. */
private val LabelHeadroom = 18.dp

/** Width of the y-axis gutter — identical across the charts so switching periods doesn't shift the plot. */
private val AxisGutter = 26.dp

/**
 * One bar on a score history chart: the period's label and its combined score, or null for a period
 * with nothing scoreable. The distinction matters visually — null draws a stub, never a zero-height
 * bar, because a zero is the worst possible driver while an empty week is no driver at all.
 */
data class ScoreBar(
    val label: String,
    val score: Int?,
)

/** The 100 / 50 / 0 scale every score chart wears, right-aligned against its plot. */
@Composable
private fun ScoreAxis(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.height(PlotHeight).width(AxisGutter),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.End,
    ) {
        listOf("100", "50", "0").forEach {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Gridlines at 100 / 50 / 0, the last doubling as the x-axis, drawn a step stronger. */
private fun DrawScope.drawScoreGrid(
    topOffsetPx: Float,
    grid: Color,
    axis: Color,
) {
    val plot = size.height - topOffsetPx
    listOf(0f, 0.5f).forEach { f ->
        val y = topOffsetPx + plot * f
        drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
    }
    drawLine(axis, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
}

/**
 * A run of period scores as bars — one per week or one per month, whichever the caller hands over
 * (DSH-04). Bar heights are exactly proportional to the shared 0–100 scale the axis promises: a 74
 * must look like a 74 whichever period is on screen, which is also why the scale is fixed rather
 * than fitted to the data.
 */
@Composable
fun ScoreBarChart(bars: List<ScoreBar>) {
    val grid = MaterialTheme.colorScheme.surfaceContainerHighest
    val axis = MaterialTheme.colorScheme.outlineVariant
    Row {
        ScoreAxis(modifier = Modifier.padding(top = LabelHeadroom))
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Box(modifier = Modifier.fillMaxWidth().height(LabelHeadroom + PlotHeight)) {
                Canvas(Modifier.fillMaxSize()) { drawScoreGrid(LabelHeadroom.toPx(), grid, axis) }
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    bars.forEach { bar ->
                        // Proportional to the axis, with just enough floor to stay visible: a scored
                        // bar can be tiny (a terrible week really is nearly nothing on this scale),
                        // while a missing period draws a flat stub in the empty-bar colour.
                        val barHeight by animateDpAsState(
                            targetValue = if (bar.score != null) (PlotHeight * bar.score / 100f).coerceAtLeast(3.dp) else 4.dp,
                            animationSpec = tween(durationMillis = 250),
                            label = "score_bar_height",
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom,
                        ) {
                            Text(
                                text = bar.score?.toString() ?: "",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Clip,
                            )
                            Spacer(Modifier.height(2.dp))
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .height(barHeight)
                                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                        .background(
                                            if (bar.score != null) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.surfaceContainerHighest
                                            },
                                        ),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                bars.forEach { bar ->
                    // Same 10sp as the value labels: eight "KW34"s have to share the row, and the
                    // house labelMedium is wide enough to clip them to a meaningless "KW3".
                    Text(
                        text = bar.label,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
        }
    }
}

/**
 * The all-time score's own history as a line over the same 0–100 scale — each point is the headline
 * figure as it stood at the end of a day with scored driving, so the newest point always agrees
 * with the gauge above it (DSH-04). The line always grows from the left edge — a logbook one day
 * old is a line that has just started, not one floating mid-chart — and the area beneath is washed
 * in the line's own colour so the level reads at a glance. No per-point markers: with a point per
 * day the marks would outnumber the line, and the trend is the subject here, not the days.
 *
 * The x axis is proportional to *time*, not to the point count: a fortnight of daily commutes and a
 * quiet month must not occupy the same width, or the axis lies about when things happened. Ticks
 * mark the axis at its ends and middle rather than marking data points, so their dates are simply
 * where the axis says they are.
 */
@Composable
fun AllTimeScoreLine(points: List<Pair<LocalDate, Int>>) {
    if (points.isEmpty()) return
    val locale = formattingLocale()
    val lineColor = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.surfaceContainerHighest
    val axis = MaterialTheme.colorScheme.outlineVariant
    Row {
        ScoreAxis()
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            val firstDay = points.first().first.toEpochDay()
            val daySpan = (points.last().first.toEpochDay() - firstDay).coerceAtLeast(1)
            Canvas(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(PlotHeight + 4.dp),
            ) {
                val plotBottom = PlotHeight.toPx()

                fun at(index: Int): Offset {
                    val x =
                        if (points.size == 1) 0f else size.width * (points[index].first.toEpochDay() - firstDay) / daySpan
                    return Offset(x, plotBottom * (1 - points[index].second / 100f))
                }

                listOf(0f, 0.5f).forEach { f ->
                    drawLine(grid, Offset(0f, plotBottom * f), Offset(size.width, plotBottom * f), strokeWidth = 1.dp.toPx())
                }
                // The x-axis, with a tick where each date label below points at.
                drawLine(axis, Offset(0f, plotBottom), Offset(size.width, plotBottom), strokeWidth = 1.dp.toPx())
                axisFractions(points.size).forEach { f ->
                    val x = size.width * f
                    drawLine(axis, Offset(x, plotBottom), Offset(x, plotBottom + 4.dp.toPx()), strokeWidth = 1.dp.toPx())
                }

                if (points.size == 1) {
                    // One day of history has no extent to draw a line through; a single dot at the
                    // origin-edge is the honest start of the curve.
                    drawCircle(lineColor, 3.dp.toPx(), at(0))
                    return@Canvas
                }
                val line =
                    Path().apply {
                        moveTo(at(0).x, at(0).y)
                        for (i in 1 until points.size) lineTo(at(i).x, at(i).y)
                    }
                val area =
                    Path().apply {
                        addPath(line)
                        lineTo(size.width, plotBottom)
                        lineTo(0f, plotBottom)
                        close()
                    }
                drawPath(
                    area,
                    Brush.verticalGradient(
                        0f to lineColor.copy(alpha = 0.30f),
                        1f to lineColor.copy(alpha = 0.02f),
                        endY = plotBottom,
                    ),
                )
                drawPath(line, lineColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            }
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                // Full year only once it disambiguates: within one calendar year "26.8." cannot be
                // misread, while a log spanning years needs "26.8.25" to say which August.
                val skeleton = if (points.first().first.year == points.last().first.year) "Md" else "yyMd"
                val formatter = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
                axisFractions(points.size).forEach { f ->
                    Text(
                        text =
                            points
                                .first()
                                .first
                                .plusDays((daySpan * f).toLong())
                                .format(formatter),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/** Where the x axis carries ticks and date labels: its ends, plus the middle once a span exists. */
private fun axisFractions(count: Int): List<Float> =
    when {
        count == 1 -> listOf(0f)
        count < 5 -> listOf(0f, 1f)
        else -> listOf(0f, 0.5f, 1f)
    }
