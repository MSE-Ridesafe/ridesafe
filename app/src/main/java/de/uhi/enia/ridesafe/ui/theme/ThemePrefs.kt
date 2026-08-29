package de.uhi.enia.ridesafe.ui.theme

import de.uhi.enia.ridesafe.util.EnumPref

enum class ThemeSetting {
    SYSTEM,
    LIGHT,
    DARK,
}

/** The theme setting, read straight from the preference wherever it is needed (see [EnumPref]). */
object ThemePrefs : EnumPref<ThemeSetting>("theme", ThemeSetting.entries, { ThemeSetting.SYSTEM })
