@file:OptIn(ExperimentalMaterial3Api::class)

package de.uhi.enia.ridesafe.feature.settings.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.core.format.UnitPrefs
import de.uhi.enia.ridesafe.core.format.UnitSystemSetting
import de.uhi.enia.ridesafe.core.format.currentUnitSystem
import de.uhi.enia.ridesafe.core.preferences.CurrencyPrefs
import de.uhi.enia.ridesafe.core.preferences.CurrencySetting
import de.uhi.enia.ridesafe.core.preferences.ThemePrefs
import de.uhi.enia.ridesafe.core.preferences.ThemeSetting
import de.uhi.enia.ridesafe.core.preferences.currentCurrencySetting
import de.uhi.enia.ridesafe.feature.settings.SettingsFade
import kotlinx.coroutines.launch

@Composable
fun ThemeSettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    showBack: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val theme = ThemePrefs.get(context)
    val options =
        listOf(
            ThemeSetting.SYSTEM to R.string.theme_system,
            ThemeSetting.LIGHT to R.string.theme_light,
            ThemeSetting.DARK to R.string.theme_dark,
        )

    SettingsSelectionScreen(
        modifier = modifier,
        title = stringResource(R.string.settings_theme_title),
        description = stringResource(R.string.settings_theme_detail_description),
        onBack = onBack,
        showBack = showBack,
        {
            options.forEachIndexed { index, (option, labelRes) ->
                SelectableSettingRow(
                    index = index,
                    count = options.size,
                    title = stringResource(labelRes),
                    selected = option == theme,
                    onClick = {
                        scope.launch { SettingsFade.applyWhileHidden { ThemePrefs.set(context, option) } }
                    },
                )
            }
        },
    )
}

@Composable
fun UnitSettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    showBack: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val unitSystem = currentUnitSystem()
    val options =
        listOf(
            UnitSystemSetting.AUTOMATIC to R.string.unit_system_automatic,
            UnitSystemSetting.METRIC to R.string.unit_system_metric,
            UnitSystemSetting.IMPERIAL to R.string.unit_system_imperial,
        )

    SettingsSelectionScreen(
        modifier = modifier,
        title = stringResource(R.string.settings_units_title),
        description = stringResource(R.string.settings_units_detail_description),
        onBack = onBack,
        showBack = showBack,
    ) {
        options.forEachIndexed { index, (option, labelRes) ->
            SelectableSettingRow(
                index = index,
                count = options.size,
                title = stringResource(labelRes),
                selected = option == unitSystem,
                onClick = {
                    scope.launch { SettingsFade.applyWhileHidden { UnitPrefs.set(context, option) } }
                },
            )
        }
    }
}

@Composable
fun CurrencySettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    showBack: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selectedCurrency = currentCurrencySetting()
    val options =
        listOf(
            CurrencySetting.US_DOLLAR to R.string.currency_us_dollar,
            CurrencySetting.BRITISH_POUND to R.string.currency_british_pound,
            CurrencySetting.SWISS_FRANC to R.string.currency_swiss_franc,
            CurrencySetting.EURO to R.string.currency_euro,
        )

    SettingsSelectionScreen(
        title = stringResource(R.string.settings_currency_title),
        description = stringResource(R.string.settings_currency_detail_description),
        onBack = onBack,
        showBack = showBack,
        modifier = modifier,
    ) {
        options.forEachIndexed { index, (option, labelRes) ->
            SelectableSettingRow(
                index = index,
                count = options.size,
                title = stringResource(labelRes),
                selected = option == selectedCurrency,
                onClick = {
                    scope.launch { SettingsFade.applyWhileHidden { CurrencyPrefs.set(context, option) } }
                },
            )
        }
    }
}

@Composable
internal fun themeSettingLabel(theme: ThemeSetting): String =
    stringResource(
        when (theme) {
            ThemeSetting.SYSTEM -> R.string.theme_system
            ThemeSetting.LIGHT -> R.string.theme_light
            ThemeSetting.DARK -> R.string.theme_dark
        },
    )

@Composable
internal fun unitSystemLabel(unitSystem: UnitSystemSetting): String =
    stringResource(
        when (unitSystem) {
            UnitSystemSetting.AUTOMATIC -> R.string.unit_system_automatic
            UnitSystemSetting.METRIC -> R.string.unit_system_metric
            UnitSystemSetting.IMPERIAL -> R.string.unit_system_imperial
        },
    )

@Composable
internal fun currencyLabel(currency: CurrencySetting): String =
    stringResource(
        when (currency) {
            CurrencySetting.US_DOLLAR -> R.string.currency_us_dollar
            CurrencySetting.BRITISH_POUND -> R.string.currency_british_pound
            CurrencySetting.SWISS_FRANC -> R.string.currency_swiss_franc
            CurrencySetting.EURO -> R.string.currency_euro
        },
    )
