package de.uhi.enia.ridesafe.ui.screens.home

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

fun formatRecordedRideCount(count: Int): String = NumberFormat.getIntegerInstance(formattingLocale()).format(count)
