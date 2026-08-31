package de.uhi.enia.ridesafe.core.preferences

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

private const val PREFS_NAME = "ridesafe_prefs"

/**
 * One enum-valued setting in the app's shared prefs file, cached in snapshot state: a composable
 * that calls [get] subscribes to it, so [set] updates every screen already showing the value.
 * Handing the value down as a parameter instead does not work here — a screen composed by
 * NavDisplay keeps the value it was built with until the back stack changes, which is why picking
 * a unit used to leave the radio button behind.
 *
 * [default] is evaluated per read so it can depend on the current locale; a stored name no enum
 * case matches (e.g. from a newer app version) falls back to it.
 */
open class EnumPref<T : Enum<T>>(
    private val key: String,
    private val entries: List<T>,
    private val default: () -> T,
) {
    private var cached by mutableStateOf<T?>(null)

    fun get(context: Context): T = cached ?: read(context).also { cached = it }

    fun set(
        context: Context,
        value: T,
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { putString(key, value.name) }
        cached = value
    }

    private fun read(context: Context): T {
        val name = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(key, null)
        return entries.firstOrNull { it.name == name } ?: default()
    }
}
