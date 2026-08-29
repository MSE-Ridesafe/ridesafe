package de.uhi.enia.ridesafe.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** Covers the language/region merge behind [formattingLocale] — words from the app, region from the device. */
class RegionalLocaleTest {
    @Test
    fun `bare app language takes region and extensions from the device`() {
        val merged =
            mergeRegional(
                Locale.forLanguageTag("en"),
                Locale.forLanguageTag("de-DE-u-ms-metric-mu-celsius"),
            )
        assertEquals("en-DE-u-ms-metric-mu-celsius", merged.toLanguageTag())
    }

    @Test
    fun `same language still picks up the device region`() {
        val merged = mergeRegional(Locale.forLanguageTag("de"), Locale.forLanguageTag("de-CH"))
        assertEquals("de-CH", merged.toLanguageTag())
    }

    @Test
    fun `device region without extensions merges cleanly`() {
        val merged = mergeRegional(Locale.forLanguageTag("de"), Locale.forLanguageTag("en-US"))
        assertEquals("de-US", merged.toLanguageTag())
    }

    @Test
    fun `app script survives the merge`() {
        val merged = mergeRegional(Locale.forLanguageTag("sr-Latn"), Locale.forLanguageTag("de-DE"))
        assertEquals("sr-Latn-DE", merged.toLanguageTag())
    }
}
