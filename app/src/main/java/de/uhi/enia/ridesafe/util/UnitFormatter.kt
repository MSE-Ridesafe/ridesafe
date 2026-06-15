package de.uhi.enia.ridesafe.util

import android.content.Context
import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.icu.util.LocaleData
import android.icu.util.ULocale
import androidx.core.content.edit

enum class UnitSystemSetting {
    AUTOMATIC, METRIC, IMPERIAL
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

    fun set(context: Context, value: UnitSystemSetting) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { putString(KEY_UNIT_SYSTEM, value.name) }
    }
}

fun getFormattingLocale(context: Context, setting: UnitSystemSetting): java.util.Locale {
    return if (setting == UnitSystemSetting.AUTOMATIC) {
        val systemLocales = android.content.res.Resources.getSystem().configuration.locales
        if (!systemLocales.isEmpty) {
            systemLocales.get(0)
        } else {
            java.util.Locale.getDefault()
        }
    } else {
        java.util.Locale.getDefault()
    }
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

fun formatDistance(context: Context, meters: Double, setting: UnitSystemSetting): String {
    val formatLocale = getFormattingLocale(context, setting)
    val isMetric = when (setting) {
        UnitSystemSetting.METRIC -> true
        UnitSystemSetting.IMPERIAL -> false
        UnitSystemSetting.AUTOMATIC -> isMetric(formatLocale)
    }
    val (value, unit) = if (isMetric) {
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
