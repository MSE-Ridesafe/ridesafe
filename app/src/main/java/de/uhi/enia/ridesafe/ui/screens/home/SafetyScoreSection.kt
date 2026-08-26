package de.uhi.enia.ridesafe.ui.screens.home

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.data.SafetyScore
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.ui.components.SafetyScoreCard

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

/**
 * The dashboard's safety score card (DSH-06): the shared gauge card with a period selector in its
 * control slot. A period with nothing scored in it keeps the card up and says so, rather than
 * flickering the whole card away when a chip is tapped.
 */
@Composable
fun SafetyScoreSection(
    week: SafetyScore?,
    month: SafetyScore?,
    allTime: SafetyScore?,
) {
    var window by rememberSaveable { mutableStateOf(ScoreWindow.ALL_TIME) }
    SafetyScoreCard(
        score =
            when (window) {
                ScoreWindow.WEEK -> week
                ScoreWindow.MONTH -> month
                ScoreWindow.ALL_TIME -> allTime
            },
        emptyText = stringResource(R.string.home_score_empty),
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
