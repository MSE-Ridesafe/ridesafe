package de.uhi.enia.ridesafe.ui.screens.rides

import de.uhi.enia.ridesafe.data.Refuel
import de.uhi.enia.ridesafe.util.UnitSystemSetting
import de.uhi.enia.ridesafe.util.usesMetric
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import java.util.Currency
import java.util.Locale
import java.time.Instant
import java.time.ZoneId

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

/** Editable plain decimal text using the locale's decimal mark and no grouping separators. */
fun formatRefuelInput(
    value: BigDecimal,
    locale: Locale,
): String =
    value
        .stripTrailingZeros()
        .toPlainString()
        .replace('.', DecimalFormatSymbols.getInstance(locale).decimalSeparator)

fun odometerMetersToDisplay(
    meters: Long,
    unitSystem: UnitSystemSetting,
): BigDecimal =
    BigDecimal.valueOf(meters).divide(
        if (usesMetric(unitSystem)) METERS_PER_KILOMETER else METERS_PER_MILE,
        if (usesMetric(unitSystem)) 3 else 6,
        RoundingMode.HALF_UP,
    )

data class RefuelFormInitialValues(
    val vehicleId: Long,
    val dateEpochDay: Long,
    val hour: Int,
    val minute: Int,
    val fuelText: String,
    val totalText: String,
    val odometerText: String,
    val stationText: String,
    val stationSavedAddressId: Long?,
    val fullTank: Boolean,
)

fun refuelFormInitialValues(
    refuel: Refuel,
    unitSystem: UnitSystemSetting,
    locale: Locale,
    zoneId: ZoneId = ZoneId.systemDefault(),
): RefuelFormInitialValues {
    val currency = runCatching { Currency.getInstance(refuel.currencyCode) }.getOrElse { defaultCurrency(locale) }
    val fractionDigits = currency.defaultFractionDigits.takeIf { it >= 0 } ?: 2
    val dateTime = Instant.ofEpochMilli(refuel.timestampEpochMs).atZone(zoneId)
    return RefuelFormInitialValues(
        vehicleId = refuel.vehicleId,
        dateEpochDay = dateTime.toLocalDate().toEpochDay(),
        hour = dateTime.hour,
        minute = dateTime.minute,
        fuelText = formatRefuelInput(BigDecimal.valueOf(refuel.fuelAmountMilliliters, 3), locale),
        totalText = formatRefuelInput(BigDecimal.valueOf(refuel.totalPriceMinor, fractionDigits), locale),
        odometerText = formatRefuelInput(odometerMetersToDisplay(refuel.odometerMeters, unitSystem), locale),
        stationText = refuel.stationAddress.orEmpty(),
        stationSavedAddressId = refuel.stationSavedAddressId,
        fullTank = refuel.isFullTank,
    )
}

private fun exactScaledLong(
    value: BigDecimal,
    factor: BigDecimal,
): Long? =
    runCatching {
        value.multiply(factor).setScale(0, RoundingMode.UNNECESSARY).longValueExact()
    }.getOrNull()
