package de.uhi.enia.ridesafe.core.preferences

enum class ThemeSetting {
    SYSTEM,
    LIGHT,
    DARK,
}

/** The theme setting, read straight from the preference wherever it is needed (see [EnumPref]). */
object ThemePrefs : EnumPref<ThemeSetting>("theme", ThemeSetting.entries, { ThemeSetting.SYSTEM })
