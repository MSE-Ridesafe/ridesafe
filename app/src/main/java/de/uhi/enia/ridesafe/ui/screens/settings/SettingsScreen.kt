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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
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
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.permissions.PermissionAlertCard
import de.uhi.enia.ridesafe.permissions.PermissionState
import de.uhi.enia.ridesafe.permissions.bundleRequest
import de.uhi.enia.ridesafe.permissions.missingPermissionsFor
import de.uhi.enia.ridesafe.rides.recording.ReconnectGrace
import de.uhi.enia.ridesafe.rides.recording.ReconnectGracePrefs
import de.uhi.enia.ridesafe.rides.trigger.AutoTrackMode
import de.uhi.enia.ridesafe.rides.trigger.AutoTrackPrefs
import de.uhi.enia.ridesafe.rides.trigger.applyAutoTrackMode
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.util.UnitPrefs
import de.uhi.enia.ridesafe.util.UnitSystemSetting
import de.uhi.enia.ridesafe.util.currentUnitSystem
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onOpenLanguage: () -> Unit,
    onOpenUnits: () -> Unit,
    onOpenAutoTrack: () -> Unit,
    onOpenReconnectGrace: () -> Unit,
    onOpenSavedAddresses: () -> Unit,
) {
    val context = LocalContext.current
    val unitSystem = currentUnitSystem()
    val autoTrackMode = AutoTrackPrefs.get(context)
    val reconnectGrace = ReconnectGracePrefs.get(context)
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
                SettingsGroupCard {
                    SettingsListItem(
                        iconName = "language",
                        title = stringResource(R.string.settings_language_title),
                        subtitle = currentLanguageLabel(),
                        onClick = onOpenLanguage,
                    )
                    SettingsDivider()
                    SettingsListItem(
                        iconName = "straighten",
                        title = stringResource(R.string.settings_units_title),
                        subtitle = unitSystemLabel(unitSystem),
                        onClick = onOpenUnits,
                    )
                }
            }
            item {
                SettingsCategoryHeader(text = stringResource(R.string.settings_category_places))
            }
            item {
                SettingsGroupCard {
                    SettingsListItem(
                        iconName = "location_on",
                        title = stringResource(R.string.settings_saved_addresses_title),
                        subtitle = stringResource(R.string.settings_saved_addresses_summary),
                        onClick = onOpenSavedAddresses,
                    )
                }
            }
            item {
                SettingsCategoryHeader(text = stringResource(R.string.settings_category_ride_recording))
            }
            item {
                SettingsGroupCard {
                    SettingsListItem(
                        iconName = "route",
                        title = stringResource(R.string.settings_auto_track_title),
                        subtitle = autoTrackModeLabel(autoTrackMode),
                        onClick = onOpenAutoTrack,
                    )
                    SettingsDivider()
                    SettingsListItem(
                        iconName = "bluetooth_searching",
                        title = stringResource(R.string.settings_reconnect_grace_title),
                        subtitle = stringResource(reconnectGraceLabelRes(reconnectGrace)),
                        onClick = onOpenReconnectGrace,
                    )
                }
            }
        }
    }
}

@Composable
fun LanguageSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
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
        title = stringResource(R.string.settings_language_title),
        description = stringResource(R.string.settings_language_detail_description),
        onBack = onBack,
        modifier = modifier,
    ) {
        options.forEach { (tag, labelRes) ->
            SelectableSettingRow(
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
fun UnitSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
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
        title = stringResource(R.string.settings_units_title),
        description = stringResource(R.string.settings_units_detail_description),
        onBack = onBack,
        modifier = modifier,
    ) {
        options.forEach { (option, labelRes) ->
            SelectableSettingRow(
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
fun AutoTrackSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
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
        title = stringResource(R.string.settings_auto_track_title),
        description = stringResource(R.string.settings_auto_track_detail_description),
        onBack = onBack,
        modifier = modifier,
    ) {
        options.forEach { (option, labelRes) ->
            SelectableSettingRow(
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
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val grace = ReconnectGracePrefs.get(context)

    SettingsSelectionScreen(
        title = stringResource(R.string.settings_reconnect_grace_title),
        description = stringResource(R.string.settings_reconnect_grace_detail_description),
        onBack = onBack,
        modifier = modifier,
    ) {
        ReconnectGrace.entries.forEach { option ->
            SelectableSettingRow(
                title = stringResource(reconnectGraceLabelRes(option)),
                selected = option == grace,
                onClick = { ReconnectGracePrefs.set(context, option) },
            )
        }
    }
}

@Composable
private fun SettingsSelectionScreen(
    title: String,
    description: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
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
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        MaterialSymbol(
                            symbolName = "arrow_back",
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
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
                Column(content = content)
            }
        }
    }
}

@Composable
private fun SettingsListItem(
    iconName: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .clickable(onClick = onClick)
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
private fun SettingsGroupCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(content = content)
    }
}

@Composable
private fun SelectableSettingRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
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

@Composable
private fun SettingsCategoryHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 24.dp, end = 8.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 80.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    )
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
private fun unitSystemLabel(unitSystem: UnitSystemSetting): String =
    stringResource(
        when (unitSystem) {
            UnitSystemSetting.AUTOMATIC -> R.string.unit_system_automatic
            UnitSystemSetting.METRIC -> R.string.unit_system_metric
            UnitSystemSetting.IMPERIAL -> R.string.unit_system_imperial
        },
    )

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
