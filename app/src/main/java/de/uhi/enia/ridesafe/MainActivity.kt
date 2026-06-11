package de.uhi.enia.ridesafe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import de.uhi.enia.ridesafe.ui.theme.RidesafeTheme
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol

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

@PreviewScreenSizes
@Composable
fun RidesafeApp() {
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.HOME) }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            AppDestinations.entries.forEach {
                val isSelected = it == currentDestination
                item(
                    icon = {
                        MaterialSymbol(
                            symbolName = it.symbolName,
                            contentDescription = it.label,
                            fill = isSelected
                        )
                    },
                    label = { Text(it.label) },
                    selected = isSelected,
                    onClick = { currentDestination = it }
                )
            }
        }
    ) {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            Greeting(
                name = "Android",
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}

enum class AppDestinations(
    val label: String,
    val symbolName: String,
) {
    HOME("Home", "home"),
    RIDES("Rides", "route"),
    GARAGE("Garage", "garage_home"),
    SETTINGS("Settings", "settings")
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    RidesafeTheme {
        Greeting("Android")
    }
}