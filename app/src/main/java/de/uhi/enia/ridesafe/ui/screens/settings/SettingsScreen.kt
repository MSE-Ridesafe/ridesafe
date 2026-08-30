@file:OptIn(ExperimentalMaterial3Api::class)

package de.uhi.enia.ridesafe.ui.screens.settings

import android.app.LocaleManager
import android.os.LocaleList
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.permissions.PermissionAlertCard
import de.uhi.enia.ridesafe.permissions.PermissionState
import de.uhi.enia.ridesafe.permissions.bundleRequest
import de.uhi.enia.ridesafe.permissions.missingPermissionsFor
import de.uhi.enia.ridesafe.rides.recording.MinRideLength
import de.uhi.enia.ridesafe.rides.recording.MinRideLengthPrefs
import de.uhi.enia.ridesafe.rides.recording.ReconnectGrace
import de.uhi.enia.ridesafe.rides.recording.ReconnectGracePrefs
import de.uhi.enia.ridesafe.rides.trigger.AutoTrackMode
import de.uhi.enia.ridesafe.rides.trigger.AutoTrackPrefs
import de.uhi.enia.ridesafe.rides.trigger.applyAutoTrackMode
import de.uhi.enia.ridesafe.ui.components.BackNavIcon
import de.uhi.enia.ridesafe.ui.components.ListGroupItem
import de.uhi.enia.ridesafe.ui.components.ListGroupItemGap
import de.uhi.enia.ridesafe.ui.components.ListItemGroup
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.ui.components.SectionTitle
import de.uhi.enia.ridesafe.ui.onboarding.OnboardingPrefs
import de.uhi.enia.ridesafe.ui.theme.ThemePrefs
import de.uhi.enia.ridesafe.ui.theme.ThemeSetting
import de.uhi.enia.ridesafe.util.CurrencyPrefs
import de.uhi.enia.ridesafe.util.CurrencySetting
import de.uhi.enia.ridesafe.util.UnitPrefs
import de.uhi.enia.ridesafe.util.UnitSystemSetting
import de.uhi.enia.ridesafe.util.currentCurrencySetting
import de.uhi.enia.ridesafe.util.currentUnitSystem
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    // The sub-screen currently in the detail pane, so the menu can mark the row it belongs to.
    // Null on a phone, where the sub-screen covers the menu instead of sitting beside it.
    modifier: Modifier = Modifier,
    selected: NavKey? = null,
    onOpenLanguage: () -> Unit,
    onOpenTheme: () -> Unit,
    onOpenUnits: () -> Unit,
    onOpenCurrency: () -> Unit,
    onOpenAutoTrack: () -> Unit,
    onOpenReconnectGrace: () -> Unit,
    onOpenMinRideLength: () -> Unit,
    onOpenSavedAddresses: () -> Unit,
    onOpenBackupImport: () -> Unit,
) {
    val context = LocalContext.current
    val unitSystem = currentUnitSystem()
    val currency = currentCurrencySetting()
    val autoTrackMode = AutoTrackPrefs.get(context)
    val reconnectGrace = ReconnectGracePrefs.get(context)
    val minRideLength = MinRideLengthPrefs.get(context)
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.screen_settings_title),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            // NFR-05: whatever the enabled features still need, above everything else.
            item {
                PermissionAlertCard(modifier = Modifier.padding(top = 8.dp))
            }
            item {
                SettingsCategoryHeader(text = stringResource(R.string.settings_category_preferences))
            }
            item {
                ListItemGroup(
                    {
                        SettingsListItem(
                            iconName = "language",
                            title = stringResource(R.string.settings_language_title),
                            subtitle = currentLanguageLabel(),
                            isOpen = selected == SettingsLanguageRoute,
                            onClick = onOpenLanguage,
                        )
                    },
                    {
                        SettingsListItem(
                            iconName = "straighten",
                            title = stringResource(R.string.settings_units_title),
                            subtitle = unitSystemLabel(unitSystem),
                            isOpen = selected == SettingsUnitsRoute,
                            onClick = onOpenUnits,
                        )
                    },
                    {
                        SettingsListItem(
                            iconName = "payments",
                            title = stringResource(R.string.settings_currency_title),
                            subtitle = currencyLabel(currency),
                            isOpen = selected == SettingsCurrencyRoute,
                            onClick = onOpenCurrency,
                        )
                    },
                    {
                        SettingsListItem(
                            iconName = "dark_mode",
                            title = stringResource(R.string.settings_theme_title),
                            subtitle = themeSettingLabel(ThemePrefs.get(context)),
                            isOpen = selected == SettingsThemeRoute,
                            onClick = onOpenTheme,
                        )
                    },
                )
            }
            item {
                SettingsCategoryHeader(text = stringResource(R.string.settings_category_places))
            }
            item {
                ListItemGroup(
                    {
                        SettingsListItem(
                            iconName = "location_on",
                            title = stringResource(R.string.settings_saved_addresses_title),
                            subtitle = stringResource(R.string.settings_saved_addresses_summary),
                            isOpen = selected == SavedAddressesRoute,
                            onClick = onOpenSavedAddresses,
                        )
                    },
                )
            }
            item {
                SettingsCategoryHeader(text = stringResource(R.string.settings_category_ride_recording))
            }
            item {
                ListItemGroup(
                    {
                        SettingsListItem(
                            iconName = "route",
                            title = stringResource(R.string.settings_auto_track_title),
                            subtitle = autoTrackModeLabel(autoTrackMode),
                            isOpen = selected == SettingsAutoTrackRoute,
                            onClick = onOpenAutoTrack,
                        )
                    },
                    {
                        SettingsListItem(
                            iconName = "bluetooth_searching",
                            title = stringResource(R.string.settings_reconnect_grace_title),
                            subtitle = stringResource(reconnectGraceLabelRes(reconnectGrace)),
                            isOpen = selected == SettingsReconnectGraceRoute,
                            onClick = onOpenReconnectGrace,
                        )
                    },
                    {
                        SettingsListItem(
                            iconName = "timer",
                            title = stringResource(R.string.settings_min_ride_length_title),
                            subtitle = stringResource(minRideLengthLabelRes(minRideLength)),
                            isOpen = selected == SettingsMinRideLengthRoute,
                            onClick = onOpenMinRideLength,
                        )
                    },
                )
            }
            item {
                SettingsCategoryHeader(text = stringResource(R.string.settings_category_backup_restore))
            }
            item {
                ListItemGroup(
                    {
                        SettingsListItem(
                            iconName = "settings_backup_restore",
                            title = stringResource(R.string.settings_backup_import_title),
                            subtitle = stringResource(R.string.settings_backup_import_summary),
                            isOpen = selected == SettingsBackupImportRoute,
                            onClick = onOpenBackupImport,
                        )
                    },
                )
            }
            item {
                SettingsCategoryHeader(text = stringResource(R.string.settings_category_help))
            }
            item {
                ListItemGroup(
                    {
                        // An action, not a sub-screen: the wizard replaces the whole app UI (ONB-07).
                        SettingsListItem(
                            iconName = "school",
                            title = stringResource(R.string.settings_onboarding_replay_title),
                            subtitle = stringResource(R.string.settings_onboarding_replay_summary),
                            isOpen = false,
                            onClick = { OnboardingPrefs.replayRequested = true },
                        )
                    },
                )
            }
        }
    }
}

