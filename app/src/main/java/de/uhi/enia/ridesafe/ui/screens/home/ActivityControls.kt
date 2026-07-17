package de.uhi.enia.ridesafe.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityMetricTabs(
    selected: ActivityChartMetric,
    onSelected: (ActivityChartMetric) -> Unit,
) {
    PrimaryTabRow(
        selectedTabIndex = ActivityChartMetric.entries.indexOf(selected),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
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
fun ActivityTimeRangeChips(
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
                    colors =
                        FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
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
