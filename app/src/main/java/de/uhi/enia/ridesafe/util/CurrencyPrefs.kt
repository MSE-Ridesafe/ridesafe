package de.uhi.enia.ridesafe.util

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
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

object CurrencyPrefs {
    private const val PREFS_NAME = "ridesafe_prefs"
    private const val KEY_CURRENCY = "currency"

    private var cached by mutableStateOf<CurrencySetting?>(null)

    fun get(context: Context): CurrencySetting = cached ?: read(context).also { cached = it }

    fun set(context: Context, value: CurrencySetting) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_CURRENCY, value.name)
        }
        cached = value
    }

    private fun read(context: Context): CurrencySetting {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_CURRENCY, null)
        return stored?.let { runCatching { CurrencySetting.valueOf(it) }.getOrNull() }
            ?: defaultCurrencySetting(context.resources.configuration.locales[0] ?: Locale.getDefault())
    }
}

@Composable
fun currentCurrencySetting(): CurrencySetting = CurrencyPrefs.get(LocalContext.current)
