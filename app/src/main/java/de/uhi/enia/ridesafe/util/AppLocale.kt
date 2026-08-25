package de.uhi.enia.ridesafe.util

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration

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
