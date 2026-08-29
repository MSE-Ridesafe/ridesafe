package de.uhi.enia.ridesafe.util

import android.content.Context
import android.icu.text.MeasureFormat
import android.icu.text.NumberFormat
import android.icu.util.LocaleData
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.icu.util.ULocale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import de.uhi.enia.ridesafe.R
import kotlin.math.roundToLong

/** Number format capping trip measurements (distance, speed) at one fraction digit for UI legibility. */
private fun oneDecimal(locale: java.util.Locale): NumberFormat = NumberFormat.getInstance(locale).apply { maximumFractionDigits = 1 }

enum class UnitSystemSetting {
    AUTOMATIC,
    METRIC,
    IMPERIAL,
}

/**
 * The unit setting, read straight from the preference wherever it is needed.
 *
 * [get] is backed by snapshot state, so a composable that calls it subscribes to it: [set]
 * updates every screen already on screen. Handing the value down as a parameter instead does
 * not work here — a screen composed by NavDisplay keeps the value it was built with until the
 * back stack changes, which is why picking a unit used to leave the radio button behind.
 */
object UnitPrefs {
    private const val PREFS_NAME = "ridesafe_prefs"
    private const val KEY_UNIT_SYSTEM = "unit_system"

    private var cached by mutableStateOf<UnitSystemSetting?>(null)

    fun get(context: Context): UnitSystemSetting = cached ?: read(context).also { cached = it }

    fun set(
        context: Context,
        value: UnitSystemSetting,
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_UNIT_SYSTEM, value.name)
        }
        cached = value
    }

    private fun read(context: Context): UnitSystemSetting {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_UNIT_SYSTEM, UnitSystemSetting.AUTOMATIC.name)
        return try {
            UnitSystemSetting.valueOf(name ?: UnitSystemSetting.AUTOMATIC.name)
        } catch (_: Exception) {
            UnitSystemSetting.AUTOMATIC
        }
    }
}

/** The unit setting, live — the caller recomposes when it changes. */
@Composable
fun currentUnitSystem(): UnitSystemSetting = UnitPrefs.get(LocalContext.current)

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
fun usesMetric(setting: UnitSystemSetting): Boolean =
    when (setting) {
        UnitSystemSetting.METRIC -> true
        UnitSystemSetting.IMPERIAL -> false
        UnitSystemSetting.AUTOMATIC -> isMetric(formattingLocale())
    }

fun formatDistance(
    meters: Double,
    setting: UnitSystemSetting,
): String {
    val formatLocale = formattingLocale()
    val isMetric = usesMetric(setting)
    val (value, unit) =
        if (isMetric) {
            val km = meters / 1000.0
            km to MeasureUnit.KILOMETER
        } else {
            val miles = meters * 0.000621371
            miles to MeasureUnit.MILE
        }

    val measure = Measure(value, unit)
    val formatter = MeasureFormat.getInstance(formatLocale, MeasureFormat.FormatWidth.SHORT, oneDecimal(formatLocale))
    return formatter.format(measure)
}

/**
 * Short distance from canonical [meters] in the user's small units (m or ft), rounded to a whole
 * unit, e.g. "120 m" / "390 ft". For sub-kilometer figures like a saved-address offset (ADR-09),
 * where [formatDistance]'s km/mi would read "0.12 km".
 */
fun formatShortDistance(
    meters: Double,
    setting: UnitSystemSetting,
): String {
    val formatLocale = formattingLocale()
    val (value, unit) =
        if (usesMetric(setting)) {
            meters.roundToLong() to MeasureUnit.METER
        } else {
            (meters * 3.280839895).roundToLong() to MeasureUnit.FOOT
        }
    val formatter = MeasureFormat.getInstance(formatLocale, MeasureFormat.FormatWidth.SHORT)
    return formatter.format(Measure(value, unit))
}

/**
 * Speed from canonical [metersPerSecond] in the user's units, e.g. "92.4 km/h" / "57.3 mph". Uses the
 * app's own unit strings rather than ICU's speed units, which render mph as the unconventional "mi/h".
 */
fun formatSpeed(
    context: Context,
    metersPerSecond: Double,
    setting: UnitSystemSetting,
): String {
    val formatLocale = formattingLocale()
    val (value, unitRes) =
        if (usesMetric(setting)) {
            metersPerSecond * 3.6 to R.string.unit_kmh
        } else {
            metersPerSecond * 2.2369362920544 to R.string.unit_mph
        }
    return "${oneDecimal(formatLocale).format(value)} ${context.getString(unitRes)}"
}

/**
 * Odometer reading from canonical [kilometers], rounded to a whole unit (an odometer
 * is a whole-number reading, unlike a trip distance). Returns e.g. "120,000 mi".
 */
fun formatOdometer(
    kilometers: Int,
    setting: UnitSystemSetting,
): String {
    val formatLocale = formattingLocale()
    val (value, unit) =
        if (usesMetric(setting)) {
            kilometers.toLong() to MeasureUnit.KILOMETER
        } else {
            (kilometers * 0.621371).roundToLong() to MeasureUnit.MILE
        }
    val formatter = MeasureFormat.getInstance(formatLocale, MeasureFormat.FormatWidth.SHORT)
    return formatter.format(Measure(value, unit))
}
