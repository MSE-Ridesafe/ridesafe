package de.uhi.enia.ridesafe.ui.screens.home

import android.content.Context
import de.uhi.enia.ridesafe.R
import java.text.NumberFormat
import java.util.Locale

fun formatCompactDuration(durationMillis: Long): String {
    val totalMinutes = (durationMillis / 60_000).coerceAtLeast(0)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 -> "%d h %02d min".format(hours, minutes)
        else -> "%d min".format(minutes)
    }
}

fun formatLiveRideDuration(
    startedAtEpochMs: Long,
    nowEpochMs: Long,
): String {
    val totalSeconds = ((nowEpochMs - startedAtEpochMs) / 1_000).coerceAtLeast(0)
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

fun formatFuelConsumption(
    context: Context,
    fuelEconomy: Double?,
): String {
    if (fuelEconomy == null) {
        return context.getString(R.string.value_not_set)
    }
    val number =
        NumberFormat.getNumberInstance(Locale.getDefault()).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
    return "${number.format(fuelEconomy)} ${context.getString(R.string.unit_fuel_economy)}"
}

fun formatRecordedRideCount(
    context: Context,
    count: Int,
): String = NumberFormat.getIntegerInstance(context.resources.configuration.locales[0]).format(count)
