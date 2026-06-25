package de.uhi.enia.ridesafe.util

import android.content.Context
import android.icu.text.MeasureFormat
import android.icu.util.LocaleData
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.icu.util.ULocale
import androidx.core.content.edit
import kotlin.math.roundToLong

enum class UnitSystemSetting {
    AUTOMATIC,
    METRIC,
    IMPERIAL,
}

object UnitPrefs {
    private const val PREFS_NAME = "ridesafe_prefs"
    private const val KEY_UNIT_SYSTEM = "unit_system"

    fun get(context: Context): UnitSystemSetting {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_UNIT_SYSTEM, UnitSystemSetting.AUTOMATIC.name)
        return try {
            UnitSystemSetting.valueOf(name ?: UnitSystemSetting.AUTOMATIC.name)
        } catch (e: Exception) {
            UnitSystemSetting.AUTOMATIC
        }
    }

    fun set(
        context: Context,
        value: UnitSystemSetting,
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { putString(KEY_UNIT_SYSTEM, value.name) }
    }
}

fun getFormattingLocale(
    context: Context,
    setting: UnitSystemSetting,
): java.util.Locale =
    if (setting == UnitSystemSetting.AUTOMATIC) {
        val systemLocales =
            android.content.res.Resources
                .getSystem()
                .configuration.locales
        if (!systemLocales.isEmpty) {
            systemLocales.get(0)
        } else {
            java.util.Locale.getDefault()
        }
    } else {
        java.util.Locale.getDefault()
    }

fun isMetric(locale: java.util.Locale): Boolean {
    val msExtension = locale.getUnicodeLocaleType("ms")
    if (msExtension != null) {
        return msExtension == "metric"
    }
    val currentULocale = ULocale.forLocale(locale)
    val measurementSystem = LocaleData.getMeasurementSystem(currentULocale)
    return measurementSystem != LocaleData.MeasurementSystem.US &&
        measurementSystem != LocaleData.MeasurementSystem.UK
}

/** Whether the [setting] resolves to metric (km) rather than imperial (mi). */
fun usesMetric(
    context: Context,
    setting: UnitSystemSetting,
): Boolean =
    when (setting) {
        UnitSystemSetting.METRIC -> true
        UnitSystemSetting.IMPERIAL -> false
        UnitSystemSetting.AUTOMATIC -> isMetric(getFormattingLocale(context, setting))
    }

fun formatDistance(
    context: Context,
    meters: Double,
    setting: UnitSystemSetting,
): String {
    val formatLocale = getFormattingLocale(context, setting)
    val isMetric = usesMetric(context, setting)
    val (value, unit) =
        if (isMetric) {
            val km = meters / 1000.0
            km to MeasureUnit.KILOMETER
        } else {
            val miles = meters * 0.000621371
            miles to MeasureUnit.MILE
        }

    val measure = Measure(value, unit)
    val formatter = MeasureFormat.getInstance(formatLocale, MeasureFormat.FormatWidth.SHORT)
    return formatter.format(measure)
}

/** Speed from canonical [metersPerSecond] in the user's units (km/h or mph), e.g. "92 km/h". */
fun formatSpeed(
    context: Context,
    metersPerSecond: Double,
    setting: UnitSystemSetting,
): String {
    val formatLocale = getFormattingLocale(context, setting)
    val (value, unit) =
        if (usesMetric(context, setting)) {
            metersPerSecond * 3.6 to MeasureUnit.KILOMETER_PER_HOUR
        } else {
            metersPerSecond * 2.2369362920544 to MeasureUnit.MILE_PER_HOUR
        }
    val formatter = MeasureFormat.getInstance(formatLocale, MeasureFormat.FormatWidth.SHORT)
    return formatter.format(Measure(value, unit))
}

/**
 * Odometer reading from canonical [kilometers], rounded to a whole unit (an odometer
 * is a whole-number reading, unlike a trip distance). Returns e.g. "120,000 mi".
 */
fun formatOdometer(
    context: Context,
    kilometers: Int,
    setting: UnitSystemSetting,
): String {
    val formatLocale = getFormattingLocale(context, setting)
    val (value, unit) =
        if (usesMetric(context, setting)) {
            kilometers.toLong() to MeasureUnit.KILOMETER
        } else {
            (kilometers * 0.621371).roundToLong() to MeasureUnit.MILE
        }
    val formatter = MeasureFormat.getInstance(formatLocale, MeasureFormat.FormatWidth.SHORT)
    return formatter.format(Measure(value, unit))
}