@Composable
fun LanguageSettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    showBack: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val localeManager = context.getSystemService(LocaleManager::class.java)
    val currentLocales = localeManager.applicationLocales
    val currentLang =
        if (currentLocales.isEmpty) {
            "system"
        } else {
            currentLocales.get(0).language
        }

    val options =
        listOf(
            "system" to R.string.language_system,
            "en" to R.string.language_english,
            "de" to R.string.language_german,
        )

    SettingsSelectionScreen(
        modifier = modifier,
        title = stringResource(R.string.settings_language_title),
        description = stringResource(R.string.settings_language_detail_description),
        onBack = onBack,
        showBack = showBack,
    ) {
        options.forEachIndexed { index, (tag, labelRes) ->
            SelectableSettingRow(
                index = index,
                count = options.size,
                title = stringResource(labelRes),
                selected = tag == currentLang,
                onClick = {
                    val locales =
                        if (tag == "system") {
                            LocaleList.getEmptyLocaleList()
                        } else {
                            LocaleList.forLanguageTags(tag)
                        }
                    scope.launch {
                        SettingsFade.applyAcrossRestart { localeManager.applicationLocales = locales }
                    }
                },
            )
        }
    }
}

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
fun AutoTrackSettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    showBack: Boolean = true,
) {
    val context = LocalContext.current
    // Turning a mode on is where its permissions are first asked for (NFR-05). The mode is
    // applied either way — a denial isn't a reason to override the user's choice; the Settings
    // alert card then lists whatever is still missing. Reporting the result clears the card and
    // the tab badge as the dialog closes, rather than on the next resume.
    val autoTrackMode = AutoTrackPrefs.get(context)
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            PermissionState.refresh(context)
        }

    val options =
        listOf(
            AutoTrackMode.OFF to R.string.auto_track_off,
            AutoTrackMode.PAIRED_ONLY to R.string.auto_track_paired,
            AutoTrackMode.ANY to R.string.auto_track_any,
        )

    SettingsSelectionScreen(
        modifier = modifier,
        title = stringResource(R.string.settings_auto_track_title),
        description = stringResource(R.string.settings_auto_track_detail_description),
        onBack = onBack,
        showBack = showBack,
    ) {
        options.forEachIndexed { index, (option, labelRes) ->
            SelectableSettingRow(
                index = index,
                count = options.size,
                title = stringResource(labelRes),
                selected = option == autoTrackMode,
                onClick = {
                    applyAutoTrackMode(context, option)
                    val request = bundleRequest(missingPermissionsFor(context, option))
                    if (request.isNotEmpty()) permissionLauncher.launch(request)
                },
            )
        }
    }
}

