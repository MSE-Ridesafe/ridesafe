package de.uhi.enia.ridesafe.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** The theme setting resolved to dark or light, live — the caller recomposes when it changes. */
@Composable
fun resolvedDarkTheme(): Boolean =
    when (ThemePrefs.get(LocalContext.current)) {
        ThemeSetting.LIGHT -> false
        ThemeSetting.DARK -> true
        ThemeSetting.SYSTEM -> isSystemInDarkTheme()
    }

@Composable
fun RidesafeTheme(
    darkTheme: Boolean = resolvedDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Dynamic color unconditionally: it needs Android 12+, and minSdk is 34.
    val context = LocalContext.current
    MaterialTheme(
        colorScheme = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context),
        typography = Typography,
        content = content,
    )
}
