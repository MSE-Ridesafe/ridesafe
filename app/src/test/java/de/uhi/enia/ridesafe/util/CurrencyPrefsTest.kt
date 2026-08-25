package de.uhi.enia.ridesafe.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class CurrencyPrefsTest {
    @Test
    fun supportedLocalesResolveToTheirCurrency() {
        assertEquals(CurrencySetting.US_DOLLAR, defaultCurrencySetting(Locale.US))
        assertEquals(CurrencySetting.BRITISH_POUND, defaultCurrencySetting(Locale.UK))
        assertEquals(CurrencySetting.SWISS_FRANC, defaultCurrencySetting(Locale.Builder().setLanguage("de").setRegion("CH").build()))
        assertEquals(CurrencySetting.EURO, defaultCurrencySetting(Locale.GERMANY))
    }

    @Test
    fun unsupportedCurrencyFallsBackToEuro() {
        assertEquals(CurrencySetting.EURO, defaultCurrencySetting(Locale.JAPAN))
    }

    @Test
    fun settingsExposeStableIsoCodes() {
        assertEquals(listOf("USD", "GBP", "CHF", "EUR"), CurrencySetting.entries.map { it.currencyCode })
    }
}
