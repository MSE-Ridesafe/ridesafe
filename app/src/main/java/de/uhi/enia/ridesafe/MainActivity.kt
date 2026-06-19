package de.uhi.enia.ridesafe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import de.uhi.enia.ridesafe.navigation.RidesafeApp
import de.uhi.enia.ridesafe.ui.theme.RidesafeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RidesafeTheme {
                RidesafeApp()
            }
        }
    }
}