@file:OptIn(ExperimentalMaterial3Api::class)

package de.uhi.enia.ridesafe.ui.screens.settings

import android.Manifest
import android.app.LocaleManager
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.tracking.AutoTrackMode
import de.uhi.enia.ridesafe.ui.components.MaterialSymbol
import de.uhi.enia.ridesafe.util.UnitSystemSetting

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    unitSystem: UnitSystemSetting,
    autoTrackMode: AutoTrackMode,
    onOpenLanguage: () -> Unit,
    onOpenUnits: () -> Unit,
    onOpenAutoTrack: () -> Unit,
) {
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
            item {
                SettingsCategoryHeader(text = stringResource(R.string.settings_category_preferences))
            }
            item {
                SettingsGroupCard {
                    SettingsListItem(
                        iconName = "language",
                        title = stringResource(R.string.settings_language_title),
                        description = stringResource(R.string.settings_language_summary),
                        value = currentLanguageLabel(),
                        onClick = onOpenLanguage,
                    )
                    SettingsDivider()
                    SettingsListItem(
                        iconName = "straighten",
                        title = stringResource(R.string.settings_units_title),
                        description = stringResource(R.string.settings_units_summary),
                        value = unitSystemLabel(unitSystem),
                        onClick = onOpenUnits,
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
                        description = stringResource(R.string.settings_auto_track_summary),
                        value = autoTrackModeLabel(autoTrackMode),
                        onClick = onOpenAutoTrack,
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
                    localeManager.applicationLocales = locales
                },
            )
        }
    }
}

@Composable
fun UnitSettingsScreen(
    unitSystem: UnitSystemSetting,
    onUnitSystemChange: (UnitSystemSetting) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                onClick = { onUnitSystemChange(option) },
            )
        }
    }
}

@Composable
fun AutoTrackSettingsScreen(
    autoTrackMode: AutoTrackMode,
    onAutoTrackModeChange: (AutoTrackMode) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activityPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) onAutoTrackModeChange(AutoTrackMode.ANY)
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
                    val needsPermission =
                        option == AutoTrackMode.ANY &&
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.ACTIVITY_RECOGNITION,
                            ) != PackageManager.PERMISSION_GRANTED
                    if (needsPermission) {
                        activityPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                    } else {
                        onAutoTrackModeChange(option)
                    }
                },
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
    description: String,
    value: String,
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
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(max = 136.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MaterialSymbol(
                symbolName = "chevron_right",
                contentDescription = null,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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

@Composable
private fun autoTrackModeLabel(autoTrackMode: AutoTrackMode): String =
    stringResource(
        when (autoTrackMode) {
            AutoTrackMode.OFF -> R.string.auto_track_off
            AutoTrackMode.PAIRED_ONLY -> R.string.auto_track_paired
            AutoTrackMode.ANY -> R.string.auto_track_any
        },
    )
