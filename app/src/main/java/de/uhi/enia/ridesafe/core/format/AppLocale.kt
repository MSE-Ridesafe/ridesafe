package de.uhi.enia.ridesafe.core.format

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import java.util.IllformedLocaleException
import java.util.Locale

/**
 * A context whose resources speak the language picked inside Ridesafe (SET-04), rather than the
 * one the surrounding process happens to run in.
 *
 * Activities get the per-app locale applied for them. Everything outside one does not: a
 * foreground-service notification, and above all the Android Auto screens, are built against a
 * context the platform never re-configured, so they would come out in the system language while
 * the app itself is in the other one.
 *
 * Returns the receiver unchanged when no in-app language is set — then the system language is the
 * answer, and there is nothing to override.
 */
fun Context.inAppLanguage(): Context {
    val locales = getSystemService(LocaleManager::class.java)?.applicationLocales ?: return this
    if (locales.isEmpty) return this
    val config = Configuration(resources.configuration).apply { setLocales(locales) }
    return createConfigurationContext(config)
}

/**
 * The application context, so [formattingLocale] can reach [LocaleManager] from the context-free
 * formatters ([formatDistance] & co. are called from many sites that have no reason to carry
 * one). Set once in [de.uhi.enia.ridesafe.RidesafeApplication.onCreate]; null only on the JVM
 * (unit tests), where [formattingLocale] falls back to the app locale.
 */
internal var localeAppContext: Context? = null

/**
 * The locale every value is formatted with: words from the in-app language, region and unicode
 * extensions (measurement system, first day of week, …) from the device settings.
 *
 * The app locale alone is the bug this exists to fix: the SET-05 picker stores a bare "en"/"de",
 * and ICU resolves a bare language to its likely region — "en" formats as the United States, on
 * a phone whose own settings say region Germany and metric. Nor is the system configuration a
 * way back out: once a per-app language is set, the platform rewrites the whole process
 * configuration, [android.content.res.Resources.getSystem] included, so only
 * [LocaleManager.getSystemLocales] (which ignores app-specific overrides, per its contract)
 * still says what the device is set to.
 *
 * An app locale that already carries a region is used as-is: either no in-app language is set —
 * then it IS the system locale — or the user picked a full locale like "English (US)" in
 * Android's own per-app picker, which the OS honors wholesale and so do we.
 */
fun formattingLocale(): Locale {
    val appLocale = Locale.getDefault()
    if (appLocale.country.isNotEmpty()) return appLocale
    val system =
        localeAppContext
            ?.getSystemService(LocaleManager::class.java)
            ?.systemLocales
            ?.takeUnless { it.isEmpty }
            ?.get(0) ?: return appLocale
    return mergeRegional(appLocale, system)
}

// Single entry: one process has one formatting locale at a time. Races are benign — a stale
// read only rebuilds the context.
private var cachedFormatContext: Pair<String, Context>? = null

/**
 * A context configured for [formattingLocale] — what [android.text.format.DateUtils] and other
 * context-locale APIs should format through. Only for formatting: user-visible strings keep
 * coming from the caller's context, in the in-app language. Free when the locales already agree
 * (no in-app language set); otherwise a cached configuration context off the application one.
 */
fun Context.regionalFormatContext(): Context {
    val locale = formattingLocale()
    if (resources.configuration.locales.get(0) == locale) return this
    val key = locale.toLanguageTag()
    cachedFormatContext?.let { (cachedKey, cached) -> if (cachedKey == key) return cached }
    val config = Configuration(applicationContext.resources.configuration).apply { setLocale(locale) }
    return applicationContext.createConfigurationContext(config).also { cachedFormatContext = key to it }
}

/** [language]'s words with [regional]'s region, variant, and unicode extensions. */
fun mergeRegional(
    language: Locale,
    regional: Locale,
): Locale =
    try {
        Locale
            .Builder()
            .setLocale(regional)
            .setLanguage(language.language)
            .setScript(language.script)
            .build()
    } catch (_: IllformedLocaleException) {
        // A device locale weird enough to break the Builder just formats as itself.
        regional
    }

/** The in-app language tag chosen in per-app language settings, or null when following the system. */
fun currentAppLanguageTag(context: Context): String? {
    val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
    return if (locales.isEmpty) null else locales.get(0).language
}
