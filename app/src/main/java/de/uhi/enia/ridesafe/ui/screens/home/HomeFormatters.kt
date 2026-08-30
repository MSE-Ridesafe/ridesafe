package de.uhi.enia.ridesafe.ui.screens.home

import android.content.Context
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.util.formattingLocale
import java.text.NumberFormat

fun formatCompactDuration(durationMillis: Long): String {
    val totalMinutes = (durationMillis / 60_000).coerceAtLeast(0)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 -> "%d h %02d min".format(hours, minutes)
        else -> "%d min".format(minutes)
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
        NumberFormat.getNumberInstance(formattingLocale()).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
    return "${number.format(fuelEconomy)} ${context.getString(R.string.unit_fuel_economy)}"
}

fun formatRecordedRideCount(count: Int): String = NumberFormat.getIntegerInstance(formattingLocale()).format(count)
