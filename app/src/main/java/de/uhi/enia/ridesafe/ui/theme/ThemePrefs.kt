package de.uhi.enia.ridesafe.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

enum class ThemeSetting {
    SYSTEM,
    LIGHT,
    DARK,
}

/**
 * The theme setting, read straight from the preference wherever it is needed.
 *
 * [get] is backed by snapshot state, so a composable that calls it subscribes to it: [set]
 * restyles every screen already on screen. Same pattern as UnitPrefs, for the same reason.
 */
object ThemePrefs {
    private const val PREFS_NAME = "ridesafe_prefs"
    private const val KEY_THEME = "theme"

    private var cached by mutableStateOf<ThemeSetting?>(null)

    fun get(context: Context): ThemeSetting = cached ?: read(context).also { cached = it }

    fun set(
        context: Context,
        value: ThemeSetting,
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_THEME, value.name)
        }
        cached = value
    }

    private fun read(context: Context): ThemeSetting {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_THEME, ThemeSetting.SYSTEM.name)
        return try {
            ThemeSetting.valueOf(name ?: ThemeSetting.SYSTEM.name)
        } catch (_: Exception) {
            ThemeSetting.SYSTEM
        }
    }
}