@Composable
fun ReconnectGraceSettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    showBack: Boolean = true,
) {
    val context = LocalContext.current
    val grace = ReconnectGracePrefs.get(context)

    SettingsSelectionScreen(
        modifier = modifier,
        title = stringResource(R.string.settings_reconnect_grace_title),
        description = stringResource(R.string.settings_reconnect_grace_detail_description),
        onBack = onBack,
        showBack = showBack,
    ) {
        ReconnectGrace.entries.forEachIndexed { index, option ->
            SelectableSettingRow(
                index = index,
                count = ReconnectGrace.entries.size,
                title = stringResource(reconnectGraceLabelRes(option)),
                selected = option == grace,
                onClick = { ReconnectGracePrefs.set(context, option) },
            )
        }
    }
}

@Composable
fun MinRideLengthSettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    showBack: Boolean = true,
) {
    val context = LocalContext.current
    val minRideLength = MinRideLengthPrefs.get(context)

    SettingsSelectionScreen(
        modifier = modifier,
        title = stringResource(R.string.settings_min_ride_length_title),
        description = stringResource(R.string.settings_min_ride_length_detail_description),
        onBack = onBack,
        showBack = showBack,
    ) {
        MinRideLength.entries.forEachIndexed { index, option ->
            SelectableSettingRow(
                index = index,
                count = MinRideLength.entries.size,
                title = stringResource(minRideLengthLabelRes(option)),
                selected = option == minRideLength,
                onClick = { MinRideLengthPrefs.set(context, option) },
            )
        }
    }
}

