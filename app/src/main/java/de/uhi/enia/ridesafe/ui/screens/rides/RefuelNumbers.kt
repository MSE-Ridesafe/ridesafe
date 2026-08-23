package de.uhi.enia.ridesafe.ui.screens.rides

import de.uhi.enia.ridesafe.util.UnitSystemSetting
import de.uhi.enia.ridesafe.util.usesMetric
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency
import java.util.Locale

private val MILLILITERS_PER_LITER = BigDecimal("1000")
private val METERS_PER_KILOMETER = BigDecimal("1000")
private val METERS_PER_MILE = BigDecimal("1609.344")

/** Strict decimal parsing that accepts either decimal mark, but never grouping or mixed separators. */
fun parseRefuelDecimal(text: String): BigDecimal? {
    val value = text.trim()
    if (value.isEmpty() || value.any { it.isWhitespace() }) return null
    if (value.count { it == ',' } > 1 || value.count { it == '.' } > 1) return null
    if (',' in value && '.' in value) return null
    if (!value.matches(Regex("[+-]?(?:\\d+(?:[.,]\\d*)?|[.,]\\d+)"))) return null
    return runCatching { value.replace(',', '.').toBigDecimal() }.getOrNull()
}

fun litersToMilliliters(value: BigDecimal): Long? = exactScaledLong(value, MILLILITERS_PER_LITER)

fun currencyUnitsToMinor(
    value: BigDecimal,
    fractionDigits: Int,
): Long? = exactScaledLong(value, BigDecimal.TEN.pow(fractionDigits.coerceAtLeast(0)))

fun odometerToMeters(
    value: BigDecimal,
    unitSystem: UnitSystemSetting,
): Long? =
    runCatching {
        value
            .multiply(if (usesMetric(unitSystem)) METERS_PER_KILOMETER else METERS_PER_MILE)
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()
    }.getOrNull()

fun pricePerLiter(
    totalPriceMinor: Long,
    fuelAmountMilliliters: Long,
    fractionDigits: Int,
): BigDecimal? {
    if (fuelAmountMilliliters <= 0) return null
    val major = BigDecimal.valueOf(totalPriceMinor, fractionDigits.coerceAtLeast(0))
    val liters = BigDecimal.valueOf(fuelAmountMilliliters, 3)
    return major.divide(liters, 6, RoundingMode.HALF_UP)
}

fun defaultCurrency(locale: Locale): Currency =
    runCatching { Currency.getInstance(locale) }.getOrElse { Currency.getInstance("EUR") }

private fun exactScaledLong(
    value: BigDecimal,
    factor: BigDecimal,
): Long? =
    runCatching {
        value.multiply(factor).setScale(0, RoundingMode.UNNECESSARY).longValueExact()
    }.getOrNull()
