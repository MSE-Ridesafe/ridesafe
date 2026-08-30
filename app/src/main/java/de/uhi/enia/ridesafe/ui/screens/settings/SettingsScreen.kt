@file:OptIn(ExperimentalMaterial3Api::class)

package de.uhi.enia.ridesafe.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.permissions.PermissionAlertCard
import de.uhi.enia.ridesafe.rides.recording.MinRideLengthPrefs
import de.uhi.enia.ridesafe.rides.recording.ReconnectGracePrefs
import de.uhi.enia.ridesafe.rides.recording.minRideLengthLabelRes
import de.uhi.enia.ridesafe.rides.recording.reconnectGraceLabelRes
import de.uhi.enia.ridesafe.rides.trigger.AutoTrackPrefs
import de.uhi.enia.ridesafe.ui.components.ListItemGroup
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.ui.components.SectionTitle
import de.uhi.enia.ridesafe.ui.onboarding.OnboardingPrefs
import de.uhi.enia.ridesafe.ui.theme.ThemePrefs
import de.uhi.enia.ridesafe.util.currentCurrencySetting
import de.uhi.enia.ridesafe.util.currentUnitSystem

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
