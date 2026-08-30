package de.uhi.enia.ridesafe.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import java.util.Currency
import java.util.Locale

enum class CurrencySetting(
    val currencyCode: String,
) {
    US_DOLLAR("USD"),
    BRITISH_POUND("GBP"),
    SWISS_FRANC("CHF"),
    EURO("EUR"),
    ;

    val currency: Currency get() = Currency.getInstance(currencyCode)
}

fun defaultCurrencySetting(locale: Locale): CurrencySetting {
    val code = runCatching { Currency.getInstance(locale).currencyCode }.getOrNull()
    return CurrencySetting.entries.firstOrNull { it.currencyCode == code } ?: CurrencySetting.EURO
}

/** Unset until the user picks one: the default follows the region until then (see [EnumPref]). */
object CurrencyPrefs : EnumPref<CurrencySetting>("currency", CurrencySetting.entries, { defaultCurrencySetting(formattingLocale()) })

@Composable
fun currentCurrencySetting(): CurrencySetting = CurrencyPrefs.get(LocalContext.current)
