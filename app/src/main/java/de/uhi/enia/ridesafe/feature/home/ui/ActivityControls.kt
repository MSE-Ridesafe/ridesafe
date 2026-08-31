package de.uhi.enia.ridesafe.feature.home.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import de.uhi.enia.ridesafe.feature.home.ActivityChartMetric
import de.uhi.enia.ridesafe.feature.home.labelRes

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
