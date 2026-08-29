package de.uhi.enia.ridesafe

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import de.uhi.enia.ridesafe.navigation.RidesafeApp
import de.uhi.enia.ridesafe.ui.screens.settings.SettingsFade
import de.uhi.enia.ridesafe.ui.theme.RidesafeTheme
import de.uhi.enia.ridesafe.ui.theme.resolvedDarkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // The theme setting can override the system's, so the system bar icon colors follow
            // the resolved theme rather than the system default enableEdgeToEdge detects.
            val darkTheme = resolvedDarkTheme()
            DisposableEffect(darkTheme) {
                val bars =
                    if (darkTheme) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    }
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                onDispose {}
            }
            RidesafeTheme(darkTheme = darkTheme) {
                // Units and language restyle every screen at once; SettingsFade dips this layer
                // while the change is applied so the switch is a fade rather than a hard cut.
                // Changing the app locale recreates this activity, so the fade-in half of that
                // transition belongs to whichever activity comes back — this one.
                LaunchedEffect(Unit) { SettingsFade.resumeFadeIn() }
                Box(Modifier.graphicsLayer { alpha = SettingsFade.alpha }) {
                    RidesafeApp()
                }
            }
        }
    }
}
