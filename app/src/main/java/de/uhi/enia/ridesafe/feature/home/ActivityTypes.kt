package de.uhi.enia.ridesafe.feature.home

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

fun ActivityBar.valueFor(
    metric: ActivityChartMetric,
    currencyCode: String,
): Double =
    when (metric) {
        ActivityChartMetric.DISTANCE -> distanceMeters
        ActivityChartMetric.TRAVEL_TIME -> durationMillis.toDouble()
        ActivityChartMetric.COST -> (costMinorByCurrency[currencyCode] ?: 0L).toDouble()
    }

fun activityScaleMaximum(
    activity: Collection<ActivityBar>,
    metric: ActivityChartMetric,
    currencyCode: String,
): Double = (activity.maxOfOrNull { it.valueFor(metric, currencyCode) } ?: 0.0).coerceAtLeast(1.0)
