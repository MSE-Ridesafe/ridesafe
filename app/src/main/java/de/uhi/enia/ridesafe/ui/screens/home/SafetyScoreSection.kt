package de.uhi.enia.ridesafe.ui.screens.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.SafetyScore
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.ui.components.SafetyScoreCard
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields

/**
 * The periods the dashboard score can be read over (DSH-06). All-time is the default and the
 * headline — deliberately slow-moving, it describes the driver rather than the week — while month
 * and week are where recent improvement actually shows up.
 */
enum class ScoreWindow(
    val labelRes: Int,
) {
    WEEK(R.string.home_activity_period_week),
    MONTH(R.string.home_activity_period_month),
    ALL_TIME(R.string.home_score_period_all),
}

/** How much history the score charts show: the last [WEEK_BARS] weeks and [MONTH_BARS] months. */
private const val WEEK_BARS = 8
private const val MONTH_BARS = 12

/**
 * The dashboard's safety score card (DSH-06): the shared gauge card with a period selector in its
 * control slot and the period's history in its chart slot (DSH-04) — a bar per week for the last
 * weeks, a bar per month for the last year, and the all-time figure's own history as a line. Only
 * the combined score is charted; the dimensions stay on their gauges, where the split belongs. A
 * period with nothing scored in it keeps the card up and says so, rather than flickering the whole
 * card away when a chip is tapped.
 */
@Composable
fun SafetyScoreSection(
    week: SafetyScore?,
    month: SafetyScore?,
    allTime: SafetyScore?,
    scoreByWeek: Map<LocalDate, Int>,
    scoreByMonth: Map<YearMonth, Int>,
    scoreHistory: List<Pair<LocalDate, Int>>,
) {
    var window by rememberSaveable { mutableStateOf(ScoreWindow.ALL_TIME) }
    val today = LocalDate.now()
    val locale = LocalLocale.current.platformLocale
    SafetyScoreCard(
        score =
            when (window) {
                ScoreWindow.WEEK -> week
                ScoreWindow.MONTH -> month
                ScoreWindow.ALL_TIME -> allTime
            },
        emptyText = stringResource(R.string.home_score_empty),
        largeTitle = true,
        subtitle =
            stringResource(
                when (window) {
                    ScoreWindow.WEEK -> R.string.home_score_dev_week
                    ScoreWindow.MONTH -> R.string.home_score_dev_month
                    ScoreWindow.ALL_TIME -> R.string.home_score_dev_all
                },
            ),
        chart = {
            Crossfade(
                targetState = window,
                animationSpec = tween(durationMillis = 250),
                label = "score_chart",
            ) { target ->
                when (target) {
                    ScoreWindow.WEEK -> {
                        val thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                        // Calendar-week labels, not the Monday's date: a date under a bar reads as a
                        // single day, and these bars are whole weeks.
                        ScoreBarChart(
                            (WEEK_BARS - 1 downTo 0).map { offset ->
                                val monday = thisMonday.minusWeeks(offset.toLong())
                                ScoreBar(
                                    stringResource(R.string.score_week_label, monday.get(WeekFields.ISO.weekOfWeekBasedYear())),
                                    scoreByWeek[monday],
                                )
                            },
                        )
                    }

                    ScoreWindow.MONTH -> {
                        val currentMonth = YearMonth.from(today)
                        ScoreBarChart(
                            (MONTH_BARS - 1 downTo 0).map { offset ->
                                val candidate = currentMonth.minusMonths(offset.toLong())
                                ScoreBar(
                                    candidate.month.getDisplayName(TextStyle.NARROW, locale),
                                    scoreByMonth[candidate],
                                )
                            },
                        )
                    }

                    ScoreWindow.ALL_TIME -> {
                        AllTimeScoreLine(scoreHistory)
                    }
                }
            }
        },
        controls = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScoreWindow.entries.forEach { candidate ->
                    FilterChip(
                        selected = window == candidate,
                        onClick = { window = candidate },
                        colors =
                            FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ),
                        label = { Text(stringResource(candidate.labelRes)) },
                        leadingIcon =
                            if (window == candidate) {
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
        },
    )
}