@Composable
private fun SettingsSelectionScreen(
    modifier: Modifier = Modifier,
    title: String,
    description: String,
    onBack: () -> Unit,
    showBack: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackNavIcon(onBack = onBack, showBack = showBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
                )
            }
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(ListGroupItemGap),
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun SettingsListItem(
    iconName: String,
    title: String,
    subtitle: String,
    isOpen: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .background(
                    if (isOpen) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                ).clickable(onClick = onClick)
                .fillMaxWidth()
                .defaultMinSize(minHeight = 72.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsIcon(symbolName = iconName)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The current value, the way the system Settings app summarises a row. Rows without
            // one fall back to describing what they do.
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        MaterialSymbol(
            symbolName = "chevron_right",
            contentDescription = null,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SelectableSettingRow(
    index: Int,
    count: Int,
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ListGroupItem(index = index, count = count) {
        ListItem(
            modifier =
                Modifier.selectable(
                    selected = selected,
                    role = Role.RadioButton,
                    onClick = onClick,
                ),
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            trailingContent = {
                RadioButton(
                    selected = selected,
                    onClick = null,
                )
            },
        )
    }
}

@Composable
private fun SettingsCategoryHeader(text: String) {
    SectionTitle(text = text, modifier = Modifier.padding(start = 8.dp, top = 24.dp, end = 8.dp, bottom = 8.dp))
}

@Composable
private fun SettingsIcon(symbolName: String) {
    Box(
        modifier =
            Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        MaterialSymbol(
            symbolName = symbolName,
            contentDescription = null,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun currentLanguageLabel(): String {
    val localeManager = LocalContext.current.getSystemService(LocaleManager::class.java)
    val locales = localeManager.applicationLocales
    val language = if (locales.isEmpty) "system" else locales.get(0).language
    return when (language) {
        "en" -> stringResource(R.string.language_english)
        "de" -> stringResource(R.string.language_german)
        else -> stringResource(R.string.language_system)
    }
}

@Composable
private fun themeSettingLabel(theme: ThemeSetting): String =
    stringResource(
        when (theme) {
            ThemeSetting.SYSTEM -> R.string.theme_system
            ThemeSetting.LIGHT -> R.string.theme_light
            ThemeSetting.DARK -> R.string.theme_dark
        },
    )

@Composable
private fun unitSystemLabel(unitSystem: UnitSystemSetting): String =
    stringResource(
        when (unitSystem) {
            UnitSystemSetting.AUTOMATIC -> R.string.unit_system_automatic
            UnitSystemSetting.METRIC -> R.string.unit_system_metric
            UnitSystemSetting.IMPERIAL -> R.string.unit_system_imperial
        },
    )

@Composable
private fun currencyLabel(currency: CurrencySetting): String =
    stringResource(
        when (currency) {
            CurrencySetting.US_DOLLAR -> R.string.currency_us_dollar
            CurrencySetting.BRITISH_POUND -> R.string.currency_british_pound
            CurrencySetting.SWISS_FRANC -> R.string.currency_swiss_franc
            CurrencySetting.EURO -> R.string.currency_euro
        },
    )

private fun minRideLengthLabelRes(length: MinRideLength): Int =
    when (length) {
        MinRideLength.OFF -> R.string.min_ride_length_off
        MinRideLength.SEC_15 -> R.string.min_ride_length_15s
        MinRideLength.SEC_30 -> R.string.min_ride_length_30s
        MinRideLength.SEC_60 -> R.string.min_ride_length_60s
        MinRideLength.MIN_2 -> R.string.min_ride_length_2m
    }

private fun reconnectGraceLabelRes(grace: ReconnectGrace): Int =
    when (grace) {
        ReconnectGrace.OFF -> R.string.reconnect_grace_off
        ReconnectGrace.SEC_30 -> R.string.reconnect_grace_30s
        ReconnectGrace.MIN_1 -> R.string.reconnect_grace_1m
        ReconnectGrace.MIN_2 -> R.string.reconnect_grace_2m
        ReconnectGrace.MIN_5 -> R.string.reconnect_grace_5m
    }

@Composable
private fun autoTrackModeLabel(autoTrackMode: AutoTrackMode): String =
    stringResource(
        when (autoTrackMode) {
            AutoTrackMode.OFF -> R.string.auto_track_off
            AutoTrackMode.PAIRED_ONLY -> R.string.auto_track_paired
            AutoTrackMode.ANY -> R.string.auto_track_any
        },
    )
