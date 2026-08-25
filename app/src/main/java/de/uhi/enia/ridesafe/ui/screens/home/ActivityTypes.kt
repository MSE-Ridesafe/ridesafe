package de.uhi.enia.ridesafe.ui.screens.home

import de.uhi.enia.ridesafe.R

enum class ActivityChartMetric {
    DISTANCE,
    TRAVEL_TIME,
    COST,
}

val ActivityChartMetric.labelRes: Int
    get() =
        when (this) {
            ActivityChartMetric.DISTANCE -> R.string.home_activity_metric_distance
            ActivityChartMetric.TRAVEL_TIME -> R.string.home_activity_metric_time
            ActivityChartMetric.COST -> R.string.home_activity_metric_cost
        }

fun ActivityBar.valueFor(metric: ActivityChartMetric): Double =
    when (metric) {
        ActivityChartMetric.DISTANCE -> distanceMeters
        ActivityChartMetric.TRAVEL_TIME -> durationMillis.toDouble()
        ActivityChartMetric.COST -> costMinor.toDouble()
    }
