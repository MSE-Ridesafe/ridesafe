package de.uhi.enia.ridesafe.ui.screens.home

import de.uhi.enia.ridesafe.R

enum class ActivityChartMetric {
    DISTANCE,
    TRAVEL_TIME,
}

enum class ActivityTimeRange {
    WEEK,
    MONTH,
}

val ActivityTimeRange.labelRes: Int
    get() =
        when (this) {
            ActivityTimeRange.WEEK -> R.string.home_activity_period_week
            ActivityTimeRange.MONTH -> R.string.home_activity_period_month
        }

val ActivityChartMetric.labelRes: Int
    get() =
        when (this) {
            ActivityChartMetric.DISTANCE -> R.string.home_activity_metric_distance
            ActivityChartMetric.TRAVEL_TIME -> R.string.home_activity_metric_time
        }

fun ActivityBar.valueFor(metric: ActivityChartMetric): Double =
    when (metric) {
        ActivityChartMetric.DISTANCE -> distanceMeters
        ActivityChartMetric.TRAVEL_TIME -> durationMillis.toDouble()
    }
