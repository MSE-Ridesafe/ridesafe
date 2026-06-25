package de.uhi.enia.ridesafe.ui.screens.settings

import android.Manifest
import android.app.LocaleManager
import android.content.pm.PackageManager
import android.os.LocaleList
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.tracking.AutoTrackMode
import de.uhi.enia.ridesafe.util.UnitSystemSetting

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    unitSystem: UnitSystemSetting,
    onUnitSystemChange: (UnitSystemSetting) -> Unit,
    autoTrackMode: AutoTrackMode,
    onAutoTrackModeChange: (AutoTrackMode) -> Unit,
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
                                role = Role.RadioButton,
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
                        onClick = null,
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
                                role = Role.RadioButton,
                                onClick = { onUnitSystemChange(option) },
                            ).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = (option == unitSystem),
                        onClick = null,
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = stringResource(labelRes),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Automatic Ride Recording Section (SET-06)
            Text(
                text = stringResource(R.string.settings_auto_track_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.settings_auto_track_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))

            // ANY mode needs Activity Recognition; apply it only once the permission is granted.
            val activityPermissionLauncher =
                rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    if (granted) onAutoTrackModeChange(AutoTrackMode.ANY)
                }

            val autoTrackOptions =
                listOf(
                    AutoTrackMode.OFF to R.string.auto_track_off,
                    AutoTrackMode.PAIRED_ONLY to R.string.auto_track_paired,
                    AutoTrackMode.ANY to R.string.auto_track_any,
                )

            autoTrackOptions.forEach { (option, labelRes) ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = (option == autoTrackMode),
                                role = Role.RadioButton,
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
                            ).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = (option == autoTrackMode),
                        onClick = null,
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
