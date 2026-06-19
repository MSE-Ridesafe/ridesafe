package de.uhi.enia.ridesafe

import android.app.LocaleManager
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.ui.theme.RidesafeTheme
import de.uhi.enia.ridesafe.util.UnitPrefs
import de.uhi.enia.ridesafe.util.UnitSystemSetting
import de.uhi.enia.ridesafe.util.formatDistance

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
    val context = LocalContext.current
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.HOME) }
    var unitSystem by rememberSaveable { mutableStateOf(UnitPrefs.get(context)) }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            AppDestinations.entries.forEach {
                val isSelected = it == currentDestination
                item(
                    icon = {
                        MaterialSymbol(
                            symbolName = it.symbolName,
                            contentDescription = stringResource(id = it.labelRes),
                            fill = isSelected,
                        )
                    },
                    label = { Text(stringResource(id = it.labelRes)) },
                    selected = isSelected,
                    onClick = { currentDestination = it },
                )
            }
        },
    ) {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            val screenModifier = Modifier.padding(innerPadding)
            when (currentDestination) {
                AppDestinations.HOME -> {
                    HomeScreen(screenModifier, unitSystem)
                }

                AppDestinations.RIDES -> {
                    RidesScreen(screenModifier)
                }

                AppDestinations.GARAGE -> {
                    GarageScreen(screenModifier)
                }

                AppDestinations.SETTINGS -> {
                    SettingsScreen(
                        modifier = screenModifier,
                        unitSystem = unitSystem,
                        onUnitSystemChange = { newSetting ->
                            UnitPrefs.set(context, newSetting)
                            unitSystem = newSetting
                        },
                    )
                }
            }
        }
    }
}

enum class AppDestinations(
    val labelRes: Int,
    val symbolName: String,
) {
    HOME(R.string.nav_home, "home"),
    RIDES(R.string.nav_rides, "route"),
    GARAGE(R.string.nav_garage, "garage_home"),
    SETTINGS(R.string.nav_settings, "settings"),
}

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    unitSystem: UnitSystemSetting = UnitSystemSetting.AUTOMATIC,
) {
    val context = LocalContext.current
    val formattedDistance = formatDistance(context, 5000.0, unitSystem)

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.screen_home_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Greeting(name = "Android")
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.sample_distance_label, formattedDistance),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
fun RidesScreen(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.screen_rides_title),
            style = MaterialTheme.typography.headlineMedium,
        )
    }
}

@Composable
fun GarageScreen(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.screen_garage_title),
            style = MaterialTheme.typography.headlineMedium,
        )
    }
}

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    unitSystem: UnitSystemSetting,
    onUnitSystemChange: (UnitSystemSetting) -> Unit,
) {
    val context = LocalContext.current
    val localeManager = context.getSystemService(LocaleManager::class.java)
    val currentLocales = localeManager.applicationLocales
    val currentLang =
        if (currentLocales.isEmpty) {
            "system"
        } else {
            currentLocales.get(0).language
        }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .padding(16.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = stringResource(R.string.screen_settings_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(modifier = Modifier.height(24.dp))

            // App Language Section
            Text(
                text = stringResource(R.string.settings_language_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.settings_language_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))

            val langOptions =
                listOf(
                    "system" to R.string.language_system,
                    "en" to R.string.language_english,
                    "de" to R.string.language_german,
                )

            langOptions.forEach { (tag, labelRes) ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = (tag == currentLang),
                                onClick = {
                                    val locales =
                                        if (tag == "system") {
                                            LocaleList.getEmptyLocaleList()
                                        } else {
                                            LocaleList.forLanguageTags(tag)
                                        }
                                    localeManager.applicationLocales = locales
                                },
                            ).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = (tag == currentLang),
                        onClick = {
                            val locales =
                                if (tag == "system") {
                                    LocaleList.getEmptyLocaleList()
                                } else {
                                    LocaleList.forLanguageTags(tag)
                                }
                            localeManager.applicationLocales = locales
                        },
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = stringResource(labelRes),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Distance Units Section
            Text(
                text = stringResource(R.string.settings_units_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.settings_units_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))

            val unitOptions =
                listOf(
                    UnitSystemSetting.AUTOMATIC to R.string.unit_system_automatic,
                    UnitSystemSetting.METRIC to R.string.unit_system_metric,
                    UnitSystemSetting.IMPERIAL to R.string.unit_system_imperial,
                )

            unitOptions.forEach { (option, labelRes) ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = (option == unitSystem),
                                onClick = { onUnitSystemChange(option) },
                            ).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = (option == unitSystem),
                        onClick = { onUnitSystemChange(option) },
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = stringResource(labelRes),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

@Composable
fun Greeting(
    name: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = stringResource(id = R.string.greeting_hello, name),
        modifier = modifier,
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    RidesafeTheme {
        Greeting("Android")
    }
}
