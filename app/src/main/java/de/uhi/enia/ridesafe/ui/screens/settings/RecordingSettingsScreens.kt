@file:OptIn(ExperimentalMaterial3Api::class)

package de.uhi.enia.ridesafe.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.permissions.PermissionState
import de.uhi.enia.ridesafe.permissions.bundleRequest
import de.uhi.enia.ridesafe.permissions.missingPermissionsFor
import de.uhi.enia.ridesafe.rides.recording.MinRideLength
import de.uhi.enia.ridesafe.rides.recording.MinRideLengthPrefs
import de.uhi.enia.ridesafe.rides.recording.ReconnectGrace
import de.uhi.enia.ridesafe.rides.recording.ReconnectGracePrefs
import de.uhi.enia.ridesafe.rides.recording.minRideLengthLabelRes
import de.uhi.enia.ridesafe.rides.recording.reconnectGraceLabelRes
import de.uhi.enia.ridesafe.rides.trigger.AutoTrackMode
import de.uhi.enia.ridesafe.rides.trigger.AutoTrackPrefs
import de.uhi.enia.ridesafe.rides.trigger.applyAutoTrackMode

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
internal fun autoTrackModeLabel(autoTrackMode: AutoTrackMode): String =
    stringResource(
        when (autoTrackMode) {
            AutoTrackMode.OFF -> R.string.auto_track_off
            AutoTrackMode.PAIRED_ONLY -> R.string.auto_track_paired
            AutoTrackMode.ANY -> R.string.auto_track_any
        },
    )
